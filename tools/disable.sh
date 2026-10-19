#!/bin/bash
# Disable packages for user 0, one pm call per package, and log each success
# to backup/disabled-by-us.txt so restore.sh can re-enable them.
cd "$(dirname "$0")/.."
LOG=backup/disabled-by-us.txt
mkdir -p backup
touch "$LOG"
for p in "$@"; do
  out=$(tools/.venv/bin/python tools/tv.py shell "pm disable-user --user 0 $p" 2>&1)
  echo "$p: $out"
  if echo "$out" | grep -q "disabled-user"; then
    grep -qx "$p" "$LOG" || echo "$p" >> "$LOG"
  fi
done
