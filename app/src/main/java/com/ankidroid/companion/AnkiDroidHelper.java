package com.ankidroid.companion;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import com.ichi2.anki.api.AddContentApi;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Card selection on top of AnkiDroid's data ({@link AnkiDataSource}: the ContentProvider, or a fake in device
 * tests), and the stored state of the displayed card.
 */
public class AnkiDroidHelper {
    public static final String STATE_DB = "com.ichi2.anki.api.state";

    public static final String KEY_CURRENT_STATE = "CURRENT_STATE";

    private final Context mContext;
    private final AnkiDataSource mSource;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    public AnkiDroidHelper(Context context) {
        mContext = context.getApplicationContext();
        mSource = AnkiData.source(mContext);
    }

    /**
     * Whether or not the API is available to use.
     * The API could be unavailable if AnkiDroid is not installed or the user explicitly disabled the API
     * @return true if the API is available to use
     */
    public static boolean isApiAvailable(Context context) {
        return AnkiData.getOverride() != null || AddContentApi.getAnkiDroidPackageName(context) != null;
    }

    public boolean isPermissionGranted() {
        return mSource.isPermissionGranted();
    }

    /** All decks by ID, or null if they can't be read. */
    public Map<Long, String> getDeckNames() {
        return mSource.deckNames();
    }

    public String getDeckName(long deckId) {
        return mSource.deckName(deckId);
    }

    public void storeState(long deckId, CardInfo card) {
        final SharedPreferences cardsDb = mContext.getSharedPreferences(STATE_DB, Context.MODE_PRIVATE);

        Map<String, Object> message = new HashMap<>();
        message.put("deck_id", deckId);
        message.put("note_id", card.noteID);
        message.put("card_ord", card.cardOrd);
        message.put("question", card.q);
        message.put("answer", card.a);
        message.put("button_count", card.buttonCount);
        message.put("deck_name", card.deckName);
        JSONObject js = new JSONObject(message);

        cardsDb.edit().putString(KEY_CURRENT_STATE, js.toString()).apply();
    }

    /** Removes the stored card: reviewing is not started any more. */
    public void clearState() {
        mContext.getSharedPreferences(STATE_DB, Context.MODE_PRIVATE).edit().remove(KEY_CURRENT_STATE).apply();
    }

    public StoredState getStoredState() {
        final SharedPreferences cardsDb = mContext.getSharedPreferences(STATE_DB, Context.MODE_PRIVATE);
        String message = cardsDb.getString(KEY_CURRENT_STATE, "");

        // No state found in local
        if (message.isEmpty()) {
            return null;
        }

        try {
            JSONObject json = new JSONObject(message);
            StoredState state = new StoredState();
            state.deckId = json.getLong("deck_id");
            state.noteID = json.getLong("note_id");
            state.cardOrd = json.getInt("card_ord");
            state.question = json.optString("question", "");
            state.answer = json.optString("answer", "");
            state.buttonCount = json.optInt("button_count", 4);
            state.deckName = json.optString("deck_name", "");
            return state;
        } catch (JSONException e) {
            Log.e("AnkiDroidHelper", "Could not read the stored state", e);
        }
        return null;
    }

    public CardInfo queryCurrentScheduledCard(long deckID) {
        return queryCurrentScheduledCard(deckID, DeckScanner.DEFAULT_SCAN_LIMIT);
    }

    /**
     * The first displayable card among the next {@code scanLimit} due cards of the deck, or null if they all
     * have to be skipped.
     */
    public CardInfo queryCurrentScheduledCard(long deckID, int scanLimit) {
        List<CardInfo> cards = queryDisplayableCards(deckID, scanLimit);
        return cards.isEmpty() ? null : cards.get(0);
    }

    /**
     * The displayable cards among the next {@code scanLimit} due cards of the deck, in AnkiDroid's order.
     */
    public List<CardInfo> queryDisplayableCards(long deckID, int scanLimit) {
        // This call requires com.ichi2.anki.permission.READ_WRITE_DATABASE to be granted by user as it is
        // marked as "dangerous" by ankidroid app. This permission has been asked before.
        if (!mSource.isPermissionGranted()) {
            uiHandler.post(() -> Toast.makeText(mContext,
                    R.string.permission_not_granted,
                    Toast.LENGTH_SHORT).show());
            return new ArrayList<>();
        }
        // Fetch several due cards so the ones we can't display (sound in the question) can be skipped.
        List<CardInfo> cards = mSource.dueCards(deckID, scanLimit);
        List<CardInfo> displayable = new ArrayList<>();
        for (CardInfo card : cards) {
            if (isDisplayable(card)) {
                displayable.add(card);
            } else {
                Log.i("Notifications", "skipping card that can't be displayed, noteID: " + card.noteID + ", ord: " + card.cardOrd);
            }
        }
        if (!cards.isEmpty() && displayable.isEmpty()) {
            Log.i("Notifications", "no displayable card among the next " + cards.size() + " due cards");
        }
        return displayable;
    }

    /**
     * Whether the card can be shown in the notification and on the lock screen.
     * Cards whose question plays a sound are skipped for now; they stay due in AnkiDroid.
     */
    static boolean isDisplayable(CardInfo card) {
        return !CardText.hasSound(card.q) && !CardText.clean(card.q).isEmpty();
    }

    /**
     * The next card to review: the first displayable due card of the first deck that has one, in the given order.
     * Each deck is scanned up to its number of due cards (learning + review + new), so it is only passed over
     * once every due card in it has been skipped, or when nothing is due. Returns null when no selected deck
     * has a card to show.
     */
    public CardInfo queryNextCard(List<Long> deckIds) {
        Map<Long, int[]> counts = getDeckCounts();
        return DeckScanner.findNextCard(
                deckIds,
                deckId -> dueCount(counts, deckId),
                (deckId, scanLimit) -> {
                    CardInfo card = queryCurrentScheduledCard(deckId, scanLimit);
                    if (card != null) {
                        card.deckId = deckId;
                        String deckName = mSource.deckName(deckId);
                        card.deckName = deckName == null ? "" : deckName;
                    } else {
                        Log.i("Notifications", "deck " + deckId + ": no displayable card among " + scanLimit + " due cards");
                    }
                    return card;
                });
    }

    /**
     * The rotation set of "Change the card at every unlock": the first {@code size} displayable due cards of the
     * decks, in order ({@link CardRotation#collect}).
     */
    public List<CardInfo> queryRotation(List<Long> deckIds, int size) {
        Map<Long, int[]> counts = getDeckCounts();
        return CardRotation.collect(
                deckIds,
                size,
                deckId -> dueCount(counts, deckId),
                (deckId, scanLimit) -> {
                    List<CardInfo> cards = queryDisplayableCards(deckId, scanLimit);
                    String deckName = cards.isEmpty() ? null : mSource.deckName(deckId);
                    for (CardInfo card : cards) {
                        card.deckId = deckId;
                        card.deckName = deckName == null ? "" : deckName;
                    }
                    return cards;
                },
                card -> new CardSync.CardKey(card.noteID, card.cardOrd));
    }

    private static Integer dueCount(Map<Long, int[]> counts, long deckId) {
        int[] deckCounts = counts.get(deckId);
        return deckCounts == null ? null : deckCounts[0] + deckCounts[1] + deckCounts[2];
    }

    /**
     * Due counts of every deck, as {learn, review, new}, by deck ID. Empty if they can't be read.
     */
    public Map<Long, int[]> getDeckCounts() {
        return mSource.deckCounts();
    }

    /**
     * Whether the card is still due in the deck, i.e. it wasn't reviewed, buried, suspended or deleted in
     * AnkiDroid since it was shown. Null if it can't be checked.
     */
    public Boolean isCardDue(long deckId, long noteId, int cardOrd) {
        if (deckId == -1 || !mSource.isPermissionGranted()) {
            return null;
        }
        int[] deckCounts = getDeckCounts().get(deckId);
        Integer due = deckCounts == null ? null : deckCounts[0] + deckCounts[1] + deckCounts[2];
        return DeckScanner.isDue(new CardSync.CardKey(noteId, cardOrd), due, limit -> mSource.dueCardKeys(deckId, limit));
    }

    /** Records the review in AnkiDroid; {@code timeTakenMs} is how long the card was looked at ({@link ReviewTimer}). */
    public void reviewCard(long noteID, int cardOrd, int ease, long timeTakenMs) {
        // Right before the answer: the due-card queries (isCardDue()) can build the study queue again.
        dropStudyQueue();
        mSource.reviewCard(noteID, cardOrd, ease, timeTakenMs);
    }

    /**
     * Lets AnkiDroid answer any card, not only the next one of its own review: see {@link StudyQueue}. Selects
     * another deck and then the selected one again, which drops AnkiDroid's study queue.
     */
    private void dropStudyQueue() {
        Long selected = mSource.selectedDeck();
        Map<Long, String> decks = mSource.deckNames();
        Long other = selected == null || decks == null ? null : StudyQueue.otherDeck(selected, decks.keySet());
        boolean dropped = other != null && mSource.selectDeck(other);
        if (dropped && !mSource.selectDeck(selected)) {
            Log.e("Notifications", "could not select deck " + selected + " again in AnkiDroid");
        }
        if (!dropped) {
            Log.w("Notifications", "could not drop AnkiDroid's study queue: only its next card can be answered");
        }
    }
}
