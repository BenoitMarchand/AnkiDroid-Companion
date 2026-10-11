# CLAUDE.md

Guidance for Claude Code when working in this repository.

## What this app is

AnkiDroid Companion is a small Android app that lets the user review AnkiDroid flashcards from a persistent notification, without opening AnkiDroid. It stores **no** deck or card data of its own: everything is read from and written to AnkiDroid through its public ContentProvider API (`com.github.ankidroid:Anki-Android` → `FlashCardsContract` / `AddContentApi`). AnkiDroid (`com.ichi2.anki`) must be installed, with its API enabled and the `READ_WRITE_DATABASE` permission granted.

## Repository & current work

- `origin` = the developer's personal fork (push here). `upstream` = `unalkalkan/AnkiDroid-Companion`, the original repo, which we don't own: never push there or open issues there for internal tasks.
- Don't put personal information in committed files (names, accounts, emails, device models, fork URLs).
- Feature in progress: **lock screen review**. The plan, task checklist, open decisions and findings are in `docs/lockscreen-review/PLAN.md`. Read it before working on the feature, and tick tasks / add notes there as work progresses.

## Build & run

- Toolchain: Gradle 9.3, AGP 9.0.1 (built-in Kotlin, so no `kotlin-android` plugin in `app`), Kotlin 2.3.20 with the Compose compiler Gradle plugin, Java/Kotlin bytecode target 17. Runs on JDK 17+ (CI uses 17; JDK 25 bundled with recent Android Studio works). Upgrade versions as a matched set.
- Windows: `.\gradlew.bat assembleDebug` · Unix: `./gradlew assembleDebug`
- Full build + lint + unit tests: `./gradlew build`
- Unit tests: `./gradlew test` (plain JVM, in `app/src/test`: keep testable logic free of Android classes) · Instrumented tests (device needed): `./gradlew connectedAndroidTest`
- Install on a connected device: `./gradlew installDebug`, or on Windows `scripts/install.ps1` (finds the SDK and a JDK 17+, detects the device, installs and launches; `-Wait`, `-Serial`, `-Logcat`, `-NoLaunch`). Also available in VS Code as the default build task (Ctrl+Shift+B).
- APK output: `app/build/outputs/apk/debug/app-debug.apk`
- `minSdk 26`, `targetSdk 34`, `compileSdk 34`. Anything newer than API 26 must be guarded with `Build.VERSION.SDK_INT` checks.
- Manual testing needs a real device or emulator with AnkiDroid installed and at least one deck with due cards.

## Branches, versions and releases

- `develop` is the integration branch: feature branches are merged into it through pull requests (open PRs against `develop`, not `main`). `main` only receives releases.
- Version: `versionName` is `MAJOR.MINOR.PATCH` in `app/build.gradle.kts`, managed by `scripts/version.py` (`show`, `patch`, `minor`, `major`; each bump also increases `versionCode`). Don't edit the version by hand.
- `.github/workflows/integration.yml`: each PR merged into `develop` increases the **patch** digit (bot commit on `develop`).
- `.github/workflows/release.yml`: started **on request only** (`gh workflow run release.yml`, or Actions › Release): increases the **minor** digit, builds, merges `develop` into `main`, tags `vX.Y.0` and publishes the **debug** APK as a GitHub release. Issues closed by "Closes #N" in commits are only closed when they reach `main`, i.e. at release.
- `.github/workflows/develop.yml`: builds (lint + unit tests) and uploads a debug APK on pushes to `develop` and on pull requests to `develop` or `main`.
- After a merge or a release, pull `develop` before branching: the bot commits the version bump there.
- The release keystore path is git-ignored; never commit keystores or signing credentials.

## Architecture

Single module `:app`, package `com.ankidroid.companion`, mixed Java + Kotlin. The main screen is Jetpack Compose (Material 3, dynamic colors); the notification uses XML `RemoteViews` layouts.

| File | Role |
|---|---|
| `MainActivity.kt` | Creates the notification channel, requests the AnkiDroid and notification permissions, loads decks and due counts off the main thread, and holds the screen state. "Start reviewing" shows the next card and schedules the worker. |
| `ui/DeckTree.kt` | Builds the deck tree from `Parent::Child` names and the list of visible (unfolded) rows. |
| `ui/MainScreen.kt` | Compose UI: setup checklist (notifications, wallpaper, battery), current card, lock screen wallpaper setup, the "Review order" list (selected decks, reordered with up/down arrows) and the "All decks" tree built from `Parent::Child` names (fold/unfold, checkbox to select). Stateless; actions go through `MainScreenActions`. |
| `DeckSelection.kt` | The selected decks in priority order (SharedPreferences `com.ankidroid.companion.deck_selection`). |
| `CardStateController.kt` | Single entry point for the displayed card. `showNextCard()` finds the next card in the selected decks, stores it and updates the notification. `syncWithAnkiDroid()` compares the displayed card with AnkiDroid's actual next card and only acts when needed (`CardSync`); `syncOnLock()` runs it in the background, at most every 10 minutes. Both do ContentProvider I/O (off the main thread) and are serialized. |
| `CardSync.kt` | Pure decision of the sync: nothing, show the next card, show "Bravo", or post the notification again (reboot, swiped away), plus the lock check throttle. |
| `BootReceiver.kt` | After a reboot, runs the sync so the card notification comes back. |
| `AnkiDroidHelper.java` | The only gateway to AnkiDroid: deck lookup and due counts, `queryNextCard(deckIds)` (first displayable due card of the first deck that has one), `queryCurrentScheduledCard(deckId, scanLimit)` (via `FlashCardsContract.ReviewInfo`; `queryNextCard` scans each deck progressively up to its due count, max 500), `isCardDue()` (checked before answering), `reviewCard(noteId, ord, startTime, ease)`, and persistence of the current state in SharedPreferences (`com.ichi2.anki.api.state`, JSON under `CURRENT_STATE`, including the question text). |
| `DeckScanner.kt` | Pure logic of the deck order: first deck with a displayable card, each deck scanned progressively (1 card, 10, then up to its due count, max 500: the query cost grows with the scan size), decks with nothing due skipped. Also `isDue()`. |
| `AnswerButtons.kt` | Which answer buttons a card shows and the ease each one sends, from the card's button count (2, 3 or 4). |
| `CardText.kt` | Turns card HTML into displayed text (strips HTML, sound and `[[type:...]]` tags) and detects sound in a question. |
| `Notifications.kt` | Creates the `lockscreen_review` channel (public on the lock screen) and builds the single notification (ID `1`) with custom `RemoteViews` (`notification_collapsed`, `notification_expanded_full`, `notification_expanded_empty`). Buttons send `ACTION_BUTTON_1..4` broadcasts; only the buttons the card has are shown. |
| `NotificationReceiver.kt` | Handles the button broadcasts on a background thread (`goAsync()`): reviews the stored card with ease 1–4 (only if AnkiDroid still has it due, to avoid a double review), then `CardStateController.showNextCard()`. |
| `PeriodicWorker.kt` | WorkManager job (`"WORKER_ANKI"`, hourly, `PeriodicWorker.schedule()`): runs the sync, which also finds new cards after "Bravo". |
| `CardWallpaperService.kt` | Live wallpaper for the lock screen: draws the current card with `CardRenderer`, redraws when `CURRENT_STATE` or the style changes, and drives `LockSession` from screen events (the only component that receives them). |
| `CardRenderer.kt` / `CardLayout.kt` | Draws the card (background color or image, question, deck name). `CardLayout` is the pure geometry: text area, text size fitting. |
| `WallpaperStyle.kt` / `WallpaperSettingsActivity.kt` | The lock screen style (background color or image in app storage, text color and size) and its settings screen with a live preview. Also opened from the system wallpaper picker. |
| `LockSession.kt` | Removes the notification on unlock when the card wasn't answered while locked, and posts it again at the next lock. |
| `CardInfo.java` / `StoredState.java` | Plain data holders. An "empty" state is represented by `noteID == -1 && cardOrd == -1`. |

### Core flow

```
MainActivity "Start reviewing" ──► CardStateController.showNextCard ──► AnkiDroidHelper.queryNextCard(selected decks)
        ▲                                   │                             └─ per deck, in order: first due card without sound in the question
NotificationReceiver: reviewCard(ease) ─────┘──► storeState ──► Notifications.showNotification + lock screen wallpaper redraw

CardStateController.syncWithAnkiDroid: app opened, deck order changed, phone locked (max every 10 min), reboot, hourly worker
        └─► queryNextCard ─► CardSync.decide ─► nothing / show next card / "Bravo" / post the notification again
```

AnkiDroid doesn't tell other apps when a card is reviewed, edited or removed in it: that's why the displayed card is re-checked at these moments, and before each answer.

Cards are never skipped in AnkiDroid's scheduler: fetching due cards is read-only, and only `reviewCard()` records a review. Cards that can't be displayed simply stay due in AnkiDroid.

## Conventions & gotchas

- Card content: only `QUESTION_SIMPLE` / `ANSWER_PURE` are used, cleaned by `CardText`. Media is not rendered; cards with sound in the question are skipped (they stay due in AnkiDroid).
- Ease values depend on the card's button count: with 4 buttons 1 = Again, 2 = Hard, 3 = Good, 4 = Easy; with 3 buttons there is no Hard (Good = 2, Easy = 3). Always go through `AnswerButtons`. AnkiDroid's API has **no 5th ease**.
- AnkiDroid queries are ContentProvider I/O: keep them off the main thread (coroutines on `Dispatchers.IO` in the activity, `goAsync()` in receivers).
- PendingIntents must be `FLAG_IMMUTABLE` (targetSdk 31+). Distinct actions currently disambiguate the button intents; keep actions unique or use distinct request codes.
- Implicit broadcasts (`SCREEN_OFF`, `SCREEN_ON`, `USER_PRESENT`) **cannot** be registered in the manifest on API 26+. They need a runtime-registered receiver inside a long-lived component (for example a foreground service).
- Put user-facing strings in `res/values/strings.xml` (some older ones are still hard-coded in `Notifications.kt`).
- Logging uses `Log.i` with the tags `"Notifications"` and `"BackgroundService"`.
- Match the existing style: Kotlin for new Android components. The Java classes may stay Java unless they're being refactored anyway.
