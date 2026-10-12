package com.ankidroid.companion;

public class StoredState {
    long deckId;
    int cardOrd;
    long noteID;
    String question = "";
    String answer = "";
    int buttonCount = 4;
    String deckName = "";

    /** False for the "empty" state stored when there is no card to review. */
    boolean hasCard() {
        return !(noteID == -1 && cardOrd == -1);
    }

    /** The card to show again in the notification, without querying AnkiDroid. */
    CardInfo toCardInfo() {
        CardInfo card = new CardInfo();
        card.noteID = noteID;
        card.cardOrd = cardOrd;
        card.deckId = deckId;
        card.deckName = deckName;
        card.q = question;
        card.a = answer;
        card.buttonCount = buttonCount;
        return card;
    }
}
