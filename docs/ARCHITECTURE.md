# Architecture

Wheelhouse is a single-activity Android app written in Kotlin. It has no UI toolkit beyond
plain Android `View`s: every screen is drawn directly on a `Canvas`, which keeps it fast and
small on old phones and gives full control over the classic look.

```
app/src/main/java/io/github/pronzzz/wheelhouse/
├── WheelhouseApp.kt          App-wide singletons (no DI framework)
├── data/
│   ├── Prefs.kt              All settings (SharedPreferences)
│   ├── MediaScanner.kt       Reads songs from MediaStore; can index hand-copied files
│   ├── Library.kt            In-memory index (songs/albums/artists/genres), stats, playlists
│   ├── ArtworkLoader.kt      Memory-capped, off-main-thread album art
│   ├── ArtworkFetcher.kt     Online cover lookup (Deezer, then iTunes) and on-disk cover cache
│   ├── ArtMatch.kt           Picks the right cover from search results (pure, unit tested)
│   ├── NetworkMonitor.kt     Online / Wi-Fi / metered state as a StateFlow
│   └── db/                   Room: cached tracks, play stats, playlists
├── playback/
│   ├── PlaybackService.kt    Media3 MediaSessionService + ExoPlayer
│   ├── PlayerConnection.kt   App-side controller: queue, shuffle/repeat, persistence
│   └── SleepTimer.kt
├── spool/
│   ├── SpoolClient.kt        HTTP client for Spool's LAN API on the user's computer
│   ├── SpoolDiscovery.kt     Finds `spool --lan` engines with a UDP broadcast
│   ├── SpoolDownloads.kt     Download queue: create jobs, follow progress, collect files
│   ├── MusicSaver.kt         Writes finished songs into Music/Spool via MediaStore
│   └── SpoolLinks.kt         YouTube link and address parsing (unit tested)
├── launcher/
│   ├── HomeRole.kt           Becoming / leaving the default home app
│   └── AppsRepository.kt     Lists and opens other apps (Extras › Apps)
├── ui/
│   ├── MainActivity.kt       The HOME + LAUNCHER activity; routes wheel input
│   ├── device/               Case, click wheel, wheel maths, click sound, haptics, colour skins, always-on clock
│   ├── screen/               Screen base class, screen stack, status bar, list widget
│   └── screens/              Main menu, Now Playing, Cover Flow, Extras, Spool, all menus
└── util/Text.kt              Sorting keys, durations, tag clean-up
```

## How input flows

```
ClickWheelView ──(rotate / button / hold)──▶ MainActivity ──▶ ScreenHost.top (Screen)
                                                   │
                                                   └──▶ PlayerConnection (⏯ ⏮ ⏭ are global)
```

- `ClickWheelView` turns touch into wheel events. `WheelMath` (pure Kotlin, unit tested) maps
  angles to buttons and accumulates rotation into whole steps.
- `MainActivity` handles global buttons (Menu = back, ⏯/⏮/⏭ = playback) and forwards rotate /
  select to the top `Screen`. Keyboards and D-pads are mapped to the same events.
- `ScreenHost` keeps a stack of `Screen`s and slides them in and out.

## Screens

A `Screen` owns one view and receives `onRotate`, `onSelect`, `onSelectLong` and `onBack`.
Most screens are a `MenuScreen`: a title plus a function that builds `Row`s. Rows are rebuilt
whenever the screen is shown again or the library changes, so values never go stale.
`Menus.kt` holds every list in the app in one place.

## Library

1. On start, the cached tracks are loaded from Room so menus appear instantly.
2. MediaStore is queried in the background; only changed or removed rows are written back.
3. A `ContentObserver` triggers a rescan a few seconds after music is added or removed.

Play counts, "loved" flags and playlists live in separate tables keyed by MediaStore id, so a
rescan never loses them.

## Playback

`PlaybackService` is a standard Media3 `MediaSessionService`, which gives background playback,
audio focus, "becoming noisy" handling, the media notification, lock screen and headset/Bluetooth
controls for free. The app talks to it through one `MediaController` in `PlayerConnection`,
which also saves the queue on every change and restores it (paused) on the next launch.

## Launcher behaviour

`MainActivity` declares both `LAUNCHER` and `HOME` intent filters, with
`singleTask` + `clearTaskOnLaunch` + `stateNotNeeded`, the classic launcher setup.
When Home is pressed while Wheelhouse is already in front, `onNewIntent` returns to the main
menu. `HomeRole` uses `RoleManager` on Android 10+ and the home settings screen on older versions.

## Performance notes

- Everything heavy (MediaStore, Room, bitmap decoding) runs off the main thread.
- Artwork is decoded with sampling, square-cropped and kept in an `LruCache` sized from the
  device's memory class.
- Animated screens redraw at a modest rate and stop entirely when hidden or the screen is off.
- **Lite Mode** disables transitions, reflections and the Ken Burns preview.
