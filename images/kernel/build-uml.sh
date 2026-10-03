#!/bin/bash
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
. "$HERE/../lib/common.sh"

if [ -z "${TREE:-}" ]; then
	for c in "$IMAGES_DIR/../../linux-um-arm64" "$IMAGES_DIR/../../mlu-arm64" \
	         "$HOME/linux-um-arm64"; do
		[ -f "$c/Makefile" ] && { TREE=$c; break; }
		[ -f "$c/linux/Makefile" ] && { TREE=$c/linux; break; }
	done
fi
[ -n "${TREE:-}" ] && [ -f "$TREE/Makefile" ] \
	|| die "no UML kernel tree found. Clone the arm64 UML port and point at it:
    TREE=/path/to/linux-um-arm64 $0"
TREE=$(cd "$TREE" && pwd)

API=${API:-30}
JOBS=${JOBS:-$(nproc)}
O=${O:-$WORK_DIR/uml/build}
DEST=$OUT_DIR/uml

if [ -z "${NDK:-}" ]; then
	for c in "${ANDROID_NDK_HOME:-}" "${ANDROID_NDK_ROOT:-}" \
	         "$HOME"/Android/Sdk/ndk/* "$HOME"/Library/Android/sdk/ndk/* \
	         /usr/lib/android-ndk /opt/android-ndk* \
	         "${TMPDIR:-/tmp}"/ndk/android-ndk-*; do
		[ -n "$c" ] && [ -x "$c/toolchains/llvm/prebuilt/linux-x86_64/bin/clang" ] && NDK=$c
	done
fi
TOOL=${NDK:-}/toolchains/llvm/prebuilt/linux-x86_64/bin
[ -x "$TOOL/clang" ] || die "no Android NDK found. Set NDK=/path/to/android-ndk-rXX
  looked at \$NDK, \$ANDROID_NDK_HOME, \$ANDROID_NDK_ROOT,
  ~/Android/Sdk/ndk/*, /opt/android-ndk*, ${TMPDIR:-/tmp}/ndk/*"

need make sed
mkdir -p "$O" "$DEST"

SHIM=$O/.bionic-shim
mkdir -p "$SHIM"
cat > "$SHIM/clang" <<EOF
#!/bin/sh
exec $TOOL/clang "\$@" --target=aarch64-linux-android$API
EOF
cp "$SHIM/clang" "$SHIM/clang++"
chmod +x "$SHIM/clang" "$SHIM/clang++"
for t in ld.lld llvm-ar llvm-nm llvm-objcopy llvm-objdump llvm-readelf llvm-strip; do
	ln -sf "$TOOL/$t" "$SHIM/$t"
done
export PATH="$SHIM:$TOOL:$PATH"

HOST_ARGS=(HOSTCC=/usr/bin/clang HOSTCXX=/usr/bin/clang++)
MAKE_ARGS=(-C "$TREE" O="$O" ARCH=um SUBARCH=arm64 LLVM=1 "${HOST_ARGS[@]}" -j"$JOBS")

say "configuring (head $(git -C "$TREE" rev-parse --short HEAD 2>/dev/null || echo '?'))"
make "${MAKE_ARGS[@]}" defconfig >/dev/null

"$TREE"/scripts/config --file "$O/.config" -e STATIC_LINK -e UML_NET_VECTOR

FRAGMENTS=("$HERE/config/wireless.config" "$HERE/config/uml.config")
for extra in ${EXTRA_CONFIG:-}; do
	[ -f "$extra" ] || die "no such config fragment: $extra"
	FRAGMENTS+=("$extra")
done
"$TREE"/scripts/kconfig/merge_config.sh -m -O "$O" "$O/.config" "${FRAGMENTS[@]}" >/dev/null
make "${MAKE_ARGS[@]}" olddefconfig >/dev/null

say "checking the configuration took"
required=(
	STATIC_LINK UML_NET_VECTOR HOSTFS BLK_DEV_UBD
	UML_IOMEM_EMULATION UML_DMA_EMULATION USBIP_VHCI_HCD
	PACKET CFG80211 MAC80211 RTL8XXXU RTW88_8812AU RTW88_8821AU ATH9K_HTC
	MT7601U RT2800USB BLK_DEV_LOOP
)
missing=
for sym in "${required[@]}"; do
	grep -qx "CONFIG_$sym=y" "$O/.config" || missing="$missing $sym"
done
[ -z "$missing" ] || die "these did not survive olddefconfig:$missing
  Under ARCH=um the usual cause is a missing UML_IOMEM_EMULATION or
  UML_DMA_EMULATION -- without those two nothing that looks like a device
  driver is even offered."
info "all ${#required[@]} required symbols are built in"

say "building with $JOBS jobs"
make "${MAKE_ARGS[@]}"

[ -f "$O/linux" ] || die "the build produced no kernel at $O/linux"

say "collecting"
cp -f "$O/linux" "$DEST/linux-uml"
cp -f "$O/arch/um/kernel/skas/stub_exe" "$DEST/stub_exe"
cp -f "$O/.config" "$DEST/linux-uml.config"

"$TOOL/llvm-strip" "$DEST/linux-uml" 2>/dev/null || warn "llvm-strip failed; shipping unstripped"

record_artifact "$DEST/linux-uml"
record_artifact "$DEST/stub_exe"

printf '\n'
printf '%-22s %s\n' "linux-uml:" "$(human "$(stat -c%s "$DEST/linux-uml")")"
printf '%-22s %s\n' "stub_exe:"  "$(human "$(stat -c%s "$DEST/stub_exe")")"
printf '%-22s %s\n' "libc:"      "bionic (API $API)"
file -b "$DEST/linux-uml" | sed 's/^/   /'
