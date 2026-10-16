package com.ankidroid.companion

/**
 * What the home screen card widget shows. It always shows the displayed card: unlike the lock screen, it never
 * waits for the next lock ("Next card at your next unlock").
 *
 * A widget isn't told when it is seen, so the card stays behind "Start review" until the user starts: that's when
 * the review timer starts. Then every answer shows the next card right away, until the screen turns off or nothing
 * is tapped for [TIMEOUT_MS]: back to "Start review".
 */
object CardWidgetContent {
    /** Without a tap for this long, the widget goes back to "Start review". */
    const val TIMEOUT_MS = 2 * 60 * 1000L

    enum class Kind {
        /** No deck selected: reviewing hasn't started. */
        NOT_STARTED,

        /** No card left in the selected decks ("Bravo"). */
        DONE,

        /** A card is waiting: "Start review". */
        IDLE,

        /** The question and "Show answer". */
        QUESTION,

        /** The question, the answer and the answer buttons. */
        ANSWER,
    }

    fun kind(started: Boolean, hasCard: Boolean, reviewing: Boolean, answerRevealed: Boolean): Kind = when {
        !started -> Kind.NOT_STARTED
        !hasCard -> Kind.DONE
        !reviewing -> Kind.IDLE
        answerRevealed -> Kind.ANSWER
        else -> Kind.QUESTION
    }

    /** Whether a review started on the widget is still going on: the last tap was less than [TIMEOUT_MS] ago. */
    fun isReviewing(activeUntil: Long, now: Long): Boolean = now < activeUntil

    /** When a review that was just started or continued at [now] goes back to "Start review". */
    fun activeUntil(now: Long): Long = now + TIMEOUT_MS

    /**
     * The number of due cards in the selected decks, shown next to "Start review". A selected subdeck whose parent
     * is selected too is already counted in the parent. Null if a count is unknown.
     *
     * @param names deck names, `Parent::Child`
     * @param counts due cards (learning + review + new) by deck
     */
    fun dueTotal(selected: List<Long>, names: Map<Long, String>, counts: Map<Long, Int>): Int? {
        val selectedNames = selected.mapNotNull { names[it] }
        var total = 0
        for (deckId in selected) {
            val name = names[deckId]
            if (name != null && selectedNames.any { name.startsWith("$it::") }) continue
            total += counts[deckId] ?: return null
        }
        return total
    }
}
