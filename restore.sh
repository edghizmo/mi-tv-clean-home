#!/bin/bash
# Undo this project.
#   ./restore.sh                 re-enable everything that was disabled, put the
#                                stock Xiaomi launcher back and remove the new one
#   ./restore.sh pkg [pkg ...]   re-enable just these packages
cd "$(dirname "$0")"
TV() { tools/.venv/bin/python tools/tv.py shell "$1"; }
LOG=backup/disabled-by-us.txt

if [ $# -gt 0 ]; then
  for p in "$@"; do
    echo "$p: $(TV "pm enable --user 0 $p")"
    grep -vx "$p" "$LOG" > "$LOG.tmp"; mv "$LOG.tmp" "$LOG"
  done
  exit 0
fi

while read -r p; do
  [ -n "$p" ] && echo "$p: $(TV "pm enable --user 0 $p")"
done < "$LOG"
: > "$LOG"

# Accessibility services as they were before (the voice assistant's service).
orig=$(grep '^enabled_accessibility_services=' backup/original/settings-secure.txt | cut -d= -f2-)
TV "settings put secure enabled_accessibility_services '$orig'"
TV "cmd package set-home-activity com.mitv.tvhome/.MainActivityUserMode"
TV "settings delete system str_killbackgroud_pkg_white_list"
TV "settings delete global cached_apps_freezer"
TV "pm uninstall com.cleanhome.tv"
TV "am start -a android.intent.action.MAIN -c android.intent.category.HOME"
echo "Restored. The stock Xiaomi home screen is back."
