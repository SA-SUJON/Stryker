#!/bin/bash

set -u

VHCI=${STRYKER_VHCI:-/sys/devices/platform/vhci_hcd.0}
WAIT_DEVICE=${STRYKER_WAIT_DEVICE:-10}
WAIT_NETDEV=${STRYKER_WAIT_NETDEV:-10}
ERR=$(mktemp)
trap 'rm -f "$ERR"' EXIT

WANT_VID=${STRYKER_WANT_VID:-}
WANT_PID=${STRYKER_WANT_PID:-}
NEED_DRIVER=0

prog=${0##*/}

say()  { printf '%s: %s\n' "$prog" "$*"; }
fail() { printf '%s: %s\n' "$prog" "$*" >&2; exit 1; }

tail_dmesg() {
	command -v dmesg >/dev/null 2>&1 || return 0
	printf '%s: last matching kernel messages:\n' "$prog" >&2
	dmesg 2>/dev/null | grep -iE 'usb|vhci|firmware|ath9k|rtw8|rtl8|rt2800|mt76|blue' |
		tail -n 12 | sed 's/^/  | /' >&2
}

fw_diagnose() {
	local fw path pkg
	fw=$(dmesg 2>/dev/null |
		sed -n 's/.*Direct firmware load for \([^ ]*\) failed.*/\1/p' | tail -1)
	[ -n "$fw" ] || return 0
	path=$(cat /sys/module/firmware_class/parameters/path 2>/dev/null)
	say "the driver asked for firmware '$fw' and the loader did not find it."
	say "  searched ${path:-(no firmware_class.path set)} and /lib/firmware"
	case "$fw" in
	ath9k_htc/*)			pkg=firmware-ath9k-htc ;;
	rtlwifi/*|rtw88/*|rtw89/*|rtl_bt/*)	pkg=firmware-realtek ;;
	ath10k/*|ath11k/*|ar3k/*)	pkg=firmware-atheros ;;
	mediatek/*)			pkg=firmware-mediatek ;;
	*)				pkg=firmware-misc-nonfree ;;
	esac
	say "  'apt install $pkg' in the guest installs it"
	say "  or drop the file into the Stryker share under firmware/ -- the guest"
	say "  searches it as firmware_class.path"
}

speed_num() {
	case "$1" in
	low|1)			echo 1 ;;
	full|2)			echo 2 ;;
	high|3)			echo 3 ;;
	wireless|4)		echo 4 ;;
	super|5)		echo 5 ;;
	super-plus|super+|6)	echo 6 ;;
	*)			echo "" ;;
	esac
}

FD=""
CONNECT=""
DETACH=""
SPEED=""
DEVID=""

while [ $# -gt 0 ]; do
	case "$1" in
	--wifi)		NEED_DRIVER=1; shift ;;
	--connect)	[ $# -ge 2 ] || fail "$1 needs a value"; CONNECT=$2; shift 2 ;;
	--detach)	[ $# -ge 2 ] || fail "$1 needs a value"; DETACH=$2; shift 2 ;;
	--speed)	[ $# -ge 2 ] || fail "$1 needs a value"; SPEED=$2; shift 2 ;;
	--devid)	[ $# -ge 2 ] || fail "$1 needs a value"; DEVID=$2; shift 2 ;;
	--want)		[ $# -ge 2 ] || fail "$1 needs a value"
			WANT_VID=${2%%:*}; WANT_PID=${2##*:}; shift 2 ;;
	-h|--help)	sed -n '2,6p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
	-*)		fail "unknown option $1" ;;
	*)		FD=$1; shift ;;
	esac
done

SPEED=$(speed_num "${SPEED:-high}")
[ -n "$SPEED" ] || fail "--speed must be one of low full high wireless super super-plus (or 1..6)"

DEVID=${DEVID:-$(( (1 << 16) | 1 ))}

[ -d "$VHCI" ] || fail "no $VHCI: this kernel has no USB/IP virtual host controller.
  A kernel built with CONFIG_USBIP_VHCI_HCD prints 'USB/IP Virtual Host Controller'
  and 'hub 1-0:1.0: USB hub found' at boot -- check dmesg."
[ -w "$VHCI/attach" ] || fail "$VHCI/attach is not writable (running as uid $(id -u), needs root)"
[ -r "$VHCI/status" ] || fail "$VHCI/status is unreadable, so no free port can be chosen"

# --detach: the port is freed asynchronously, so this only asks.
if [ -n "$DETACH" ]; then
	printf '%u' "$DETACH" > "$VHCI/detach" 2>/dev/null ||
		fail "detach of port $DETACH failed -- port not attached?"
	say "detach requested for port $DETACH"
	exit 0
fi

# --connect: bash opens the TCP connection itself and keeps the descriptor in
# this shell, which is exactly where the sysfs write needs it.
if [ -n "$CONNECT" ]; then
	[ -z "$FD" ] || fail "give either --connect HOST:PORT or an fd number, not both"
	chost=${CONNECT%:*}
	cport=${CONNECT##*:}
	[ -n "$chost" ] && [ -n "$cport" ] && [ "$chost" != "$cport" ] ||
		fail "--connect wants HOST:PORT, got '$CONNECT'"
	exec {sock}<>"/dev/tcp/$chost/$cport" ||
		fail "cannot connect to $chost:$cport.
  umusb has to be listening there on the Android side, and the guest reaches the
  phone's loopback through passt's gateway address (10.0.2.2 by default). If the
  guest has no network at all, 'ip addr show vec0' will say so first."
	FD=$sock
	say "connected to $chost:$cport on fd $FD"
fi

[ -n "$FD" ] || fail "no socket given: pass an inherited fd number, or --connect HOST:PORT"
case "$FD" in
''|*[!0-9]*)	fail "'$FD' is not a file descriptor number" ;;
esac

[ -e "/proc/self/fd/$FD" ] ||
	fail "fd $FD is not open in this shell.
  The descriptor must be open in the process that writes to $VHCI/attach,
  because vhci looks it up in the caller's file table."
[ -S "/proc/self/fd/$FD" ] ||
	fail "fd $FD is not a socket ($(readlink "/proc/self/fd/$FD")).
  vhci rejects anything that is not SOCK_STREAM with EINVAL."

# If it is a TCP socket, /proc/net/tcp knows whether it ever connected. A
# half-open descriptor is accepted by the attach and then simply never answers,
# which looks like a device that does not enumerate -- an expensive way to find
# out that nothing was listening.
sockino=$(readlink "/proc/self/fd/$FD")
sockino=${sockino#socket:[}
sockino=${sockino%]}
tcpstate=$(awk -v ino="$sockino" '$10 == ino { print $4; exit }' \
	/proc/net/tcp /proc/net/tcp6 2>/dev/null)
if [ -n "$tcpstate" ] && [ "$tcpstate" != "01" ]; then
	fail "fd $FD is a TCP socket in state 0x$tcpstate, not ESTABLISHED (0x01)"
fi

# Pick a port, the way the usbip tool does it: read the status table and take
# the first free port on the hub that matches the speed. Both halves matter --
# attach_store() derives rhport as (port % VHCI_HC_PORTS) and then picks the
# high-speed or super-speed hub from the *speed* argument alone, so a
# super-speed port number written with speed=high quietly lands on the
# high-speed hub instead, and the status table then disagrees with what was
# asked for.
#
#	hub port sta spd dev      sockfd local_busid
#	hs  0000 004 000 00000000 000000 0-0
#
# sta 004 is VDEV_ST_NULL (free), 005 VDEV_ST_NOTASSIGNED, 006 VDEV_ST_USED,
# 007 VDEV_ST_ERROR -- see enum usbip_device_status in <linux/usbip.h>.
if [ "$SPEED" -ge 5 ]; then WANT_HUB=ss; else WANT_HUB=hs; fi

STALE=""
while read -r _hub port _sta _spd dev _rest; do
	case "$_hub" in hs|ss) ;; *) continue ;; esac
	[ "$((10#$_sta))" -eq 6 ] || continue
	[ "$dev" = "$(printf '%08x' "$DEVID")" ] || continue
	STALE=$((10#$port))
	break
done < "$VHCI/status"

if [ -n "$STALE" ]; then
	say "device $DEVID is still on port $STALE from an earlier attach -- taking it back"
	printf '%u' "$STALE" > "$VHCI/detach" 2>/dev/null ||
		say "could not detach port $STALE; carrying on"
	i=0
	while [ "$i" -lt 20 ]; do
		awk -v p="$STALE" '$2+0==p && $3+0!=4 { found=1 } END { exit !found }' \
			"$VHCI/status" || break
		sleep 0.1
		i=$((i + 1))
	done
fi

PORT=""
while read -r hub port sta _rest; do
	case "$hub" in hs|ss) ;; *) continue ;; esac
	[ "$hub" = "$WANT_HUB" ] || continue
	[ "$((10#$sta))" -eq 4 ] || continue
	PORT=$((10#$port))
	break
done < "$VHCI/status"

[ -n "$PORT" ] || {
	printf '%s: no free %s port on the virtual host controller:\n' "$prog" "$WANT_HUB" >&2
	sed 's/^/  | /' < "$VHCI/status" >&2
	fail "free one with '$prog --detach PORT'"
}

say "attaching fd $FD on port $PORT (hub $WANT_HUB, speed $SPEED, devid $DEVID)"

# The builtin redirection keeps the write in this process; see the header.
if ! printf '%u %u %u %u' "$PORT" "$FD" "$DEVID" "$SPEED" > "$VHCI/attach" 2>"$ERR"; then
	err=$(cat "$ERR" 2>/dev/null)
	case "$err" in
	*"Invalid argument"*)
		fail "attach refused with EINVAL: $err
  vhci says the descriptor is not a SOCK_STREAM socket, or the port/speed pair
  is out of range. Speed was $SPEED, port $PORT." ;;
	*"busy"*)
		fail "attach refused with EBUSY: port $PORT was taken between reading
  status and writing attach. Just run it again." ;;
	*"temporarily unavailable"*)
		fail "attach refused with EAGAIN: the controller for port $PORT is not
  ready. This is a driver-probe problem on this side, not a socket problem." ;;
	*)
		fail "attach failed: $err" ;;
	esac
fi

# Printed before the waits below, because a detach has to be possible even when
# enumeration then fails: the port is taken from the moment the write returned.
printf "STRYKER_USB_PORT=%s\n" "$PORT"

# Enumeration is the server's first real test: the guest now sends control
# transfers for the descriptors and umusb has to turn them into usbfs ioctls.
BUSID=""
for _ in $(seq $((WAIT_DEVICE * 10))); do
	line=$(awk -v want="$PORT" -v hub="$WANT_HUB" \
		'$1 == hub && 0+$2 == want { print; exit }' "$VHCI/status")
	set -- $line
	sta=${3:-000}
	busid=${7:-0-0}
	if [ "$((10#$sta))" -eq 7 ]; then
		tail_dmesg
		fail "port $PORT went to VDEV_ST_ERROR: the connection dropped or the
  server sent something vhci could not parse."
	fi
	if [ "$busid" != "0-0" ] && [ -n "$busid" ]; then
		BUSID=$busid
		break
	fi
	sleep 0.1
done

if [ -z "$BUSID" ]; then
	tail_dmesg
	fail "the socket was accepted but no device enumerated within ${WAIT_DEVICE}s.
  vhci is waiting for descriptor replies that never came: umusb is either not
  reading the USB/IP submit headers, not answering them, or answering on a
  device it cannot open."
fi

DEV=/sys/bus/usb/devices/$BUSID
say "device enumerated as $BUSID"
if [ -r "$DEV/idVendor" ]; then
	vid=$(cat "$DEV/idVendor")
	pid=$(cat "$DEV/idProduct")
	say "  $vid:$pid $(cat "$DEV/manufacturer" 2>/dev/null) $(cat "$DEV/product" 2>/dev/null)"
	if [ -n "$WANT_VID$WANT_PID" ] && [ "$vid$pid" != "$WANT_VID$WANT_PID" ]; then
		say "  (expected $WANT_VID:$WANT_PID -- umusb is serving a different device)"
	fi
fi

# What bound to it. An interface with no driver is the interesting case: the
# device is there, the ids do not match anything built in, and no amount of
# waiting will change that.
bound=0
for intf in "$DEV":*; do
	[ -d "$intf" ] || continue
	if [ -L "$intf/driver" ]; then
		drv=$(readlink -f "$intf/driver")
		say "  ${intf##*/} bound to ${drv##*/}"
		printf "STRYKER_USB_DRIVER=%s\n" "${drv##*/}"
		bound=1
	else
		say "  ${intf##*/} has no driver"
	fi
done
if [ "$bound" -eq 0 ] && [ "$NEED_DRIVER" -eq 1 ]; then
	tail_dmesg
	fail "nothing claimed the device. 'ls /sys/bus/usb/drivers' shows what this
  kernel has; for a WiFi adapter the driver has to be built in."
fi

# The path a container is given with --device, and what a userspace driver
# opens. On its own line and in a greppable form, because for a passthrough
# with no in-kernel driver this is the whole product of the exercise.
BUSNUM=$(cat "$DEV/busnum" 2>/dev/null)
DEVNUM=$(cat "$DEV/devnum" 2>/dev/null)
if [ -n "$BUSNUM" ] && [ -n "$DEVNUM" ]; then
	NODE=$(printf "/dev/bus/usb/%03d/%03d" "$BUSNUM" "$DEVNUM")
	say "device node $NODE"
	printf "STRYKER_USB_NODE=%s\n" "$NODE"
fi

# What the guest itself can do with the device, beyond handing the node on.
#
# Only worth waiting for if something bound: with no driver there is nothing
# that could register an interface, and the wait would be ten seconds spent
# proving it. Both kinds are looked for in the same loop because a combo dongle
# registers both, and because they are found the same way -- through the device
# link back to the USB interface, not by name. The name is not dependable:
# there is no udev here, but a guest that already had a wireless device shifts
# the numbering, and "some wlan0 appeared" is not the same claim as "the device
# just attached has one".
#
# mac80211 registers the netdev during probe, before any firmware is needed, so
# a name appearing here is not yet proof the adapter works -- only that the
# driver got as far as registering a wiphy. Bringing it up below is the test.
NETDEV=""
HCI=""
if [ "$bound" -eq 1 ]; then
	for _ in $(seq $((WAIT_NETDEV * 10))); do
		for n in /sys/class/net/*; do
			[ -e "$n/phy80211" ] || continue
			case "$(readlink -f "$n/device" 2>/dev/null)" in
			*/"$BUSID":*)	NETDEV=${n##*/}; break ;;
			esac
		done
		for h in /sys/class/bluetooth/hci*; do
			[ -d "$h" ] || continue
			case "$(readlink -f "$h/device" 2>/dev/null)" in
			*/"$BUSID":*)	HCI=${h##*/}; break ;;
			esac
		done
		[ -n "$NETDEV$HCI" ] && break
		sleep 0.1
	done
fi

if [ -n "$NETDEV" ]; then
	phy=$(readlink -f "/sys/class/net/$NETDEV/phy80211")
	say "wireless interface $NETDEV on ${phy##*/}"
	printf "STRYKER_USB_NETDEV=%s\n" "$NETDEV"
	printf "STRYKER_USB_PHY=%s\n" "${phy##*/}"

	# rfkill first: a fresh wiphy can come up soft-blocked, and "Operation not
	# possible due to RF-kill" is the error people spend longest on.
	rfkill unblock all 2>/dev/null ||
		for f in /sys/class/rfkill/*/state; do echo 1 > "$f" 2>/dev/null; done
	if ! ip link set "$NETDEV" up 2>"$ERR"; then
		err=$(cat "$ERR" 2>/dev/null)
		fw_diagnose
		tail_dmesg
		fail "'ip link set $NETDEV up' failed: $err"
	fi
	say "$NETDEV is up"
fi

if [ -n "$HCI" ]; then
	say "bluetooth controller $HCI"
	printf "STRYKER_USB_HCI=%s\n" "$HCI"
	# Down is how btusb leaves it; bluetoothd would normally bring it up. Done
	# here so that a tool that only reads -- an LE scanner -- finds a
	# controller that works.
	if ! hciconfig "$HCI" up 2>"$ERR" && ! btmgmt --index "${HCI#hci}" power on 2>>"$ERR"; then
		fw_diagnose
		say "could not power up $HCI: $(cat "$ERR" 2>/dev/null)"
	fi
fi

if [ "$NEED_DRIVER" -eq 1 ] && [ -z "$NETDEV" ]; then
	tail_dmesg
	fail "the driver bound but no wireless interface appeared within ${WAIT_NETDEV}s.
  A failed efuse read or a failed USB control transfer in the log above means
  the URB path to the device is wrong even though enumeration worked."
fi

printf "STRYKER_USB_ATTACHED=%s\n" "$BUSID"
