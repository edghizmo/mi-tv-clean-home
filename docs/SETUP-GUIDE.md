# Setup guide and reference

The full, detailed record of how this TV was set up: every script, every
package that was disabled and why, how the Home button works on this ROM,
and the fixes made along the way. For the overview, see the
[main README](../README.md). Paths below are relative to the repository root.

> **Not in the repository:** `apks/` (third-party APKs; the table under
> [Files](#files) lists where each came from and its checksum), `backup/`
> (this TV's settings snapshots and the list of disabled packages, which
> `debloat.sh` creates as it runs), `shots/` and `tools/adbkey`.


The TV was debloated over ADB and its PatchWall home screen replaced with a small
Apple TV style launcher (`com.cleanhome.tv`). Nothing was uninstalled except the
earlier `com.cleantv.launcher` attempt: system apps are only disabled for user 0,
so everything can be switched back on.

TV address: `192.168.1.232:5555` (change with `TV_IP=... ./install.sh`).

## After a factory reset

1. Set the TV up and connect it to Wi-Fi.
2. Reinstall Stremio, Spotify and WakaTV if the reset removed them.
3. Turn on Developer options (Settings → Device Preferences → About → press
   OK on **Build** 7 times). In Developer options, turn on **USB debugging**
   and the **network ADB debugging** switch.
4. Find the TV's IP address (Settings → Network → Wi-Fi details).
5. In this folder, run the commands below. If the IP changed, put the new one
   after `TV_IP=` (or leave that part out if it is still 192.168.1.232).

   ```bash
   TV_IP=192.168.1.232 ./debloat.sh
   TV_IP=192.168.1.232 ./install.sh
   TV_IP=192.168.1.232 ./keyboard.sh
   tools/.venv/bin/python tools/tv.py install apks/SmartTube_stable_32.56_armeabi-v7a.apk
   tools/.venv/bin/python tools/tv.py install apks/AuroraStore-4.8.4.apk
   tools/.venv/bin/python tools/tv.py install apks/TVBro-2.1.6-gecko-armeabi-v7a.apk
   ```

   The first command makes the TV ask "Allow USB debugging?". Tick
   "Always allow" and press Allow. `debloat.sh` disables the packages in
   `debloat-list.txt`. `install.sh` builds and installs the launcher and turns
   on the Home button service. `keyboard.sh` installs Gboard (English
   keyboard) from `apks/`, makes it the default and then disables Sogou.
6. Press Home. The new launcher opens, and it will also open at every boot.

## Files

| Path | What it is |
| --- | --- |
| `install.sh` | Builds the launcher, installs it, sets it up as home |
| `restore.sh` | Re-enables what was disabled and puts the stock home back |
| `keyboard.sh` + `apks/` | Installs the saved Gboard for Android TV APK, makes it the default keyboard, disables Sogou |
| `apks/SmartTube_stable_32.56_armeabi-v7a.apk` | SmartTube (YouTube without ads) from github.com/yuliskov/SmartTube, SHA-256 checked against the release |
| `apks/AuroraStore-4.8.4.apk` | Aurora Store (Play Store client, anonymous login) from gitlab.com/AuroraOSS/AuroraStore; certificate matches the fingerprint in its README |
| `apks/TVBro-2.1.6-gecko-armeabi-v7a.apk` | TV Bro web browser (`com.phlox.tvwebbrowser`) from github.com/truefedex/tv-bro, Firefox engine built in; SHA-256 `963fa7a6…` matches the release, signed by CN=Pheodor Tsapana, O=PhloX (cert SHA-256 1e5124be…) |
| `apks/AirScreen-2.18.0-armeabi-v7a.apkm` | AirScreen casting receiver (APK bundle from APKMirror; every split signed by CN=AirScreen, OU=IoniTech, cert SHA-256 022735b1…) |
| `apks/TikTok-for-Android-TV-12.3.7.1.apkm` | TikTok for Android TV (`com.tiktok.tv`), APK bundle from APKMirror; all parts signed by ByteDance, cert SHA-256 d7811ec4… (install every split except `arm64_v8a`) |
| `apks/aptoide-tv-5.1.3.apk` | Aptoide TV app store (`cm.aptoidetv.pt`) from aptoide.com; MD5 6212d8ef…, cert SHA-1 d590a7d7… (Aptoide, Portugal) |
| `apks/Downloader-2.0.4-ForGoogleAndroidDevices.apkm` | Downloader by AFTVnews (`com.esaba.downloader`), APK bundle from APKMirror; signed via Google Play app signing, cert SHA-1 63d2b74e… |
| `tools/install-bundle.sh` | Installs an `.apkm` bundle (or folder of splits), keeping only the parts this TV uses |
| `debloat.sh` + `debloat-list.txt` | Disables your chosen packages in one go (edit the list to change what's disabled) |
| `backup/original/` | Package lists, disabled list, `getprop`, all settings, as they were before any change |
| `backup/after-reset/` | The same snapshot taken after the TV's reset, before debloating again |
| `backup/disabled-by-us.txt` | Every package this project disabled (restore.sh reads it) |
| `launcher/` | Launcher source (Java, AndroidX RecyclerView, minSdk 34) |
| `tools/make-wallpapers.py` | Generates the ambient wallpaper pack into the launcher's assets |
| `tools/tv.py` | ADB client (Python `adb-shell`); `shell`, `key`, `shot`, `push`, `pull`, `install` |
| `tools/disable.sh` | Disables packages one `pm` call at a time and logs them |
| `tools/` (rest) | Self-contained Python, JDK 17, Android SDK and Gradle used by the scripts |
| `shots/` | Screenshots taken from the TV while verifying |

`tools/tv.py` is used instead of the stock `adb` binary because macOS Local
Network privacy can block `adb`. Its key is `tools/adbkey`; the TV trusts it
("Always allow"). If the key is lost, the TV will ask again.

## Re-enabling apps

One or more apps:

```bash
./restore.sh com.xiaomi.tweather com.xiaomi.mitv.upgrade
```

Everything, including the stock home screen (also removes the new launcher):

```bash
./restore.sh
```

What was disabled, by group:

- **Telemetry / analytics:** `com.miui.tv.analytics`, `com.mitv.cloudcontrol`,
  `com.miui.misightservice`, `com.xiaomi.mitv.healthdiagnosis`,
  `com.xiaomi.mitv.health`, `com.android.federatedcompute.services`,
  `com.android.ondevicepersonalization.services`
- **Content, push and ads:** `com.xm.webcontent`, `com.duokan.videodaily`,
  `com.xiaomi.mitv.tvpush.tvpushservice`, `com.mitv.shoplugin`,
  `com.xiaomi.mitv.shop`, `com.xiaomi.mitv.hyper.screensaver`
- **Background services:** `com.xiaomi.mitv.tvmanager` (cleaner),
  `com.mitv.toolhouse`, `com.xiaomi.mitv.karaoke.service`,
  `com.xiaomi.screenrecorder`, `com.xiaomi.gamecenter.sdk.service.mibox`,
  `com.miui.hybrid.tv` (quick apps), `com.xiaomi.smarthome.tv`,
  `com.android.printspooler`
- **Your choices (not ticked):** Play Store `com.android.vending`; GITV/iQIYI
  `com.gitvdemo.video`, Tencent `com.ktcp.video`, Bilibili
  `com.xiaodianshi.tv.yst`, CIBN `com.cibn.tv`, Mango `com.hunantv.license`;
  casting
  `com.xiaomi.miplay` + `com.xiaomi.mitv.smartshare`; HyperConnect
  `com.xiaomi.hyper_connect.tv` + `com.xiaomi.mi_connect_service`; system
  updates `com.xiaomi.mitv.upgrade`; weather `com.xiaomi.tweather`

Kept on purpose: Google Play services (Spotify/Stremio may need it), the TV
player and MediaTek input services (needed for HDMI/AV/Live TV), Media
Explorer, Gallery and Calendar. Xiaomi's voice assistant
(`com.xiaomi.voicecontrol` + `com.xiaomi.wakeupservice`) is not in the debloat
list; `install.sh` disables it because it holds the remote's microphone, which
Clean Home's offline voice commands need.

## Keyboard

The Chinese Sogou keyboard (`com.sohu.inputmethod.sogou.tv`) is replaced by
**Gboard for Android TV** 17.3.11.902587967 (armeabi-v7a), saved in `apks/`.
It came from APKMirror because this ROM blocks the Play Store. Before
installing, its signature was checked: it is signed with Google's app key
(SHA-256 `f0fd6c5b410f25cb25c3b53346c8972fae30f8ee7411df910480ad6b2d60db83`,
rotated to `7ce83c1b…` for Android 13+), and the file's SHA-256
`5029eeeff573bb39ee3cd655850b839469cc2f04f482f768af4fce702beb36fa` matches
APKMirror's listing. Gboard won't update itself without the Play Store.

To go back to Sogou: `./restore.sh com.sohu.inputmethod.sogou.tv`, then pick
it in Settings → Device Preferences → Keyboard.

## Installing apps (Aptoide TV and Downloader)

**Aptoide TV** is the TV app store: browse with the remote, no account
needed. Its catalogue is uploaded by users, so prefer apps marked "trusted".
**Downloader** installs apps from a URL or short code typed with the remote,
for example an APKMirror or developer download link. Both were granted
"install unknown apps" over ADB
(`appops set <package> REQUEST_INSTALL_PACKAGES allow`); confirm each install
on screen.

To reinstall after a reset:

```bash
tools/.venv/bin/python tools/tv.py install apks/aptoide-tv-5.1.3.apk
tools/install-bundle.sh apks/Downloader-2.0.4-ForGoogleAndroidDevices.apkm
```

## Installing apps (Aurora Store)

> **Status:** Aurora was uninstalled from the TV on 18 Oct 2026 at your
> request. Its APK is still in `apks/` if you want it back. Uptodown was
> considered as a replacement but not installed: uptodown.com's download sits
> behind a Cloudflare bot check, so the APK has to be downloaded by hand
> (then verify its SHA-256 against the site and install it with
> `tools/tv.py install`).

The Google Play Store cannot run on this TV: it was sideloaded as a normal app
and crashes at start because it needs system-only permissions
(`MANAGE_USERS`), which only a system or rooted install can give it. It stays
disabled.

Use **Aurora Store** instead. Choose anonymous login. The first time it installs
something, allow it to "install unknown apps", then confirm each install on
screen. It also keeps Gboard, SmartTube and other apps up to date.

On its setup screen, "Background downloads" and "App links" do nothing,
because this firmware lacks the phone settings pages they open. Grant them
over ADB instead (then switch away from Aurora and back so it re-checks):

```bash
tools/.venv/bin/python tools/tv.py shell "dumpsys deviceidle whitelist +com.aurora.store"
tools/.venv/bin/python tools/tv.py shell "pm set-app-links-user-selection --user 0 --package com.aurora.store true all"
tools/.venv/bin/python tools/tv.py shell "appops set com.aurora.store REQUEST_INSTALL_PACKAGES allow"
tools/.venv/bin/python tools/tv.py shell "pm grant com.aurora.store android.permission.POST_NOTIFICATIONS"
```

## Web browser (TV Bro)

**TV Bro** is a browser built for remote controls: big buttons, D-pad cursor,
voice search and a built-in ad blocker. It appears in the launcher's app grid.

This is the build with the Firefox (Gecko) engine included (150 MB). The
smaller build relies on the TV's system WebView, which here is stuck at
version 116 (2023) with no Play Store to update it, so many sites would
break. Update TV Bro by downloading the newer `geckoIncluded-armeabi-v7a`
APK from its GitHub releases and running `tv.py install` on it.

`tv.py install` sends files over 20 MB through `nc` (about 20 s for 150 MB)
because ADB's own file transfer stalls on this TV.

## Casting from a Pixel (AirScreen)

This firmware has no Google Cast receiver, and Pixels only cast via Google
Cast, so the TV uses **AirScreen** as a third-party receiver (AirPlay, Google
Cast, Miracast, DLNA). Open AirScreen on the TV, then on the Pixel use the
Cast button or Quick Settings → Screen cast and pick the AirScreen device.
Some apps (e.g. Netflix) may not list it, since it is not a certified receiver.

Alternatives that need nothing extra: YouTube via SmartTube's *Link with TV
code*, and Spotify Connect.

AirScreen ships as a bundle, so reinstall it with Android's multi-part
installer. Unzip the `.apkm`, push each `.apk` to `/data/local/tmp/`, then
run `pm install-create`, a `pm install-write` for each part, and
`pm install-commit` (see the session for exact commands), or ask Claude.

## Voice assistant

Clean Home's voice commands replace Xiao Ai, which `install.sh` disables. To
go back to Xiao Ai, enable both packages with `pm enable`. Re-enabling `com.xiaomi.voicecontrol` does not turn its accessibility service
back on. Enable it in Settings, or run `./restore.sh` with no arguments to put
the original accessibility setting back.

## How Home works on this TV

This ROM only lets whitelisted system apps be the home app (the whitelist is
`MitvSettings.isThirdpartyHomeAllowed`, fed from `/data/system/mitv_features.xml`,
which needs root to change). `cmd package set-home-activity` and the HOME role
both report success but are ignored.

So the launcher includes a small accessibility service, **Home button**
(`HomeKeyService`). It opens the launcher:

- when Home is pressed
- right after boot, when the service starts
- whenever Xiaomi's stock home comes to the front

It reacts to events only and never polls. `install.sh` turns it on.

**Self-repair:** the TV once switched this service off after an unexpected
restart, which left the stock Xiaomi screen on. The launcher now switches the
service back on by itself at boot, after it is updated, and every time the
home screen opens. It uses the `WRITE_SECURE_SETTINGS` permission, which
`install.sh` grants over ADB, and leaves any other accessibility services
alone. If the Xiaomi screen ever shows instead of the launcher, open
**Home** from the app list once and the service comes back on.

**Standby whitelist:** when the power button puts the TV into standby
(suspend-to-RAM), Xiaomi's `MitvActivityManagerService.killAllThirdApp`
force-stops every non-system app. That was the "unexpected" service switch-off:
force-stopping an app also unbinds its accessibility service. The launcher is
now exempt through Xiaomi's own setting, which `install.sh` sets:

```bash
tools/.venv/bin/python tools/tv.py shell "settings put system str_killbackgroud_pkg_white_list com.cleanhome.tv"
```

It is read once at boot (log line: `str_killbackgroud_pkg_white_list WhiteList
… received : [com.cleanhome.tv]`), so restart the TV after setting it.
Verified 18 Oct 2026: on standby the log shows `packageName:com.cleanhome.tv in
strKillWhiteList`, and the launcher was still there on wake. Add more apps
comma-separated (e.g. `com.cleanhome.tv,com.spotify.tv.android`) if you want
them to survive standby too. `restore.sh` removes the setting. (The
`system_str` setting is a status flag the TV manages itself; leave it alone.)

**The stock home (`com.mitv.tvhome`) is deliberately left enabled.** It is the
only home this ROM will start at boot. An earlier version disabled it, and the
TV booted to a blank screen. With it enabled, the TV boots to the stock home
for a moment and the new launcher then opens on top (verified after a reboot).
The cost is that PatchWall's process may sit in the background (around 170 MB
when loaded), which Android reclaims when it needs memory.

If the new launcher ever fails to appear, the TV still shows the normal Xiaomi
home, and `./restore.sh` puts everything back.

## Background audio pausing (app freezer turned off)

On 19 Oct 2026, SmartTube's sound kept cutting out while it played behind
Home. The cause was Android's **cached-app freezer**: SmartTube plays in the
background without a foreground service, so Android counted it as idle and
froze its process about 10 seconds after you left it. Its media session
still said "playing", but a frozen process makes no sound. (The TV's logs
showed `am_freeze` for SmartTube while its audio player was "started".)

The freezer is now off (`settings put global cached_apps_freezer disabled`,
the Developer options switch "Suspend execution for cached apps"); it takes
effect after a restart. `install.sh` sets it and `restore.sh` undoes it.
Background apps may use slightly more CPU and memory as a result, which
matters little on a TV that is always plugged in.

Other things that pause music, all normal Android behaviour: another app
taking the audio (opening WakaTV, Stremio or a video, or switching inputs),
and standby, when Xiaomi force-stops every non-system app (see the standby
whitelist above to spare Spotify or SmartTube).

## ADB after a reboot

This ROM turns network debugging off at every boot (`persist.adb.tcp.port` is
ignored). To use the scripts again after the TV restarts, re-enable the
network/ADB debugging switch in Developer options, then run the script. The
TV remembers this computer's key, so it won't ask to allow it again.

## Changing the dock

Edit `launcher/app/src/main/res/values/config.xml`:

```xml
<string-array name="dock_apps">
    <item>com.stremio.one</item>
    <item>com.mm.droid.livetv.wakatv</item>
    <item>com.spotify.tv.android</item>
    <item>org.smarttube.stable</item>
</string-array>
```

Use a package name, or `package/full.Activity.Name` for apps without a
launcher entry. Apps that have no launcher entry but should still show in
the grid (such as Media Player) go in the `extra_apps` list in the same file. Order is left to right. Dock apps are left out of the grid
automatically. The same file holds display-name overrides and apps to hide
from the grid. Then:

```bash
./install.sh
```

## Weather

The temperature and a small icon next to the date come from Open-Meteo (free,
no account) for **Kumasi**. The launcher fetches only when the home screen
opens and the last reading is over 30 minutes old; there is no background
polling. The last reading is cached and hidden if it is more than 6 hours
old. To change the place, edit the latitude/longitude in
`launcher/app/src/main/res/values/weather.xml` and run `./install.sh`.

## Popular on Stremio

A row of movie posters sits between the dock and the app grid. It is the
first 20 movies of Stremio's own **Popular** catalog (Cinemeta,
`v3-cinemeta.strem.io/catalog/movie/top.json`, no account). OK on a poster
opens that movie's page in Stremio (`stremio:///detail/movie/<imdb id>`).
The list is checked at most every 6 hours, only when Home opens; posters
(300x450, about 20 KB each) are cached in the launcher's private storage, and
only new ones are downloaded. Offline, the last list stays. The row is hidden
if Stremio isn't installed. Count and refresh interval are at the top of
`launcher/app/src/main/java/com/cleanhome/tv/Movies.java`.

Remote: Down from the dock goes to the row, Left/Right moves along it (it
scrolls), Up returns to the dock, Down goes to the app tile below.

## Home screen pills and Now Playing

- **Top pills:** Inputs, Wi-Fi, Wallpaper, Settings. The dock and the Now Playing dropdown use exactly the same
  transparency as the pills (white at 12% with a faint outline,
  `FrostedPanel.java` / `res/drawable/pill_bg.xml`).
- **Now Playing card:** while media is playing or paused, a card appears
  right-aligned under the pills (moved out of the pill row on 19 Oct 2026),
  with the app's artwork, the title, the artist/channel and a play/pause icon.
  Down from any pill reaches it; Up goes back to that pill, Down to the dock. OK opens
  a dropdown with artwork, title, artist and previous / play-pause / next
  (Left/Right to choose, OK to press, Up/Down/Back to close). It needs
  notification-listener access, which `install.sh` grants:
  `cmd notification allow_listener com.cleanhome.tv/com.cleanhome.tv.MediaListener`.
- The sound-output, Bluetooth and sleep-timer pills were tried and removed at
  your request.

## Wallpaper

Home comes with an **ambient wallpaper pack** in a PS5-like style: 12 dark,
abstract designs (Midnight, Aurora, Nebula, Ember, Ocean Light, Dusk Waves,
Graphite, Sakura, Forest Mist, Cosmic Rays, Desert Dusk, Polar Night). They
are original, generated by `tools/make-wallpapers.py`, and built into the
launcher (about 2.6 MB), so they need no internet. Midnight is the default.

**Changing it on the TV:** go to the top bar and choose **Wallpaper**. The
home screen fades away and the gallery opens. Left/Right previews each
wallpaper full screen, OK keeps it, Back puts the old one back. The one in use
has a ring around its thumbnail. **Black** is the last entry.

**Your own picture:** push a JPG, PNG or WebP named `wallpaper.jpg` (or
`.png`/`.webp`). It shows up first in the gallery as **Your photo** (choose it
there; if you have never picked a wallpaper, it is used automatically). It is
dimmed about 45%, with darker areas behind the clock and the dock:

```bash
tools/.venv/bin/python tools/tv.py push ~/Pictures/my-wallpaper.jpg /sdcard/Android/data/com.cleanhome.tv/files/wallpaper.jpg
```

**Changing the pack:** edit or add a design in `tools/make-wallpapers.py`,
then regenerate and reinstall (needs numpy and Pillow, installed in
`tools/.venv`):

```bash
tools/.venv/bin/python tools/make-wallpapers.py
./install.sh
```

The Bing photo-of-the-day rotation was removed on 19 Oct 2026.

## Remote control behaviour

- **Top bar** (Inputs, Wi-Fi, Wallpaper, Settings): Left/Right move between pills and stop at the ends. Down goes to the Now Playing card when something is playing, otherwise to the dock.
- **Now Playing card**: Up returns to the pill above, Down goes to the dock, OK opens the controls.
- **Wallpaper gallery**: Left/Right preview, OK sets, Back cancels.
- **Dock**: Left/Right between the four apps. Up goes to the top bar. Down goes to the Popular on Stremio row.
- **Popular on Stremio**: Left/Right between posters. Up goes to the dock, Down to the grid tile below.
- **Grid**: 4 columns. Up/Down move exactly one row. Up from the first row returns to the movies row.
- **Inputs**: opens a dropdown with HDMI 1–4, AV and Live TV. Up/Down to choose, OK to switch, Back to close.
- **Back** returns to the dock. **Home** while already home also returns to the dock.
- The last focused tile is remembered when you come back from an app.

Inputs are read from the TV input framework. The launcher first tries the
standard passthrough intent. This TV's player doesn't handle it, so it falls
back to Xiaomi's tvplayer (`EXTSRC_PLAY` with `input` 23–26 for HDMI 1–4 and
2 for AV; `ATV_PLAY` for Live TV).

## Resource use

Measured on the TV: about 52 MB PSS, 0% CPU at rest, no frames drawn while
idle. No blur, no video, no network polling. The Wi-Fi name comes from a
connectivity callback, and the clock updates on the system's minute tick.

Smooth scrolling (19 Oct 2026): the GPU (Mali-G52) was the limit, redrawing
the wallpaper, its dim and two full-screen scrims every frame. These are now
painted into one bitmap when a wallpaper loads, which cut GPU time per frame
from about 17 ms to 10 ms. All rows stay bound while off screen, so scrolling
back up no longer rebuilds the dock or the poster row mid-animation. The
focus highlight now moves with its tile while the list scrolls instead of
chasing it. Each Up/Down/Left/Right move measured 0 late frames afterwards.
