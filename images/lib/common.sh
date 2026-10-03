IMAGES_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
OUT_DIR=${OUT_DIR:-$IMAGES_DIR/out}
WORK_DIR=${WORK_DIR:-$IMAGES_DIR/work}

say()  { printf '\n== %s ==\n' "$*"; }
info() { printf '   %s\n' "$*"; }
warn() { printf '   ! %s\n' "$*" >&2; }
die()  { printf '%s: %s\n' "${0##*/}" "$*" >&2; exit 2; }

need() {
	local missing=
	for t in "$@"; do
		command -v "$t" >/dev/null 2>&1 || missing="$missing $t"
	done
	[ -z "$missing" ] || die "missing tools:$missing"
}

need_root() {
	[ "$(id -u)" = 0 ] || die "run as root: this mounts loop devices and makes device nodes"
}

export KBUILD_BUILD_USER=${KBUILD_BUILD_USER:-stryker}
export KBUILD_BUILD_HOST=${KBUILD_BUILD_HOST:-images}
export KBUILD_BUILD_TIMESTAMP=${KBUILD_BUILD_TIMESTAMP:-"Thu Jan  1 00:00:00 UTC 1970"}
export SOURCE_DATE_EPOCH=${SOURCE_DATE_EPOCH:-0}

GZ_JOBS=${GZ_JOBS:-8}
gz() {
	if command -v pigz >/dev/null 2>&1; then
		pigz -9 -n -b 128 -p "$GZ_JOBS" "$@"
	else
		gzip -9n "$@"
	fi
}

deterministic_tar() {
	local out=$1 dir=$2
	shift 2
	tar --create \
	    --directory="$dir" \
	    --owner=root --group=root --numeric-owner \
	    --mtime="@${SOURCE_DATE_EPOCH}" \
	    --sort=name \
	    --format=gnu \
	    "$@" . | gz > "$out"
}

sha256_of() { sha256sum "$1" | cut -d' ' -f1; }

record_artifact() {
	local file=$1
	mkdir -p "$OUT_DIR"
	printf '%s\t%s\t%s\n' "$(basename "$file")" "$(sha256_of "$file")" \
		"$(stat -c%s "$file")" >> "$OUT_DIR/artifacts.tsv"
}

human() { numfmt --to=iec --suffix=B "$1" 2>/dev/null || echo "$1"; }
