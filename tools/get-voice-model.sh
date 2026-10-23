#!/bin/bash
# Downloads the Vosk small English speech model into the launcher's assets
# (offline voice commands). Skips if it is already there. Not kept in git.
set -e
cd "$(dirname "$0")/.."
DEST=launcher/app/src/main/assets/model-en-us
[ -f "$DEST/am/final.mdl" ] && exit 0
URL=https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip
SHA=30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
echo "Downloading Vosk English model (41 MB)..."
curl -sSL -o "$TMP/model.zip" "$URL"
echo "$SHA  $TMP/model.zip" | shasum -a 256 -c -
unzip -q "$TMP/model.zip" -d "$TMP"
mkdir -p "$DEST"
cp -R "$TMP/vosk-model-small-en-us-0.15/." "$DEST/"
rm -f "$DEST/README"
# Vosk's StorageService re-unpacks the model when this id changes.
echo "vosk-model-small-en-us-0.15" > "$DEST/uuid"
