# TODO: when the TV is on

The TV was off on 2026-10-22 when TroiaOS got its name, so these wait for it.

## Install

- [x] 2026-10-22: moved the TV from the old `io.freemytv` to `io.troiaos`. Original gave all 11 apps back (the other
      12 disabled ones are Sony's factory-disabled regional apps), the old app was uninstalled, Set Up ran on the TV
      and switched to TroiaOS (HOME = io.troiaos, screensaver io.troiaos/.Screensaver), notification access granted.

## New screenshots for the README

Take them with the greeting name left empty, at 1920 × 1080, and save them as JPEG in `.github/screenshots/`:

- [ ] `home.jpg` again, with the ⚙ button next to Control Center (the current one is from before it).
- [ ] `control-center.jpg` again, with the Free RAM button (the current one is from before it).
- [ ] `lights.jpg`: the lights panel (scenes, brightness, colour). The old one showed the corner buttons that are gone.
- [ ] `markets.jpg`: the markets panel, opened from Control Center.
- [ ] `setup.jpg`: Set Up's handwritten "hello", plus one wizard step.
- [ ] `screensaver.jpg`: the screensaver, with the Now Playing card while music plays.
- [x] `space.jpg`: the home screen with the Space · NASA wallpaper.
- [ ] `wifi-qr.jpg`: the guest Wi-Fi QR panel, but only with a made-up network (never the real one).

Then add them to the README's Screenshots section.

## Check, then update the README's "Not yet checked" list

- [x] Set Up ran start to finish on 2026-10-22 and its switch made TroiaOS the home screen. Still to look at: the Hello
      animation itself.
- [ ] Set Up's Language step shows a house icon next to Türkçe / English: give it a language symbol.
- [ ] The ⚙ button shows right of Control Center (seen 2026-10-22); check that it opens Settings, and ↑ from the apps still lands on Control Center.
- [ ] Customize → Lights, Guest Wi-Fi QR, Music App and Privacy pages; the welcome toast; notices in English.
- [ ] The screensaver starts while YouTube Music plays, and shows the Now Playing card.
- [ ] YouTube gives a video ID (`adb logcat -s TroiaOS | grep continue` after starting a new video), so Continue
      resumes at the exact second.
- [ ] The no-computer path from the README: Downloader → the release link → install → Set Up → the switch. (It needs
      the repo to be public for the link to work.)
