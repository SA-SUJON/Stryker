#!/bin/bash
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
. "$HERE/../lib/common.sh"

BASH_V=${BASH_V:-5.2.37}
API=${API:-26}
PAGE=${PAGE:-16384}
O=${O:-$WORK_DIR/bash}
DEST=$OUT_DIR/bash
QEMU=${QEMU:-qemu-aarch64-static}
MIRROR=${MIRROR:-https://ftp.gnu.org/gnu/bash}

need curl tar make sed awk gpgv file

if [ -z "${NDK:-}" ]; then
	for c in "${ANDROID_NDK_HOME:-}" "${ANDROID_NDK_ROOT:-}" \
	         "$HOME"/Android/Sdk/ndk/* "$HOME"/Library/Android/sdk/ndk/* \
	         /usr/lib/android-ndk /opt/android-ndk* \
	         "${TMPDIR:-/tmp}"/ndk/android-ndk-*; do
		[ -n "$c" ] && [ -x "$c/toolchains/llvm/prebuilt/linux-x86_64/bin/clang" ] && NDK=$c
	done
fi
TOOL=${NDK:-}/toolchains/llvm/prebuilt/linux-x86_64/bin
[ -x "$TOOL/clang" ] || die "no Android NDK found. Set NDK=/path/to/android-ndk-rXX"

mkdir -p "$O" "$DEST"
cd "$O"

say "bash $BASH_V (bionic static, aarch64)"
info "ndk     $NDK (API $API)"

[ -f "bash-$BASH_V.tar.gz" ] || curl -fL --retry 3 -sS -o "bash-$BASH_V.tar.gz" \
	"$MIRROR/bash-$BASH_V.tar.gz"
[ -f "bash-$BASH_V.tar.gz.sig" ] || curl -fL --retry 3 -sS -o "bash-$BASH_V.tar.gz.sig" \
	"$MIRROR/bash-$BASH_V.tar.gz.sig"
[ -f gnu-keyring.gpg ] || curl -fL --retry 3 -sS -o gnu-keyring.gpg \
	https://ftp.gnu.org/gnu/gnu-keyring.gpg
gpgv --keyring ./gnu-keyring.gpg "bash-$BASH_V.tar.gz.sig" "bash-$BASH_V.tar.gz" \
	|| die "the tarball is not the one GNU signed"
info "sha256  $(sha256_of "bash-$BASH_V.tar.gz")"

rm -rf "bash-$BASH_V"
tar xf "bash-$BASH_V.tar.gz"

cat > tlsalign.c <<'EOF'
__attribute__((aligned(64))) __thread char __stryker_tls_align[64];

void *__stryker_tls_align_ref(void)
{
	return (void *)__stryker_tls_align;
}
EOF
"$TOOL/aarch64-linux-android$API-clang" -Os -fno-emulated-tls -c -o tlsalign.o tlsalign.c

cat > android.cache <<'CACHE'
ac_cv_func_getrandom=no
ac_cv_func_getentropy=no
ac_cv_func_mmap_fixed_mapped=yes
ac_cv_func_strcoll_works=yes
ac_cv_func_working_mktime=yes
ac_cv_func_setvbuf_reversed=no
ac_cv_type_getgroups=gid_t
ac_cv_sys_restartable_syscalls=yes
bash_cv_dev_fd=standard
bash_cv_dup2_broken=no
bash_cv_fionread_in_ioctl=yes
bash_cv_func_ctype_nonascii=yes
bash_cv_func_sigsetjmp=present
bash_cv_getcwd_malloc=yes
bash_cv_getenv_redef=no
bash_cv_job_control_missing=present
bash_cv_must_reinstall_sighandlers=no
bash_cv_opendir_not_robust=no
bash_cv_pgrp_pipe=no
bash_cv_printf_a_format=yes
bash_cv_sys_named_pipes=present
bash_cv_sys_siglist=no
bash_cv_termcap_lib=gnutermcap
bash_cv_type_rlimit=rlim_t
bash_cv_ulimit_maxfds=yes
bash_cv_under_sys_siglist=no
bash_cv_unusable_rtsigs=no
bash_cv_wcwidth_broken=no
bash_cv_func_strcoll_broken=no
CACHE

cd "bash-$BASH_V"

sed -i '1i #include <unistd.h>' lib/termcap/tparam.c

export CC="$TOOL/aarch64-linux-android$API-clang"
export AR="$TOOL/llvm-ar" RANLIB="$TOOL/llvm-ranlib" STRIP="$TOOL/llvm-strip"
export CC_FOR_BUILD=gcc CFLAGS_FOR_BUILD= LDFLAGS_FOR_BUILD=
export CFLAGS="-Os -fno-strict-aliasing -ffile-prefix-map=$O=."
export LDFLAGS="-static $O/tlsalign.o -Wl,-u,__stryker_tls_align_ref \
-Wl,-z,max-page-size=$PAGE -Wl,-z,common-page-size=$PAGE"

./configure \
	--host=aarch64-linux-android \
	--build="$(bash ./support/config.guess)" \
	--cache-file="$O/android.cache" \
	--without-bash-malloc \
	--disable-nls \
	--enable-readline --enable-history --enable-job-control \
	--enable-alias --enable-array-variables --enable-brace-expansion \
	--enable-command-timing --enable-process-substitution --enable-select \
	> "$O/configure.log" 2>&1 || { tail -40 "$O/configure.log"; die "configure failed"; }

make -j"$(nproc)" > "$O/make.log" 2>&1 || { tail -60 "$O/make.log"; die "make failed"; }

"$STRIP" bash -o "$O/libbash.so"
cd "$O"

if "$TOOL/llvm-readelf" -l libbash.so | grep -q INTERP; then
	die "the binary has a PT_INTERP: an app cannot load an interpreter"
fi
case "$(file -b libbash.so)" in
*"ARM aarch64"*"statically linked"*) ;;
*) die "not a static aarch64 binary: $(file -b libbash.so)" ;;
esac
for align in $("$TOOL/llvm-readelf" -l libbash.so | awk '$1 == "LOAD" { print $NF }'); do
	[ "$((align))" -ge "$PAGE" ] \
		|| die "a LOAD segment is aligned $align, below the $PAGE page size"
done

if command -v "$QEMU" >/dev/null 2>&1; then
	"$QEMU" ./libbash.so -c 'true' || die "the aarch64 binary does not run"
	v=$("$QEMU" ./libbash.so -c 'echo $BASH_VERSION')
	case "$v" in "$BASH_V"*) ;; *) die "wrong version reported: $v" ;; esac
	"$QEMU" ./libbash.so -c 'declare -A m; m[k]=v; [ "${m[k]}" = v ]' \
		|| die "associative arrays do not work"
	"$QEMU" ./libbash.so -c '[[ abc =~ ^a.c$ ]]' || die "the regex operator does not work"
	"$QEMU" ./libbash.so -c 'read -r x < <(echo ok); [ "$x" = ok ]' \
		|| die "process substitution does not work"
	"$QEMU" ./libbash.so -c 'type -t bind >/dev/null' || die "readline was not built in"
	info "runs    $v"
else
	warn "no $QEMU, the aarch64 binary was not run -- it is only known to link"
fi

leak=$(grep -a -o -E '/(home|root|Users)/[A-Za-z0-9._-]+' libbash.so | sort -u || true)
[ -z "$leak" ] || die "the binary carries build-machine paths:
$leak"

cp -f libbash.so "$DEST/libbash.so"
record_artifact "$DEST/libbash.so"

say "done"
info "$DEST/libbash.so  $(human "$(stat -c%s "$DEST/libbash.so")")"
info "copy it to terminal/src/main/jniLibs/arm64-v8a/libbash.so"
info "and to terminal/src/main/assets/bin/bash, which is the copy su runs"
