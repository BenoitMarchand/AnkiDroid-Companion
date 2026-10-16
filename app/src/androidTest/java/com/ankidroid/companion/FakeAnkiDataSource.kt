package com.ankidroid.companion

/**
 * A fake AnkiDroid for the device tests: decks with due cards, configured per scenario. Answers are recorded
 * and remove the card from the due list, like a review in AnkiDroid would.
 *
 * Like AnkiDroid ([StudyQueue]), it keeps the study queue of its selected deck once built, and then refuses to
 * answer any card but the first one of that deck. The due-card queries select their deck for the query and
 * select the previous one again, and changing the selected deck drops the queue.
 */
class FakeAnkiDataSource : AnkiDataSource {
    data class Card(val noteId: Long, val ord: Int = 0, val question: String, val answer: String = "answer", val buttonCount: Int = 4)
    data class Answer(val noteId: Long, val ord: Int, val ease: Int)

    private val decks = LinkedHashMap<Long, String>()
    private val dueCards = HashMap<Long, MutableList<Card>>()
    private val _answers = mutableListOf<Answer>()
    private val _refused = mutableListOf<Answer>()
    private val _timesTaken = mutableListOf<Long>()

    /** The deck selected in AnkiDroid: by default one the scenarios don't use. */
    private var selected = UNUSED_DECK_ID

    /** The deck whose study queue AnkiDroid keeps, or null if there is none. */
    private var queueDeck: Long? = null

    /** The answers received, in order. */
    val answers: List<Answer> get() = synchronized(this) { _answers.toList() }

    /** The answers AnkiDroid refused: the card wasn't at the top of its study queue. */
    val refused: List<Answer> get() = synchronized(this) { _refused.toList() }

    /** The time taken sent with each answer, in milliseconds. */
    val timesTaken: List<Long> get() = synchronized(this) { _timesTaken.toList() }

    fun deck(id: Long, name: String, vararg cards: Card) = apply {
        decks[id] = name
        dueCards[id] = cards.toMutableList()
    }

    /** The user selects [deckId] in AnkiDroid. */
    fun selectInAnkiDroid(deckId: Long) = apply { selectDeck(deckId) }

    override fun isPermissionGranted() = true

    override fun deckNames(): Map<Long, String> = synchronized(this) { decks.toMap() }

    override fun deckName(deckId: Long): String? = synchronized(this) { decks[deckId] }

    /** Every due card is counted as a review card. */
    override fun deckCounts(): Map<Long, IntArray> = synchronized(this) {
        dueCards.mapValues { (_, cards) -> intArrayOf(0, cards.size, 0) }
    }

    override fun dueCards(deckId: Long, limit: Int): List<CardInfo> = withStudyQueue(deckId) {
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

    override fun dueCardKeys(deckId: Long, limit: Int): List<CardSync.CardKey> = withStudyQueue(deckId) {
        dueCards[deckId].orEmpty().take(limit).map { CardSync.CardKey(it.noteId, it.ord) }
    }

    override fun reviewCard(noteId: Long, cardOrd: Int, ease: Int, timeTakenMs: Long) = synchronized(this) {
        val top = queueDeck?.let { dueCards[it]?.firstOrNull() }
        if (queueDeck != null && (top?.noteId != noteId || top.ord != cardOrd)) {
            _refused.add(Answer(noteId, cardOrd, ease))
            return@synchronized
        }
        _answers.add(Answer(noteId, cardOrd, ease))
        _timesTaken.add(timeTakenMs)
        dueCards.values.forEach { cards -> cards.removeAll { it.noteId == noteId && it.ord == cardOrd } }
    }

    override fun selectedDeck(): Long = synchronized(this) { selected }

    override fun selectDeck(deckId: Long): Boolean = synchronized(this) {
        if (deckId != selected) {
            selected = deckId
            queueDeck = null
        }
        true
    }

    /** Like AnkiDroid's due-card query: selects [deckId] for the query, which builds its study queue. */
    private fun <T> withStudyQueue(deckId: Long, query: () -> T): T = synchronized(this) {
        val before = selected
        selectDeck(deckId)
        if (queueDeck == null) queueDeck = deckId
        val result = query()
        selectDeck(before)
        result
    }

    private companion object {
        const val UNUSED_DECK_ID = 999L
    }
}
