# AnkiDroid Companion

AnkiDroid Companion is an extension app for the AnkiDroid flashcard app. It lets you review your cards from a notification, and see the next card right on your lock screen, without opening AnkiDroid.

![](.github/output.gif)


## Features

- **Review from a notification**: the current card's question is shown in a notification. Tap **Show answer** to see the answer and the Again / Hard / Good / Easy buttons. Only the buttons a card actually has are shown.
- **Review screen over the lock screen**: tap the notification to open a full review screen on top of the lock screen, without unlocking or expanding the notification.

- **Lock screen card**: set the "AnkiDroid Companion card" live wallpaper on your lock screen, and the question of the card to review is shown there. Your home screen wallpaper is not changed. The notification is fully visible on the lock screen, so you can answer without unlocking.

- **Several decks, in your order**: select the decks to review and put them in order. Cards come from the first deck that has a card to review, then the next one, and so on. A deck includes its subdecks.

- **Cards with sound are skipped**: cards whose question plays a sound can't be reviewed from a notification, so they are skipped. They stay due in AnkiDroid: nothing is recorded until you answer a card.

- **Lock screen style**: choose a background color or image, the text color and the text size, with a live preview. On a picture, choose how it is filtered so the text stays readable (darken, blur, gradient behind the text, text panel or none) and how strongly.

- **One card per unlock**: with the lock screen wallpaper set, the card notification is only visible while the phone is locked: it is removed at every unlock and comes back at the next lock. After you answer, "Next card at your next unlock" is shown for a few seconds: one card each time you unlock, as a small habit. Turn on **Review several cards in a row** on the main screen to get the next card right away instead. Without the wallpaper, the notification stays visible and cards follow each other.

- **Seamless integration**: the app doesn't store your decks, cards or reviews. Everything happens in your AnkiDroid app, through its official API.


## Getting Started

1. **Install AnkiDroid** and make sure its API is enabled (AnkiDroid: Settings › Advanced › Enable AnkiDroid API).

2. **Install AnkiDroid Companion** from the [releases](../../releases) page.

3. **Open the app and grant access**: allow access to your AnkiDroid cards and allow notifications. The "Finish setting up" checklist on the main screen shows anything still missing.

4. **Choose your decks**: tick decks in **All decks**, then order them under **Review order** with the arrows.

5. **Tap "Start reviewing"**: the first card shows up in a notification right away.

6. **Optional, lock screen**: tap **Set lock screen wallpaper** and follow the instructions to choose the AnkiDroid Companion card for the **lock screen only** (on Android 8 to 13, live wallpapers apply to both screens). Tap **Customize** to change its look.

7. **When you're done**: when no selected deck has a card left, you'll see "Bravo! You've finished all cards in the selected decks." A new notification comes when it's time to study again.


## Limitations

- **Simple cards only**: cards are shown as plain text. Images aren't shown, and cards with sound in the question are skipped.

- **Changes made in AnkiDroid**: AnkiDroid doesn't notify other apps. The companion checks the displayed card when you open it, when you lock the phone (at most every 10 minutes), after a restart and every hour, and never answers a card that is no longer due.

- **Lock screen wallpaper**: a lock-screen-only live wallpaper needs Android 14 or newer, and some manufacturers' lock screens don't show third-party live wallpapers.


## Development

- Build: `./gradlew assembleDebug` (JDK 17 or newer). Full check (lint and unit tests): `./gradlew build`.
- On Windows, `scripts/install.ps1` builds, installs and starts the app on a connected device (also available as the default build task in VS Code).
- See `CLAUDE.md` for the architecture.


## Contributing

Contributions to AnkiDroid Companion are welcome! If you have any ideas, feature requests, or bug reports, please feel free to open an issue or submit a pull request on GitHub.


## License

This project is licensed under the MIT License.
