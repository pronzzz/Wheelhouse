# Changelog

All notable changes to this project are documented here.
The project follows [Semantic Versioning](https://semver.org/).

## [1.1.0] - 2026-11-01

### Added
- **Download music with Spool.** A new *Spool* menu searches YouTube from the wheel and sends
  the songs you pick to [Spool](https://github.com/pronzzz/spool) 2.1+ running with `--lan` on
  your computer. Finished songs arrive tagged, with a square cover, in `Music/Spool`. Pair with
  a 6-character code; the computer is found automatically on Wi-Fi or by address. Choose M4A or
  MP3 320, follow progress in *Spool › Downloads*, cancel or retry, and share YouTube links
  from other apps to *Download with Spool*.
- **Album covers from the internet.** Albums without artwork get a cover from Deezer or iTunes
  (only album and artist names are sent). Wi-Fi only by default; *Settings › Album Artwork* can
  find all missing covers at once, show how many were downloaded and delete them.
- **Always-On Display.** After a minute without input the screen dims to a black clock with the
  date, battery and the current song, drifting slightly to protect the screen. Any touch or key wakes it.
- **Wi-Fi icon** in the status bar, shown only when the phone is on Wi-Fi with a working internet connection.
- **Haptics strength:** Off, Light, Medium or Strong, with a preview when changed.
- **Keep Screen On** now has Off, On Charger and Always.

### Fixed
- Wheel haptics were silent on some phones (for example a Samsung Galaxy J7 Pro on LineageOS)
  when Android's own "touch vibration" setting was off or the phone ignored view haptic
  feedback. The wheel now drives the vibration motor directly with short, rate-limited pulses.

### Changed
- The app now requests the `INTERNET` and `VIBRATE` permissions, and storage write access on
  Android 5–9 for saving Spool downloads. See [PRIVACY.md](PRIVACY.md).
- Settings from 1.0 (haptics on/off, awake on charger) carry over to the new options.

## [1.0.0] - 2026-10-21

First release of **Wheelhouse**, the successor to PocketPod. The app was rebuilt from the
ground up as a home-screen launcher that turns an Android phone into a dedicated music player.

### Added
- Home launcher mode: set Wheelhouse as the default home app; the Home button always returns to the main menu.
- Touch click wheel with rotation scrolling, click sound, haptics, tap and hold actions and three speeds.
- Split-screen main menu with an animated album-art preview.
- Music browsing: Playlists, Artists, Albums, Songs, Genres and wheel-spelled Search.
- Cover Flow with perspective covers and reflections.
- Now Playing with artwork reflection, progress, volume, scrubbing, shuffle and repeat modes.
- Up Next queue, Play Next, Add to Up Next, Love, Go to Album/Artist and song info.
- Smart playlists (Loved, Recently Added, Most Played, Recently Played) and user playlists.
- Extras: Clock, Stopwatch, Sleep Timer and an Apps list for opening other apps.
- Nine device colours, light/dark screen, backlight, full-screen and keep-awake-on-charger settings.
- Lite Mode for low-RAM phones (on by default when Android reports a low-RAM device).
- Queue restore after restart, auto-return to Now Playing, background playback with media session controls.
- D-pad, keyboard and remote support.
- Automatic library refresh when music is added; "Rescan Library" also indexes files copied by hand.

### Changed
- Renamed from PocketPod to Wheelhouse; new package id `io.github.pronzzz.wheelhouse`.
- Minimum Android version lowered from 11 to 5.0 so far older phones can run it.
- Upgraded to Kotlin 2.0, Android Gradle Plugin 8.7 and Media3 1.5.

### Removed
- The unfinished PocketPod scaffold (Fragments/Navigation setup that never launched).

[1.1.0]: https://github.com/pronzzz/Wheelhouse/releases/tag/v1.1.0
[1.0.0]: https://github.com/pronzzz/Wheelhouse/releases/tag/v1.0.0
