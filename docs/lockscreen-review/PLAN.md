# Lock screen review: plan

Feature: while the phone is locked, the lock-screen wallpaper shows the current due card, and a lock-screen notification shows the answer with review buttons. When the phone is unlocked, the original wallpaper is restored automatically.

Status: **not started** · Branch: `feature/lockscreen-review` · Fork: https://github.com/BenoitMarchand/AnkiDroid-Companion

## Open decisions

- [ ] **5th button**: AnkiDroid's API only supports ease 1–4 (Again/Hard/Good/Easy). What should the 5th button do? Candidates: Show answer / Skip / Bury.
- [ ] **On unlock**: restore only the wallpaper, keeping the notification in the shade as today? Or hide the notification too until the next lock?
- [ ] **Restore strategy**: `WallpaperManager.clear(FLAG_LOCK)`, which falls back to the home wallpaper, versus a user-picked lock image (or both, as a setting).

## Phase 0: Spike to reduce risk (~1 day)

- [ ] 0.1 Prototype: set a generated bitmap as the lock wallpaper (`FLAG_LOCK`), restore it with `clear(FLAG_LOCK)`, and measure how long a swap takes.
- [ ] 0.2 Check that the notification buttons work from the lock screen without unlocking.
- [ ] 0.3 Settle the open decisions above and record the results in the Notes section.

## Phase 1: Refactor the shared logic (~1 day)

- [ ] 1.1 Add a `CardStateController` as the single "current card changed" entry point, used by `MainActivity`, `NotificationReceiver` and `PeriodicWorker`.
- [ ] 1.2 Move the `NotificationReceiver` work off the main thread (`goAsync()`).
- [ ] 1.3 Honor `CardInfo.buttonCount`.

## Phase 2: Lock-screen notification (~0.5–1 day)

- [ ] 2.1 Add a `lockscreen_review` channel with `lockscreenVisibility = PUBLIC`, and call `setVisibility(VISIBILITY_PUBLIC)` on the notification.
- [ ] 2.2 Add the 5th button to `notification_expanded_full.xml` and handle `ACTION_BUTTON_5`.
- [ ] 2.3 Request `POST_NOTIFICATIONS` at runtime on API 33+.

## Phase 3: Generating the wallpaper (~1–1.5 days)

- [ ] 3.1 `LockWallpaperRenderer`: turn `CardInfo` into a `Bitmap` at screen size (`StaticLayout`, font size adapted to the text).
- [ ] 3.2 Style: background color or user image, with the text kept in a safe area that avoids the clock and notifications.
- [ ] 3.3 Strip basic HTML from the question (`Html.fromHtml`).
- [ ] 3.4 `LockWallpaperManager`: `apply(bitmap)` / `restore()` (`SET_WALLPAPER`, `FLAG_LOCK`, restore mode).

## Phase 4: Lock/unlock lifecycle (~2 days)

- [ ] 4.1 `LockScreenService`: a foreground service of type `specialUse` that reuses the review notification.
- [ ] 4.2 Receiver registered at runtime: `SCREEN_OFF` → apply the card wallpaper, `USER_PRESENT` → restore. Debounce it and skip redundant writes.
- [ ] 4.3 After answering while locked, regenerate the wallpaper. When the deck is finished, restore the original and stop the service.
- [ ] 4.4 `BOOT_COMPLETED` receiver, plus a hook in `PeriodicWorker` to restart the service when new cards are due.
- [ ] 4.5 Safety nets: restore the wallpaper when the feature is disabled or the service crashes.

## Phase 5: Settings UI (~1 day)

- [ ] 5.1 On/off switch, restore-mode choice, image picker (Photo Picker), appearance preview.
- [ ] 5.2 Onboarding: notification permission, battery optimization exemption, "show sensitive content on lock screen".
- [ ] 5.3 Move the new strings into `strings.xml`.

## Phase 6: Testing and release (~1–2 days)

- [ ] 6.1 Unit tests: the renderer's text layout and the state controller.
- [ ] 6.2 Manual test matrix: API 26 / 29 / 33 / 34+, Pixel / Samsung / Xiaomi, reboot, Doze, deck finished, AnkiDroid missing.
- [ ] 6.3 Update the README and version, tag a release on the fork, and optionally open a PR to upstream.

## Notes and findings

<!-- Record spike results, device quirks, and the decisions made (with dates). -->
