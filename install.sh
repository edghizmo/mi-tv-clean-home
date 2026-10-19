#!/bin/bash
# Build the launcher and install it on the TV as the default home screen.
#   ./install.sh            build + install + set as home
#   ./install.sh --no-build install the last built APK
set -e
cd "$(dirname "$0")"
ROOT="$PWD"
# Use the toolchain in tools/ when it is there (see docs/BUILDING.md),
# otherwise whatever JDK 17, Android SDK and Gradle the system has.
[ -d "$ROOT/tools/jdk" ] && export JAVA_HOME="$ROOT/tools/jdk/Contents/Home"
[ -d "$ROOT/tools/android-sdk" ] && export ANDROID_HOME="$ROOT/tools/android-sdk"
GRADLE="$ROOT/tools/gradle-8.7/bin/gradle"
[ -x "$GRADLE" ] || GRADLE=gradle
TV() { "$ROOT/tools/.venv/bin/python" "$ROOT/tools/tv.py" "$@"; }
PKG=com.cleanhome.tv
APK="$ROOT/launcher/app/build/outputs/apk/debug/app-debug.apk"

if [ "$1" != "--no-build" ]; then
  echo "sdk.dir=$ANDROID_HOME" > launcher/local.properties
  (cd launcher && "$GRADLE" --quiet assembleDebug)
fi

echo "Installing $(basename "$APK") ($(du -h "$APK" | cut -f1))"
TV install "$APK"
# Location permission is only used to read the Wi-Fi network name.
TV shell "pm grant $PKG android.permission.ACCESS_FINE_LOCATION"
# Lets the launcher switch its Home button service back on if it gets turned off.
TV shell "pm grant $PKG android.permission.WRITE_SECURE_SETTINGS"
# Now Playing widget: lets the launcher see other apps' media sessions.
TV shell "cmd notification allow_listener $PKG/$PKG.MediaListener"
# Xiaomi force-stops every non-system app when the TV goes to standby; apps in
# this list are spared. It is read at boot, so it takes effect after a restart.
WL="$(TV shell 'settings get system str_killbackgroud_pkg_white_list' | tr -d '\r\n')"
case ",$WL," in
  *",$PKG,"*) ;;
  ",null,"|",,") TV shell "settings put system str_killbackgroud_pkg_white_list $PKG" ;;
  *) TV shell "settings put system str_killbackgroud_pkg_white_list $WL,$PKG" ;;
esac
# Android freezes background apps it considers idle. SmartTube plays in the
# background without a foreground service, so it was frozen mid-playback and
# the sound cut out. Takes effect after a restart.
TV shell "settings put global cached_apps_freezer disabled"
TV shell "cmd package set-home-activity $PKG/.HomeActivity"
# This ROM only accepts system apps as HOME, so an accessibility service in the
# launcher takes over the Home button and covers Xiaomi's fallback home screen.
# Add it to the existing list rather than replacing it, so other services
# (e.g. the voice assistant's) stay on.
SVC="$PKG/.HomeKeyService"
CUR="$(TV shell 'settings get secure enabled_accessibility_services' | tr -d '\r\n')"
case ":$CUR:" in
  *":$SVC:"*) ;;
  ":null:"|"::") TV shell "settings put secure enabled_accessibility_services $SVC" ;;
  *) TV shell "settings put secure enabled_accessibility_services $CUR:$SVC" ;;
esac
TV shell "settings put secure accessibility_enabled 1"
# The stock home stays enabled on purpose: it is the only home this ROM will
# start at boot, so disabling it leaves a blank screen. The service above puts
# this launcher on top of it.
TV shell "am start -n $PKG/.HomeActivity"
