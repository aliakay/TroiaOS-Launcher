# TroiaOS 1.2 (draft release notes)

Tag `v1.2` on main after this commit. Build the APK with the same `troiaos.keystore` as 1.0 and 1.1, or the TV
refuses the update:

```bash
CONFIG=config.example.properties ./build.sh
shasum -a 256 TroiaOS.apk
```

Attach `TroiaOS.apk` to the release (the README's Downloader link points to `releases/latest/download/TroiaOS.apk`).

---

## What's new

- **Liquid Glass on Android 13+.** Over a wallpaper photo the dock, weather, cards, drawer, Control Center, Lights,
  Wi-Fi and markets panels and Customize's menu are clear glass with a lens edge that bends the photo. Turn it off in
  Customize → Home Screen → Glass; a TV that can't keep up turns it off by itself. Android 11 and 12 look as before.
  (#7)
- **Tapo bulbs.** TP-Link Tapo bulbs (L530, L535…) work locally, with no cloud: Customize → Lights → Tapo takes the
  Tapo account (stored encrypted on the TV) and the bulbs' addresses. (#6)
- **Android 11 and Philips TVs.** TroiaOS now runs on Android 11; Philips' system apps (`org.droidtv`, MediaTek) are
  protected, and their Live TV app is used. (#1)
- **Sunrise and sunset** next to the weather details. (#3, #2)
- **Click sound.** A soft tick and tock as you move; Customize → Home Screen → Click Sound. (#4)
- **Lights in Control Center** show *Loading…* while they switch. (#5)

## Install

Download `TroiaOS.apk` and install it with `adb install -r TroiaOS.apk`, or on the TV with Downloader:
`https://github.com/aliakay/TroiaOS-Launcher/releases/latest/download/TroiaOS.apk`. Upgrading from 1.1 keeps your
settings.

SHA-256: `<fill in>`
