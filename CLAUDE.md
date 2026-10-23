# TroiaOS: notes for Claude

A home screen for Google TV and Android TV that replaces the stock one, plus a settings app that switches between
TroiaOS and the TV's original home screen. A plain Android app in Java, built without Gradle by `build.sh`.
Package `io.troiaos`. It started as a fork of [Free My TV](https://github.com/simbasjungles/free-my-tv) (MIT); that
name stays only in the credits (README, LICENSE). Everywhere else it's TroiaOS.

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
5. **Build:** `./build.sh` → `TroiaOS.apk`. Keep `troiaos.keystore`: updates must be signed with the same key.
6. **Install:** `adb connect <IP>:5555`. The TV asks *Allow USB debugging?*: tell them to accept and tick *Always
   allow*. Then `adb install -r TroiaOS.apk`.
7. **Tell them what the switch does** before they make it: the home screen and screensaver change, a short list of
   promo apps is turned off (never uninstalled), and *Original* brings it all back. On some TVs the remote's
   shortcut buttons belong to the stock home screen (TCL: Netflix, YouTube, Prime), so they rest while TroiaOS
   is on.
8. **They switch on the TV:** *TroiaOS* → *Home Screen* → *TroiaOS*. The app uses the TV's own ADB on
   127.0.0.1 with its own key, so the TV asks once more to allow debugging. Don't switch modes from the computer.
9. **Check:** `adb logcat -s TroiaOS` shows what the switch did.

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
- Build, install, have them switch on the TV, read `adb logcat -s TroiaOS`, then have them switch back to
  *Original* once and confirm everything returned.

TVs that don't run Google TV or Android TV (Samsung, LG and others): the way is a Google TV streamer on HDMI
(Chromecast with Google TV, Google TV Streamer, onn 4K, NVIDIA Shield). Set TroiaOS up on the streamer.

**Uninstalled while in TroiaOS mode?** The apps it turned off stay off. Bring them back with
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
- Settings come from `config.properties` through the generated `out/gen/io/troiaos/Config.java` (never edit that).
- After any change, `./build.sh` must end with `Built TroiaOS.apk`.

## Always

- Never commit `config.properties`, `*.keystore` or any key.
- Never uninstall or clear the data of system apps; only `pm disable-user --user 0`, through `HomeMode`, so
  *Original* can bring it back.
- Don't change the TV's picture, sound, network or account settings.
- Keep the way back working: the *Original* mode and the watchdog.
- Keep the privacy promise (README → Privacy, Customize → Privacy): no analytics, no servers of ours. A new request
  to the internet goes on both lists, over HTTPS. Secrets typed on the TV go through `Prefs.putSecret` (encrypted with
  the Android Keystore, `Secrets`); plain HTTP only through `Net`, which allows home-network addresses only.

## TroiaOS's additions

- **The photo look, on every TV.** `Scene.AGSL` is false, so `Scene.flat()` is true on Android 13+ too (the AGSL
  aurora stays in the code, off: it can't show the photos). Then the background is `Wallpaper`
  (a photo from `Photos`, or soft clouds of colour) and every glass surface is `LiquidGlass`: the pre-blurred
  wallpaper ("frost") drawn through a slight lens, a tint, light on top and a specular rim. Plain Canvas, cheap.
- **Photos** (`Photos`, `assets/wallpapers/photos.json`): nature photos from Wikimedia Commons (day and night sets)
  and public-domain NASA photos (`"set": "space"`, files `space-*.jpg`, cropped to 1920 × 1080), a new one every hour
  or one chosen photo, darkened as chosen (`Prefs.wallpaper`, `wallpaperPhoto`, `wallpaperDim`; Customize →
  Wallpaper, whose photo picker previews behind the list). Each keeps its own licence; list them in
  `assets/wallpapers/CREDITS.md` and keep the on-screen credit (place, photographer, licence). Only add photos with a
  free licence and no extra conditions. Photos show on the flat look (`Scene.flat()`), which is every TV now.
- **NASA's picture of the day** (`Apod`): from the official feed science.nasa.gov/feed/apod-basic/ (api.nasa.gov's
  APOD endpoint returns only the NASA logo since APOD moved). Many APOD pictures are the photographers' own and the
  feed's copyright field just repeats the credit, so `Apod.free` accepts a day only if every name in the credit is a
  public agency or mission; the newest free landscape one is kept in `files/apod.img` (checked every 6 hours), else
  the NASA set shows.
- **Top of the home screen** (`HomeView.drawLightTop`, `drawWeatherWidget`): greeting (`name`), clock, date, the
  weather (Open-Meteo: feels-like, wind, humidity); the corner buttons are icons, with glass only on focus.
- **Dock** (`CLEAN_DOCK`, after CleanFrame): white brand marks in `assets/symbols/app_*.png` (`GLYPHS` maps shelf
  keys to them; licences in `LICENSE-app-icons.txt`) with the app names, on a smoked glass capsule with a soft rim;
  the focused app gets a pill of light, no outline.
- **Control Center** (`ControlCenterActivity`, the corner button next to the weather; the gear right of it opens TroiaOS's Settings): round buttons (Live TV,
  inputs, Wi-Fi QR, settings, sleep through the TV's own ADB, Free RAM: `Ram`, `am kill-all` through the TV's own ADB,
  which only closes cached apps; the app's own `killBackgroundProcesses` freed nothing on the BRAVIA), Now Playing, lights (OK on/off, ← → brightness, hold
  for the panel), markets (OK for the watchlist), volume (← →, OK mutes) and "Edit". The rows are built from
  `Prefs.modules`, so a new control needs an id in `Prefs.BUTTONS` or `TILES`, a symbol and label in
  `moduleSymbol`/`moduleLabel`, and its drawing and keys.
- **Set Up** (`SetupActivity`, `Hello`): the first-run wizard. Settings (the app's icon) and the home screen open it
  while `Prefs.setupDone` is false (not while a switch runs: `HomeMode` opens Settings and Home to measure). `Hello`
  writes "TroiaOS" along `assets/setup/hello.txt` (SVG path data from the Hershey Script font, `tools/make_hello.py`;
  keep the acknowledgement in `assets/setup/CREDITS.md`), then "hello" in many languages. The steps save with Customize's
  `Prefs` keys at once, plus `setup_step` (resumed there), `setup_done` and `setup_skipped` ("Later"). A TV that had
  TroiaOS set up before counts as done (`setup_checked`, once). "Set Up Again" in Settings and Customize. Shared with
  Customize: `Dialogs` (keyboard field, messages), `Geocode` (city search).
- **Customize** (`CustomizeActivity`, from Settings, Control Center's Edit, or holding OK on a dock app): dock apps
  (add any installed app, move, remove), Control Center (show, hide, move), and the home screen (greeting, weather
  city via Open-Meteo geocoding, °C/°F, watchlist, language). Saved by `Prefs` in `custom.json` (both processes read
  it on resume); every getter falls back to `Config`, so config.properties is the default, never the only way.
- **Now Playing** (`MediaListener`, `NowPlaying`): media sessions are only visible to a notification listener, so Control Center
  grants itself that access once through the TV's ADB (`cmd notification allow_listener`). The listener reads no
  notifications. Without access, ⏯ ⏮ ⏭ fall back to media keys; with nothing playing, OK opens YouTube Music
  (`com.google.android.youtube.tvmusic`). The screensaver (`DreamView.drawNowPlaying`) shows a frosted card at the
  top left while music plays, and for a minute after it pauses.
- **Apps layout** (`Prefs.layout`: `dock` or `list`): List draws big names with their marks on the left
  (`HomeView.drawList`, its own layer `listLayer`, scrolled by `listScroll`, a shade from the left in `drawSky`);
  the platter and tiles layers draw nothing then. Keys in `moveList`: ↑ ↓ along the names, → to the continue card.
- **Continue watching** (`Continue`, `HomeView.drawContinue`): `Continue.Recorder` runs inside `MediaListener` (kept
  bound by the system once access is granted) and saves, per app, the latest session with a title: position,
  duration, media ID, picture (`files/continue.json`, `continue-<pkg>.jpg`); finished ones are dropped, idle or
  stopped states never overwrite the position. The home screen reads it on resume and shows a card above the dock
  for the focused app (its own hardware layer, `contLayer`; zone `Z_CONTINUE`). YouTube resumes through
  `youtube.com/watch?v=ID&t=Ns` when the session's media ID looks like a video ID; otherwise the app just opens.
- **Panels** (floating windows over the home screen, theme `TroiaOS.Panel`): `InfoActivity` (weather hours,
  markets, the guest Wi-Fi QR) and `LightsActivity` (all lamps or one, on/off, scenes, brightness, colour).
- **Lights** (`Lights`: `Lamp`, `Pilot`; every brand at the same time, all on the home network):
  - `Wiz`: UDP 38899 (`getPilot`, `setPilot`), broadcast; needs "Allow local communication" (on by default).
  - `Lifx`: the LAN protocol, UDP 56700 (LightGet → LightState; SetColor HSBK, SetPower, with acks).
  - `Govee`: the LAN API (scan to 239.255.255.250:4001, answers on 4002, commands to 4003; no confirmations); each
    lamp needs "LAN Control" on in the Govee app.
  - `Hue`: the bridge's local v1 API over `Net`; found with `Nsd` (`_hue._tcp`) or typed; paired with its button.
  - `HomeAssistant`: REST (`/api/states`, `light.turn_on`/`turn_off`) with a long-lived token; for any other brand.
  - Names, hidden lamps, the bridge and the token are in `Prefs` (`lamp_names`, `lamp_hidden`, `hue_*`, `ha_*`);
    Customize → Lights searches, renames, blinks, hides and connects. WiZ scenes become a white or a colour on other
    brands (`Mood.temp`/`rgb` in `LightsActivity`). Only WiZ has been tried on a real TV; the other brands follow their
    published protocols.
- **Guest Wi-Fi QR**: Customize → Guest Wi-Fi QR (`Prefs.wifiSsid`, `wifiPassword` (encrypted), `wifiSecurity`);
  `config.properties` `guest_wifi_*` is only the starting value.
- **Music app**: `Prefs.musicApp` (YouTube Music when unset): what Now Playing opens and prefers; marks and colours of
  known music apps in `NowPlaying.mark`/`markColor`. YouTube Music is no longer turned off (`HomeMode.OLD_DEBLOAT`:
  the check after boot or an update turns it back on where an older version turned it off).
- **Markets** (`Stocks`): Yahoo Finance's public chart endpoint, unofficial and keyless; off when `stocks` is empty.
- **Android 12 HOME role**: `cmd role get-role-holders` doesn't exist there; `HomeMode.homeHolder` reads `dumpsys
  role` instead.
- **Sony BRAVIA** entries are in `HomeMode.DEBLOAT` and `PROTECTED` (Samba TV, Sony Select, promos, demo, IDD).
