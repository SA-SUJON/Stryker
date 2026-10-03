#!/bin/bash
set -euo pipefail

TREE=${1:?usage: prune-firmware.sh <tree> <modules-dir>}
MODDIR=${2:-}
FW=$TREE/usr/lib/firmware
[ -d "$FW" ] || FW=$TREE/lib/firmware
[ -d "$FW" ] || { echo "prune-firmware.sh: no firmware directory in $TREE" >&2; exit 0; }
FW=$(cd "$FW" && pwd)
if [ -n "$MODDIR" ] && [ -d "$MODDIR" ]; then MODDIR=$(cd "$MODDIR" && pwd); fi

printf '\n== pruning firmware ==\n'
before=$(du -sm "$FW" | cut -f1)

KEEP=$(mktemp)
trap 'rm -f "$KEEP"' EXIT

if [ -n "$MODDIR" ] && [ -d "$MODDIR" ]; then
	if [ -f "$MODDIR/modules.builtin.modinfo" ]; then
		tr '\0' '\n' < "$MODDIR/modules.builtin.modinfo" \
			| sed -n 's/^[a-zA-Z0-9_]*\.firmware=//p' >> "$KEEP"
	fi
	find "$MODDIR" -name '*.ko' -print0 2>/dev/null \
		| xargs -0 -r modinfo -F firmware 2>/dev/null >> "$KEEP" || true
fi

cat >> "$KEEP" <<'EOF'
regulatory.db
regulatory.db.p7s
regulatory.db-debian
regulatory.db.p7s-debian
regulatory.db-upstream
regulatory.db.p7s-upstream
ath9k_htc/
htc_7010.fw
htc_9271.fw
carl9170-1.fw
ar5523.bin
rtlwifi/
rtw88/
rtw89/
rtl_nic/
mt7601u.bin
rt73.bin
rt2870.bin
rt2860.bin
rt3070.bin
rt3071.bin
rt3290.bin
zd1211/
isl3886usb
isl3887usb
EOF

for g in "mediatek/mt7601*" "mediatek/mt7610*" "mediatek/mt7650*" "mediatek/mt7662*" \
         "mediatek/mt7663*" "mediatek/mt7668*" \
         "mediatek/WIFI_RAM_CODE_MT796*" "mediatek/WIFI_MT796*" \
         "mediatek/BT_RAM_CODE_MT796*" "mediatek/mt7925/"; do
	printf '%s\n' "$g" >> "$KEEP"
done

cd "$FW"
KEEPSET=$(mktemp)
trap 'rm -f "$KEEP" "$KEEPSET"' EXIT
while read -r entry; do
	[ -n "$entry" ] || continue
	case "$entry" in
		*/) find "${entry%/}" \( -type f -o -type l \) 2>/dev/null || true ;;
		*)  for m in $entry; do
			if [ -e "$m" ] || [ -L "$m" ]; then printf '%s\n' "$m"; fi
		    done ;;
	esac
done < "$KEEP" | sort -u > "$KEEPSET"

> "$KEEPSET.links"
while read -r l; do
	[ -L "$l" ] || continue
	t=$(realpath -m --relative-to="$FW" "$l" 2>/dev/null) || continue
	case "$t" in
		/*|../*) ;;
		*) printf '%s\n' "$t" >> "$KEEPSET.links" ;;
	esac
done < "$KEEPSET"
cat "$KEEPSET.links" >> "$KEEPSET"
rm -f "$KEEPSET.links"
sort -u -o "$KEEPSET" "$KEEPSET"

kept=$(wc -l < "$KEEPSET")
[ "$kept" -gt 20 ] || { echo "prune-firmware.sh: only $kept files matched -- refusing to prune.
  Something is wrong with the module tree argument; deleting almost all
  firmware because a path was mistyped is not a failure worth having." >&2; exit 2; }

find . \( -type f -o -type l \) | sed 's,^\./,,' | sort -u > "$KEEP"
comm -23 "$KEEP" "$KEEPSET" | tr '\n' '\0' | xargs -0 -r rm -f
find . -type d -empty -delete 2>/dev/null || true

after=$(du -sm "$FW" | cut -f1)
printf '   kept %s files, %s MB (was %s MB, freed %s MB)\n' \
	"$kept" "$after" "$before" "$((before - after))"
