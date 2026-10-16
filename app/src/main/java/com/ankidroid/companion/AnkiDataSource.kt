package com.ankidroid.companion

import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import com.ichi2.anki.FlashCardsContract
import com.ichi2.anki.api.AddContentApi
import org.json.JSONArray
import org.json.JSONException

/**
 * What the app reads from and writes to AnkiDroid. [AnkiDroidHelper] builds the card selection on top of it
 * (deck order, skipped cards, stored state). The real implementation is [ContentProviderAnkiData]; the device
 * tests replace it with a fake ([AnkiData.override]), so they run the real app code without AnkiDroid.
 */
interface AnkiDataSource {
    /** Whether AnkiDroid's database may be read and written (`READ_WRITE_DATABASE` granted). */
    fun isPermissionGranted(): Boolean

    /** All decks by ID, or null if they can't be read. */
    fun deckNames(): Map<Long, String>?

    fun deckName(deckId: Long): String?

    /** Due counts of every deck, as {learn, review, new}, by deck ID. Empty if they can't be read. */
    fun deckCounts(): Map<Long, IntArray>

    /**
     * The next [limit] due cards of the deck (subdecks included), in AnkiDroid's order, with their identifiers,
     * button count, question (`QUESTION_SIMPLE`) and answer (`ANSWER_PURE`). Cards whose content can't be read
     * are left out.
     */
    fun dueCards(deckId: Long, limit: Int): List<CardInfo>

    /** The identifiers of the next [limit] due cards of the deck, without their content. */
    fun dueCardKeys(deckId: Long, limit: Int): List<CardSync.CardKey>

    /** Records an answer. [ease] is 1 to the card's button count. */
    fun reviewCard(noteId: Long, cardOrd: Int, ease: Int, timeTakenMs: Long)

    /** The deck selected in AnkiDroid (the one its study screen shows), or null if it can't be read. */
    fun selectedDeck(): Long?

    /** Selects a deck in AnkiDroid. Returns whether it was selected. */
    fun selectDeck(deckId: Long): Boolean
}

/** The data source in use: AnkiDroid's ContentProvider, unless a test installed a fake. */
object AnkiData {
    @Volatile
    @VisibleForTesting
    @JvmStatic
    var override: AnkiDataSource? = null

    @JvmStatic
    fun source(context: Context): AnkiDataSource = override ?: ContentProviderAnkiData(context.applicationContext)
}

/** AnkiDroid's public ContentProvider API ([FlashCardsContract]). Each call is IPC: keep it off the main thread. */
class ContentProviderAnkiData(private val context: Context) : AnkiDataSource {
    private val api by lazy { AddContentApi(context) }

    override fun isPermissionGranted(): Boolean =
        ContextCompat.checkSelfPermission(context, AddContentApi.READ_WRITE_PERMISSION) == PackageManager.PERMISSION_GRANTED

    override fun deckNames(): Map<Long, String>? = api.deckList

    override fun deckName(deckId: Long): String? = api.getDeckName(deckId)

    override fun deckCounts(): Map<Long, IntArray> {
        val counts = HashMap<Long, IntArray>()
        if (!isPermissionGranted()) return counts
        val projection = arrayOf(FlashCardsContract.Deck.DECK_ID, FlashCardsContract.Deck.DECK_COUNTS)
        try {
            context.contentResolver.query(FlashCardsContract.Deck.CONTENT_ALL_URI, projection, null, null, null)?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(FlashCardsContract.Deck.DECK_ID)
                val countsColumn = cursor.getColumnIndexOrThrow(FlashCardsContract.Deck.DECK_COUNTS)
                while (cursor.moveToNext()) {
                    val json = JSONArray(cursor.getString(countsColumn))
                    counts[cursor.getLong(idColumn)] = intArrayOf(json.getInt(0), json.getInt(1), json.getInt(2))
                }
            }
        } catch (e: JSONException) {
            Log.e(TAG, "Could not read deck counts", e)
        } catch (e: RuntimeException) {
            Log.e(TAG, "Could not read deck counts", e)
        }
        return counts
    }

    override fun dueCards(deckId: Long, limit: Int): List<CardInfo> {
        if (!isPermissionGranted()) return emptyList()
        val cards = queryReviewInfo(deckId, limit, projection = null) { cursor ->
            CardInfo().apply {
                cardOrd = cursor.getInt(cursor.getColumnIndexOrThrow(FlashCardsContract.ReviewInfo.CARD_ORD))
                noteID = cursor.getLong(cursor.getColumnIndexOrThrow(FlashCardsContract.ReviewInfo.NOTE_ID))
                buttonCount = cursor.getInt(cursor.getColumnIndexOrThrow(FlashCardsContract.ReviewInfo.BUTTON_COUNT))
                try {
                    fileNames = JSONArray(cursor.getString(cursor.getColumnIndexOrThrow(FlashCardsContract.ReviewInfo.MEDIA_FILES)))
                    nextReviewTexts = JSONArray(cursor.getString(cursor.getColumnIndexOrThrow(FlashCardsContract.ReviewInfo.NEXT_REVIEW_TIMES)))
                } catch (e: JSONException) {
                    Log.w(TAG, "Could not read the media files or next review times", e)
                }
            }
        }
        // The content of each card is a separate query.
        return cards.filter { card -> readContent(card) }
    }

    override fun dueCardKeys(deckId: Long, limit: Int): List<CardSync.CardKey> {
        if (!isPermissionGranted()) return emptyList()
        val projection = arrayOf(FlashCardsContract.ReviewInfo.NOTE_ID, FlashCardsContract.ReviewInfo.CARD_ORD)
        return queryReviewInfo(deckId, limit, projection) { cursor ->
            CardSync.CardKey(
                cursor.getLong(cursor.getColumnIndexOrThrow(FlashCardsContract.ReviewInfo.NOTE_ID)),
                cursor.getInt(cursor.getColumnIndexOrThrow(FlashCardsContract.ReviewInfo.CARD_ORD)),
            )
        }
    }

    override fun reviewCard(noteId: Long, cardOrd: Int, ease: Int, timeTakenMs: Long) {
        val values = ContentValues().apply {
            put(FlashCardsContract.ReviewInfo.NOTE_ID, noteId)
            put(FlashCardsContract.ReviewInfo.CARD_ORD, cardOrd)
            put(FlashCardsContract.ReviewInfo.EASE, ease)
            put(FlashCardsContract.ReviewInfo.TIME_TAKEN, timeTakenMs)
        }
        context.contentResolver.update(FlashCardsContract.ReviewInfo.CONTENT_URI, values, null, null)
    }

    override fun selectedDeck(): Long? {
        if (!isPermissionGranted()) return null
        val projection = arrayOf(FlashCardsContract.Deck.DECK_ID)
        return try {
            context.contentResolver.query(FlashCardsContract.Deck.CONTENT_SELECTED_URI, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(cursor.getColumnIndexOrThrow(FlashCardsContract.Deck.DECK_ID)) else null
            }
        } catch (e: RuntimeException) {
            Log.e(TAG, "Could not read the selected deck", e)
            null
        }
    }

    override fun selectDeck(deckId: Long): Boolean {
        if (!isPermissionGranted()) return false
        val values = ContentValues().apply { put(FlashCardsContract.Deck.DECK_ID, deckId) }
        return try {
            context.contentResolver.update(FlashCardsContract.Deck.CONTENT_SELECTED_URI, values, null, null) > 0
        } catch (e: RuntimeException) {
            Log.e(TAG, "Could not select deck $deckId", e)
            false
        }
    }

    /** Runs a due-card query (`ReviewInfo`, deck -1 = current deck) and maps each row. Empty on error. */
    private fun <T> queryReviewInfo(deckId: Long, limit: Int, projection: Array<String>?, map: (Cursor) -> T): List<T> {
        val selection = if (deckId == -1L) "limit=?" else "limit=?,deckID=?"
        val arguments = if (deckId == -1L) arrayOf("$limit") else arrayOf("$limit", "$deckId")
        val rows = ArrayList<T>()
        try {
            context.contentResolver.query(FlashCardsContract.ReviewInfo.CONTENT_URI, projection, selection, arguments, null)?.use { cursor ->
                while (cursor.moveToNext()) rows.add(map(cursor))
            }
        } catch (e: RuntimeException) {
            Log.e(TAG, "Could not read due cards", e)
        }
        return rows
    }

    /** Reads the question and answer of [card]. Returns false if they can't be read. */
    private fun readContent(card: CardInfo): Boolean {
        val uri = Uri.withAppendedPath(FlashCardsContract.Note.CONTENT_URI, card.noteID.toString())
            .let { Uri.withAppendedPath(it, "cards") }
            .let { Uri.withAppendedPath(it, card.cardOrd.toString()) }
        val projection = arrayOf(FlashCardsContract.Card.ANSWER_PURE, FlashCardsContract.Card.QUESTION_SIMPLE)
        return try {
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use false
                card.a = cursor.getString(cursor.getColumnIndexOrThrow(FlashCardsContract.Card.ANSWER_PURE))
                card.q = cursor.getString(cursor.getColumnIndexOrThrow(FlashCardsContract.Card.QUESTION_SIMPLE))
                true
            } ?: false
        } catch (e: RuntimeException) {
            Log.e(TAG, "Could not read card ${card.noteID}/${card.cardOrd}", e)
            false
        }
    }

    private companion object {
        const val TAG = "AnkiDroidHelper"
    }
}
