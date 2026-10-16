# Lockscreen Cards for AnkiDroid

Lockscreen Cards for AnkiDroid is an extension app for the AnkiDroid flashcard app. It lets you review your cards from a notification, and see the next card right on your lock screen, without opening AnkiDroid.

![](.github/output.gif)


## Features

- **Review from a notification**: the current card's question is shown in a notification. Tap **Show answer** to see the answer and the Again / Hard / Good / Easy buttons. Only the buttons a card actually has are shown.

- **Lock screen card**: set the "Lockscreen Cards for AnkiDroid" live wallpaper on your lock screen, and the question of the card to review is shown there. Your home screen wallpaper is not changed. The notification is fully visible on the lock screen, so you can answer without unlocking.

- **Several decks, in your order**: select the decks to review and put them in order. Cards come from the first deck that has a card to review, then the next one, and so on. A deck includes its subdecks.

- **Cards with sound are skipped**: cards whose question plays a sound can't be reviewed from a notification, so they are skipped. They stay due in AnkiDroid: nothing is recorded until you answer a card.

- **Lock screen style**: choose a background color or image, the text color and the text size, with a live preview. On a picture, choose how it is filtered so the text stays readable (darken, blur, gradient behind the text, text panel or none) and how strongly.

- **One card per unlock**: with the lock screen wallpaper set, the card notification is only visible while the phone is locked: it is removed at every unlock and comes back at the next lock. After you answer, "Next card at your next unlock" is shown for a few seconds: one card each time you unlock, as a small habit. Turn on **Review several cards in a row** in **Settings** (gear icon) to get the next card right away instead. Without the wallpaper, the notification stays visible and cards follow each other.

- **Home screen widget**: add the **Card** widget to your home screen and tap **Start review** to review the current card there: **Show answer**, then the answer buttons. Each answer shows the next card right away (it doesn't use up the lock screen's card). The widget goes back to **Start review** after 2 minutes without a tap, or when the screen turns off (with the lock screen wallpaper set).

- **Seamless integration**: the app doesn't store your decks, cards or reviews. Everything happens in your AnkiDroid app, through its official API.


## Getting Started

1. **Install AnkiDroid** and make sure its API is enabled (AnkiDroid: Settings › Advanced › Enable AnkiDroid API).

2. **Install Lockscreen Cards for AnkiDroid** from the [releases](../../releases) page.

3. **Open the app and grant access**: allow access to your AnkiDroid cards and allow notifications. The "Finish setting up" checklist on the main screen shows anything still missing.

4. **Choose your decks**: tick decks in **All decks**, then order them under **Review order** with the arrows. Reviewing starts as soon as a deck is ticked: the first card shows up in a notification. Untick every deck to stop.

5. **Optional, lock screen**: tap **Set lock screen wallpaper** and follow the instructions to choose Lockscreen Cards for AnkiDroid for the **lock screen only** (on Android 8 to 13, live wallpapers apply to both screens). In **Settings** (gear icon), choose whether the lock screen shows the question only or the question and the answer (the notification then shows the answer buttons right away), and change its look.

6. **When you're done**: when no selected deck has a card left, you'll see "Bravo! You've finished all cards in the selected decks." A new notification comes when it's time to study again.


## Limitations

- **Simple cards only**: cards are shown as plain text. Images aren't shown, and cards with sound in the question are skipped.

- **Changes made in AnkiDroid**: AnkiDroid doesn't notify other apps. The app checks the displayed card when you open it, when you lock the phone (at most every 10 minutes), after a restart and every hour, and never answers a card that is no longer due.

- **Lock screen wallpaper**: a lock-screen-only live wallpaper needs Android 14 or newer, and some manufacturers' lock screens don't show third-party live wallpapers.


## Development

- Build: `./gradlew assembleDebug` (JDK 17 or newer). Full check (lint and unit tests): `./gradlew build`.
- On Windows, `scripts/install.ps1` builds, installs and starts the app on a connected device (also available as the default build task in VS Code).
- Device tests: `scripts/device-tests.ps1` runs end-to-end scenarios with a fake AnkiDroid (`app/src/androidTest`), on an emulator by default (VS Code task "Android: run device tests"). They also run on an emulator for each pull request and before each release.
- Release: `scripts/release.ps1` runs the build, unit tests and device tests, then starts the Release workflow.
- See `CLAUDE.md` for the architecture.


## Contributing

Contributions to Lockscreen Cards for AnkiDroid are welcome! If you have any ideas, feature requests, or bug reports, please feel free to open an issue or submit a pull request on GitHub.


## License

This project is licensed under the MIT License (see [LICENSE](LICENSE)).

It includes the AnkiDroid API library (`com.github.ankidroid:Anki-Android`), licensed under the
[GNU LGPL v3](https://github.com/ankidroid/Anki-Android/blob/main/api/COPYING.LESSER).
