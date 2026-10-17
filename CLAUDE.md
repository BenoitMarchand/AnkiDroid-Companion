# CLAUDE.md

Guidance for Claude Code when working in this repository.

## What this app is

Micro Review for AnkiDroid is a small Android app that lets the user review AnkiDroid flashcards in short bursts from a persistent notification, the lock screen wallpaper or a home screen widget, without opening AnkiDroid. It stores **no** deck or card data of its own: everything is read from and written to AnkiDroid through its public ContentProvider API (`com.github.ankidroid:Anki-Android` → `FlashCardsContract` / `AddContentApi`). AnkiDroid (`com.ichi2.anki`) must be installed, with its API enabled and the `READ_WRITE_DATABASE` permission granted.

## Repository & current work

- `origin` = the developer's personal fork (push here). `upstream` = `unalkalkan/AnkiDroid-Companion`, the original repo, which we don't own: never push there or open issues there for internal tasks.
- Don't put personal information in committed files (names, accounts, emails, device models, fork URLs). Exception: the copyright lines in `LICENSE`, which name the authors.
- Feature in progress: **lock screen review**. The plan, task checklist, open decisions and findings are in `docs/lockscreen-review/PLAN.md`. Read it before working on the feature, and tick tasks / add notes there as work progresses.

## Build & run

- Toolchain: Gradle 9.3, AGP 9.0.1 (built-in Kotlin, so no `kotlin-android` plugin in `app`), Kotlin 2.3.20 with the Compose compiler Gradle plugin, Java/Kotlin bytecode target 17. Runs on JDK 17+ (CI uses 17; JDK 25 bundled with recent Android Studio works). Upgrade versions as a matched set.
- Windows: `.\gradlew.bat assembleDebug` · Unix: `./gradlew assembleDebug`
- Full build + lint + unit tests: `./gradlew build`
- Unit tests: `./gradlew test` (plain JVM, in `app/src/test`: keep testable logic free of Android classes) · Device tests: `scripts/device-tests.ps1` (VS Code task "Android: run device tests"): end-to-end scenarios in `app/src/androidTest` with `FakeAnkiDataSource` (installed through `AnkiData.override`), each saving and restoring the app's data (`AppDataSnapshot`). Uses a running emulator or starts one (`-Avd`); a phone only with `-Serial`, because `am instrument` force-stops the app and Android then resets the card wallpaper. Never use `connectedAndroidTest` on a phone (it uninstalls the app); CI uses it on a fresh emulator.
- Play Store assets: `scripts/store-assets.ps1`, on demand only (VS Code task "Android: generate store assets"): on an emulator with AnkiDroid, resets the "World capitals" demo deck (`StoreAssetsSetup`, skipped unless the script passes `-e storeAssets true`), then generates the screenshots, the lock screen / notification / widget GIFs and the icon in `build/store-assets`. Listing texts, privacy policy and Play Console answers: `docs/play-store/`.
- Release check: `scripts/release.ps1` (build + unit tests + device tests, then `gh workflow run release.yml`; a release candidate by default, `-Final` to release the validated candidate). Shared script helpers: `scripts/android-env.ps1`.
- Install on a connected device: `./gradlew installDebug`, or on Windows `scripts/install.ps1` (finds the SDK and a JDK 17+, detects the device, installs and launches; `-Wait`, `-Serial`, `-Logcat`, `-NoLaunch`). Also available in VS Code as the default build task (Ctrl+Shift+B).
- APK output: `app/build/outputs/apk/debug/app-debug.apk`
- `minSdk 26`, `targetSdk 34`, `compileSdk 34`. Anything newer than API 26 must be guarded with `Build.VERSION.SDK_INT` checks.
- Manual testing needs a real device or emulator with AnkiDroid installed and at least one deck with due cards.

## Branches, versions and releases

- `develop` is the integration branch: feature branches are merged into it through pull requests (open PRs against `develop`, not `main`). `main` only receives releases.
- Version: `versionName` is `MAJOR.MINOR.PATCH`, with `-rcN` on a release candidate, in `app/build.gradle.kts`, managed by `scripts/version.py` (`show`, `patch`, `rc`, `final`, `minor`, `major`; each bump also increases `versionCode`). Don't edit the version by hand.
- `.github/workflows/integration.yml`: each PR merged into `develop` increases the **patch** digit (bot commit on `develop`); on a release candidate it adds `.N` after it instead (`0.4.0-rc1.1`).
- `.github/workflows/release.yml`: started **on request only** (`gh workflow run release.yml -f stage=rc|final`, or Actions › Release), in two stages. `rc` (default): a **release candidate** for users to validate: the next **minor** version with `-rc1` (`0.3.3` → `0.4.0-rc1`; again → `-rc2`), committed and tagged `vX.Y.0-rcN` on `develop`, the **debug** APK published as a GitHub **pre-release**; `main` is not changed. `final`: once validated, drops the suffix (`0.4.0-rc2` → `0.4.0`), merges `develop` into `main`, tags `vX.Y.0` and publishes the latest GitHub release. Issues closed by "Closes #N" in commits are only closed when they reach `main`, i.e. at the final release.
- `.github/workflows/develop.yml`: builds (lint + unit tests) and uploads a debug APK on pushes to `develop` and on pull requests to `develop` or `main`; on pull requests it also runs the device tests on an emulator.
- `release.yml` only tags when the device tests pass on an emulator.
- After a merge or a release, pull `develop` before branching: the bot commits the version bump there.
- The release keystore path is git-ignored; never commit keystores or signing credentials.

## Architecture

Single module `:app`, package `com.ankidroid.companion`, mixed Java + Kotlin. The main screen is Jetpack Compose (Material 3, dynamic colors); the notification uses XML `RemoteViews` layouts.

| File | Role |
|---|---|
| `MainActivity.kt` | Creates the notification channel, requests the AnkiDroid and notification permissions, loads decks and due counts off the main thread, and holds the screen state. Reviewing starts as soon as a deck is selected: the deck selection sync shows the first card and the worker is scheduled; unselecting every deck stops it. |
| `ui/DeckTree.kt` | Builds the deck tree from `Parent::Child` names and the list of visible (unfolded) rows. |
| `ui/MainScreen.kt` | Compose UI: setup checklist (notifications, lock screen wallpaper, background activity not restricted; checked at every start and resume, shown only when something is missing; once the card wallpaper is set, a light "Lock screen wallpaper configured" line instead), current card, a gear icon to Settings, the "Review order" list (selected decks, reordered with up/down arrows) and the "All decks" tree built from `Parent::Child` names (fold/unfold, checkbox to select). Stateless; actions go through `MainScreenActions`. |
| `ui/WallpaperSetup.kt` | Setting the card wallpaper on the lock screen, shared by the main screen and Settings: opens the system wallpaper settings and shows the steps dialog (lock screen only on Android 14+). |
| `DeckSelection.kt` | The selected decks in priority order (SharedPreferences `com.ankidroid.companion.deck_selection`). |
| `CardStateController.kt` | Single entry point for the displayed card. `showNextCard()` finds the next card in the selected decks, stores it and updates the notification. `syncWithAnkiDroid()` compares the displayed card with AnkiDroid's actual next card and only acts when needed (`CardSync`); `syncOnLock()` runs it in the background, at most every 10 minutes. Both do ContentProvider I/O (off the main thread) and are serialized. |
| `CardSync.kt` | Pure decision of the sync: nothing, show the next card (or the first one once a deck is selected), show "Bravo", post the notification again (reboot, swiped away), or stop (no deck selected), plus the lock check throttle. |
| `BootReceiver.kt` | After a reboot, runs the sync so the card notification comes back, and sets the scheduled review alarm again (also after an app update or a clock / time zone change). |
| `AnkiDataSource.kt` | The seam to AnkiDroid: `AnkiDataSource` (deck names and counts, raw due cards, due card keys, record a review, read and change the deck selected in AnkiDroid), `ContentProviderAnkiData` (the real ContentProvider calls) and `AnkiData.source()` (a fake in device tests). |
| `AnkiDroidHelper.java` | Card selection on top of `AnkiDataSource`: deck lookup and due counts, `queryNextCard(deckIds)` (first displayable due card of the first deck that has one), `queryCurrentScheduledCard(deckId, scanLimit)` (via `FlashCardsContract.ReviewInfo`; `queryNextCard` scans each deck progressively up to its due count, max 500), `isCardDue()` (checked before answering), `reviewCard(noteId, ord, ease, timeTakenMs)` (drops AnkiDroid's study queue first, see `StudyQueue`), and persistence of the current state in SharedPreferences (`com.ichi2.anki.api.state`, JSON under `CURRENT_STATE`, including the question text). |
| `StudyQueue.kt` | Why an answer can be lost: AnkiDroid keeps the study queue of its selected deck once built and then only answers its top card ("not at top of queue", swallowed by its ContentProvider). Changing the selected deck drops the queue, so `AnkiDroidHelper.reviewCard()` selects another deck (`otherDeck()`) and the selected one again right before each answer. |
| `DeckScanner.kt` | Pure logic of the deck order: first deck with a displayable card, each deck scanned progressively (1 card, 10, then up to its due count, max 500: the query cost grows with the scan size), decks with nothing due skipped. Also `isDue()`. |
| `AnswerButtons.kt` | Which answer buttons a card shows and the ease each one sends, from the card's button count (2, 3 or 4). |
| `CardText.kt` | Turns card HTML into displayed text (strips HTML, sound and `[[type:...]]` tags) and detects sound in a question. |
| `Notifications.kt` | Creates the `lockscreen_review` channel (public on the lock screen) and builds the single notification (ID `1`) with custom `RemoteViews` (`notification_collapsed`, `notification_expanded_full`, `notification_expanded_empty`). The expanded view shows the question and **Show answer**; once revealed, the answer and the answer buttons (`ACTION_BUTTON_1..4`; only the buttons the card has). After the session's answer (one review per unlock), the same notification is updated in place: the answered card with "Next card at your next unlock" instead of the buttons, removed after 5 s. Pill buttons on a color scale, red → orange → light green → green (`res/values/colors.xml`, accent from `system_accent1_*` on Android 12+). No tap action (`contentIntent`): on the lock screen, tapping expands the notification in place, while opening an activity asks to unlock first. |
| `NotificationReceiver.kt` | Handles the button broadcasts (`ACTION_SHOW_ANSWER`, `ACTION_BUTTON_1..4`) on a background thread (`goAsync()`) through `CardReview`. |
| `CardReview.kt` | Reviewing the displayed card, from the notification buttons or the review screen: `answer()` reviews it (only if AnkiDroid still has it due, to avoid a double review) then `CardStateController.showNextCard()`; `revealAnswer()` stores which card's answer is shown (prefs `com.ankidroid.companion.card_review`, keyed by card so it clears itself) and updates the notification in place. |
| `StandardCardWidget.kt` / `CardWidgetContent.kt` | Home screen widget (`home_screen` only; the lock screen version is issue #60): the displayed card behind **Start review**, then the question, **Show answer**, the answer and the answer buttons (own broadcasts `WIDGET_*`, handled in the provider on a background executor). Start restarts `ReviewTimer` (again with each new card); an answer goes through `CardReview.answer(countsForSession = false)`: it never ends the lock session, so the widget always moves on to the next card. Back to Start review after 2 min without a tap (inexact alarm) or at screen off (from `CardWallpaperService`). Redrawn by `StandardCardWidget.updateAll()`, called by `CardStateController` (card stored, "Bravo", stop) and `CardReview.revealAnswer()`. `CardWidgetContent` is the pure part: what to show, the timeout, the due total of the selected decks (a selected subdeck of a selected parent counted once). |
| `PeriodicWorker.kt` | WorkManager job (`"WORKER_ANKI"`, hourly, `PeriodicWorker.schedule()`): runs the sync, which also finds new cards after "Bravo". |
| `CardWallpaperService.kt` | Live wallpaper for the lock screen: draws the current card with `CardRenderer` (or "Next card at your next unlock"), redraws when `CURRENT_STATE`, the session or the style changes, and drives `LockSession` from screen events (the only component that receives them; one receiver per service, not per engine). `isActive()` tells whether it is set on the lock screen. With "Open after unlock", opens `ReviewActivity` at unlock. |
| `ReviewActivity.kt` | Review screen of "Open after unlock" (Compose): question, Show answer, answer buttons through `CardReview`; "Next card at your next unlock" after the session's answer, then closes. Finished when left; left without an answer, it rotates the card ("Change the card at every unlock"). Started from the wallpaper service, i.e. from the background: Android only allows it with "Display over other apps" (`SYSTEM_ALERT_WINDOW`, asked for in Settings). |
| `CardRenderer.kt` / `CardLayout.kt` | Draws the card (background color or image, question, optional answer under a divider, deck name). `CardLayout` is the pure geometry: text area, text size fitting, height shared between question and answer. |
| `WallpaperStyle.kt` / `SettingsActivity.kt` | The lock screen style (background color or image in app storage, picture filter and intensity, text color and size, question only or question and answer) and the app's Settings screen (gear icon on the main screen), one card per group: behavior ("Review several cards in a row"), reviews at set times, lock screen content (with whether the card wallpaper is set, and the steps to set it if not), the style with a live preview, and help ("Report an issue"). Also opened from the system wallpaper picker. With the answer on the wallpaper, the notification skips "Show answer" (`CardReview.isAnswerOnWallpaper`). |
| `PictureFilter.kt` | Pure logic of the background picture filters (darken, blur, gradient, text panel, none): opacity and blur radius from the intensity, box blur, gradient band. `CardRenderer` applies them and caches the filtered picture. |
| `CardRotation.kt` | Pure logic of "Change the card at every unlock" (off by default, `ReviewSettings`): the rotation set (first N displayable due cards of the selected decks, in order), the card after the displayed one, and whether to rotate at an unlock (no answer in the session, card visible ≥ 1.5 s). `CardStateController.rotateOnUnlock()` stores the next card at unlock; it is posted at the next lock. The sync keeps a displayed card that is in the rotation set. |
| `ReviewTimer.kt` | The review time sent to AnkiDroid with an answer: starts when the card is seen (screen on on the lock screen via `CardWallpaperService`, a new card posted while it runs; without the wallpaper, the notification post; "Show answer" if none runs), cleared at screen off and unlock, keyed by card (prefs `com.ankidroid.companion.review_timer`). 0 when no timer runs for the answered card. |
| `LockSession.kt` | Lock session state and decisions (pure parts unit tested). With the wallpaper set, the notification is only visible while locked (`mayPostNow()`, used by every post in `CardStateController`; a held-back post is marked hidden and posted at the next lock), and removed at every unlock. One review per session by default: after the answer the next card is stored but waits for the next lock ("Next card at your next unlock"). Also `ReviewSettings` ("Review several cards in a row", "Change the card at every unlock" and its rotation size, "How to review": notification or "Open after unlock"). With "Open after unlock" in effect (`opensAfterUnlock()`: chosen, wallpaper set, "Display over other apps" allowed), no card notification is posted. |
| `ReviewSchedule.kt` | Pure logic of "Reviews at set times": the times of day (minutes since midnight) and the next one, plus `ScheduledReviewSettings` (on/off, off by default, and the times). |
| `ScheduledReview.kt` | "Reviews at set times": one inexact `AlarmManager` alarm at a time (each sets the next; set again by `BootReceiver` at boot, app update, clock or time zone change, by `MainActivity` and the hourly worker). At each time, a notification separate from the lock screen one (ID `2`, high-importance channel `scheduled_review`): "Time for a card" + **Start** (a standard action, shown in the pop-up), then the question and "Show answer" (Start restarts `ReviewTimer`), then the answer buttons (`ACTION_SCHEDULED_*`, handled by `NotificationReceiver`). Same card as the lock screen notification: `CardStateController` removes a started one when the displayed card changes. |
| `IssueReport.kt` / `ReportIssue.kt` | "Report an issue" (Settings › Help): opens the GitHub issue form `.github/ISSUE_TEMPLATE/bug_report.yml` prefilled through query parameters named after its field ids (phone, Android version, app and AnkiDroid versions). `IssueReport` is the pure URL building. The repository is not in the sources: Gradle sets the `issues_repository` string from `GITHUB_REPOSITORY` in CI or the `origin` remote locally, and the entry is hidden when it's empty. |
| `CardInfo.java` / `StoredState.java` | Plain data holders. An "empty" state is represented by `noteID == -1 && cardOrd == -1`. |

### Core flow

```
NotificationReceiver: reviewCard(ease) ──► CardStateController.showNextCard ──► AnkiDroidHelper.queryNextCard(selected decks)
                                                     │                             └─ per deck, in order: first due card without sound in the question
                                                     └──► storeState ──► Notifications.showNotification + lock screen wallpaper redraw

CardStateController.syncWithAnkiDroid: app opened, deck selection/order changed, phone locked (max every 10 min), reboot, hourly worker
        └─► queryNextCard ─► CardSync.decide ─► nothing / show next (or first) card / "Bravo" / post the notification again / stop
```

AnkiDroid doesn't tell other apps when a card is reviewed, edited or removed in it: that's why the displayed card is re-checked at these moments, and before each answer.

Cards are never skipped in AnkiDroid's scheduler: fetching due cards is read-only, and only `reviewCard()` records a review. Cards that can't be displayed simply stay due in AnkiDroid.

## Conventions & gotchas

- Card content: only `QUESTION_SIMPLE` / `ANSWER_PURE` are used, cleaned by `CardText`. Media is not rendered; cards with sound in the question are skipped (they stay due in AnkiDroid).
- Ease values depend on the card's button count: with 4 buttons 1 = Again, 2 = Hard, 3 = Good, 4 = Easy; with 3 buttons there is no Hard (Good = 2, Easy = 3). Always go through `AnswerButtons`. AnkiDroid's API has **no 5th ease**.
- AnkiDroid silently refuses to answer a card that isn't at the top of its kept study queue (#62): always answer through `AnkiDroidHelper.reviewCard()`, which drops that queue first, and don't query due cards between that and the answer. `FakeAnkiDataSource` refuses such answers too.
- AnkiDroid queries are ContentProvider I/O: keep them off the main thread (coroutines on `Dispatchers.IO` in the activity, `goAsync()` in receivers).
- PendingIntents must be `FLAG_IMMUTABLE` (targetSdk 31+). Distinct actions currently disambiguate the button intents; keep actions unique or use distinct request codes.
- Implicit broadcasts (`SCREEN_OFF`, `SCREEN_ON`, `USER_PRESENT`) **cannot** be registered in the manifest on API 26+. They need a runtime-registered receiver inside a long-lived component (for example a foreground service).
- Put user-facing strings in `res/values/strings.xml`.
- RemoteViews layouts only accept some views (no plain `View`: use `FrameLayout` for a divider); lint checks it.
- `am instrument` force-stops the app, and Android then resets a live wallpaper of a force-stopped app: after running instrumented tests, the card wallpaper must be selected again.
- Logging uses `Log.i` with the tags `"Notifications"` and `"BackgroundService"`.
- Match the existing style: Kotlin for new Android components. The Java classes may stay Java unless they're being refactored anyway.
