# Privacy

Wheelhouse is an offline-first music player. In short: **your music and listening history
never leave your phone.**

- **No analytics, crash reporters, ads or accounts.**
- **What it reads:** your music library through Android's media store (song titles, artists,
  albums, artwork). This is needed to show and play your music.
- **What it stores, on the phone only:** a cache of your library, your playlists, play counts,
  "loved" songs, your settings, the current queue, downloaded album covers and your Spool
  download list.
- **Other apps:** the Extras › Apps menu lists installed apps so you can open them. This list
  is never stored or shared.

## When Wheelhouse uses the internet

Since version 1.1 the app has the `INTERNET` permission for two optional features:

- **Album covers.** For an album with no artwork, Wheelhouse sends the album title and artist
  name to the public search APIs of [Deezer](https://www.deezer.com/legal/personal-datas) and
  [Apple iTunes](https://www.apple.com/legal/privacy/) and downloads the matching cover. Nothing
  else is sent: no song list, no device id, no account. By default this only happens on Wi-Fi.
  Turn it off in *Settings › Album Artwork › Download Missing Artwork*.
- **Spool downloads.** If you pair Wheelhouse with [Spool](https://github.com/pronzzz/spool) on
  your own computer, the phone talks only to that computer on your home network: your search
  words and the songs you pick go to it, and the finished files come back. Spool on the computer
  then contacts YouTube. Wheelhouse itself never contacts YouTube. If you never pair, nothing is sent.

The connection to Spool is plain HTTP on your local network, protected by the pairing code.
Use it on a home network you trust.

## Permissions

| Permission | Why |
| --- | --- |
| Music and audio / Storage | Read your songs and album artwork |
| Notifications (Android 13+) | Show playback controls in the notification shade and lock screen |
| Foreground service, Wake lock | Keep music playing with the screen off |
| Internet | Optional album-cover lookup and talking to Spool on your computer (see above) |
| Network state | Show the Wi-Fi icon only when online, and only fetch covers on Wi-Fi |
| Vibrate | Haptic ticks from the click wheel |
| Storage (Android 5–9 only) | Save songs downloaded with Spool into the Music folder |

Uninstalling Wheelhouse removes everything it stored.
