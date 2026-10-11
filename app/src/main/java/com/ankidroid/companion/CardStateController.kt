package com.ankidroid.companion

import android.content.Context
import android.util.Log

/** Single entry point for "move to the next card": finds it, stores it and updates the notification. */
object CardStateController {

    /**
     * Shows the next card from the selected decks. Does ContentProvider I/O: call it off the main thread.
     *
     * @param notifyIfNone whether to show the "deck finished" notification when there is no card
     * @return the card now shown, or null if there is none
     */
    @JvmStatic
    fun showNextCard(context: Context, silent: Boolean, notifyIfNone: Boolean): CardInfo? {
        val anki = AnkiDroidHelper(context)
        val deckIds = DeckSelection.load(context)
        val card = anki.queryNextCard(deckIds)

        if (card != null) {
            Log.i("Notifications", "next card found in deck ${card.deckId}")
            anki.storeState(card.deckId, card)
            Notifications.create().showNotification(context, card, card.deckName, silent)
        } else if (notifyIfNone) {
            Log.i("Notifications", "no card to review in the selected decks")
            val emptyCard = CardInfo()
            emptyCard.cardOrd = -1
            emptyCard.noteID = -1
            anki.storeState(deckIds.firstOrNull() ?: -1L, emptyCard)
            Notifications.create().showNotification(context, null, "", silent)
        }
        return card
    }
}
