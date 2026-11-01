# Turning an old phone into a music player

This guide takes a spare Android phone from "drawer junk" to "dedicated music player" in about
fifteen minutes.

## What you need

- An Android phone running **Android 5.0 or newer**
- Your music as files (MP3, M4A/AAC, FLAC, Ogg, Opus, WAV…)
- A USB cable or a microSD card
- Optional: wired headphones, or a Bluetooth speaker/headphones

## 1. Start fresh (recommended)

A factory reset gives you the most free space and the best battery life.
*Settings › System › Reset options › Erase all data*. When setting the phone up again you can
skip the Google account if you like; Wheelhouse doesn't need one. Wi-Fi is only needed for the
optional album-cover downloads and for downloading music with Spool.

## 2. Install Wheelhouse

1. On the phone, download [`Wheelhouse.apk`](https://github.com/pronzzz/Wheelhouse/releases/latest/download/Wheelhouse.apk)
   (or copy it over from a computer).
2. Open it. Android will ask to allow installing from this source; allow it, then **Install**.

No browser on the phone? With USB debugging enabled you can also install from a computer:

```bash
adb install Wheelhouse.apk
```

## 3. Add your music

Copy your music into the phone's **Music** folder:

- **Windows / Linux:** plug the phone in, choose *File transfer* on the phone, and drag folders
  into `Internal storage/Music`.
- **macOS:** use [OpenMTP](https://openmtp.ganeshrvel.com/) or Android File Transfer.
- **SD card:** put the folders anywhere on the card and insert it.

Folders like `Artist/Album/01 Song.mp3` work best. Embedded album art, or a `cover.jpg` /
`folder.jpg` in the album folder, is picked up automatically.

Wheelhouse notices new music on its own. If something is missing, use
**Settings › Rescan Library**.

## 4. First launch

1. Open Wheelhouse and choose **Allow Music Access**.
2. Choose **Set as Home Screen** and pick Wheelhouse in the dialog.
   On older Android versions you will see the system's home-app chooser instead; select
   Wheelhouse and **Always**.

Now the phone boots straight into Wheelhouse, and the Home button brings you back to the
main menu.

## 5. Make it feel like an appliance

These are all optional, but they make a big difference.

| Do this | Where | Why |
| --- | --- | --- |
| Turn on **Airplane mode**, then turn Bluetooth back on if you use it (and Wi-Fi when you want covers or Spool) | Quick settings | Doubles or triples standby battery life |
| Turn off notifications for other apps, or uninstall/disable them | *Settings › Apps* | Nothing else interrupts the music |
| Set screen timeout to 30 seconds | *Settings › Display* | The screen is only on while you browse |
| Turn on **Lite Mode** on very slow phones | Wheelhouse *Settings* | Fewer animations, smaller artwork |
| Choose a **Device Colour** | Wheelhouse *Settings* | Make it yours |
| Set **Keep Screen On** to *On Charger* and open *Extras › Clock* | Wheelhouse *Settings* | A bedside or desk clock while it charges |
| Turn on **Always-On Display** | Wheelhouse *Settings* | A dim clock and the current song instead of a black screen |
| Pick a **Haptics** strength | Wheelhouse *Settings* | Stronger ticks on phones with weak vibration motors |
| Limit charging to ~80% if the phone supports it | *Settings › Battery* | Old batteries last longer |

Need a phone setting, the camera or Bluetooth pairing? **Extras › Apps** opens any installed app.
You can hide that menu with **Settings › Apps in Extras**.

## 6. Using the wheel

- **Scroll:** drag your thumb around the ring. Each step clicks.
- **Select:** press the centre button. **Back:** press **Menu**.
- **Main menu:** hold **Menu** (or press the phone's Home button).
- **Song options:** hold the centre button on any song (Play Next, Add to Playlist, Love…).
- **Now Playing:** turn the wheel for volume; press the centre to switch to scrub, shuffle and
  repeat; hold **⏮/⏭** to rewind or fast-forward.
- **Search:** turn the wheel to pick a letter, press the centre to type it, hold the centre when done.

Prefer touch? You can also tap and swipe directly on the screen.

## Going back to a normal phone

**Settings › Home Screen › Switch to Another Home App…**, pick your previous launcher, then
uninstall Wheelhouse like any app if you wish.

## Troubleshooting

**No music shows up.** Check the files are in `Music` (or anywhere on the SD card) and try
**Settings › Rescan Library**. Files the phone itself can't play won't be listed.

**I denied music access by mistake.** The welcome screen offers **Open App Settings**; turn on
*Music and audio* (or *Storage*) there.

**The status bar keeps appearing.** Swipe it away, or turn off **Settings › Full Screen** if you
prefer to see the clock and notifications.

**Playback stops when the screen is off.** Some phone makers kill background apps aggressively.
Open *Settings › Apps › Wheelhouse › Battery* and choose *Unrestricted*, or see
[dontkillmyapp.com](https://dontkillmyapp.com) for your phone model.
