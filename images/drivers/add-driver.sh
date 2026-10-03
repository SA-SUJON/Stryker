#!/bin/bash
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
. "$HERE/../lib/common.sh"

KDIR=${KDIR:-$WORK_DIR/vm/build}
DEST=$OUT_DIR/drivers
SRCDIR=$WORK_DIR/drivers
CROSS=${CROSS_COMPILE:-aarch64-linux-gnu-}

need git make dpkg-deb
command -v "${CROSS}gcc" >/dev/null || die "no ${CROSS}gcc (apt install gcc-aarch64-linux-gnu)"
[ -f "$KDIR/.config" ] || die "no kernel build tree at $KDIR
  Run images/kernel/build-vm.sh first. A module has to be compiled against the
  configuration and toolchain of the kernel that will load it -- headers of the
  same version are not enough, and a mismatch shows up as 'invalid module
  format' after the user has already installed the package."

KREL=$(cat "$OUT_DIR/vm/kernel.release" 2>/dev/null || make -C "$KDIR" -s kernelrelease)
[ -n "$KREL" ] || die "cannot determine the kernel release"
mkdir -p "$DEST" "$SRCDIR"

build_one() {
	local name=$1 url=$2 ref=$3 sub=${4:-.}
	local src=$SRCDIR/$name

	say "$name"
	if [ -d "$src/.git" ]; then
		git -C "$src" fetch --depth 1 origin "$ref" >/dev/null 2>&1 || true
		git -C "$src" checkout -q FETCH_HEAD 2>/dev/null || git -C "$src" checkout -q "$ref"
	else
		rm -rf "$src"
		git clone --depth 1 --branch "$ref" "$url" "$src" >/dev/null 2>&1 \
			|| git clone --depth 1 "$url" "$src" >/dev/null
	fi
	local rev
	rev=$(git -C "$src" rev-parse --short HEAD)
	info "source $url @ $rev"

	local build=$src/$sub

	if grep -qE '(^|[^A-Z_])EXTRA_CFLAGS' "$build/Makefile" 2>/dev/null; then
		info "rewriting EXTRA_CFLAGS to ccflags-y (removed from kbuild in 6.13)"
		find "$build" \( -name Makefile -o -name '*.mk' \) \
			-exec sed -i 's/\bEXTRA_CFLAGS\b/ccflags-y/g' {} +
	fi

	make -C "$build" clean >/dev/null 2>&1 || true
	if ! make -C "$build" -j"$(nproc)" \
			ARCH=arm64 CROSS_COMPILE="$CROSS" \
			CONFIG_PLATFORM_I386_PC=n CONFIG_PLATFORM_ARM64_RPI=y \
			KSRC="$KDIR" KDIR="$KDIR" KERNELDIR="$KDIR" \
			KVER="$KREL" KERNEL_VERSION="$KREL" \
			modules 2>&1 | tail -30; then
		warn "$name failed to build against $KREL -- skipping"
		return 1
	fi

	local kos
	kos=$(find "$build" -name '*.ko' -newer "$build/Makefile" 2>/dev/null)
	[ -n "$kos" ] || kos=$(find "$build" -name '*.ko')
	[ -n "$kos" ] || { warn "$name produced no .ko"; return 1; }

	local ver="1.0~${rev}"
	local pkgdir=$WORK_DIR/drivers/pkg-$name
	rm -rf "$pkgdir"
	mkdir -p "$pkgdir/DEBIAN" "$pkgdir/lib/modules/$KREL/updates"
	for ko in $kos; do
		"${CROSS}strip" --strip-debug "$ko" 2>/dev/null || true
		install -m 644 "$ko" "$pkgdir/lib/modules/$KREL/updates/"
	done
	if [ -d "$build/firmware" ]; then
		mkdir -p "$pkgdir/lib/firmware"
		cp -a "$build/firmware/." "$pkgdir/lib/firmware/"
	fi

	cat > "$pkgdir/DEBIAN/control" <<EOF
Package: stryker-driver-$name
Version: $ver
Architecture: arm64
Maintainer: Stryker images <images@localhost>
Section: kernel
Priority: optional
Depends: kmod
Description: $name wireless driver for the Stryker guest kernel $KREL
 Out-of-tree build of $name, compiled against the guest kernel $KREL.
 .
 The in-tree driver for this chip, where there is one, is already built into
 the kernel and supports the adapter as a client. This build is the fork that
 supports monitor mode and frame injection.
 .
 Built from $url at $rev.
EOF
	cat > "$pkgdir/DEBIAN/postinst" <<EOF
#!/bin/sh
set -e
depmod -a $KREL || true
if command -v update-initramfs >/dev/null 2>&1; then
	update-initramfs -u -k $KREL >/dev/null 2>&1 || true
fi
exit 0
EOF
	cat > "$pkgdir/DEBIAN/postrm" <<EOF
#!/bin/sh
set -e
depmod -a $KREL 2>/dev/null || true
exit 0
EOF
	chmod 755 "$pkgdir/DEBIAN/postinst" "$pkgdir/DEBIAN/postrm"

	local deb=$DEST/stryker-driver-${name}_${ver}_arm64.deb
	rm -f "$deb"
	dpkg-deb --root-owner-group --build "$pkgdir" "$deb" >/dev/null
	rm -rf "$pkgdir"
	record_artifact "$deb"
	info "$(basename "$deb")  $(human "$(stat -c%s "$deb")")"
}

list_entries() {
	grep -vE '^\s*(#|$)' "$HERE/drivers.list" | while IFS='|' read -r n u r s; do
		printf '%s|%s|%s|%s\n' \
			"$(echo "$n" | xargs)" "$(echo "$u" | xargs)" \
			"$(echo "$r" | xargs)" "$(echo "${s:-.}" | xargs)"
	done
}

target=${1:-all}
failed=0
if [ "$target" = all ]; then
	while IFS='|' read -r n u r s; do
		build_one "$n" "$u" "$r" "$s" || failed=$((failed + 1))
	done < <(list_entries)
elif [ $# -ge 2 ]; then
	build_one "$1" "$2" "${3:-main}" "${4:-.}" || failed=1
else
	entry=$(list_entries | awk -F'|' -v n="$target" '$1 == n')
	[ -n "$entry" ] || die "no such driver in drivers.list: $target
  Known: $(list_entries | cut -d'|' -f1 | tr '\n' ' ')
  Or give it directly: $0 <name> <git-url> [ref] [subdir]"
	IFS='|' read -r n u r s <<< "$entry"
	build_one "$n" "$u" "$r" "$s" || failed=1
fi

if ls "$DEST"/*.deb >/dev/null 2>&1; then
	say "repository index"
	if command -v dpkg-scanpackages >/dev/null 2>&1; then
		( cd "$DEST" && dpkg-scanpackages -m . /dev/null 2>/dev/null | gzip -9n > Packages.gz )
		info "$(basename "$DEST")/Packages.gz"
	else
		warn "no dpkg-scanpackages (apt install dpkg-dev) -- index not written."
		warn "The .deb files are still installable with 'dpkg -i'."
	fi
fi

printf '\n'
ls -la "$DEST"/*.deb 2>/dev/null | sed 's/^/   /'
[ "$failed" = 0 ] || { printf '\n%d driver(s) failed to build.\n' "$failed"; exit 1; }
