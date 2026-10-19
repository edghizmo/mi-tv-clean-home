#!/bin/bash
# Install Gboard for Android TV from apks/, make it the default keyboard, then
# disable the Chinese Sogou keyboard (only after Gboard is in place).
set -e
cd "$(dirname "$0")"
TV() { tools/.venv/bin/python tools/tv.py "$@"; }
G=com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME
TV install apks/gboard-tv-17.3.11.902587967-armeabi-v7a.apk
TV shell "ime enable $G"
TV shell "ime set $G"
[ "$(TV shell 'settings get secure default_input_method' | tr -d '\r\n')" = "$G" ]
tools/disable.sh com.sohu.inputmethod.sogou.tv
