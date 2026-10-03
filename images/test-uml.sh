#!/bin/bash
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
. "$HERE/lib/common.sh"

VMOUT=$OUT_DIR/vm
UMLOUT=$OUT_DIR/uml
T=$WORK_DIR/test-uml
PORT=${PORT:-2298}
TIMEOUT=${TIMEOUT:-240}

need qemu-system-aarch64 ssh ssh-keygen
for f in "$VMOUT/Image" "$VMOUT/initrd.img" "$VMOUT/rootfs.img" \
         "$UMLOUT/linux-uml" "$UMLOUT/stub_exe"; do
	[ -f "$f" ] || die "missing $f -- run build-all.sh first"
done

rm -rf "$T"
mkdir -p "$T/share/.ssh"
say "preparing"
cp --sparse=always "$VMOUT/rootfs.img" "$T/outer.img"
truncate -s 4G "$T/outer.img"
e2fsck -fp "$T/outer.img" >/dev/null 2>&1 || true
resize2fs "$T/outer.img" >/dev/null 2>&1 || true
cp --sparse=always "$VMOUT/rootfs.img" "$T/share/inner.img"
truncate -s 3G "$T/share/inner.img"
info "image sha256: $(sha256_of "$VMOUT/rootfs.img")"

cp -f "$UMLOUT/linux-uml" "$UMLOUT/stub_exe" "$T/share/"
ssh-keygen -t ed25519 -N '' -C stryker-uml-test -f "$T/id" >/dev/null
cp "$T/id.pub" "$T/share/.ssh/authorized_keys"

say "booting the outer VM"
qemu-system-aarch64 \
	-M virt,gic-version=3 -cpu cortex-a72 -m 4096 -smp 4 \
	-kernel "$VMOUT/Image" -initrd "$VMOUT/initrd.img" \
	-append "root=/dev/vda rw rootwait rootflags=noatime console=ttyAMA0 loglevel=4 net.ifnames=0" \
	-drive "file=$T/outer.img,if=none,id=d0,format=raw,cache=writeback,aio=threads" \
	-device virtio-blk-pci,drive=d0 \
	-netdev "user,id=n0,ipv6=off,hostfwd=tcp:127.0.0.1:$PORT-:22" \
	-device virtio-net-pci,netdev=n0,romfile= \
	-fsdev "local,id=f0,security_model=none,path=$T/share" \
	-device virtio-9p-pci,fsdev=f0,mount_tag=strykershare \
	-display none -serial "file:$T/outer.log" -monitor none \
	-pidfile "$T/qemu.pid" -daemonize

qemu_pid=$(cat "$T/qemu.pid")
cleanup() { kill "$qemu_pid" 2>/dev/null || true; }
trap cleanup EXIT

SSHOPT=(-i "$T/id" -p "$PORT" -o StrictHostKeyChecking=no
        -o UserKnownHostsFile=/dev/null -o ConnectTimeout=3
        -o BatchMode=yes -o LogLevel=ERROR)

say "waiting for the outer guest"
for i in $(seq 1 "$TIMEOUT"); do
	ssh "${SSHOPT[@]}" root@127.0.0.1 true 2>/dev/null && break
	kill -0 "$qemu_pid" 2>/dev/null || { tail -30 "$T/outer.log"; die "outer VM exited"; }
	[ "$i" = "$TIMEOUT" ] && { tail -40 "$T/outer.log"; die "outer guest never answered"; }
	sleep 1
done
info "outer guest up (this is the image booted as /dev/vda)"

say "running UML inside it, on the same image"
uml_out=$(ssh "${SSHOPT[@]}" root@127.0.0.1 '
	set -e
	S=/sdcard/Stryker
	cp -f $S/linux-uml $S/stub_exe /tmp/
	chmod +x /tmp/linux-uml /tmp/stub_exe
	mkdir -p /tmp/umlshare
	# A static aarch64 binary; if the loader refuses it, say so plainly rather
	# than leaving a timeout to be interpreted.
	/tmp/linux-uml --version >/dev/null 2>&1 || echo "__UML_WONT_RUN__"
	# Piped into cat rather than redirected straight to a file, and that is not
	# cosmetic: UML drives its console with epoll, and epoll_ctl() refuses a
	# regular file with EPERM. Redirected to /tmp/uml.log directly the kernel
	# boots as far as the console driver and then stops with
	#   epollctl add err fd 1, Operation not permitted
	# which looks like the image failing to boot and is the harness handing it
	# a file descriptor it cannot poll. A pipe is pollable.
	( timeout 120 /tmp/linux-uml \
		ubda=$S/inner.img \
		root=/dev/ubda rw \
		init=/stryker-init \
		stryker.share=/tmp/umlshare \
		mem=1024M con=null con0=fd:0,fd:1 \
		< /dev/null 2>&1 | cat > /tmp/uml.log ) || true
	echo "__UML_CONSOLE__"
	cat /tmp/uml.log
' 2>&1) || true

printf '%s\n' "$uml_out" | sed 's/^/  | /' | tail -45

FAIL=0
note() { printf '  %-6s %s\n' "$1" "$2"; [ "$1" = FAIL ] && FAIL=$((FAIL+1)); return 0; }

say "what the UML boot showed"
case "$uml_out" in
	*__UML_WONT_RUN__*)
		note FAIL "the UML kernel would not exec on this aarch64 host"
		;;
esac
case "$uml_out" in
	*"STRYKER_BOOT mounted"*) note ok   "UML mounted the image" ;;
	*)                        note FAIL "no mount marker from stryker-init" ;;
esac
case "$uml_out" in
	*"STRYKER_INIT ready"*|*"STRYKER_BOOT ready"*) note ok "guest init ran to completion" ;;
	*) note FAIL "stryker-guest-init did not reach its ready marker" ;;
esac
case "$uml_out" in
	*"STRYKER_BOOT sshd"*) note ok   "sshd started under UML" ;;
	*)                     note FAIL "sshd did not start" ;;
esac
if ssh "${SSHOPT[@]}" root@127.0.0.1 'grep -q "engine=uml" /tmp/umlshare/.ssh/ready' 2>/dev/null; then
	note ok "wrote its ready marker into the hostfs share, tagged engine=uml"
else
	note FAIL "no ready marker in the hostfs share"
fi

say "shutting down"
ssh "${SSHOPT[@]}" root@127.0.0.1 'nohup poweroff >/dev/null 2>&1 &' 2>/dev/null || true
for i in $(seq 1 20); do kill -0 "$qemu_pid" 2>/dev/null || break; sleep 1; done
cleanup
trap - EXIT

printf '\n'
if [ "$FAIL" -gt 0 ]; then
	printf '%d check(s) failed. Outer console: %s\n\n' "$FAIL" "$T/outer.log"
	exit 1
fi
printf 'the same rootfs.img boots under both kernels: as /dev/vda in the VM and\n'
printf 'as /dev/ubda under UML, with the same init and the same guest setup.\n\n'
