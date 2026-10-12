package com.ankidroid.companion

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end scenarios on a device, with a fake AnkiDroid ([FakeAnkiDataSource]): the real card selection,
 * HTML cleaning, stored state, notification and notification buttons. The app's data is saved before each
 * test and restored after ([AppDataSnapshot]).
 *
 * Run them with `scripts/device-tests.ps1` (an emulator by default). Running instrumented tests force-stops the
 * app, and Android then resets the lock screen card wallpaper: on a phone, it has to be selected again.
 */
@RunWith(AndroidJUnit4::class)
class ReviewScenariosTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val anki = FakeAnkiDataSource()
    private lateinit var snapshot: AppDataSnapshot

    @Before
    fun setUp() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        snapshot = AppDataSnapshot.take(context)
        // Start from a clean state: no card shown, no lock session, default settings.
        listOf(AnkiDroidHelper.STATE_DB, "com.ankidroid.companion.lock_session", "com.ankidroid.companion.card_review",
            "com.ankidroid.companion.review_settings", "com.ankidroid.companion.review_timer").forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
        notificationManager().cancel(Notifications.NOTIFICATION_ID)
        AnkiData.override = anki
    }

    @After
    fun tearDown() {
        AnkiData.override = null
        snapshot.restore()
    }

    @Test
    fun oneCardInTheFirstDeck_isShownAndNotified() {
        anki.deck(1, "Spanish", FakeAnkiDataSource.Card(10, question = "<div><b>Hola</b></div>", answer = "Hello"))
        select(1)

        start()

        val state = storedState()
        assertEquals(10L, state.noteID)
        assertEquals("Hola", CardText.clean(state.question))
        assertEquals("Anki • Spanish", notificationTitle())
    }

    @Test
    fun noCardInAnyDeck_showsBravo() {
        anki.deck(1, "Spanish").deck(2, "German")
        select(1, 2)

        start()

        assertFalse(storedState().hasCard())
        assertEquals(context.getString(R.string.notification_title), notificationTitle())
    }

    @Test
    fun noCardInDeck1_showsTheCardOfDeck2() {
        anki.deck(1, "Spanish").deck(2, "German", FakeAnkiDataSource.Card(20, question = "Hallo"))
        select(1, 2)

        start()

        assertEquals(20L, storedState().noteID)
        assertEquals("Anki • German", notificationTitle())
    }

    @Test
    fun onlyCardHasSoundInTheQuestion_isSkipped() {
        anki.deck(1, "Spanish", FakeAnkiDataSource.Card(10, question = "Hola [sound:hola.mp3]"))
        select(1)

        start()

        assertFalse(storedState().hasCard())
    }

    @Test
    fun soundCardFirst_theTextCardOfTheSameDeckIsShown() {
        anki.deck(
            1, "Spanish",
            FakeAnkiDataSource.Card(10, question = "[sound:hola.mp3]"),
            FakeAnkiDataSource.Card(11, question = "Adiós"),
        )
        select(1)

        start()

        assertEquals(11L, storedState().noteID)
    }

    @Test
    fun soundCardInDeck1_theTextCardOfDeck2IsShown() {
        anki.deck(1, "Spanish", FakeAnkiDataSource.Card(10, question = "[sound:hola.mp3]"))
            .deck(2, "German", FakeAnkiDataSource.Card(20, question = "Hallo"))
        select(1, 2)

        start()

        assertEquals(20L, storedState().noteID)
    }

    @Test
    fun answeringACard_sendsTheEaseAndShowsTheNextCard() {
        anki.deck(
            1, "Spanish",
            FakeAnkiDataSource.Card(10, question = "Hola"),
            FakeAnkiDataSource.Card(11, question = "Adiós"),
        )
        select(1)
        start()

        press("ACTION_BUTTON_3")
        waitUntil { anki.answers.isNotEmpty() && storedState().noteID == 11L }

        assertEquals(listOf(FakeAnkiDataSource.Answer(10, 0, ease = 3)), anki.answers)
        assertEquals("Anki • Spanish", notificationTitle())
    }

    @Test
    fun answeringACard_sendsTheTimeSinceTheCardWasShown() {
        anki.deck(1, "Spanish", FakeAnkiDataSource.Card(10, question = "Hola"))
        select(1)
        start()

        // Without the lock screen wallpaper (reset by the test run), the timer starts when the card is posted.
        SystemClock.sleep(1_000)
        press("ACTION_BUTTON_3")
        waitUntil { anki.answers.isNotEmpty() }

        val timeTaken = anki.timesTaken.single()
        assertTrue("time taken: $timeTaken ms", timeTaken in 1_000L..10_000L)
    }

    @Test
    fun cardWithThreeButtons_goodSendsEase2() {
        anki.deck(1, "Spanish", FakeAnkiDataSource.Card(10, question = "Hola", buttonCount = 3))
        select(1)
        start()

        press("ACTION_BUTTON_3")
        waitUntil { anki.answers.isNotEmpty() }

        assertEquals(listOf(FakeAnkiDataSource.Answer(10, 0, ease = 2)), anki.answers)
    }

    @Test
    fun cardWithThreeButtons_hardIsIgnored() {
        anki.deck(1, "Spanish", FakeAnkiDataSource.Card(10, question = "Hola", buttonCount = 3))
        select(1)
        start()

        press("ACTION_BUTTON_2")
        // The receiver works in the background: give it time to (not) answer.
        SystemClock.sleep(1_500)

        assertTrue(anki.answers.isEmpty())
        assertEquals(10L, storedState().noteID)
    }

    @Test
    fun unselectingEveryDeck_stopsReviewing() {
        anki.deck(1, "Spanish", FakeAnkiDataSource.Card(10, question = "Hola"))
        select(1)
        start()
        notificationTitle()

        select()
        assertEquals(CardSync.Action.STOP, CardStateController.syncWithAnkiDroid(context))

        assertEquals(null, AnkiDroidHelper(context).storedState)
        // The system removes it asynchronously.
        waitUntil { notificationManager().activeNotifications.none { it.id == Notifications.NOTIFICATION_ID } }
    }

    @Test
    fun showAnswer_revealsTheAnswerOfTheDisplayedCard() {
        anki.deck(1, "Spanish", FakeAnkiDataSource.Card(10, question = "Hola"))
        select(1)
        start()
        val card = storedState().toCardInfo()
        assertFalse(CardReview.isAnswerRevealed(context, card))

        press(Notifications.ACTION_SHOW_ANSWER)
        waitUntil { CardReview.isAnswerRevealed(context, card) }
    }

    @Test
    fun rotationOnUnlock_goesThroughTheFirstCardsThenBackToTheFirstAfterAnAnswer() {
        anki.deck(
            1, "Spanish",
            FakeAnkiDataSource.Card(10, question = "Hola"),
            FakeAnkiDataSource.Card(11, question = "Adiós"),
            FakeAnkiDataSource.Card(12, question = "Gracias"),
        )
        select(1)
        ReviewSettings.setRotationSize(context, 2)
        start()
        assertEquals(10L, storedState().noteID)

        // The rotation set is the first 2 due cards: 12 isn't in it.
        rotate(expected = 11)
        rotate(expected = 10)
        rotate(expected = 11)

        // Answering shows the first card again, and the next due card joins the set.
        press("ACTION_BUTTON_3")
        waitUntil { anki.answers.isNotEmpty() && storedState().noteID == 10L }
        assertEquals(listOf(FakeAnkiDataSource.Answer(11, 0, ease = 3)), anki.answers)
        rotate(expected = 12)
    }

    /** An unlock without an answer, as decided by the lock session. */
    private fun rotate(expected: Long) {
        CardStateController.rotateOnUnlock(context)
        waitUntil { storedState().noteID == expected }
    }

    private fun select(vararg deckIds: Long) = DeckSelection.save(context, deckIds.toList())

    /** Reviewing starts with the deck selection: the app runs the sync once the decks are selected. */
    private fun start() {
        CardStateController.syncWithAnkiDroid(context)
    }

    /** A notification button: the broadcast goes through the real receiver, on the foreground queue like a tap. */
    private fun press(action: String) {
        context.sendBroadcast(
            Intent(context, NotificationReceiver::class.java).setAction(action).addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        )
    }

    private fun storedState(): StoredState =
        AnkiDroidHelper(context).storedState ?: throw AssertionError("no card stored: reviewing not started")

    private fun notificationManager() = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun notificationTitle(): String? {
        // Read it in the same check: a new card cancels the notification just before posting it again.
        var notification: Notification? = null
        waitUntil {
            notification = notificationManager().activeNotifications.firstOrNull { it.id == Notifications.NOTIFICATION_ID }?.notification
            notification != null
        }
        return notification!!.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
    }

    private fun waitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (!condition()) {
            if (SystemClock.uptimeMillis() > end) throw AssertionError("condition not met within $timeoutMs ms")
            SystemClock.sleep(50)
        }
    }
}
