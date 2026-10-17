# Google Play Store

Everything needed to publish Micro Review for AnkiDroid on the Play Store: the listing texts, the privacy policy, the answers to the Play Console forms, and how to generate the screenshots and GIFs.

| File | Content |
|---|---|
| [listing.md](listing.md) | App name, short and full description, release notes, category |
| [privacy-policy.md](privacy-policy.md) | Privacy policy (the Play Console needs its public URL) |
| this file | Assets, Play Console forms, checklist |

## Generating the assets

The screenshots, GIFs and icon are generated **on demand only**, by a script (nothing generates them in CI or during a build):

```powershell
.\scripts\store-assets.ps1                         # lists the emulators and asks which one to use
.\scripts\store-assets.ps1 -Device Pixel_9a         # this emulator, started first if it isn't running
.\scripts\store-assets.ps1 -Device emulator-5554 -SkipBuild -OutputDir C:\temp\store
.\scripts\store-assets.ps1 -ConvertOnly             # convert the last captures again, without the emulator
```

Also available in VS Code: task "Android: generate store assets" (it asks for the emulator).

Without `-Device`, the script lists every emulator created in Android Studio, running or not, with its status, whether AnkiDroid is installed (known once it runs) and its Android version, then asks for a number or a name:

```
    #   Emulator               Status                   AnkiDroid  Android
    1   Matrix_API31           stopped                  -          Android 12 (API 31), Google Play
    2   Matrix_API34           running (emulator-5556)  no         Android 14 (API 34), Google APIs
    3   Pixel_9a               running (emulator-5554)  yes        Android 16 (API 36), Google Play
```

A stopped emulator is started first, and the script goes on once Android has booted, reporting the progress every 15 s. It stops with the end of the emulator's output (full output in `%TEMP%\emulator-<name>.log`) if the emulator exits while starting (crash, window closed), disappears from adb, or hasn't booted after 5 minutes. An emulator started by the script is shut down at the end, unless `-KeepEmulator`.

### Before the first run

- **An emulator, never a phone**: the script taps through the screens, locks and unlocks, and changes the decks selected in the app. Use an emulator with Android 14 or later (the card wallpaper must be settable on the lock screen only), a Google Play system image (for the Pixel launcher, to add the widget), and no PIN.
- **AnkiDroid installed** on it, its collection created, and its API enabled (AnkiDroid: Settings › Advanced › Enable AnkiDroid API).
- **ffmpeg**: on the PATH (`winget install Gyan.FFmpeg`), or through Python (`pip install imageio-ffmpeg`).
- **The look**: the screenshots show the app as it is set up on the emulator: the lock screen style (background color or picture, text), the review settings and the decks listed on the main screen. Use a picture you are allowed to publish, keep the default review settings ("How to review": notification), and remove decks you don't want to show.

### What it does

1. Builds and installs the app and the test APK, grants the AnkiDroid and notification permissions.
2. Runs `StoreAssetsSetup` (in `app/src/androidTest`, skipped in the device tests and in CI): empties and fills again the **"World capitals"** demo deck in AnkiDroid, so its 12 cards are new, selects it in the app, and renders the icon.
3. Sets the card wallpaper on the lock screen if needed (reboots the emulator if Android didn't start it again after the reinstall), adds the Card widget to the home screen if needed (resized to the full width), and tidies the screen: status bar demo mode (9:30, full battery), system notifications snoozed for an hour, touches shown, screen kept on.
4. Takes the screenshots, records the three flows, converts them, and puts the emulator's settings back.

Each run answers about 4 demo cards. The demo deck counts toward AnkiDroid's daily limit of new cards (20 by default): after about 5 runs on the same day, raise the deck's limit in AnkiDroid or wait for the next day.

### Output (default: `build/store-assets`, not committed)

| Path | Content |
|---|---|
| `screenshots/1-main.png` … `5-widget.png` | Main screen, Settings, lock screen, notification with the answer buttons, widget with the answer buttons. Cropped to 1080 × 2160 (the Play Store refuses images longer than twice their width) |
| `gif/lockscreen.gif` | Home screen → screen off → the card on the lock screen → unlock |
| `gif/notification.gif` | Lock screen → expand the notification → Show answer → Good → "Next card at your next unlock" |
| `gif/widget.gif` | Start review → Show answer → Good → next card → Show answer → Easy |
| `icon-512.png` | Listing icon, 512 × 512, full square (the Play Store rounds the corners) |
| `raw/` | Full-size screenshots and the screen recordings |

The GIFs are 360 px wide, for the README or a website: **the Play Store doesn't accept GIFs**. On the listing, the flows are shown by the screenshots, and optionally by a YouTube video made from the recordings in `raw/`.

The lock screen shows "Charged" because the script simulates a charger to keep the screen on; the script blanks it in the lock screen GIF and screenshot.

## Listing assets

| Asset | Play Store requirement | Source |
|---|---|---|
| App icon | 512 × 512 PNG, 32-bit, ≤ 1 MB | `icon-512.png` |
| Feature graphic | 1024 × 500 PNG or JPEG, required | **To make by hand** (e.g. the icon and the app name on the icon's background color) |
| Phone screenshots | 2 to 8, PNG or JPEG, 320 to 3840 px, long side ≤ 2 × short side | `screenshots/` |
| Video | Optional, YouTube URL | From `raw/*.mp4` |

## Play Console forms

### Data safety

- Does the app collect or share user data? **No.** The app has no internet permission; cards and reviews stay in AnkiDroid, settings in the app's private storage. "Report an issue" only opens a pre-filled GitHub form in the browser, sent by the user under GitHub's policy: this is not collection by the app.
- Data encrypted in transit / deletion request: not applicable (no data collected).

### App content

- Privacy policy: public URL of [privacy-policy.md](privacy-policy.md) (its GitHub page, or GitHub Pages).
- Ads: **no**. App access: **all functionality available without special access** (no account), but AnkiDroid is needed: say so in the instructions for reviewers.
- Content rating: questionnaire, category "Reference, news or educational"; no violence, no user-generated content shared, no purchases → rated for everyone.
- Target audience: 13 and over (not designed for children, to avoid the Families policy requirements).
- News app: no. Health app: no. Government app: no. Financial features: none.

### Permissions to justify if a reviewer asks

| Permission | Why |
|---|---|
| `com.ichi2.anki.permission.READ_WRITE_DATABASE` | Read the decks and cards, record the answers (AnkiDroid's API) |
| `POST_NOTIFICATIONS` | The card notification, the scheduled review reminders |
| `RECEIVE_BOOT_COMPLETED` | Post the card again and set the reminders again after a restart |
| `SYSTEM_ALERT_WINDOW` | Optional "Open after unlock": start the review screen from the lock screen wallpaper (a background activity start) |
| Live wallpaper service | The card on the lock screen |

## Checklist before the first publication

These are not handled by the script or the repository yet:

- [ ] **Target API level**: the app targets API 34. The Play Store requires new apps and updates to target a recent API level (API 35 since August 2025, raised each year): check the current requirement in the Play Console and raise `targetSdk` (and test) first.
- [ ] **Release build**: the Play Store needs an Android App Bundle (`./gradlew bundleRelease`) signed with an upload key, with Play App Signing. `release.yml` only publishes the debug APK on GitHub. Never commit the keystore.
- [ ] **Application ID**: `com.ankidroid.companion` can't be changed once published. It contains "ankidroid", and the name refers to AnkiDroid: the listing must not suggest an official AnkiDroid app (no AnkiDroid logo, the disclaimer at the end of the description), and asking the AnkiDroid team beforehand is wise. Check also that the ID isn't already used on the Play Store.
- [ ] **Developer account**: a personal account created after November 2023 must run a closed test with at least 12 testers for 14 days before applying for production (check the current rule in the Play Console).
- [ ] Feature graphic made, screenshots generated, privacy policy published, Data safety and App content forms filled.
