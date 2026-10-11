package com.ankidroid.companion

/**
 * A fake AnkiDroid for the device tests: decks with due cards, configured per scenario. Answers are recorded
 * and remove the card from the due list, like a review in AnkiDroid would.
 */
class FakeAnkiDataSource : AnkiDataSource {
    data class Card(val noteId: Long, val ord: Int = 0, val question: String, val answer: String = "answer", val buttonCount: Int = 4)
    data class Answer(val noteId: Long, val ord: Int, val ease: Int)

    private val decks = LinkedHashMap<Long, String>()
    private val dueCards = HashMap<Long, MutableList<Card>>()
    private val _answers = mutableListOf<Answer>()

    /** The answers received, in order. */
    val answers: List<Answer> get() = synchronized(this) { _answers.toList() }

    fun deck(id: Long, name: String, vararg cards: Card) = apply {
        decks[id] = name
        dueCards[id] = cards.toMutableList()
    }

    override fun isPermissionGranted() = true

    override fun deckNames(): Map<Long, String> = synchronized(this) { decks.toMap() }

    override fun deckName(deckId: Long): String? = synchronized(this) { decks[deckId] }

    /** Every due card is counted as a review card. */
    override fun deckCounts(): Map<Long, IntArray> = synchronized(this) {
        dueCards.mapValues { (_, cards) -> intArrayOf(0, cards.size, 0) }
    }

    override fun dueCards(deckId: Long, limit: Int): List<CardInfo> = synchronized(this) {
        dueCards[deckId].orEmpty().take(limit).map { card ->
            CardInfo().apply {
                noteID = card.noteId
                cardOrd = card.ord
                q = card.question
                a = card.answer
                buttonCount = card.buttonCount
            }
        }
    }

    override fun dueCardKeys(deckId: Long, limit: Int): List<CardSync.CardKey> = synchronized(this) {
        dueCards[deckId].orEmpty().take(limit).map { CardSync.CardKey(it.noteId, it.ord) }
    }

    override fun reviewCard(noteId: Long, cardOrd: Int, ease: Int, timeTakenMs: Long) = synchronized(this) {
        _answers.add(Answer(noteId, cardOrd, ease))
        dueCards.values.forEach { cards -> cards.removeAll { it.noteId == noteId && it.ord == cardOrd } }
    }
}
