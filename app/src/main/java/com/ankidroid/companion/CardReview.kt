package com.ankidroid.companion

import android.content.Context
import android.util.Log

/**
 * Reviewing the displayed card from the notification buttons.
 * Does ContentProvider I/O: call it off the main thread.
 */
object CardReview {
    private const val TAG = "Notifications"
    private const val PREFS = "com.ankidroid.companion.card_review"

    /** The card whose answer is shown, as "noteId:ord". A new card doesn't match, so the flag clears itself. */
    private const val KEY_REVEALED_CARD = "revealed_card"

    /**
     * Answers the displayed card with [button] (one of [AnswerButtons]) and shows the next card.
     *
     * @return the card now displayed, or null if there is none
     */
    fun answer(context: Context, button: Int): CardInfo? {
        val anki = AnkiDroidHelper(context)
        val localState = anki.storedState

        if (localState != null && localState.hasCard()) {
            Log.i(TAG, "answering note ${localState.noteID}, ord ${localState.cardOrd}")
            val ease = AnswerButtons.easeFor(button, localState.buttonCount)
            if (ease == null) {
                Log.w(TAG, "button $button is not valid for a card with ${localState.buttonCount} buttons")
                return localState.toCardInfo()
            }
            LockSession.onAnswered(context)
            // The card may have been reviewed, buried or deleted in AnkiDroid since it was shown: answering it
            // again would record a second review. Null means it couldn't be checked: answer as before.
            if (anki.isCardDue(localState.deckId, localState.noteID, localState.cardOrd) == false) {
                Log.i(TAG, "card no longer due in AnkiDroid, not answering it")
            } else {
                anki.reviewCard(localState.noteID, localState.cardOrd, localState.cardStartTime, ease)
            }
        }

        return CardStateController.showNextCard(context, silent = true, notifyIfNone = true)
    }

    /** Shows the answer of the displayed card, in the notification too (updated in place, without alert). */
    fun revealAnswer(context: Context) {
        val state = AnkiDroidHelper(context).storedState?.takeIf { it.hasCard() } ?: return
        prefs(context).edit().putString(KEY_REVEALED_CARD, key(state.noteID, state.cardOrd)).apply()
        Notifications.create().showNotification(context, state.toCardInfo(), state.deckName, isSilent = true, replace = false)
    }

    /** Whether the answer of [card] was revealed. */
    fun isAnswerRevealed(context: Context, card: CardInfo): Boolean =
        prefs(context).getString(KEY_REVEALED_CARD, null) == key(card.noteID, card.cardOrd)

    private fun key(noteId: Long, ord: Int) = "$noteId:$ord"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
