# TODO: when the TV is on

The TV was off on 2026-10-22 when TroiaOS got its name, so these wait for it.

## Install

- [ ] Move the TV from the old app to TroiaOS. The package is now `io.troiaos` with a new signing key, so it is a
      different app from the old `io.freemytv` ("Toria" build of 2026-10-21 ~19:40) and starts with no settings:
  1. On the TV, in the old app: Settings → Home Screen → **Original** (brings the stock home screen and the
     turned-off apps back). Check with `adb shell cmd package resolve-activity -c android.intent.category.HOME
     -a android.intent.action.MAIN`.
  2. `adb uninstall io.freemytv`, then `adb install TroiaOS.apk`.
  3. Open TroiaOS: Set Up runs (name, city Berlin, dock, lights, guest Wi-Fi, music, watchlist), then the switch to
     TroiaOS mode; accept the debugging prompt once.
  4. Grant notification access again: `adb shell cmd notification allow_listener io.troiaos/io.troiaos.MediaListener 0`
     (Control Center also asks for it the first time it opens).
  5. Check the app list, Settings, notices and the banner all say **TroiaOS**.

## New screenshots for the README

Take them with the greeting name left empty, at 1920 × 1080, and save them as JPEG in `.github/screenshots/`:

- [ ] `home.jpg` again, with the ⚙ button next to Control Center (the current one is from before it).
- [ ] `control-center.jpg` again, with the Free RAM button (the current one is from before it).
- [ ] `lights.jpg`: the lights panel (scenes, brightness, colour). The old one showed the corner buttons that are gone.
- [ ] `markets.jpg`: the markets panel, opened from Control Center.
- [ ] `setup.jpg`: Set Up's handwritten "hello", plus one wizard step.
- [ ] `screensaver.jpg`: the screensaver, with the Now Playing card while music plays.
- [ ] `space.jpg`: the home screen with the Space · NASA wallpaper.
- [ ] `wifi-qr.jpg`: the guest Wi-Fi QR panel, but only with a made-up network (never the real one).

Then add them to the README's Screenshots section.

## Check, then update the README's "Not yet checked" list

- [ ] Set Up: every step, the Hello animation, and the step-11 switch to the home screen (Settings → Set Up Again).
- [ ] The ⚙ button right of Control Center opens Settings, and ↑ from the apps still lands on Control Center.
- [ ] Customize → Lights, Guest Wi-Fi QR, Music App and Privacy pages; the welcome toast; notices in English.
- [ ] The screensaver starts while YouTube Music plays, and shows the Now Playing card.
- [ ] YouTube gives a video ID (`adb logcat -s TroiaOS | grep continue` after starting a new video), so Continue
      resumes at the exact second.
- [ ] The no-computer path from the README: Downloader → the release link → install → Set Up → the switch. (It needs
      the repo to be public for the link to work.)
