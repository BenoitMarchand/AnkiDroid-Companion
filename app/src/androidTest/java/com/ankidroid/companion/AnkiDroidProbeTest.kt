package com.ankidroid.companion

import android.content.ContentValues
import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ichi2.anki.FlashCardsContract
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Measures how the real AnkiDroid answers our queries (issue #21). Read-only: no review is sent, no
 * notification is posted and the companion's state isn't changed. Results are logged under [TAG].
 *
 * Run it with `scripts/install.ps1 -Probe`, which keeps the app installed (connectedAndroidTest would uninstall it).
 */
@RunWith(AndroidJUnit4::class)
class AnkiDroidProbeTest {

    @Test
    fun measureQueries() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val anki = AnkiDroidHelper(context)
        assumeTrue("AnkiDroid permission not granted", anki.isPermissionGranted)

        val deckIds = DeckSelection.load(context)
        log("selected decks in the companion: ${deckIds.size}")

        // 1. First query: AnkiDroid may have to be started (cold) if Android closed it.
        val (counts, countsMs) = timed { anki.deckCounts }
        log("first query (deck counts, ${counts.size} decks): $countsMs ms")
        val (firstCard, firstMs) = timed { anki.queryNextCard(deckIds) }
        log("first queryNextCard: $firstMs ms, card found: ${firstCard != null}")

        // 2. Same queries with AnkiDroid in memory (warm).
        val warmCounts = (1..5).map { timed { anki.deckCounts }.second }
        val warmNext = (1..5).map { timed { anki.queryNextCard(deckIds) }.second }
        log("warm deck counts: $warmCounts ms")
        log("warm queryNextCard: $warmNext ms")

        // 2b. Cost of the due-card query by scan size, on the first selected deck that has due cards.
        val probeDeck = deckIds.firstOrNull { id -> counts[id]?.let { it[0] + it[1] + it[2] > 0 } == true }
        if (probeDeck != null) {
            val due = counts.getValue(probeDeck).sum()
            for (limit in listOf(1, 10, 50, due.coerceAtMost(DeckScanner.MAX_SCAN_LIMIT)).distinct()) {
                val times = (1..3).map { timed { anki.queryCurrentScheduledCard(probeDeck, limit) }.second }
                log("scan $limit of $due due cards: $times ms")
            }
        }

        // 3. Does querying a deck's due cards change the deck selected in AnkiDroid's own interface?
        val selectedBefore = selectedDeck(context)
        val otherDeck = counts.keys.firstOrNull { it != selectedBefore }
        if (otherDeck != null) {
            timed { anki.queryCurrentScheduledCard(otherDeck, 1) }
            val selectedAfter = selectedDeck(context)
            log("AnkiDroid selected deck before: $selectedBefore, after querying deck $otherDeck: $selectedAfter " +
                "-> ${if (selectedBefore == selectedAfter) "UNCHANGED" else "CHANGED"}")
            if (selectedBefore != null && selectedAfter != selectedBefore) {
                // Put the user's selection back.
                val values = ContentValues().apply { put(FlashCardsContract.Deck.DECK_ID, selectedBefore) }
                context.contentResolver.update(FlashCardsContract.Deck.CONTENT_SELECTED_URI, values, null, null)
                log("restored AnkiDroid selected deck: ${selectedDeck(context)}")
            }
        }
    }

    private fun selectedDeck(context: Context): Long? =
        context.contentResolver.query(
            FlashCardsContract.Deck.CONTENT_SELECTED_URI, arrayOf(FlashCardsContract.Deck.DECK_ID), null, null, null
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }

    private fun <T> timed(block: () -> T): Pair<T, Long> {
        val start = System.nanoTime()
        val result = block()
        return result to (System.nanoTime() - start) / 1_000_000
    }

    private fun log(message: String) = Log.i(TAG, message)

    companion object {
        const val TAG = "AnkiProbe"
    }
}
