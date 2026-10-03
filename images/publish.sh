#!/bin/bash
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
. "$HERE/lib/common.sh"

BASE=${1:?usage: publish.sh <base-url>
  e.g. https://github.com/zalexdev/strykerapp/releases/download}
BASE=${BASE%/}
PUB=$OUT_DIR/publish
CHROOT_TAG=${CHROOT_TAG:-chroot-main}
ROOTLESS_TAG=${ROOTLESS_TAG:-rootless-main}

[ -f "$OUT_DIR/artifacts.tsv" ] || die "nothing built yet -- run images/build-all.sh"

rm -rf "$PUB"
mkdir -p "$PUB/$CHROOT_TAG" "$PUB/$ROOTLESS_TAG" "$PUB/drivers"

place() {
	local src=$1 dst=$2
	[ -f "$src" ] || return 1
	cp -f "$src" "$dst"
	printf '%s\n' "$dst"
}

say "collecting"
place "$OUT_DIR/chroot64-debian.tar.gz" "$PUB/$CHROOT_TAG/chroot64-debian.tar.gz" >/dev/null \
	|| warn "no chroot tarball"
place "$OUT_DIR/vm/Image"        "$PUB/$ROOTLESS_TAG/Image"       >/dev/null || warn "no VM kernel"
place "$OUT_DIR/vm/initrd.img"   "$PUB/$ROOTLESS_TAG/initrd.img"  >/dev/null || warn "no initrd"
place "$OUT_DIR/vm/rootfs.imgz"  "$PUB/$ROOTLESS_TAG/rootfs.imgz" >/dev/null || warn "no VM rootfs"
place "$OUT_DIR/uml/linux-uml"   "$PUB/$ROOTLESS_TAG/linux-uml"   >/dev/null || true
place "$OUT_DIR/uml/stub_exe"    "$PUB/$ROOTLESS_TAG/stub_exe"    >/dev/null || true
for f in qemu-system-aarch64 libslirp.so; do
	[ -f "$OUT_DIR/prebuilt/$f" ] && place "$OUT_DIR/prebuilt/$f" "$PUB/$ROOTLESS_TAG/$f" >/dev/null
done
cp -f "$OUT_DIR"/drivers/*.deb "$OUT_DIR"/drivers/Packages.gz "$PUB/drivers/" 2>/dev/null || true

{
	printf 'images        %s\n' "$(git -C "$IMAGES_DIR" rev-parse HEAD 2>/dev/null || echo 'not a git checkout')"
	printf 'kernel        %s\n' "$(cat "$OUT_DIR/vm/kernel.release" 2>/dev/null || echo '-')"
	printf 'kernel source %s\n' "$(cat "$OUT_DIR/vm/kernel.source" 2>/dev/null || echo '-')"
	printf 'suite         %s\n' "${SUITE:-trixie}"
	printf 'built         %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
	printf 'reproduce     images/build-all.sh\n'
	printf '\n'
	printf 'Uncompressed checksums, for the artifacts that ship compressed --\n'
	printf 'the gzip wrapper is only reproducible with the same compressor\n'
	printf 'settings, the content underneath it always is:\n'
	for f in "$OUT_DIR/vm/rootfs.img"; do
		[ -f "$f" ] && printf '  %s  %s\n' "$(sha256_of "$f")" "$(basename "$f")"
	done
} > "$PUB/BUILDINFO.txt"
cp -f "$OUT_DIR/vm/Image.config" "$PUB/$ROOTLESS_TAG/Image.config" 2>/dev/null || true

say "checksums"
asset() {
	local file=$1 url=$2 indent=$3
	[ -f "$file" ] || return 1
	printf '%s"url": "%s",\n'    "$indent" "$url"
	printf '%s"sha256": "%s",\n' "$indent" "$(sha256_of "$file")"
	printf '%s"size": %s\n'      "$indent" "$(stat -c%s "$file")"
}

MF=$PUB/manifest-fragment.json
{
	printf '{\n'
	printf '  "core": {\n'
	printf '    "debian": {\n'
	printf '      "min_version_code": 600,\n'
	printf '      "version": "chroot-debian-%s",\n' "${SUITE:-trixie}"
	printf '      "note": "Debian %s arm64. Same tree as the rootless VM image; drivers are built into the guest kernel, SSH is the transport.",\n' "${SUITE:-trixie}"
	printf '      "chroot64": {\n'
	asset "$PUB/$CHROOT_TAG/chroot64-debian.tar.gz" \
	      "$BASE/$CHROOT_TAG/chroot64-debian.tar.gz" '        '
	printf '      }\n'
	printf '    }\n'
	printf '  },\n'
	printf '  "rootless": {\n'
	printf '    "version": "%s",\n' "$ROOTLESS_TAG"
	for pair in "kernel:Image" "initrd:initrd.img" "rootfs:rootfs.imgz" \
	            "uml_kernel:linux-uml" "uml_stub:stub_exe"; do
		key=${pair%%:*}; name=${pair##*:}
		[ -f "$PUB/$ROOTLESS_TAG/$name" ] || continue
		printf '    "%s": {\n' "$key"
		asset "$PUB/$ROOTLESS_TAG/$name" "$BASE/$ROOTLESS_TAG/$name" '      '
		printf '    },\n'
	done
	printf '    "kernel_release": "%s"\n' "$(cat "$OUT_DIR/vm/kernel.release" 2>/dev/null || echo '')"
	printf '  }\n'
	printf '}\n'
} > "$MF"

if command -v python3 >/dev/null 2>&1; then
	python3 -c "import json,sys; json.load(open(sys.argv[1]))" "$MF" \
		&& info "manifest fragment parses as JSON" \
		|| die "the fragment this wrote is not valid JSON: $MF"
fi

printf '\n'
find "$PUB" -type f -printf '%-52p %10s\n' | sort | sed 's/^/   /'
printf '\n%s\n' "manifest block: $MF"
printf '%s\n' "Merge it into stryker_manifest.json, upload out/publish/<tag>/* to the"
printf '%s\n' "matching release tag, and keep the legacy core.chroot64/chroot32 keys as"
printf '%s\n' "they are -- builds below version 6 read those directly and cannot be changed."
