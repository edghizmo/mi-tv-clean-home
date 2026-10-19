# Building

## Toolchain

| Tool | Version used | Notes |
|---|---|---|
| JDK | 17 | |
| Android SDK | platform `android-34`, build-tools `34.0.0`, platform-tools | |
| Gradle | 8.7 | Android Gradle Plugin 8.5.2 |
| Python | 3.12 | For `tools/tv.py` and `tools/make-wallpapers.py` |

`install.sh` looks for a toolchain in `tools/` first (`tools/jdk`,
`tools/android-sdk`, `tools/gradle-8.7`), the way the original machine was
set up so nothing had to be installed system-wide. If those folders aren't
there, it uses `JAVA_HOME`, `ANDROID_HOME` and `gradle` from your system.

Python packages:

```bash
python3 -m venv tools/.venv
tools/.venv/bin/pip install -r tools/requirements.txt
```

## Building and installing

```bash
TV_IP=192.168.1.232 ./install.sh            # build, install, set up Home
TV_IP=192.168.1.232 ./install.sh --no-build # reinstall the last build
```

Besides installing the APK, `install.sh` grants the permissions ADB has to
give (location for the Wi-Fi name, secure settings, notification listener),
switches on the Home button accessibility service, puts the launcher on
Xiaomi's standby whitelist, and turns off Android's cached-app freezer.

To build only:

```bash
cd launcher && gradle assembleDebug
# → launcher/app/build/outputs/apk/debug/app-debug.apk
```

## Talking to the TV

`tools/tv.py` is a small ADB client built on the `adb-shell` Python package.
It's used instead of the stock `adb` because macOS Local Network privacy can
block `adb`. On first connect it creates `tools/adbkey`. Keep that file
private and never commit it.

```bash
tools/.venv/bin/python tools/tv.py shell "dumpsys activity activities | grep topResumed"
tools/.venv/bin/python tools/tv.py key HOME
tools/.venv/bin/python tools/tv.py shot screen.png
tools/.venv/bin/python tools/tv.py install some.apk
```

Network ADB on this TV switches itself off at every reboot and on waking
from standby. Turn it back on in Developer options before running scripts.

## Regenerating the wallpapers

```bash
tools/.venv/bin/python tools/make-wallpapers.py          # all 12
tools/.venv/bin/python tools/make-wallpapers.py aurora   # just one
./install.sh
```

Each design is a Python function in `tools/make-wallpapers.py` built from a
few helpers: `gradient`, `glow`, `ribbon`, `particles`, `rays`, `dunes`,
`noise` and `finish`. To add a design, write a function, add it to
`DESIGNS`, and run the script. The output goes to
`launcher/app/src/main/assets/wallpapers/`, along with `index.txt`, which the
gallery uses for order and names.

## Taking screenshots for the docs

```bash
tools/.venv/bin/python tools/tv.py shot docs/screenshots/new.png
```
