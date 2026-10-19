#!/bin/bash
# Install an APK bundle (.apkm from APKMirror, or a folder of split APKs) on
# the TV, skipping splits this 32-bit, 320 dpi TV can't use.
#   tools/install-bundle.sh path/to/app.apkm   (or a folder of .apk files)
# Verify signatures before running this; it only installs.
set -e
cd "$(dirname "$0")/.."
SRC="$1"
TV() { tools/.venv/bin/python tools/tv.py "$@"; }

if [ -d "$SRC" ]; then
  DIR="$SRC"
else
  DIR="$(mktemp -d)"
  trap 'rm -rf "$DIR"' EXIT
  unzip -q "$SRC" '*.apk' -d "$DIR"
fi

# Keep base, armeabi-v7a, xhdpi (the TV's density) and every language split.
PARTS=()
for f in "$DIR"/*.apk; do
  n="$(basename "$f")"
  case "$n" in
    split_config.arm64_v8a.apk|split_config.x86.apk|split_config.x86_64.apk) continue ;;
    split_config.*dpi.apk) [ "$n" = split_config.xhdpi.apk ] || continue ;;
  esac
  PARTS+=("$n")
done
echo "Installing ${#PARTS[@]} parts: ${PARTS[*]}"

TV shell 'rm -rf /data/local/tmp/bundle; mkdir -p /data/local/tmp/bundle'
for n in "${PARTS[@]}"; do
  TV push "$DIR/$n" "/data/local/tmp/bundle/$n" >/dev/null
done
SID="$(TV shell 'pm install-create -r' | grep -oE '[0-9]+')"
for n in "${PARTS[@]}"; do
  TV shell "pm install-write -S $(stat -f %z "$DIR/$n") $SID $n /data/local/tmp/bundle/$n" >/dev/null
done
TV shell "pm install-commit $SID"
TV shell 'rm -rf /data/local/tmp/bundle'
