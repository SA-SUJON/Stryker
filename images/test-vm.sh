#!/bin/bash
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
. "$HERE/lib/common.sh"

VMOUT=$OUT_DIR/vm
T=$WORK_DIR/test
KERNEL=$VMOUT/Image
INITRD=$VMOUT/initrd.img
TIMEOUT=${TIMEOUT:-180}
PORT=${PORT:-2299}

need qemu-system-aarch64 ssh-keygen ssh
[ -f "$KERNEL" ] || die "no kernel at $KERNEL -- run images/kernel/build-vm.sh"
[ -f "$VMOUT/rootfs.img" ] || die "no rootfs at $VMOUT/rootfs.img -- run images/rootfs/build.sh"

rm -rf "$T"
mkdir -p "$T/share/.ssh"
say "preparing a scratch copy"
cp --sparse=always "$VMOUT/rootfs.img" "$T/rootfs.img"
truncate -s 4G "$T/rootfs.img"
e2fsck -fp "$T/rootfs.img" >/dev/null 2>&1 || true
resize2fs "$T/rootfs.img" >/dev/null 2>&1 || true

ssh-keygen -t ed25519 -N '' -C stryker-test -f "$T/id" >/dev/null
cp "$T/id.pub" "$T/share/.ssh/authorized_keys"

say "booting"
qemu-system-aarch64 \
	-M virt,gic-version=3 -cpu cortex-a72 -m 2048 -smp 2,sockets=1,cores=2,threads=1 \
	-kernel "$KERNEL" ${INITRD:+-initrd "$INITRD"} \
	-append "root=/dev/vda rw rootwait rootflags=noatime console=ttyAMA0 loglevel=4 net.ifnames=0 mitigations=off stryker.rootless=1" \
	-drive "file=$T/rootfs.img,if=none,id=drive0,format=raw,cache=writeback,aio=threads,discard=unmap,detect-zeroes=unmap" \
	-device virtio-blk-pci,drive=drive0 \
	-netdev "user,id=net0,ipv6=off,hostfwd=tcp:127.0.0.1:$PORT-:22" \
	-device virtio-net-pci,netdev=net0,romfile= \
	-device qemu-xhci,id=usbhc0,p2=8,p3=8 \
	-device virtio-rng-pci \
	-fsdev "local,id=fsdev0,security_model=none,path=$T/share" \
	-device virtio-9p-pci,fsdev=fsdev0,mount_tag=strykershare \
	-display none -serial "file:$T/console.log" -monitor none \
	-pidfile "$T/qemu.pid" -daemonize

qemu_pid=$(cat "$T/qemu.pid")
cleanup() { kill "$qemu_pid" 2>/dev/null || true; }
trap cleanup EXIT

SSHOPT=(-i "$T/id" -p "$PORT"
        -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null
        -o ConnectTimeout=3 -o BatchMode=yes -o LogLevel=ERROR)

say "waiting for ssh (up to ${TIMEOUT}s)"
booted=0
for i in $(seq 1 "$TIMEOUT"); do
	if ssh "${SSHOPT[@]}" root@127.0.0.1 true 2>/dev/null; then
		booted=$i
		break
	fi
	kill -0 "$qemu_pid" 2>/dev/null || { echo; sed 's/^/  | /' "$T/console.log" | tail -40; die "qemu exited"; }
	sleep 1
done
if [ "$booted" = 0 ]; then
	sed 's/^/  | /' "$T/console.log" | tail -60
	die "no ssh after ${TIMEOUT}s. The console log above is the whole boot."
fi
info "ssh answered after ${booted}s"

FAIL=0
check() {
	local label=$1 cmd=$2
	local out
	if out=$(ssh "${SSHOPT[@]}" root@127.0.0.1 "$cmd" 2>&1); then
		printf '  ok     %-38s %s\n' "$label" "$(echo "$out" | head -1)"
	else
		printf '  FAIL   %-38s %s\n' "$label" "$(echo "$out" | head -1)"
		FAIL=$((FAIL + 1))
	fi
}

say "checking the guest"
check "root filesystem"    'findmnt -no SOURCE / | grep -q vda && echo /dev/vda'
check "9p share mounted"   'mountpoint -q /sdcard/Stryker && echo /sdcard/Stryker'
check "guest init ran"     'grep -q ready=1 /sdcard/Stryker/.ssh/ready && cat /sdcard/Stryker/.ssh/ready | tr "\n" " "'
check "host key published" 'test -s /sdcard/Stryker/.ssh/host_fingerprint && cat /sdcard/Stryker/.ssh/host_fingerprint'
check "root has no password" 'grep -q "^root:[!*]" /etc/shadow && echo locked'
check "kernel modules match"  'test -d /lib/modules/$(uname -r) && echo /lib/modules/$(uname -r)'
check "loop devices"       'losetup -f'
check "sftp subsystem"     'grep -qi "internal-sftp" /etc/ssh/sshd_config.d/10-stryker.conf && echo internal-sftp'

say "checking password authentication is refused"
if ssh -p "$PORT" -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null \
       -o PreferredAuthentications=password -o PubkeyAuthentication=no \
       -o ConnectTimeout=3 -o BatchMode=yes -o LogLevel=ERROR \
       root@127.0.0.1 true 2>/dev/null; then
	printf '  FAIL   %s\n' "password authentication succeeded"
	FAIL=$((FAIL + 1))
else
	printf '  ok     %s\n' "password authentication refused"
fi

say "checking the drivers are built in"
for sym in rtl8xxxu rtw88_8812au rtw88_8821au ath9k_htc carl9170 mt7601u \
           mt76x0u mt76x2u rt2800usb rtl8187 vhci-hcd usbip-core; do
	if ssh "${SSHOPT[@]}" root@127.0.0.1 \
			"grep -q '/$sym\.ko' /lib/modules/\$(uname -r)/modules.builtin" 2>/dev/null; then
		printf '  ok     %-38s built in\n' "$sym"
	else
		printf '  FAIL   %-38s not built in\n' "$sym"
		FAIL=$((FAIL + 1))
	fi
done

say "checking the firmware the drivers ask for"
for fw in ath9k_htc/htc_9271-1.4.0.fw mt7601u.bin mediatek/mt7610u.bin \
          rtw88/rtw8812a_fw.bin rtw88/rtw8821a_fw.bin rtw89/rtw8852a_fw.bin \
          rtlwifi/rtl8192eu_nic.bin rt2870.bin regulatory.db; do
	if ssh "${SSHOPT[@]}" root@127.0.0.1 "test -e /lib/firmware/$fw" 2>/dev/null; then
		printf '  ok     %s\n' "$fw"
	else
		printf '  FAIL   %s is missing\n' "$fw"
		FAIL=$((FAIL + 1))
	fi
done

say "checking the tools the app runs"
for t in aircrack-ng airodump-ng aireplay-ng airmon-ng wash reaver bully \
         pixiewps mdk4 hydra nmap macchanger hcxdumptool hcxpcapngtool \
         tcpdump iw wpa_supplicant python3 socat usbip; do
	if ssh "${SSHOPT[@]}" root@127.0.0.1 "command -v $t >/dev/null" 2>/dev/null; then
		printf '  ok     %s\n' "$t"
	else
		printf '  FAIL   %s not found\n' "$t"
		FAIL=$((FAIL + 1))
	fi
done

say "checking the transport the app actually uses"
KH=$T/known_hosts.pinned
: > "$KH"
while read -r line; do
	case "$line" in ''|'#'*) continue ;; esac
	printf '[127.0.0.1]:%s %s\n' "$PORT" "$line" >> "$KH"
done < "$T/share/.ssh/host_keys.pub"
info "pinned $(wc -l < "$KH") host key(s) from the share"

if ssh -i "$T/id" -p "$PORT" -o UserKnownHostsFile="$KH" \
       -o StrictHostKeyChecking=yes -o PreferredAuthentications=publickey \
       -o ConnectTimeout=5 -o BatchMode=yes -o LogLevel=ERROR \
       root@127.0.0.1 true 2>/dev/null; then
	printf '  ok     %s\n' "the pinned host key verifies (StrictHostKeyChecking=yes)"
else
	printf '  FAIL   %s\n' "the key published in the share does not verify against the guest"
	FAIL=$((FAIL + 1))
fi

ptyout=$(ssh -tt -i "$T/id" -p "$PORT" -o StrictHostKeyChecking=no \
         -o UserKnownHostsFile=/dev/null -o ConnectTimeout=5 -o BatchMode=yes \
         -o LogLevel=ERROR root@127.0.0.1 'tty; stty size' 2>/dev/null | tr -d '\r')
case "$ptyout" in
	*/dev/pts/*) printf '  ok     %-38s %s\n' "shell channel gets a pty" "$(echo "$ptyout" | tr '\n' ' ')" ;;
	*) printf '  FAIL   %-38s %s\n' "no pty on the shell channel" "$ptyout"; FAIL=$((FAIL + 1)) ;;
esac

say "shutting down"
ssh "${SSHOPT[@]}" root@127.0.0.1 'nohup poweroff >/dev/null 2>&1 &' 2>/dev/null || true
for i in $(seq 1 20); do kill -0 "$qemu_pid" 2>/dev/null || break; sleep 1; done
cleanup
trap - EXIT

printf '\n'
if [ "$FAIL" -gt 0 ]; then
	printf '%d check(s) failed. Console log: %s\n\n' "$FAIL" "$T/console.log"
	exit 1
fi
printf 'the guest boots, answers on ssh with the key from the share, and has\n'
printf 'every driver, firmware blob and tool the app expects.\n\n'
