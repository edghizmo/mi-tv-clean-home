# Launcher internals

Clean Home (`com.cleanhome.tv`) is a single-activity Android app written in
plain Java. Its only dependency is AndroidX RecyclerView; there are no image
loaders, frameworks or Kotlin. It targets Android 14 (`minSdk 34`) and has
been tuned on a Xiaomi Mi TV (`MiTV-MFTR0`, MediaTek MT9653, Mali-G52 GPU,
4K panel with the interface drawn at 1920×1080). See
[The TV](../README.md#the-tv) for full specs.

## Screen layout

```
┌──────────────────────────────────────────────────────────────────────┐
│ Good evening                    [Inputs] [Wi-Fi] [Wallpaper] [Settings]
│ 20:23                                      [ ♪ Now Playing card     ]│
│ Monday, October 19  ☁ 25°                                            │
│                                                                      │
│ SmartTube            ← name of the focused dock app, with a glow     │
│ ┌──────────────────────── dock (4 apps) ───────────────────────────┐ │
│ └──────────────────────────────────────────────────────────────────┘ │
├──────────────────────── scrolls up ──────────────────────────────────┤
│ Popular on Stremio   [poster][poster][poster][poster][poster] →      │
│ [app][app][app][app]                                                 │
│ [app][app][app][app]          ← app grid, 4 columns                  │
└──────────────────────────────────────────────────────────────────────┘
```

The whole screen is one `RecyclerView`. Item 0 is the header, exactly one
screen tall (clock, top bar, dock). Item 1 is the movie row (when Stremio
is installed), and the rest are app tiles in a 4-column grid. Overlays drawn
above the list: the glass focus highlight, the Inputs dropdown, the Now
Playing controls and the wallpaper gallery.

## Focus model

Android's own focus search is switched off. `HomeActivity.dispatchKeyEvent`
handles every D-pad key itself, so movement is deterministic. Focus is
always in one *section*:

| Section | Contents | Notes |
|---|---|---|
| `TOP` | The pills, plus the Now Playing card below them | Left/Right skips the card; Down reaches it |
| `DOCK` | The four dock apps | Up → top bar, Down → movies |
| `MOVIES` | Popular on Stremio posters | Down picks the grid column under the poster |
| `GRID` | All other apps | Up from the first row → movies |
| `MENU` | Inputs dropdown | |
| `PLAYER` | Now Playing controls | |
| `GALLERY` | Wallpaper picker | Browsing previews live; Back restores |

**One press, one step.** This TV's Bluetooth remote often sends its key
release late, so Android auto-repeats the key. The launcher ignores D-pad
auto-repeats and identical presses within 90 ms.

**The glass highlight** (`GlassHighlight`) is a single view drawn above
everything. It sits at the focused view's live position plus an offset,
and only the offset springs to zero. So it moves rigidly with its tile while
the list scrolls, and the spring only covers the jump between tiles.

## Code map

| File | What it does |
|---|---|
| `HomeActivity.java` | The screen: list, sections, key handling, scrolling, top bar, Inputs menu, Now Playing card, wallpaper and gallery |
| `GlassHighlight.java` | The spring-animated focus highlight; draws frames only while moving |
| `FrostedPanel.java` | Translucent glass panel for the dock band and Now Playing controls |
| `AspectFrame.java` | 16:9 frame for app tiles |
| `AppEntry.java` | Loads dock and grid apps (banners, icons, labels, accent colour from the artwork) |
| `Movies.java` | Fetches and caches "Popular on Stremio" (Cinemeta catalogue and posters) |
| `Waka.java` | "Continue on WakaTV": what was watched, progress, backdrops, and the title to steer WakaTV back to |
| `VoiceAssistant.java` | Records the remote's microphone while the voice button is held; Vosk recognition with chimes |
| `VoiceCommands.java` | Turns recognised speech into actions: open apps, media keys, inputs, SmartTube search |
| `Weather.java` | Current temperature and conditions from Open-Meteo |
| `NowPlaying.java` | Watches media sessions; play/pause/next/previous |
| `MediaListener.java` | Empty notification listener, required to read other apps' media sessions |
| `HomeKeyService.java` | Accessibility service that brings the launcher up on Home, at boot, and over Xiaomi's home; also catches the voice button and reads WakaTV's screens |
| `ServiceGuard.java` | Switches `HomeKeyService` back on if the system turned it off |
| `BootReceiver.java` | Opens the launcher after boot and after an update |

Resources: `res/values/config.xml` (dock, extra and hidden apps, names),
`res/values/weather.xml` (location), and `assets/wallpapers/` (the pack,
its thumbnails and `index.txt`, which sets gallery order and names).

## Data and network

Everything is fetched only when Home opens and the cached copy is stale.
There is no background work, so nothing runs while you watch something else.

| Data | Source | Refresh | Cache |
|---|---|---|---|
| Weather | `api.open-meteo.com` | 30 min | Hidden when older than 6 h |
| Popular on Stremio | `v3-cinemeta.strem.io/catalog/movie/top.json` | 6 h | First 20 movies; posters (300×450, ~20 KB) in app storage, only new ones downloaded |
| Wallpapers | Built into the APK | None | Your chosen wallpaper is remembered |
| Now Playing | Android media sessions | Live callbacks | |

Choosing a movie sends `stremio:///detail/movie/<imdb id>` to Stremio
(`com.stremio.one`). If Stremio won't accept the link, Stremio itself opens
instead.

## Wallpapers

- **Built-in pack.** 12 JPEGs (1920×1080, about 210 KB each) and 384×216
  thumbnails, generated by `tools/make-wallpapers.py` from gradients, glows,
  ribbons, particles and light rays. Each design has a fixed seed, so
  regenerating gives the same images.
- **Your photo.** A `wallpaper.jpg/png/webp` in
  `/sdcard/Android/data/com.cleanhome.tv/files/` appears as "Your photo".
- **Preparing the image.** The chosen image is cropped to the screen once.
  The dim (light for the pack, about 45% for photos) and the two readability
  shades (behind the clock and behind the dock) are painted into it, giving
  one opaque bitmap.
- **Gallery previews.** Previews decode on a dedicated thread. Only the
  latest request wins, so fast browsing never queues up.

## Permissions

| Permission | Why | Granted by |
|---|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE` | Weather, the Stremio row and WakaTV artwork | Install |
| `RECORD_AUDIO` | The remote's microphone while the voice button is held | `install.sh` |
| `ACCESS_WIFI_STATE`, `ACCESS_FINE_LOCATION` | Show the Wi-Fi network name (Android requires location for this) | `install.sh` |
| `QUERY_ALL_PACKAGES` | List installed apps | Install |
| `RECEIVE_BOOT_COMPLETED` | Open at boot | Install |
| `WRITE_SECURE_SETTINGS` | Switch the Home button service back on | `install.sh` (ADB) |
| Accessibility service | Take over the Home button and the voice button; read WakaTV's screens (only WakaTV and Xiaomi's home are watched) | `install.sh` |
| Notification listener | Read media sessions for Now Playing | `install.sh` |

## Performance notes

Measured on the TV with `dumpsys gfxinfo`:

- **GPU was the limit.** Painting the dim and shades into the wallpaper
  bitmap cut GPU time per frame from about 17 ms to 10 ms. The window
  background is also removed, because the opaque wallpaper covers it.
- **No mid-scroll rebuilds.** `setItemViewCacheSize(64)` keeps every row
  bound off screen, so scrolling back up doesn't re-inflate the dock or the
  poster row. The poster strip is only rebuilt when the movie list changes.
- **Fonts built once.** Outfit is a variable font; one typeface per weight is
  built and reused instead of being rebuilt on every bind.
- **Result:** each Up/Down/Left/Right move rendered with 0 frames over
  deadline. At rest the launcher uses 0% CPU, draws nothing, and holds about
  110 MB PSS.
