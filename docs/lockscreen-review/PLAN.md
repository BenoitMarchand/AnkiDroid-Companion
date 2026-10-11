# Lock screen review: plan

Feature: while the phone is locked, the lock screen shows the current due card, and a lock-screen notification shows the answer with the 4 review buttons. Once unlocked, the lock-screen card disappears, and an unused notification is removed.

Status: **not started** · Branch: `feature/lockscreen-review`

## Approach: live wallpaper

The app provides a **live wallpaper** ("AnkiDroid Companion") that the user selects in Android's own wallpaper picker, ideally for the **lock screen only**. The wallpaper draws the current card while the device is locked. When unlocked, it draws the user's own background image.

Why not swap static wallpapers (`WallpaperManager.setBitmap`)?
- Nothing has to be saved and restored, so the original wallpaper is never lost.
- No disk write on every lock/unlock, so no flicker or delay and no theme recolor.
- It doesn't need the `SET_WALLPAPER` permission or a foreground service just to detect unlocking. The wallpaper engine is kept alive by the system while it's selected.

Limits:
- A separate live wallpaper for the lock screen only is supported from **Android 14**, in the system picker and on most OEM launchers.
- On Android 8–13, a live wallpaper applies to both the home screen and the lock screen. The engine then draws the card only when the keyguard is locked, and draws a user-picked background image otherwise, so the home screen looks normal.
- Some OEM lock screens (MIUI/HyperOS, some Samsung modes) may not show third-party live wallpapers. Check this in Phase 0.

## Decisions

- [x] **Buttons**: keep the original 4 (Again/Hard/Good/Easy). No 5th button. *(2026-10-11)*
- [x] **Wallpaper scope**: only the lock-screen wallpaper changes. The home wallpaper stays untouched. *(2026-10-11)*
- [x] **Wallpaper mechanism**: live wallpaper selectable in Android's wallpaper picker. *(2026-10-11, to be confirmed by Phase 0)*
- [x] **Unused notification**: if no button was pressed during the lock session, the notification is removed on unlock and posted again at the next lock. *(2026-10-11)*
- [ ] Should the notification also time out after N minutes on the lock screen (`setTimeoutAfter`)? Optional.

## Phase 0: Spike on a real device (~1 day)

- [x] 0.1 Minimal `WallpaperService` that draws fixed text, selectable in the system picker. Check: can it be set for the lock screen only on the target phone?
- [ ] 0.2 Engine: detect lock/unlock (`KeyguardManager.isKeyguardLocked()` in `onVisibilityChanged`, plus a `USER_PRESENT` receiver registered inside the engine) and switch between drawing the card and the background.
- [ ] 0.3 Check that the notification buttons work from the lock screen without unlocking.
- [ ] 0.4 Record the results

How to test the spike (`CardWallpaperService.kt`, written but **not yet built or run**):
1. Build and install the debug APK (in VS Code: Ctrl+Shift+B, which runs `scripts/install.ps1`).
2. In the app, pick a deck and tap **Refresh**, then tap **Set lock screen wallpaper** and choose "Lock screen" in the system picker.
3. Lock the phone: the question should be on the lock screen. Answer from the notification: the wallpaper should switch to the next card.
4. Watch the logs: `adb logcat -s CardWallpaper Notifications`.
 (Android version, OEM, what works) in the Notes section.

## Phase 1: Refactor the shared logic (~1 day)

- [ ] 1.1 Add a `CardStateController` as the single "current card changed" entry point, used by `MainActivity`, `NotificationReceiver` and `PeriodicWorker`. It notifies listeners (notification and wallpaper engine), e.g. through a SharedPreferences change listener or an in-app broadcast.
- [ ] 1.2 Move the `NotificationReceiver` work off the main thread (`goAsync()`).
- [ ] 1.3 Honor `CardInfo.buttonCount` (some cards have fewer than 4 buttons).

## Phase 2: Lock-screen notification (~1 day)

- [ ] 2.1 Add a `lockscreen_review` channel with `lockscreenVisibility = PUBLIC`, and call `setVisibility(VISIBILITY_PUBLIC)` on the notification.
- [ ] 2.2 Track "answered during this lock session". On unlock, if nothing was answered, cancel the notification. On the next lock (`SCREEN_OFF`), post it again.
- [ ] 2.3 The lock/unlock receivers live in the wallpaper engine when it's active. Otherwise, fall back to the current behavior (a permanent notification, no auto-remove).
- [ ] 2.4 Request `POST_NOTIFICATIONS` at runtime on API 33+.

## Phase 3: Live wallpaper (~2 days)

- [ ] 3.1 `CardWallpaperService` + `Engine`, declared in the manifest (`BIND_WALLPAPER` permission, `res/xml/card_wallpaper.xml` with thumbnail, description and settings activity).
- [ ] 3.2 `CardRenderer`: draws the question on the `SurfaceHolder` canvas (`StaticLayout`, font size adapted to the text, a safe area that avoids the clock and notifications, basic HTML stripped with `Html.fromHtml`).
- [ ] 3.3 States: locked + card → the card; locked + deck finished → "Deck done" or background only; unlocked/home → the user's background image.
- [ ] 3.4 Redraw when the card changes (listener from 1.1) and on `onSurfaceChanged` (rotation, resolution).
- [ ] 3.5 `onComputeColors()` so the system theme colors stay consistent.

## Phase 4: Settings (~1 day)

- [ ] 4.1 Wallpaper settings activity: pick a background image (Photo Picker, copied to internal storage) or a color, text color/size, and a preview.
- [ ] 4.2 In `MainActivity`: a "Set as lock screen wallpaper" button that opens the system picker preselected on our wallpaper (`ACTION_CHANGE_LIVE_WALLPAPER`).
- [ ] 4.3 Onboarding hints: notification permission, "show sensitive content on lock screen", battery optimization.
- [ ] 4.4 Move the new strings into `strings.xml`.

## Phase 5: Testing and release (~1–2 days)

- [ ] 5.1 Unit tests: the renderer's text layout and the state controller.
- [ ] 5.2 Manual test matrix: API 26 / 29 / 33 / 34+, Pixel / Samsung / Xiaomi, reboot, Doze, deck finished, AnkiDroid missing, wallpaper deselected.
- [ ] 5.3 Update the README and version, tag a release on the fork, and optionally open a PR to upstream.

## Notes and findings

<!-- Record spike results, device quirks, and the decisions made (with dates). -->

- 2026-10-11: The main test device runs stock Android 15+. The Google wallpaper picker supports separate live wallpapers on the lock screen, which is the best case for this approach. Older Android versions and other OEMs remain to be tested in 5.2 (the emulator is fine for API 26–33).
- 2026-10-11: `WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER` opens the legacy live-wallpaper preview, which applies the wallpaper to **home and lock** (`which = 3`), replacing the home wallpaper. Apps can't set a lock-only live wallpaper themselves (`setWallpaperComponentWithFlags` is a system API). The button now shows instructions and opens `android.settings.WALLPAPER_SETTINGS` ("Wallpaper & style" on Pixel), where the user selects the lock screen.
- 2026-10-11: Verified on the test device (Android 17): set through "Wallpaper & style" > Lock screen, the card wallpaper is bound to the lock screen only and the home wallpaper is unchanged. The engine logs each screen event twice, so two engine instances are probably alive (e.g. a leftover preview engine): check that previews are destroyed and that there are no duplicate redraws.
