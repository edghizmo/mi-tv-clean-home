#!/bin/bash
# Records the demo clips on the TV with screenrecord while driving the remote.
cd "$(dirname "$0")/.."
T() { tools/.venv/bin/python tools/tv.py shell "$1"; }
K() { tools/.venv/bin/python tools/tv.py key "$@"; }
rec() {  # rec NAME SECONDS -- starts recording in the background on the TV
  # Runs on the Mac in the background so the ADB session (and screenrecord) stays alive.
  T "rm -f /data/local/tmp/$1.mp4; screenrecord --time-limit $2 --bit-rate 12000000 /data/local/tmp/$1.mp4" &
  REC_PID=$!
  sleep 2
}
finish() {  # finish NAME SECONDS_LEFT
  sleep "$2"
  wait "$REC_PID"
  tools/.venv/bin/python tools/tv.py pull "/data/local/tmp/$1.mp4" "demo/clips/$1.mp4" && T "rm -f /data/local/tmp/$1.mp4"
  echo "got $1"
}
K HOME sleep2 HOME sleep2 DPAD_LEFT DPAD_LEFT DPAD_LEFT sleep2

# 1. Home screen + dock: glass highlight springs, hero title + glow change
rec 01-dock 22
sleep 3
K DPAD_RIGHT sleep1.6 DPAD_RIGHT sleep1.6 DPAD_RIGHT sleep1.6 DPAD_LEFT sleep1.6 DPAD_LEFT sleep1.6 DPAD_LEFT sleep2
finish 01-dock 3

# 2. App grid: Down one row at a time, then back up
rec 02-grid 22
sleep 2
K DPAD_DOWN sleep1.8 DPAD_DOWN sleep1.8 DPAD_DOWN sleep1.8 DPAD_RIGHT sleep1.4 DPAD_UP sleep1.8 DPAD_UP sleep1.8 DPAD_UP sleep2
finish 02-grid 2

# 3. Top bar + Inputs dropdown
rec 03-inputs 18
sleep 1.5
K DPAD_UP sleep1.4 DPAD_LEFT DPAD_LEFT DPAD_LEFT sleep1.2 DPAD_RIGHT sleep1.2 DPAD_RIGHT sleep1.2 DPAD_LEFT sleep1.2 DPAD_LEFT sleep1.2 DPAD_CENTER sleep1.6 DPAD_DOWN sleep1.2 DPAD_DOWN sleep1.2 DPAD_DOWN sleep1.4 BACK sleep1.5
finish 03-inputs 2

# 4. Now Playing pill + dropdown (Spotify resumed briefly, never press OK on buttons)
T "am start -n com.spotify.tv.android/.SpotifyTVActivity" >/dev/null; sleep 6
T "cmd media_session dispatch play"; sleep 2
K HOME sleep3
rec 05-nowplaying 18
sleep 2
K DPAD_UP sleep1.2 DPAD_RIGHT sleep1.4 DPAD_CENTER sleep2.5 DPAD_LEFT sleep1.5 DPAD_RIGHT sleep1.5 DPAD_RIGHT sleep1.5 BACK sleep1.5 DPAD_DOWN sleep1.5
finish 05-nowplaying 2
T "cmd media_session dispatch pause"
K HOME sleep2 HOME
echo done
