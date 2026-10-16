package com.ankidroid.companion

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
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

    @get:Rule
    val compose = createEmptyComposeRule()

    @Before
    fun setUp() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        snapshot = AppDataSnapshot.take(context)
        // Start from a clean state: no card shown, no lock session, default settings.
        listOf(AnkiDroidHelper.STATE_DB, "com.ankidroid.companion.lock_session", "com.ankidroid.companion.card_review",
            "com.ankidroid.companion.review_settings", "com.ankidroid.companion.review_timer",
            "com.ankidroid.companion.scheduled_review", "com.ankidroid.companion.scheduled_review_settings",
            "com.ankidroid.companion.card_widget").forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
        notificationManager().cancel(Notifications.NOTIFICATION_ID)
        notificationManager().cancel(ScheduledReview.NOTIFICATION_ID)
        AnkiData.override = anki
    }

    @After
    fun tearDown() {
        AnkiData.override = null
        snapshot.restore()
        notificationManager().cancel(ScheduledReview.NOTIFICATION_ID)
        // Back to the alarm of the restored settings.
        ScheduledReview.schedule(context)
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

    @Test
    fun rotatedCard_ofTheDeckSelectedInAnkiDroid_isAnswered() {
        // AnkiDroid keeps the study queue of its selected deck, and then only answers its first card (#62).
        // Not deck 1: that's AnkiDroid's "Default" deck.
        anki.deck(
            5, "Spanish",
            FakeAnkiDataSource.Card(10, question = "Hola"),
            FakeAnkiDataSource.Card(11, question = "Adiós"),
        ).selectInAnkiDroid(5)
        select(5)
        ReviewSettings.setRotationSize(context, 2)
        start()
        rotate(expected = 11)

        press("ACTION_BUTTON_3")
        waitUntil { anki.answers.isNotEmpty() || anki.refused.isNotEmpty() }

        assertEquals(listOf(FakeAnkiDataSource.Answer(11, 0, ease = 3)), anki.answers)
        assertEquals(5L, anki.selectedDeck())
    }

    @Test
    fun cardAfterASkippedSoundCard_ofTheDeckSelectedInAnkiDroid_isAnswered() {
        anki.deck(
            5, "Spanish",
            FakeAnkiDataSource.Card(10, question = "[sound:hola.mp3]"),
            FakeAnkiDataSource.Card(11, question = "Adiós"),
        ).selectInAnkiDroid(5)
        select(5)
        start()
        assertEquals(11L, storedState().noteID)

        press("ACTION_BUTTON_3")
        waitUntil { anki.answers.isNotEmpty() || anki.refused.isNotEmpty() }

        assertEquals(listOf(FakeAnkiDataSource.Answer(11, 0, ease = 3)), anki.answers)
    }

    @Test
    fun scheduledReview_startShowsTheQuestionAndTheAnswerIsTimedFromStart() {
        anki.deck(
            1, "Spanish",
            FakeAnkiDataSource.Card(10, question = "Hola"),
            FakeAnkiDataSource.Card(11, question = "Adiós"),
        )
        select(1)
        start()
        ScheduledReviewSettings.setEnabled(context, true)

        ScheduledReview.onAlarm(context)
        val prompt = scheduledNotification()
        assertEquals("Anki • Spanish", prompt.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
        assertEquals(context.getString(R.string.start), prompt.actions.single().title.toString())

        // The lock screen notification's timer runs since the card was posted: Start restarts it.
        SystemClock.sleep(2_000)
        press(ScheduledReview.ACTION_START)
        waitUntil { scheduledNotificationOrNull()?.actions.isNullOrEmpty() }
        SystemClock.sleep(1_000)
        press(ScheduledReview.ACTION_SHOW_ANSWER)
        val card = storedState().toCardInfo()
        waitUntil { CardReview.isAnswerRevealed(context, card) }

        press(ScheduledReview.ACTION_BUTTON_PREFIX + 3)
        waitUntil { anki.answers.isNotEmpty() && storedState().noteID == 11L }
        assertEquals(listOf(FakeAnkiDataSource.Answer(10, 0, ease = 3)), anki.answers)
        val timeTaken = anki.timesTaken.single()
        assertTrue("time taken: $timeTaken ms", timeTaken in 1_000L until 2_900L)
        waitUntil { scheduledNotificationOrNull() == null }
        // The lock screen notification moved to the next card too.
        assertEquals("Anki • Spanish", notificationTitle())
    }

    @Test
    fun scheduledReview_withoutCardToReview_isNotSent() {
        anki.deck(1, "Spanish")
        select(1)
        start()
        ScheduledReviewSettings.setEnabled(context, true)

        ScheduledReview.onAlarm(context)

        SystemClock.sleep(500)
        assertEquals(null, scheduledNotificationOrNull())
    }

    @Test
    fun answeringFromTheLockScreenNotification_removesAStartedScheduledReview() {
        anki.deck(
            1, "Spanish",
            FakeAnkiDataSource.Card(10, question = "Hola"),
            FakeAnkiDataSource.Card(11, question = "Adiós"),
        )
        select(1)
        start()
        ScheduledReviewSettings.setEnabled(context, true)
        ScheduledReview.onAlarm(context)
        ScheduledReview.start(context)
        scheduledNotification()

        press("ACTION_BUTTON_3")
        waitUntil { anki.answers.isNotEmpty() && storedState().noteID == 11L }

        waitUntil { scheduledNotificationOrNull() == null }
    }

    @Test
    fun openAfterUnlock_withoutTheWallpaper_stillUsesTheNotification() {
        // The test run resets the lock screen card wallpaper: without it, the unlock can't be detected.
        anki.deck(1, "Spanish", FakeAnkiDataSource.Card(10, question = "Hola"))
        select(1)
        ReviewSettings.setReviewMode(context, ReviewSettings.ReviewMode.OPEN_AFTER_UNLOCK)

        start()

        assertFalse(ReviewSettings.opensAfterUnlock(context))
        assertEquals("Anki • Spanish", notificationTitle())
    }

    @Test
    fun reviewScreen_showAnswerThenAnswer_sendsTheEaseAndShowsTheNextCard() {
        anki.deck(
            1, "Spanish",
            FakeAnkiDataSource.Card(10, question = "Hola", answer = "Hello"),
            FakeAnkiDataSource.Card(11, question = "Adiós"),
        )
        select(1)
        start()

        ActivityScenario.launch<ReviewActivity>(ReviewActivity.intent(context)).use {
            waitForText("Hola")
            compose.onNodeWithText(context.getString(R.string.show_answer)).performClick()
            waitForText("Hello")
            compose.onNodeWithText(context.getString(R.string.good)).performClick()

            waitUntil { anki.answers.isNotEmpty() }
            assertEquals(listOf(FakeAnkiDataSource.Answer(10, 0, ease = 3)), anki.answers)
            // Without the wallpaper there is no lock session: the next card comes right away.
            waitForText("Adiós")
        }
    }

    /** The review screen loads its card off the main thread: wait for it to show up. */
    private fun waitForText(text: String) {
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun scheduledNotificationOrNull(): Notification? =
        notificationManager().activeNotifications.firstOrNull { it.id == ScheduledReview.NOTIFICATION_ID }?.notification

    private fun scheduledNotification(): Notification {
        var notification: Notification? = null
        waitUntil {
            notification = scheduledNotificationOrNull()
            notification != null
        }
        return notification!!
    }

    /** An unlock without an answer, as decided by the lock session. */
    private fun rotate(expected: Long) {
        CardStateController.rotateOnUnlock(context)
        waitUntil { storedState().noteID == expected }
    }

    @Test
    fun widgetAnswer_reviewsTheCardAndShowsTheNextOne_withoutEndingTheLockSession() {
        anki.deck(
            1, "Spanish",
            FakeAnkiDataSource.Card(10, question = "Hola"),
            FakeAnkiDataSource.Card(11, question = "Adiós"),
        )
        select(1)
        start()

        pressWidget(StandardCardWidget.ACTION_START)
        pressWidget(StandardCardWidget.ACTION_ANSWER_PREFIX + AnswerButtons.GOOD)
        // The timer restarts for the next card once the answer is handled.
        waitUntil { ReviewTimer.timeTaken(context, CardSync.CardKey(11, 0)) > 0 }

        assertEquals(listOf(FakeAnkiDataSource.Answer(10, 0, ease = 3)), anki.answers)
        assertEquals(11L, storedState().noteID)
        // The home screen always moves on: the lock screen still has its review to do.
        assertFalse(LockSession.prefs(context).getBoolean("answered", false))
        assertFalse(LockSession.isWaitingForNextLock(context))
    }

    @Test
    fun widgetAnswer_sendsTheTimeSinceStartReview() {
        anki.deck(1, "Spanish", FakeAnkiDataSource.Card(10, question = "Hola"))
        select(1)
        start()

        // Without the wallpaper, the timer also starts when the card is posted: Start review restarts it.
        SystemClock.sleep(3_000)
        pressWidget(StandardCardWidget.ACTION_START)
        // The widget handles taps in the background: wait until Start is handled.
        waitUntil {
            context.getSharedPreferences("com.ankidroid.companion.card_widget", Context.MODE_PRIVATE)
                .getLong("active_until", 0) > 0
        }
        SystemClock.sleep(1_000)
        pressWidget(StandardCardWidget.ACTION_ANSWER_PREFIX + AnswerButtons.GOOD)
        waitUntil { anki.answers.isNotEmpty() }

        // A little under 1 s is possible (the timer uses the wall clock); 3 s or more means Start didn't restart it.
        val timeTaken = anki.timesTaken.single()
        assertTrue("time taken: $timeTaken ms", timeTaken in 900L until 3_000L)
    }

    private fun select(vararg deckIds: Long) = DeckSelection.save(context, deckIds.toList())

    /** A widget button: the broadcast goes through the widget provider, like a tap. */
    private fun pressWidget(action: String) {
        context.sendBroadcast(
            Intent(context, StandardCardWidget::class.java).setAction(action).addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        )
    }

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
