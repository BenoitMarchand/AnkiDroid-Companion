# CLAUDE.md

Guidance for Claude Code when working in this repository.

## What this app is

AnkiDroid Companion is a small Android app that lets the user review AnkiDroid flashcards from a persistent notification, without opening AnkiDroid. It stores **no** deck or card data of its own: everything is read from and written to AnkiDroid through its public ContentProvider API (`com.github.ankidroid:Anki-Android` → `FlashCardsContract` / `AddContentApi`). AnkiDroid (`com.ichi2.anki`) must be installed, with its API enabled and the `READ_WRITE_DATABASE` permission granted.

## Repository & current work

- `origin` = fork `BenoitMarchand/AnkiDroid-Companion` (push here). `upstream` = `unalkalkan/AnkiDroid-Companion`, the original repo, which we don't own: never push there or open issues there for internal tasks.
- Feature in progress: **lock screen review**. The plan, task checklist, open decisions and findings are in `docs/lockscreen-review/PLAN.md`. Read it before working on the feature, and tick tasks / add notes there as work progresses.

## Build & run

- JDK 17 is required (same as CI). AGP 8.2.2, Kotlin 1.9.0, Gradle 8.2.
- Windows: `.\gradlew.bat assembleDebug` · Unix: `./gradlew assembleDebug`
- Full build + lint + unit tests: `./gradlew build`
- Unit tests: `./gradlew test` · Instrumented tests (device needed): `./gradlew connectedAndroidTest`
- Install on a connected device: `./gradlew installDebug`
- APK output: `app/build/outputs/apk/debug/app-debug.apk`
- `minSdk 26`, `targetSdk 34`, `compileSdk 34`. Anything newer than API 26 must be guarded with `Build.VERSION.SDK_INT` checks.
- Manual testing needs a real device or emulator with AnkiDroid installed and at least one deck with due cards. There are no meaningful automated tests yet (only the template `Example*Test` files).

## CI / release

- `.github/workflows/develop.yml`: builds and uploads a debug APK on pushes to `develop`.
- `.github/workflows/release.yml`: on any pushed tag, creates a GitHub release and attaches the **debug** APK.
- The release keystore path is git-ignored; never commit keystores or signing credentials.

## Architecture

Single module `:app`, package `com.ankidroid.companion`, mixed Java + Kotlin. The UI uses classic XML layouts (`res/layout/main_layout.xml`). Compose is configured, but only the unused `Spinner.kt` / `ui/theme` use it.

| File | Role |
|---|---|
| `MainActivity.kt` | Creates the notification channel (`"channel_id"`), checks API availability and permission, shows the deck picker, and on "Refresh" stores the deck, fetches the first due card, shows the notification and schedules the worker. |
| `AnkiDroidHelper.java` | The only gateway to AnkiDroid: deck lookup, `queryCurrentScheduledCard(deckId)` (via `FlashCardsContract.ReviewInfo`), `reviewCard(noteId, ord, startTime, ease)`, and persistence of the current state in SharedPreferences (`com.ichi2.anki.api.state`, JSON under `CURRENT_STATE`). |
| `Notifications.kt` | Builds the single notification (ID `1`) with custom `RemoteViews` (`notification_collapsed`, `notification_expanded_full`, `notification_expanded_empty`). Buttons send `ACTION_BUTTON_1..4` broadcasts. |
| `NotificationReceiver.kt` | Handles the button broadcasts: reviews the stored card with ease 1–4, fetches the next card, and re-posts the notification (or the "deck finished" one). |
| `PeriodicWorker.kt` | WorkManager job (`"WORKER_ANKI"`, every 8 h). When the stored state is the empty card, it checks for new due cards and re-posts the notification. |
| `CardInfo.java` / `StoredState.java` | Plain data holders. An "empty" state is represented by `noteID == -1 && cardOrd == -1`. |

### Core flow

```
MainActivity.onClickRefresh ──► AnkiDroidHelper.queryCurrentScheduledCard ──► storeState ──► Notifications.showNotification
        ▲                                                                                             │ button tap
PeriodicWorker (8h, only when state is empty)                       NotificationReceiver ◄────────────┘
                                                                     reviewCard(ease) → next card → showNotification
```

Any new surface that shows the "current card" (widget, lock screen, etc.) should hook into the same points: after `storeState(...)` and wherever `showNotification(...)` is called. Ideally, refactor these into one "current card changed" entry point instead of duplicating calls.

## Conventions & gotchas

- Card content: only `QUESTION_SIMPLE` / `ANSWER_PURE` are used. HTML and media are not rendered, so long or HTML cards display poorly (a known limitation).
- Ease values follow the Anki scheduler: 1 = Again, 2 = Hard, 3 = Good, 4 = Easy (`AnkiDroidHelper.EASE_1..4`). AnkiDroid's API has **no 5th ease**. `CardInfo.buttonCount` can be < 4 for some cards and isn't honored yet.
- `NotificationReceiver` runs on the main thread and does ContentProvider I/O synchronously. Keep that work short, or move it to `goAsync()` / WorkManager.
- PendingIntents must be `FLAG_IMMUTABLE` (targetSdk 31+). Distinct actions currently disambiguate the button intents; keep actions unique or use distinct request codes.
- Implicit broadcasts (`SCREEN_OFF`, `SCREEN_ON`, `USER_PRESENT`) **cannot** be registered in the manifest on API 26+. They need a runtime-registered receiver inside a long-lived component (for example a foreground service).
- `POST_NOTIFICATIONS` is declared, but it is not requested at runtime on API 33+. The user has to enable notifications manually.
- Many UI strings are hard-coded. Put new user-facing strings in `res/values/strings.xml`.
- Logging uses `Log.i` with the tags `"Notifications"` and `"BackgroundService"`.
- Match the existing style: Kotlin for new Android components. The Java classes may stay Java unless they're being refactored anyway.
