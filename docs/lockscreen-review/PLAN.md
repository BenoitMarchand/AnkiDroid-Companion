# Lock screen review: plan

Feature: while the phone is locked, the lock screen shows the current due card, and a lock-screen notification shows the answer with the 4 review buttons. Once unlocked, the lock-screen card disappears, and an unused notification is removed.

Status: **implemented, testing on devices** · Branch: `feature/lockscreen-review` · Open steps are tracked as GitHub issues on the fork, under the milestone "Lock screen review".

## Approach: live wallpaper

The app provides a **live wallpaper** ("AnkiDroid Companion card") that the user selects in Android's own wallpaper settings, for the **lock screen only**. The wallpaper draws the current card's question.

Why not swap static wallpapers (`WallpaperManager.setBitmap`)?
- Nothing has to be saved and restored, so the original wallpaper is never lost.
- No disk write on every lock/unlock, so no flicker or delay and no theme recolor.
- It doesn't need the `SET_WALLPAPER` permission or a foreground service just to detect unlocking. The wallpaper engine is kept alive by the system while it's selected.

Limits:
- A separate live wallpaper for the lock screen only is supported from **Android 14**.
- On Android 8–13, a live wallpaper applies to both the home screen and the lock screen. The engine then draws the card only while the keyguard is shown, and a background otherwise.
- Apps can't set a lock-only live wallpaper themselves: the user picks it in the system wallpaper settings.
- Some OEM lock screens (MIUI/HyperOS, some Samsung modes) may not show third-party live wallpapers.

## Decisions

- [x] **Buttons**: keep the original 4 (Again/Hard/Good/Easy). No 5th button. *(2026-10-11)*
- [x] **Wallpaper scope**: only the lock-screen wallpaper changes. The home wallpaper stays untouched. *(2026-10-11)*
- [x] **Wallpaper mechanism**: live wallpaper, set by the user in the system wallpaper settings. Verified on the test device. *(2026-10-11)*
- [x] **Unused notification**: if no button was pressed during the lock session, the notification is removed on unlock and posted again at the next lock. *(2026-10-11)*
- [x] **Cards that can't be displayed**: cards with sound in the question are skipped. They stay due in AnkiDroid: fetching due cards is read-only, only an answer records a review. *(2026-10-11)*
- [x] **Several decks**: the user selects decks and orders them. Cards come from the first deck that has a displayable due card; a deck is passed over once all its due cards were skipped, or when nothing is due. *(2026-10-11)*
- [x] **Parent decks**: a parent deck gives cards from all its subdecks, mixed by AnkiDroid's scheduler, exactly like studying it in AnkiDroid. No per-subdeck ordering. *(2026-10-11)*
- [x] **Notification timeout** (#6): no timeout while locked; the notification disappears when the phone is unlocked (#24). *(2026-10-11)*
- [x] **One review per unlock** (#24): the notification is only visible while locked; after the answer, "Next card at your next unlock" for a few seconds; an answer while unlocked counts as the session's review; "Review several cards in a row" is an option, off by default. Without the wallpaper: permanent notification, cards follow each other. *(2026-10-11)*

## Phase 0: Spike on a real device

- [x] 0.1 Minimal `WallpaperService`, selectable for the lock screen only.
- [x] 0.2 Engine detects lock/unlock (`SCREEN_OFF` / `SCREEN_ON` / `USER_PRESENT` receiver in the engine, `KeyguardManager.isKeyguardLocked()`) and switches between the card and the background. Lock-screen-only wallpapers always draw the card.
- [ ] 0.3 Notification buttons work from the lock screen without unlocking, and the wallpaper switches to the next card. (#2)

## Phase 1: Card selection and main screen

- [x] 1.1 `CardStateController`: single "show next card" entry point, used by `MainActivity`, `NotificationReceiver` and `PeriodicWorker`. The wallpaper redraws through a SharedPreferences listener.
- [x] 1.2 `NotificationReceiver` works off the main thread (`goAsync()`).
- [x] 1.3 Honor `CardInfo.buttonCount` (some cards have fewer than 4 buttons). (#3)
- [x] 1.4 Skip cards whose question has sound. Sound and `[[type:...]]` tags are hidden from the displayed text.
- [x] 1.5 Each deck is scanned up to its due count (learning + review + new, max 500). When no selected deck has a card: "Bravo! You've finished all cards in the selected decks." (notification, lock screen, app).
- [x] 1.6 Several decks in priority order.
- [x] 1.7 Modern main screen in Jetpack Compose (Material 3, dynamic colors): current card, lock screen setup, review order, foldable deck tree with due counts.

## Phase 2: Lock-screen notification

- [x] 2.1 Notification fully visible on the lock screen (`VISIBILITY_PUBLIC`, dedicated channel). (#4)
- [x] 2.2 / 2.3 Remove an unused notification on unlock, post it again at the next lock; receivers in the wallpaper engine, current behavior without the wallpaper. (#5)
- [x] 2.4 Request `POST_NOTIFICATIONS` at runtime on API 33+.
- [x] 2.5 The expanded notification shows the question and a **Show answer** button first; the answer and the buttons are revealed in place. Pill buttons in soft per-ease colors, Show answer in the dynamic accent color. (#23)
- [x] 2.6 One review per lock session, notification only while locked, option to review several in a row. Screen turned on again before the keyguard locked counts as an unlock. (#24)

## Phase 3: Live wallpaper

- [x] 3.1 `CardWallpaperService` + `Engine` declared in the manifest (`BIND_WALLPAPER`, `res/xml/card_wallpaper.xml`).
- [x] 3.2 Better rendering: extract a `CardRenderer`, tune the safe area, typography. (#7)
- [x] 3.3 States: card / "Bravo" / not started are done; drawing the user's background on the home screen (Android 8–13) needs the settings below. (#8)
- [x] 3.4 Redraw when the card changes and on `onSurfaceChanged`.
- [x] 3.5 `onComputeColors()` so the system theme colors stay consistent. (#9)
- [x] Bug: two engine instances received each screen event. The screen events are now received once by the service, which drives `LockSession` once and lets the non-preview engines redraw. (#10)

## Phase 4: Settings and onboarding

- [x] 4.1 Wallpaper settings: background image or color, text style, preview. (#8)
- [x] 4.5 Picture filter: darken, blur, gradient, text panel or none, with an intensity (0–80%, default darken 35%). Applied once and cached; wallpaper colors computed from the filtered picture. (#19)
- [x] 4.2 "Set lock screen wallpaper" shows instructions and opens the system wallpaper settings (`ACTION_CHANGE_LIVE_WALLPAPER` applied the wallpaper to both screens).
- [x] 4.3 Onboarding hints: notifications, sensitive content on the lock screen, battery optimization, wallpaper set. (#11)
- [x] 4.4 User-facing strings in `strings.xml`.

## Phase 5: Testing and release

- [x] 5.1 Unit tests: card text cleaning, deck order logic, deck tree, renderer. (#12)
- [x] 5.4 Device tests with a fake AnkiDroid (`AnkiDataSource` seam): `scripts/device-tests.ps1` on an emulator, CI on each PR and before each release, `scripts/release.ps1`. (#18)
- [ ] 5.2 Manual test matrix: API 26 / 29 / 33 / 34+, Pixel / Samsung / Xiaomi, reboot, Doze, decks finished, AnkiDroid missing, wallpaper deselected. (#13)
- [ ] 5.3 README and version 0.1 done; tag a release on the fork, optional PR to upstream. (#14)
- [x] CI workflows: `actions/upload-artifact@v3` is retired. (#15)

## How to test on a device

1. Build and install the debug APK (in VS Code: Ctrl+Shift+B, which runs `scripts/install.ps1`).
2. In the app, tick decks in **All decks**, order them under **Review order**, and tap **Start reviewing**.
3. Tap **Set lock screen wallpaper** and follow the instructions to choose the lock screen only.
4. Lock the phone: the question should be on the lock screen. Answer from the notification: the wallpaper should switch to the next card.
5. Watch the logs: `adb logcat -s CardWallpaper Notifications` (or the VS Code task "Android: install on device + logs").

## Notes and findings

<!-- Record spike results, device quirks, and the decisions made (with dates). -->

- 2026-10-11: The main test device runs stock Android 15+. The Google wallpaper picker supports separate live wallpapers on the lock screen, which is the best case for this approach. Older Android versions and other OEMs remain to be tested in 5.2 (the emulator is fine for API 26–33).
- 2026-10-11: `WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER` opens the legacy live-wallpaper preview, which applies the wallpaper to **home and lock** (`which = 3`), replacing the home wallpaper. Apps can't set a lock-only live wallpaper themselves (`setWallpaperComponentWithFlags` is a system API). The button now shows instructions and opens `android.settings.WALLPAPER_SETTINGS` ("Wallpaper & style" on Pixel), where the user selects the lock screen.
- 2026-10-11: Verified on the test device (Android 17): set through "Wallpaper & style" > Lock screen, the card wallpaper is bound to the lock screen only and the home wallpaper is unchanged. The engine logs each screen event twice, so two engine instances are probably alive (#10).
- 2026-10-11: On a fresh install, no notification was shown because `POST_NOTIFICATIONS` was never requested (fixed in 2.4). A card stored by an older version (sound-only question) made the wallpaper blank: it now shows a hint to start reviewing.
- 2026-10-11 (#21, `scripts/install.ps1 -Probe`, test device, 48 decks, 4 selected): a query when AnkiDroid was closed takes **330–390 ms** (AnkiDroid is started in the background); with AnkiDroid in memory, deck counts take **~10 ms**. Querying due cards costs about **25–45 ms for 1 card, 85 ms for 10, 150–200 ms for 40**: the cost grows with the number of cards scanned, so a full `queryNextCard` takes 200–400 ms. Querying a deck's due cards does **not** change the deck selected in AnkiDroid. Conclusion: scan progressively (1 card, then 10, then the rest) so the usual case stays around 30–50 ms; a check at lock is then cheap, except right after Android closed AnkiDroid (~0.4 s once).
- 2026-10-11 (#21): with the progressive scan (1 card, then 10, then the rest), "next card" takes **45–78 ms** with AnkiDroid in memory, instead of 207–426 ms. The sync runs when the app opens (seen on the device: the missing notification was posted again), at lock (max every 10 minutes), after a reboot, when the deck order changes, and hourly (WorkManager job confirmed at ~59 min).
- 2026-10-11 (#10): with the receiver in the service, each `SCREEN_OFF` / `SCREEN_ON` is logged once ("1 active engine(s)"). `dumpsys wallpaper` shows a single connection to `CardWallpaperService`. Right after an `adb install -r`, the first event can be missed while the system binds the wallpaper again.
- 2026-10-11 (#19): rendered on the test device at 540×1200: each filter takes 5–70 ms to compute (blur is the slowest), then is reused from the cache on each redraw.
- 2026-10-11: running an instrumented test (`am instrument`) force-stops the app, and Android then **resets the live wallpaper** (the lock screen went back to the system image wallpaper). The card wallpaper has to be selected again after device tests (#18).
- 2026-10-11 (#23): a review screen opened by tapping the notification (`setShowWhenLocked`) was tried and dropped: with a tap action, the lock screen asks to unlock before opening anything, while without one, tapping expands the notification in place. The notification stays the way to review from the lock screen.
