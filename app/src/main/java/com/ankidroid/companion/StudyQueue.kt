package com.ankidroid.companion

/**
 * AnkiDroid's study queue, and why an answer sent to AnkiDroid can be lost.
 *
 * AnkiDroid (v3 scheduler) builds the study queue of its selected deck when the cards to review are asked for
 * (by its own review screen, or by our due-card queries) and keeps it. While it is kept, it only answers the card
 * at the top of the queue: any other card is refused ("not at top of queue"), and its ContentProvider swallows the
 * error, so the review is lost without us knowing. The displayed card isn't always the top one: "Change the card
 * at every unlock", skipped cards with sound, or AnkiDroid's queue changing after the card was shown (a learning
 * card coming due, a review in AnkiDroid).
 *
 * Changing the selected deck drops the queue, and without a queue AnkiDroid answers any card. So before each
 * answer, another deck is selected and then the selected one again ([AnkiDroidHelper.reviewCard]).
 */
object StudyQueue {
    /** AnkiDroid's "Default" deck: it always exists, even when hidden from the deck list. */
    const val DEFAULT_DECK_ID = 1L

    /** A deck to select for a moment so that AnkiDroid drops its study queue: any deck but [selected]. */
    @JvmStatic
    fun otherDeck(selected: Long, deckIds: Collection<Long>): Long? =
        deckIds.firstOrNull { it != selected } ?: DEFAULT_DECK_ID.takeIf { it != selected }
}
