# At Yazılım: notes for Claude

A home screen for Google TV and Android TV that replaces the stock one, plus a settings app that switches between
At Yazılım and the TV's original home screen. A plain Android app in Java, built without Gradle by `build.sh`.
It is a fork of [Free My TV](https://github.com/simbasjungles/free-my-tv) (MIT): the package is still `io.freemytv`
and many comments still say "Free My TV"; the app's visible name is At Yazılım.

The person you're helping may not be technical. Keep it short and friendly, say what you're about to do on their TV
before you do it, and let them do the taps on the TV.

## Installing it for someone

1. **Ask** for the TV's IP address and their city, if they didn't say.
2. **Tools:** a JDK 17+, the Android SDK command-line tools (build-tools 34+, platform android-34) and adb. Check
   what's already there (`java -version`, `$ANDROID_HOME`, `adb version`) and install only what's missing.
   - macOS: `brew install --cask temurin android-commandlinetools android-platform-tools`, then
     `sdkmanager "build-tools;34.0.0" "platforms;android-34"`.
   - Linux: OpenJDK 17, Android's command-line tools and adb from the package manager, then the same `sdkmanager` line.
   - Windows: WSL, then as on Linux.
3. **Settings:** run `./build.sh` once; it creates `config.properties`. Fill in `latitude` and `longitude` (decimal
   degrees) and `city` for their city, `name` for the greeting, `temperature`, and `stocks` if they want the markets
   panel (Yahoo Finance symbols). These are only defaults: all of them, and the dock's apps (`dock`), can be changed
   later on the TV (Settings → Customize).
4. No keys are needed: the weather (Open-Meteo) and the markets (Yahoo Finance chart data) are keyless, and the
   WiZ lights are found on the home network.
5. **Build:** `./build.sh` → `FreeMyTV.apk`. Keep `freemytv.keystore`: updates must be signed with the same key.
6. **Install:** `adb connect <IP>:5555`. The TV asks *Allow USB debugging?*: tell them to accept and tick *Always
   allow*. Then `adb install -r FreeMyTV.apk`.
7. **Tell them what the switch does** before they make it: the home screen and screensaver change, a short list of
   promo apps is turned off (never uninstalled), and *Original* brings it all back. On some TVs the remote's
   shortcut buttons belong to the stock home screen (TCL: Netflix, YouTube, Prime), so they rest while At Yazılım
   is on.
8. **They switch on the TV:** *At Yazılım* → *Home Screen* → *At Yazılım*. The app uses the TV's own ADB on
   127.0.0.1 with its own key, so the TV asks once more to allow debugging. Don't switch modes from the computer.
9. **Check:** `adb logcat -s FreeMyTV` shows what the switch did.

## Adapting it to another TV

Read first, change after. Read-only commands that tell you what the TV has:

```bash
adb shell getprop ro.product.brand; adb shell getprop ro.product.model; adb shell getprop ro.build.version.release
adb shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME
adb shell cmd role get-role-holders android.app.role.HOME
adb shell settings get secure screensaver_components; adb shell settings get secure screensaver_default_component
adb shell pm list packages -s     # system apps (-3 for the ones they installed)
```

Then, in the code:

- `HomeMode.DEBLOAT`: add the brand's promo, demo, store-mode and telemetry apps, one comment per line. Only those.
- `HomeMode.PROTECTED`: add the brand's system pieces: picture, sound, inputs and HDMI-CEC, the tuner and live TV,
  remote and Bluetooth pairing, setup, updates, system UI. When in doubt, protect it.
- `HomeApps.SHELF`, `LIVE_TV_APPS` and `HIDDEN`: the shelf apps, the brand's live TV app, apps to hide from All Apps.
- Build, install, have them switch on the TV, read `adb logcat -s FreeMyTV`, then have them switch back to
  *Original* once and confirm everything returned.

TVs that don't run Google TV or Android TV (Samsung, LG and others): the way is a Google TV streamer on HDMI
(Chromecast with Google TV, Google TV Streamer, onn 4K, NVIDIA Shield). Set Free My TV up on the streamer.

**Uninstalled while in Free My TV mode?** The apps it turned off stay off. Bring them back with
`adb shell pm enable <package>` (the `DEBLOAT` list and the stock home screen), then
`adb shell cmd package set-home-activity <stock home activity>` and
`adb shell settings put secure screensaver_components <stock screensaver>`.

## The code

- Java 8 language level, minSdk 31 (Android 12), targetSdk 33. No Gradle, no libraries: only the Android SDK.
- The home screen is drawn by hand on one Canvas (`HomeView`); the glass and the sky are AGSL shaders (`Shaders`,
  Android 13+, with a fallback for 12). Keep it at 60 fps: no allocations in `onDraw`, no work on the UI thread.
- `HomeMode` runs every switch as a background job (`ModeJob`) in the main process; the UI is in the `:home`
  process. `Watchdog` goes back to the stock home screen after 3 crashes in 5 minutes.
- On-screen text is bilingual, `Ui.tr("tr", "en")` (Turkish, English): add both. Comments in English, short, only where the why
  isn't obvious.
- Settings come from `config.properties` through the generated `out/gen/io/freemytv/Config.java` (never edit that).
- After any change, `./build.sh` must end with `Built FreeMyTV.apk`.

## Always

- Never commit `config.properties`, `*.keystore` or any key.
- Never uninstall or clear the data of system apps; only `pm disable-user --user 0`, through `HomeMode`, so
  *Original* can bring it back.
- Don't change the TV's picture, sound, network or account settings.
- Keep the way back working: the *Original* mode and the watchdog.

## At Yazılım's additions

- **Android 12 look (no AGSL).** `Scene.flat()` is true without RuntimeShader. Then the background is `Wallpaper`
  (a photo from `Photos`, or soft clouds of colour) and every glass surface is `LiquidGlass`: the pre-blurred
  wallpaper ("frost") drawn through a slight lens, a tint, light on top and a specular rim. Plain Canvas, cheap.
- **Photos** (`Photos`, `assets/wallpapers/photos.json`): nature photos from Wikimedia Commons, day and night sets, a
  new one every hour. Each keeps its own licence; list them in `assets/wallpapers/CREDITS.md` and keep the on-screen
  credit (place, photographer, licence). Only add photos with a free licence and no extra conditions.
- **Top of the home screen** (`HomeView.drawLightTop`, `drawWeatherWidget`): greeting (`name`), clock, date, the
  weather (Open-Meteo: feels-like, wind, humidity); the corner buttons are icons, with glass only on focus.
- **Dock** (`CLEAN_DOCK`, after CleanFrame): white brand marks in `assets/symbols/app_*.png` (`GLYPHS` maps shelf
  keys to them; licences in `LICENSE-app-icons.txt`) with the app names, on a smoked glass capsule with a soft rim;
  the focused app gets a pill of light, no outline.
- **Control Center** (`ControlCenterActivity`, the one corner button next to the weather): round buttons (Live TV,
  inputs, Wi-Fi QR, settings, sleep through the TV's own ADB), Now Playing, lights (OK on/off, ← → brightness, hold
  for the panel), markets (OK for the watchlist), volume (← →, OK mutes) and "Edit". The rows are built from
  `Prefs.modules`, so a new control needs an id in `Prefs.BUTTONS` or `TILES`, a symbol and label in
  `moduleSymbol`/`moduleLabel`, and its drawing and keys.
- **Customize** (`CustomizeActivity`, from Settings, Control Center's Edit, or holding OK on a dock app): dock apps
  (add any installed app, move, remove), Control Center (show, hide, move), and the home screen (greeting, weather
  city via Open-Meteo geocoding, °C/°F, watchlist, language). Saved by `Prefs` in `custom.json` (both processes read
  it on resume); every getter falls back to `Config`, so config.properties is the default, never the only way.
- **Now Playing** (`MediaListener`, `NowPlaying`): media sessions are only visible to a notification listener, so Control Center
  grants itself that access once through the TV's ADB (`cmd notification allow_listener`). The listener reads no
  notifications. Without access, ⏯ ⏮ ⏭ fall back to media keys; with nothing playing, OK opens YouTube Music
  (`com.google.android.youtube.tvmusic`). The screensaver (`DreamView.drawNowPlaying`) shows a frosted card at the
  top left while music plays, and for a minute after it pauses.
- **Panels** (floating windows over the home screen, theme `FreeMyTV.Panel`): `InfoActivity` (weather hours,
  markets) and `LightsActivity` (WiZ: all lamps or one, on/off, scenes, brightness, colour). OK on the bulb switches
  every lamp; holding OK opens the panel.
- **WiZ** (`Wiz`): local UDP on port 38899 (`getPilot`, `setPilot`), broadcast discovery, no cloud. Lamps answer
  only with "Allow local communication" on in the WiZ app.
- **Markets** (`Stocks`): Yahoo Finance's public chart endpoint, unofficial and keyless; off when `stocks` is empty.
- **Android 12 HOME role**: `cmd role get-role-holders` doesn't exist there; `HomeMode.homeHolder` reads `dumpsys
  role` instead.
- **Sony BRAVIA** entries are in `HomeMode.DEBLOAT` and `PROTECTED` (Samba TV, Sony Select, promos, demo, IDD).
