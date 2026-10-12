package com.ankidroid.companion

/**
 * Maps the notification's 4 buttons (1 = Again, 2 = Hard, 3 = Good, 4 = Easy) to the ease AnkiDroid expects,
 * which depends on how many answer buttons the card has (`FlashCardsContract.ReviewInfo.BUTTON_COUNT`):
 * - 4 buttons: Again = 1, Hard = 2, Good = 3, Easy = 4
 * - 3 buttons: Again = 1, Good = 2, Easy = 3 (no Hard)
 * - 2 buttons: Again = 1, Good = 2
 */
object AnswerButtons {
    const val AGAIN = 1
    const val HARD = 2
    const val GOOD = 3
    const val EASY = 4

    /** The buttons to show for a card with [buttonCount] answer buttons. Unknown counts show all 4. */
    @JvmStatic
    fun visibleButtons(buttonCount: Int): List<Int> = when (buttonCount) {
        2 -> listOf(AGAIN, GOOD)
        3 -> listOf(AGAIN, GOOD, EASY)
        else -> listOf(AGAIN, HARD, GOOD, EASY)
    }

    /** The ease to send to AnkiDroid when [button] is tapped, or null if that button isn't valid for the card. */
    @JvmStatic
    fun easeFor(button: Int, buttonCount: Int): Int? {
        val index = visibleButtons(buttonCount).indexOf(button)
        return if (index == -1) null else index + 1
    }
}
