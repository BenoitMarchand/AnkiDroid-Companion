package com.ankidroid.companion

import android.content.ContentUris
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ichi2.anki.FlashCardsContract
import com.ichi2.anki.api.AddContentApi
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Prepares the Play Store assets (`scripts/store-assets.ps1`), on the real AnkiDroid: a demo deck whose cards
 * are new again at each run, selected in the app, and the 512 px listing icon.
 *
 * Changes the user's data, so it only runs when the script passes `-e storeAssets true`: it's skipped in the
 * device tests and in CI.
 */
@RunWith(AndroidJUnit4::class)
class StoreAssetsSetup {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun onlyWhenAsked() {
        assumeTrue(
            "run by scripts/store-assets.ps1 only",
            InstrumentationRegistry.getArguments().getString("storeAssets") == "true"
        )
    }

    /** Empties the demo deck and fills it again, so every card is new and due (the deck is only for this). */
    @Test
    fun resetDemoDeck() {
        val api = AddContentApi(context)
        val deckId: Long = api.deckList!!.entries.firstOrNull { it.value == DECK }?.key ?: api.addNewDeck(DECK)!!
        val modelId: Long = api.modelList!!.entries.firstOrNull { it.value == MODEL }?.key ?: api.addNewBasicModel(MODEL)!!

        // The notes provider takes an AnkiDroid search as its selection.
        val previous = context.contentResolver.query(
            FlashCardsContract.Note.CONTENT_URI, arrayOf(FlashCardsContract.Note._ID), "deck:\"$DECK\"", null, null
        )?.use { cursor -> List(cursor.count) { cursor.moveToPosition(it); cursor.getLong(0) } }.orEmpty()
        for (noteId in previous) {
            context.contentResolver.delete(ContentUris.withAppendedId(FlashCardsContract.Note.CONTENT_URI, noteId), null, null)
        }
        CARDS.forEach { (country, capital) ->
            api.addNote(modelId, deckId, arrayOf(question(country), capital), setOf(TAG))
        }

        DeckSelection.save(context, listOf(deckId))
        CardStateController.showNextCard(context, silent = true, notifyIfNone = false)
        Log.i(LOG_TAG, "demo deck $deckId reset with ${CARDS.size} cards")
    }

    /** The launcher icon as the Play Store wants it: 512 x 512, full square (the store applies the mask). */
    @Test
    fun renderListingIcon() {
        val icon = ContextCompat.getDrawable(context, R.mipmap.ic_launcher) as AdaptiveIconDrawable
        val size = 512
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        // The layers are 108 dp, of which the inner 72 dp are always visible: that part fills the square.
        val inset = size * 18 / 72
        for (layer in listOfNotNull(icon.background, icon.foreground)) {
            layer.setBounds(-inset, -inset, size + inset, size + inset)
            layer.draw(canvas)
        }
        // App-specific external storage: adb can pull it without run-as.
        val file = File(context.getExternalFilesDir(null), "store/icon-512.png").apply { parentFile!!.mkdirs() }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        Log.i(LOG_TAG, "icon written to $file")
    }

    private fun question(country: String) = "What is the capital of $country?"

    companion object {
        const val DECK = "World capitals"
        private const val MODEL = "Basic"
        private const val TAG = "micro-review-demo"
        private const val LOG_TAG = "StoreAssets"
        private val CARDS = listOf(
            "Japan" to "Tokyo", "Australia" to "Canberra", "Canada" to "Ottawa", "Brazil" to "Brasília",
            "Kenya" to "Nairobi", "Norway" to "Oslo", "Peru" to "Lima", "Egypt" to "Cairo",
            "Vietnam" to "Hanoi", "Portugal" to "Lisbon", "New Zealand" to "Wellington", "Morocco" to "Rabat",
        )
    }
}
