# Prompt: set up a new Xiaomi Mi TV (same model) like the first one

Paste everything below the line into a new Claude Code session that is opened
in this folder (`~/Documents/Mi TV Clean Home`).

---

I have a new Xiaomi Mi TV, the same model as before (Chinese-market firmware,
HyperOS TV, Android 14, MediaTek, 32-bit ARM, model family MiTV-MFTR0 /
"freeguy") but 85 inches. Set it up exactly like my first TV. This folder
already contains the working project from the first TV: launcher source,
scripts, verified APKs in `apks/`, backups, and a README. Reuse it; don't
rebuild things from scratch. Work on the real device step by step and verify
everything with screenshots pulled from the TV. Ask me before anything that
depends on my preferences, before any download (say file name, source and
size), and before anything that will take over or interrupt the TV screen.

## 0. Ground rules learned the hard way

- **ADB client:** use `tools/tv.py` (Python `adb-shell` in `tools/.venv`), not
  the stock adb binary. It does `shell`, `key`, `shot`, `push` (base64 chunks,
  md5 verified), `pull` and `install`. Set `TV_IP=...` for the new TV's
  address. The ADB key in `tools/adbkey` is new to this TV, so tell me when the
  "Allow USB debugging?" prompt will appear.
- **One `pm` command per shell call.** In zsh, loop over package lists with
  `xargs` or a bash script, never `$var` (zsh doesn't word-split, and the TV's
  shell then runs only the first command). Check the TV's real
  `pm list packages -d` against the log afterwards.
- **Logcat:** always use `logcat -d -t 3000` (or similar); a full `logcat -d`
  over the network takes longer than the 2-minute tool timeout.
- **Network ADB turns off on every reboot and on wake from standby** (this
  firmware ignores `persist.adb.tcp.port`). Before a reboot, tell me I'll need
  to switch network ADB debugging back on in Developer options.
- **Never shorten `screen_off_timeout`** (e.g. to 15 s to test something). On
  this TV it puts the set into standby, which drops ADB. Leave it at 300000.
- **Watch for real remote use:** `logcat` key events with `deviceId=5` and a
  non-zero `scanCode` are someone using the physical remote. Pause testing and
  ask me if that happens.
- **Don't stop or answer things I started:** leave playback, permission dialogs
  in apps, logins and terms acceptance to me unless I ask.

## 1. Connect and back up

1. Find the TV (ARP table / scan for port 5555, or ask me for the IP) and
   connect with `tools/tv.py`.
2. Record model, Android version, ABI, RAM, `wm size` and `wm density`. The
   85" panel may report a different resolution/density than the first TV
   (1920x1080 @ 320 dpi); if it differs, check the launcher still lays out
   correctly (it is all in dp, so it should).
3. Back up to `backup/<new-tv-name>/`: `pm list packages -f -U`, `-d`, `-s`,
   `-3`, `getprop`, `settings list system|secure|global`, current HOME
   resolution. Keep a separate `backup/disabled-by-us-<new-tv>.txt` log, or
   reset `backup/disabled-by-us.txt` for this TV and say which.
4. If apps like Stremio, Spotify, WakaTV aren't installed, tell me; I'll
   install them (or ask you to fetch verified copies).

## 2. Debloat (my choices are already decided)

Run `./debloat.sh` (it reads `debloat-list.txt`, one `pm disable-user --user 0`
per package, logged for `restore.sh`). The list is: telemetry
(`com.miui.tv.analytics`, `com.mitv.cloudcontrol`, `com.miui.misightservice`,
`com.xiaomi.mitv.healthdiagnosis`, `com.xiaomi.mitv.health`,
`com.android.federatedcompute.services`,
`com.android.ondevicepersonalization.services`), content/ads
(`com.xm.webcontent`, `com.duokan.videodaily`,
`com.xiaomi.mitv.tvpush.tvpushservice`, `com.mitv.shoplugin`,
`com.xiaomi.mitv.shop`, `com.xiaomi.mitv.hyper.screensaver`), background
(`com.xiaomi.mitv.tvmanager`, `com.mitv.toolhouse`,
`com.xiaomi.mitv.karaoke.service`, `com.xiaomi.screenrecorder`,
`com.xiaomi.gamecenter.sdk.service.mibox`, `com.miui.hybrid.tv`,
`com.xiaomi.smarthome.tv`, `com.android.printspooler`), Chinese streaming apps
(`com.gitvdemo.video`, `com.ktcp.video`, `com.xiaodianshi.tv.yst`,
`com.cibn.tv`, `com.hunantv.license`), casting/Xiaomi link
(`com.xiaomi.miplay`, `com.xiaomi.mitv.smartshare`,
`com.xiaomi.hyper_connect.tv`, `com.xiaomi.mi_connect_service`), updates
(`com.xiaomi.mitv.upgrade`), weather (`com.xiaomi.tweather`) and the Play
Store (`com.android.vending`).

**Never disable:** `com.mitv.tvhome` (stock home: must stay enabled, see
section 3), Google Play services/GSF, the TV player and MediaTek input
services, Media Explorer, Gallery, Calendar, and the voice assistant
(`com.xiaomi.voicecontrol` + `com.xiaomi.wakeupservice`: I keep it). Don't
disable Sogou until Gboard is installed (section 5).

Never uninstall system packages; screenshot the home screen after debloating.

## 3. Launcher (already built: `./install.sh`)

`./install.sh` builds `launcher/` with the bundled JDK 17, Android SDK 34 and
Gradle 8.7, installs it, grants `ACCESS_FINE_LOCATION` (Wi-Fi name) and
`WRITE_SECURE_SETTINGS` (self-repair), enables the Home button accessibility
service and opens the launcher.

What the launcher is: Apple TV 4K style, pitch black; a dock band at the bottom
with **Stremio, WakaTV, Spotify, SmartTube**; a 4-column app grid below
(Media Player is added via the `extra_apps` list because it has no launcher
entry); one spring-animated glass highlight; focused tile scales up and shows
its name; large Outfit-font hero title with a glow sampled from the dock
app's banner; frosted pills for Inputs, Wi-Fi name and Settings; greeting,
thin clock and date top left. Deterministic D-pad navigation, remembered last
tile, no blur/video/network polling (~52 MB, 0% CPU idle).

**Facts about this firmware that the launcher relies on (don't rediscover):**
- The ROM only lets whitelisted system apps be HOME
  (`MitvSettings.isThirdpartyHomeAllowed`, fed from
  `/data/system/mitv_features.xml`, root only). `cmd package
  set-home-activity` and the HOME role report success but are ignored.
- So the launcher includes `HomeKeyService` (accessibility): catches the Home
  key, opens the launcher when the stock home/FallbackHome appears, and opens
  it when the service connects within 5 minutes of boot. `BootReceiver` and
  `ServiceGuard` switch the service back on at boot, after updates and on
  every home-screen open (the TV once turned it off after a crash restart).
- **Standby kills apps:** power-button standby runs Xiaomi's
  `killAllThirdApp`, which force-stops every non-system app (and unbinds the
  launcher's accessibility service). The fix is Xiaomi's own whitelist
  setting, set by `install.sh`:
  `settings put system str_killbackgroud_pkg_white_list com.cleanhome.tv`.
  It's read at boot, so restart once, then verify in logcat on standby:
  `packageName:com.cleanhome.tv in strKillWhiteList`. Don't touch the
  `system_str` setting (a status flag the TV manages itself).
- **Keep `com.mitv.tvhome` enabled.** With it disabled the TV boots to a blank
  FallbackHome screen (which also polls every 500 ms).
- The launcher also has a LAUNCHER/LEANBACK_LAUNCHER entry, as a way back from
  the stock app list.
- Home pressed while already home → back to the dock. This uses
  onStart/onStop visibility, because Android pauses the activity before
  `onNewIntent`.
- **Input switching:** the stock TV player ignores the TIF passthrough intent.
  Use `com.xiaomi.mitv.tvplayer` action `com.xiaomi.mitv.tvplayer.EXTSRC_PLAY`
  with int extra `input` = 23–26 for HDMI 1–4 and 2 for AV; Live TV is
  `com.xiaomi.mitv.tvplayer.ATV_PLAY`. (Already implemented.)
- The launcher has no screen saver, and that's fine (see section 7).

Verify after install with key presses and screenshots: top bar ends stop,
Up/Down one row, Up from the dock → top bar, Home from inside an app returns
to the launcher, Inputs dropdown switches to HDMI 1, wallpaper push works.
Ask me before rebooting to test the boot path, and remind me about ADB first.

**Added later (all in the launcher source):** dock and Now Playing dropdown
with the same transparency as the pills; a Now Playing pill in the top bar whose
dropdown has prev/play-pause/next (needs
`cmd notification allow_listener com.cleanhome.tv/com.cleanhome.tv.MediaListener`,
in `install.sh`); Kumasi weather next to the date; Bing photo-of-the-day
wallpapers rotating every 15 minutes. Sound, Bluetooth and sleep pills were
removed by request. `install.sh` adds the Home service to the existing
accessibility list (don't overwrite it, the voice assistant uses it too).
Never press OK on the Now Playing buttons while testing if someone is
listening.

## 4. Apps from `apks/` (already downloaded and verified)

Reinstall these from `apks/` (re-check signatures with
`tools/android-sdk/build-tools/34.0.0/apksigner verify --print-certs`). If you
want newer versions, ask before downloading:
- **SmartTube** `SmartTube_stable_32.56_armeabi-v7a.apk`, package
  `org.smarttube.stable`, signer CN=Yurii L (GitHub yuliskov/SmartTube; check
  the SHA-256 against the GitHub release digest).
- (Dropped, kept for reference) **Aurora Store** `AuroraStore-4.8.4.apk`, signer cert SHA-256
  `4C:62:61:57:…:41:AA:28:0F` (from Aurora's README). On its setup screen,
  "Background downloads" and "App links" don't work on this firmware; grant
  them over ADB:
  `dumpsys deviceidle whitelist +com.aurora.store`,
  `pm set-app-links-user-selection --user 0 --package com.aurora.store true all`,
  `appops set com.aurora.store REQUEST_INSTALL_PACKAGES allow`,
  `pm grant com.aurora.store android.permission.POST_NOTIFICATIONS`.
  Anonymous login serves phone apps ("app not supported"); the fix to try is
  Spoof manager → an Android TV device profile, then log out and in again.
- **AirScreen** `AirScreen-2.18.0-armeabi-v7a.apkm` (casting receiver for my
  Pixel), signer CN=AirScreen, OU=IoniTech, cert SHA-256 `022735b1…`. It's an
  APK bundle: unzip it, push each `.apk` to `/data/local/tmp/`, then
  `pm install-create -r`, `pm install-write -S <size> <session> <name> <path>`
  for each part, and `pm install-commit <session>`.
- **Aptoide TV** `aptoide-tv-5.1.3.apk` (`cm.aptoidetv.pt`, MD5 6212d8ef…) and
  **Downloader** `Downloader-2.0.4-ForGoogleAndroidDevices.apkm`
  (`com.esaba.downloader`): my app stores. Install the bundle with
  `tools/install-bundle.sh`, then grant both
  `appops set <pkg> REQUEST_INSTALL_PACKAGES allow`. Aurora and Uptodown were
  dropped (Aurora served phone apps; uptodown.com blocks automated downloads).
- **TikTok for Android TV** `TikTok-for-Android-TV-12.3.7.1.apkm`: **skip
  unless I ask.** It installs, but in Ghana it can't load feeds or a login QR
  (region block).

## 5. Keyboard

Run `./keyboard.sh`: installs Gboard for Android TV from `apks/` (signed with
Google's key, SHA-256 `f0fd6c5b…`, rotated to `7ce83c1b…` for Android 13+),
sets it as default, verifies, and only then disables Sogou
(`com.sohu.inputmethod.sogou.tv`). Test by opening a text field and taking a
screenshot.

## 6. Things that don't work on this firmware (don't retry unless I ask)

- **Google Play Store:** sideloaded as a user app it crashes at start
  (`SecurityException: MANAGE_USERS or CREATE_USERS`); it needs a system or
  rooted install. Keep it disabled; use Aurora.
- **Google Assistant:** same problem; the Xiaomi assistant is Mandarin-only.
  Voice is left as is.
- **Chromecast / Google Cast:** no receiver in this firmware; Pixels can't use
  Miracast. Use AirScreen, SmartTube's "Link with TV code", Spotify Connect.
- **Screen savers:** `mDreamsSupportedConfig=false`. Android never starts any
  screen saver (Aerial Views etc. install but never run). Don't touch
  `screensaver_*` or `screen_off_timeout`.

## 7. Finish

- Set the time zone to Africa/Accra if it's wrong (the first TV was on
  Atlantic/Azores); ask me first.
- Update `README.md` for this TV (IP, anything that differed), keep
  `restore.sh` working, and give me a short summary: what's done, what's
  verified with screenshots, and what isn't.
