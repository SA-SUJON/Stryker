#!/bin/bash
set -euo pipefail

TREE=${1:?usage: scrub.sh <tree>}
[ -d "$TREE/etc" ] || { echo "scrub.sh: $TREE is not a root filesystem" >&2; exit 2; }

printf '\n== scrubbing ==\n'
note() { printf '   %s\n' "$*"; }

rm -f "$TREE/usr/bin/qemu-aarch64-static"
rm -f "$TREE/usr/sbin/policy-rc.d"

: > "$TREE/etc/machine-id"
rm -f "$TREE/var/lib/dbus/machine-id"
rm -f "$TREE/etc/ssh/ssh_host_"*
rm -f "$TREE/var/lib/systemd/random-seed"

if [ -f "$TREE/etc/shadow" ]; then
	sed -i 's/^root:[^:]*:/root:!:/' "$TREE/etc/shadow"
	note "root password locked (key authentication only)"
fi
rm -rf "$TREE/root/.ssh"
rm -f  "$TREE/etc/ssh/sshd_config.d/"*_local.conf

rm -rf "$TREE/root/.gnupg" "$TREE/root/.cache" "$TREE/root/.config" \
       "$TREE/root/.local" "$TREE/root/.npm" "$TREE/root/go"
rm -f  "$TREE/root/."{bash_history,zsh_history,python_history,lesshst,viminfo,wget-hsts,netrc}
for h in "$TREE"/home/*; do
	[ -d "$h" ] || continue
	rm -rf "$h/.ssh" "$h/.gnupg" "$h/.cache" "$h/.config"
	rm -f  "$h/."{bash_history,zsh_history,lesshst,viminfo,netrc}
done

find "$TREE/var/log" -type f -exec truncate -s 0 {} + 2>/dev/null || true
rm -rf "$TREE"/var/tmp/* "$TREE"/tmp/* 2>/dev/null || true
rm -f  "$TREE"/var/lib/systemd/catalog/database 2>/dev/null || true
printf 'nameserver 1.1.1.1\n' > "$TREE/etc/resolv.conf"

if grep -qE 'https?://(10\.|192\.168\.|172\.(1[6-9]|2[0-9]|3[01])\.|localhost|127\.)' \
		"$TREE/etc/apt/sources.list" 2>/dev/null; then
	note "sources.list pointed at a local mirror; rewriting to deb.debian.org"
	suite=$(awk '$1 == "deb" { print $3; exit }' "$TREE/etc/apt/sources.list")
	suite=${suite:-trixie}
	cat > "$TREE/etc/apt/sources.list" <<EOF
deb http://deb.debian.org/debian $suite main contrib non-free non-free-firmware
deb http://deb.debian.org/debian ${suite}-updates main contrib non-free non-free-firmware
deb http://security.debian.org/debian-security ${suite}-security main contrib non-free non-free-firmware
EOF
fi

rm -rf "$TREE"/var/lib/apt/lists/*
rm -f  "$TREE"/var/cache/apt/archives/*.deb
rm -f  "$TREE"/var/cache/apt/*.bin
rm -rf "$TREE"/var/cache/debconf/*.dat-old

before_share=$(du -sm "$TREE/usr/share" 2>/dev/null | cut -f1)
rm -rf "$TREE"/usr/share/man/* "$TREE"/usr/share/doc/*/[!c]* \
       "$TREE"/usr/share/info/* "$TREE"/usr/share/lintian \
       "$TREE"/usr/share/linda "$TREE"/usr/share/groff \
       "$TREE"/usr/share/help 2>/dev/null || true
find "$TREE/usr/share/locale" -mindepth 1 -maxdepth 1 -type d \
	! -name 'en*' ! -name 'C*' -exec rm -rf {} + 2>/dev/null || true
after_share=$(du -sm "$TREE/usr/share" 2>/dev/null | cut -f1)
note "docs and locales: ${before_share:-?} MB -> ${after_share:-?} MB"

mkdir -p "$TREE/etc/dpkg/dpkg.cfg.d"
cat > "$TREE/etc/dpkg/dpkg.cfg.d/01-stryker-nodoc" <<'EOF'
path-exclude=/usr/share/man/*
path-exclude=/usr/share/info/*
path-exclude=/usr/share/doc/*/README.*
path-exclude=/usr/share/doc/*/TODO.*
path-exclude=/usr/share/doc/*/NEWS.*
path-exclude=/usr/share/locale/*
path-include=/usr/share/locale/en*
path-include=/usr/share/locale/C*
EOF

note "done"
