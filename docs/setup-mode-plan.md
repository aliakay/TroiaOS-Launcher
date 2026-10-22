# Plan: Set Up mode (first-run wizard)

*Status: implemented (`SetupActivity`, `Hello`, `Dialogs`, `Geocode`, `tools/make_hello.py`). Not yet tried on a TV.*

The goal: the first time someone opens TroiaOS, it greets them like a new iPhone ("hello" written by hand on black,
then the word in other languages, with TroiaOS's Trojan horse at the bottom), then walks them, one step at a time
with the remote, through setting up their TV and the launcher. Read [CLAUDE.md](../CLAUDE.md) first: its rules apply
(Java 8, no libraries, one Canvas per view, 60 fps with no allocations in `onDraw`, every visible string as
`Ui.tr("tr", "en")`, `./build.sh` must end with `Built TroiaOS.apk`).

## 1. When it shows

- **First open.** The person opens TroiaOS from the TV's app list (`SettingsActivity`, the launcher entry) before
  anything else exists. In `SettingsActivity.onCreate`: if `!Prefs.setupDone()`, start `SetupActivity` and `finish()`.
  Also in `HomeActivity`/`HomeView.resume()`, same check (for a TV switched without the wizard).
- **Existing installs are not forced into it.** Migration, once: if `custom.json` already has any of `welcomed`,
  `dock`, `wallpaper`, `name`, `layout`, or `HomeMode.state` says mode `free`, set `setup_done=true` silently.
- **Again on demand.** A row "Set Up Again" / "Kurulumu Yeniden Başlat" in Settings and at the bottom of Customize's
  main page. It starts `SetupActivity` (it doesn't clear any setting: every step shows the current values).
- **Leaving early.** Back on the Hello screen asks "Set up later?" (Later / Continue). "Later" sets `setup_skipped`
  so it never nags again; it stays reachable from "Set Up Again". The step reached is saved (`setup_step`), so opening
  it again resumes there.
- Replace the one-time welcome toast in `HomeView.resume()` (the `welcomed` key): a TV that finished setup doesn't need it.

## 2. The Hello screen (step 0)

Pure black, full screen. Timeline (all times from the activity's start; use `SystemClock.uptimeMillis()`):

| Time | What |
|---|---|
| 0–0.6 s | black |
| 0.6–3.0 s | a cursive "hello" is **written**: the stroke grows along its path (`PathMeasure.getSegment`), eased (ease-in-out), round caps and joins, stroke ~16 px at 1920 wide, white with a soft warm gradient across it (cream `#FFF4E6` → peach `#FFC9A8`, TroiaOS's palette; no blue) |
| 3.0–4.2 s | hold |
| 4.2 s on | the word fades (0.5 s) and other languages follow, typed in Inter (`Ui.displaySemibold`, ~150 px), each 1.6 s with a 0.4 s crossfade and a 12 px rise: Merhaba, Hola, Bonjour, Hallo, Ciao, Olá, Hej, Привет, こんにちは, 안녕하세요, 你好, مرحبا, then "hello" written again (loop). Put the TV's own language first if it's in the list. |
| 1.2 s on | the **Trojan horse** at the bottom center (`assets/symbols/horse.png` through `Symbols.draw`, ~110 px tall, centered at y≈900 of 1080), fading in over 1 s, white at 85% |
| 3.4 s on | under the horse, a small hint fades in and breathes slowly (alpha 0.45↔0.85, 2.4 s period): "Press OK to set up" / "Kurmak için OK'e basın" |

OK at any time continues (also during the animation). Back: the "Set up later?" dialog.

**The handwriting path must be our own.** Do not trace Apple's "hello" lettering (it is Apple's artwork). Two
acceptable sources:

1. **Preferred:** the Hershey *Script* single-stroke font (public domain, 1967). Write `tools/make_hello.py`
   that turns the glyphs of "hello" into one path (the letters' strokes joined at the baseline), normalized to a
   1000 × 400 box, and prints it as SVG path data. Store the result in `app/assets/setup/hello.txt` and credit
   "Hershey fonts (public domain)" in `CREDITS.md`/README. The Hershey data file must come from a public-domain
   copy; keep the tool's download in a temp directory and don't commit the data file, only the generated path.
2. Otherwise: hand-author a single-line cursive "hello" as ~10–14 cubic Béziers in `Hello.java`.

Parse SVG path data at start-up (only M, L, C, Q, Z are needed) into a `Path`, scale it to ~900 px wide centered at
y≈470, build one `PathMeasure` (with `forceClosed=false`; iterate `nextContour()` if there are several strokes, and
grow them one after the other by their share of the total length). Reuse one `Path` for the drawn segment; never
allocate in `onDraw`.

Make it a class of its own, `Hello` (a `View`): `start()`, `stop()`, a `Choreographer` frame callback while
animating (like `ControlCenterActivity.CenterView.doFrame`).

## 3. The steps

After Hello, the background becomes the person's wallpaper (`Scene`/`LiquidGlass.background`, as `CustomizeActivity`
draws it), with the same dark veil. Layout like Customize: on the left the step's symbol, title and one line of
explanation; on the right the choices as rows (same row look: capsule, white when focused, value on the right).
At the bottom: progress dots (one per step, the current one wide) and the hint "OK: choose · → Next · Back: previous"
(→ or a "Continue" row at the end of each list moves on). Every optional step has a "Skip" / "Atla" row.

Every choice is saved **at once** with the same `Prefs` keys Customize uses, so leaving half-way keeps it and
Customize shows the same values.

| # | Step | Content | Saves |
|---|---|---|---|
| 1 | **Language** | English · Türkçe (the TV's language preselected). Changing it redraws everything in that language at once (`HomeMode.state` `language` + `Ui.loadLanguage`, as Customize's language row does). | state `language` |
| 2 | **Your name** | "What should TroiaOS call you?" A row that opens the keyboard dialog; "Skip" leaves just "Hello". | `name` |
| 3 | **Your city** | Search by name (keyboard dialog) → the list of places (Open-Meteo geocoding), pick one; then °C / °F / Automatic. Offline: say so and offer Skip. | `city`, `latitude`, `longitude`, `temperature` |
| 4 | **Your apps** | The installed launchable apps, the known streaming apps first and already ticked (as the default dock is built: `HomeApps.dockPackages`). OK ticks/unticks, max `HomeApps.MAX_SHELF`. Then "Dock" or "List" with a one-line description of each. | `dock`, `layout` |
| 5 | **Wallpaper** | Nature · Space · NASA · NASA Picture of the Day · Soft Colors, shown live behind as the focus moves (`Photos.preview` / `refresh`, as Customize's picker does). | `wallpaper` |
| 6 | **Lights** (optional) | Searches at once (`Lights.all`), lists what answered (name + brand) with "Blink" on OK to tell them apart. Text: WiZ, LIFX and Govee are found by themselves; Hue and Home Assistant are connected in Customize → Lights later (keep pairing out of the wizard). "Search again", "Skip". | (nothing new; `lights_seen` is set by `Lights.all`) |
| 7 | **Guest Wi-Fi** (optional) | Network name, password (secret field), security, then a preview of the QR code. "Skip". | `wifi_ssid`, `wifi_password` (via `Prefs.putSecret`), `wifi_security` |
| 8 | **Music** | Installed apps, known music apps first (Spotify, YouTube Music). | `music_app` |
| 9 | **Markets** (optional) | None · "S&P 500, Bitcoin, Gold" (`^GSPC:S&P,BTC-USD:BTC,GC=F:XAU`) · "Type my own" (keyboard dialog). | `stocks` |
| 10 | **Privacy** | The same facts as Customize → Privacy, as a short list, and one row "Got it". Not skippable (one press). | — |
| 11 | **Make TroiaOS your home screen** | Explain before doing it (CLAUDE.md, *Installing* step 7): the home screen and screensaver change; a short list of promo apps is turned **off, never uninstalled**; *Original* brings it all back. Then: "Switch now" runs `HomeMode.request(c, HomeMode.FREE)` and shows the progress from `HomeMode.state` (`busy`, `error`) exactly as `SettingsActivity` does (see its `busy()` and the "Allow" handling around line 273: the TV asks *Allow USB debugging?* — tell them to tick *Always allow* and press OK). If ADB is off: explain how to turn on Developer options → USB / network debugging on Android TV, and "Try again". "Later" skips (they can switch in Settings). Skip this step when TroiaOS is already the home screen. | (HomeMode's own state) |
| 12 | **All set** | "You're all set" + three tips: hold OK on an app to move it; the button next to the weather is Control Center; Edit in Control Center changes all this. Small horse at the bottom again. OK: `setup_done=true`, go Home (`Intent.ACTION_MAIN` + `CATEGORY_HOME`). | `setup_done` |

## 4. Code structure

New:

- `SetupActivity.java`: the activity (`:home` process, theme `TroiaOS.Home`, not exported; declare it in
  `AndroidManifest.xml` like `CustomizeActivity`), hosting `Hello` first, then `SetupView`.
- `SetupView` (inside `SetupActivity`, like `CustomizeView` inside `CustomizeActivity`): the steps as a small state
  machine (`step`, `focus`, rows per step built in `rebuild()`, `onKey`). Copy Customize's row drawing and
  scrolling rather than inventing a new look.
- `Hello.java`: the animation (section 2).
- `Geocode.java`: move the Open-Meteo city search out of `CustomizeActivity.searchCity`/`get` into
  `static List<String[]> search(String query, String lang) throws Exception` (name, detail, lat, lon), and use it
  from both.
- `Dialogs.java`: move `CustomizeActivity`'s `ask(...)` (text field dialog, `FIELD_WORDS/CODE/SECRET`) and
  `dialog(...)` into static helpers taking the `Activity`, and use them from both.

Changed:

- `Prefs`: `setupDone()`, `setupStep()`, and the migration of section 1.
- `SettingsActivity`: first-run redirect; "Set Up Again" row.
- `CustomizeActivity`: use `Geocode` and `Dialogs`; "Set Up Again" row at the bottom of the main page.
- `HomeView`: drop the `welcomed` toast; the first-run check.
- `README.md` ("Set it up on the TV": the wizard first), `CLAUDE.md` (a bullet for Set Up mode),
  `app/assets/wallpapers/CREDITS.md` or the README credits (Hershey fonts, if used).

## 5. Details that matter

- **Keys:** D-pad, OK, Back only. Long presses aren't needed in the wizard. Every text entry is the existing
  keyboard dialog (secrets with `FIELD_SECRET`).
- **Never block the UI thread:** geocoding, `Lights.all`, `Lights.blink` and `HomeMode.request` run on an executor,
  results posted back (as Customize does).
- **No allocations in `onDraw`**: Paints, Paths, `PathMeasure`, gradients made in `onSizeChanged` / start-up.
- **Bilingual**: every string `Ui.tr("…", "…")`, Turkish first.
- **Privacy promise**: the wizard adds no new internet request (geocoding and the NASA feed are already listed).
- **Sizes** in 1920 × 1080 units times `k = width / 1920f`, like every other view.

## 6. How to check it

1. `./build.sh` ends with `Built TroiaOS.apk`.
2. On the TV: Settings → "Set Up Again" (the activity isn't exported, so `adb shell am start` can't open it).
3. Walk every step with the remote; at each step, leave with Back and open Customize to see the value saved.
4. Leave half-way, open TroiaOS again: it resumes at the same step.
5. Hello: the stroke is smooth (no jumps between letters), the languages crossfade, the horse and the hint appear,
   OK works during the animation.
6. Turn the network off: the city step says so and Skip works.
7. Screenshots: `adb exec-out screencap -p > file.png`. Tell the person before pressing keys on their TV; they may
   be using the remote at the same time.
