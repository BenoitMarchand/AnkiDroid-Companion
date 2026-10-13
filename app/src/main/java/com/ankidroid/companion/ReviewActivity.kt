package com.ankidroid.companion

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.ankidroid.companion.ui.theme.AnkiNotificationsTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Review screen of "Open after unlock" ([ReviewSettings.ReviewMode]): opened by the lock screen wallpaper when the
 * phone is unlocked, with the card that was on the lock screen: question, "Show answer", then the answer and the
 * answer buttons. Closed when it is left; closing it without an answer counts as an unanswered card ("Change
 * the card at every unlock").
 */
class ReviewActivity : ComponentActivity() {

    /** What the screen shows. */
    sealed interface ReviewState {
        data object Loading : ReviewState
        data class Card(val card: CardInfo, val revealed: Boolean, val busy: Boolean = false) : ReviewState
        data object Done : ReviewState
        data object NotStarted : ReviewState

        /** This session's card was answered: the next one comes at the next unlock. */
        data object WaitingForNextUnlock : ReviewState
    }

    private var state by mutableStateOf<ReviewState>(ReviewState.Loading)

    /** Whether a card was answered since the screen was opened. */
    private var answered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            AnkiNotificationsTheme {
                ReviewScreen(
                    state = state,
                    onShowAnswer = ::showAnswer,
                    onAnswer = ::answer,
                    onClose = ::finish,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Opened again at a new unlock.
        answered = false
    }

    override fun onResume() {
        super.onResume()
        // The card may have changed since the screen was opened (synced with AnkiDroid).
        if ((state as? ReviewState.Card)?.busy != true) load()
    }

    override fun onStop() {
        super.onStop()
        if (isChangingConfigurations) return
        // Left without an answer: like an unlock without an answer, another card comes at the next lock.
        if (!answered && state is ReviewState.Card && ReviewSettings.isRotating(this)) {
            CardStateController.rotateOnUnlock(this)
        }
        // Not kept in the background: the next unlock opens it again with the card of the lock screen.
        finish()
    }

    private fun load() {
        lifecycleScope.launch {
            state = withContext(Dispatchers.IO) { currentState() }
        }
    }

    private fun currentState(): ReviewState {
        val context = applicationContext
        if (LockSession.isWaitingForNextLock(context)) return ReviewState.WaitingForNextUnlock
        val stored = AnkiDroidHelper(context).storedState ?: return ReviewState.NotStarted
        if (!stored.hasCard()) return ReviewState.Done
        val card = stored.toCardInfo()
        // The card is seen from now on: the review time runs from here (the unlock cleared the timer).
        ReviewTimer.onCardSeen(context, CardSync.CardKey(card.noteID, card.cardOrd))
        // The answer already seen on the lock screen: no "Show answer", like the notification.
        val revealed = CardReview.isAnswerRevealed(context, card) || CardReview.isAnswerOnWallpaper(context)
        return ReviewState.Card(card, revealed)
    }

    private fun showAnswer() {
        val current = state as? ReviewState.Card ?: return
        state = current.copy(revealed = true)
        lifecycleScope.launch(Dispatchers.IO) { CardReview.revealAnswer(applicationContext) }
    }

    private fun answer(button: Int) {
        val current = state as? ReviewState.Card ?: return
        if (current.busy) return
        state = current.copy(busy = true)
        answered = true
        lifecycleScope.launch {
            state = withContext(Dispatchers.IO) {
                try {
                    CardReview.answer(applicationContext, button)
                } catch (e: Exception) {
                    // AnkiDroid unavailable (uninstalled, API disabled, permission revoked): the card stays.
                    Log.e("Notifications", "answering from the review screen failed", e)
                    answered = false
                }
                currentState()
            }
            // One card per unlock: show the message for a moment, then get out of the way.
            if (state == ReviewState.WaitingForNextUnlock) {
                delay(CLOSE_DELAY_MS)
                finish()
            }
        }
    }

    companion object {
        private const val CLOSE_DELAY_MS = 1500L

        fun intent(context: Context): Intent =
            Intent(context, ReviewActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}

@Composable
private fun ReviewScreen(
    state: ReviewActivity.ReviewState,
    onShowAnswer: () -> Unit,
    onAnswer: (Int) -> Unit,
    onClose: () -> Unit,
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val deck = (state as? ReviewActivity.ReviewState.Card)?.card?.deckName?.replace("::", " › ").orEmpty()
                Text(
                    deck,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.review_close))
                }
            }
            when (state) {
                ReviewActivity.ReviewState.Loading -> Centered { CircularProgressIndicator() }
                ReviewActivity.ReviewState.Done -> Message(stringResource(R.string.all_cards_done), onClose)
                ReviewActivity.ReviewState.NotStarted -> Message(stringResource(R.string.review_no_card), onClose)
                ReviewActivity.ReviewState.WaitingForNextUnlock -> Message(stringResource(R.string.next_card_at_next_unlock), onClose)
                is ReviewActivity.ReviewState.Card -> CardReviewContent(state, onShowAnswer, onAnswer)
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.CardReviewContent(
    state: ReviewActivity.ReviewState.Card,
    onShowAnswer: () -> Unit,
    onAnswer: (Int) -> Unit,
) {
    Column(
        Modifier
            .weight(1f)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Text(
            CardText.clean(state.card.q),
            Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        if (state.revealed) {
            HorizontalDivider()
            Text(
                CardText.clean(state.card.a),
                Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
        }
    }
    if (!state.revealed) {
        Button(onClick = onShowAnswer, Modifier.fillMaxWidth().height(56.dp)) {
            Text(stringResource(R.string.show_answer))
        }
    } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AnswerButtons.visibleButtons(state.card.buttonCount).forEach { button ->
                val (label, background, text) = EASE_STYLES.getValue(button)
                Button(
                    onClick = { onAnswer(button) },
                    enabled = !state.busy,
                    modifier = Modifier.weight(1f).height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colorResource(background),
                        contentColor = colorResource(text),
                    ),
                    contentPadding = ButtonDefaults.TextButtonContentPadding,
                ) { Text(stringResource(label), maxLines = 1) }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
}

/** Label, background and text color of each answer button: the same soft tones as the notification. */
private val EASE_STYLES = mapOf(
    AnswerButtons.AGAIN to Triple(R.string.again, R.color.again_button_bg, R.color.again_button_text),
    AnswerButtons.HARD to Triple(R.string.hard, R.color.hard_button_bg, R.color.hard_button_text),
    AnswerButtons.GOOD to Triple(R.string.good, R.color.good_button_bg, R.color.good_button_text),
    AnswerButtons.EASY to Triple(R.string.easy, R.color.easy_button_bg, R.color.easy_button_text),
)

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.Centered(content: @Composable () -> Unit) {
    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.Message(text: String, onClose: () -> Unit) {
    Centered {
        Text(text, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
    }
    Button(onClick = onClose, Modifier.fillMaxWidth().height(56.dp)) { Text(stringResource(R.string.review_close)) }
    Spacer(Modifier.height(8.dp))
}
