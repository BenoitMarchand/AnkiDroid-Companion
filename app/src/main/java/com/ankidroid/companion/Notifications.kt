package com.ankidroid.companion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat


class Notifications {
    companion object Factory {
        /** Shown in full on the lock screen, so a card can be answered without unlocking. */
        const val CHANNEL_ID = "lockscreen_review"
        const val NOTIFICATION_ID = 1
        const val ACTION_SHOW_ANSWER = "ACTION_SHOW_ANSWER"

        /** How long "Next card at your next unlock" stays before disappearing. */
        private const val NEXT_CARD_MESSAGE_TIMEOUT_MS = 5_000L
        private const val LEGACY_CHANNEL_ID = "channel_id"

        fun create(): Notifications = Notifications()

        /** Creates the notification channel. Safe to call repeatedly. */
        fun createChannel(context: Context) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.notification_channel_description)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            notificationManager.createNotificationChannel(channel)
            // The lock screen visibility of an existing channel can't be changed: replace the old channel.
            notificationManager.deleteNotificationChannel(LEGACY_CHANNEL_ID)
        }
    }

    /**
     * Posts the card notification, or the "Bravo" notification when [card] is null. The answer and the answer
     * buttons are only shown once "Show answer" was tapped ([CardReview.isAnswerRevealed]).
     *
     * @param replace whether to remove the current notification first, which also closes its expanded view.
     * Pass false to update it in place (e.g. to reveal the answer).
     */
    fun showNotification(context: Context, card: CardInfo?, deckName: String, isSilent: Boolean, replace: Boolean = true) {
        Log.i("Notifications", "showNotification called")
        createChannel(context)
        val builder: NotificationCompat.Builder
        val collapsedView = RemoteViews(context.packageName, R.layout.notification_collapsed)

        // There is a card to show, show a notification with the expanded view.
        if (card != null) {
            LockSession.onNotificationPosted(context)
            val title = context.getString(R.string.notification_title_deck, deckName)
            val revealed = CardReview.isAnswerRevealed(context, card)
            collapsedView.setTextViewText(R.id.textViewCollapsedHeader, CardText.clean(card.q))
            collapsedView.setTextViewText(R.id.textViewCollapsedTitle, title)

            val expandedView = cardView(context, card)
            expandedView.setViewVisibility(R.id.answerGroup, if (revealed) View.VISIBLE else View.GONE)
            expandedView.setViewVisibility(R.id.buttonShowAnswer, if (revealed) View.GONE else View.VISIBLE)
            expandedView.setOnClickPendingIntent(R.id.buttonShowAnswer, createIntent(context, ACTION_SHOW_ANSWER))

            expandedView.setOnClickPendingIntent(R.id.button1, createIntent(context,"ACTION_BUTTON_1"))
            expandedView.setOnClickPendingIntent(R.id.button2, createIntent(context,"ACTION_BUTTON_2"))
            expandedView.setOnClickPendingIntent(R.id.button3, createIntent(context,"ACTION_BUTTON_3"))
            expandedView.setOnClickPendingIntent(R.id.button4, createIntent(context,"ACTION_BUTTON_4"))
            val visibleButtons = AnswerButtons.visibleButtons(card.buttonCount)
            listOf(R.id.button1, R.id.button2, R.id.button3, R.id.button4).forEachIndexed { index, buttonId ->
                expandedView.setViewVisibility(buttonId, if (index + 1 in visibleButtons) View.VISIBLE else View.GONE)
            }

            builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setStyle(NotificationCompat.DecoratedCustomViewStyle())
                .setCustomContentView(collapsedView)
                .setCustomBigContentView(expandedView)
                .setContentTitle(title)
                // No tap action: on the lock screen, tapping expands the notification in place, while opening
                // anything would ask to unlock first.
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setSilent(isSilent)
                .setOnlyAlertOnce(!replace)
                .setOngoing(true)
        } else {
            val title = context.getString(R.string.notification_title)
            collapsedView.setTextViewText(R.id.textViewCollapsedHeader, context.getString(R.string.all_cards_done))
            collapsedView.setTextViewText(R.id.textViewCollapsedTitle, title)

            val expandedView = RemoteViews(context.packageName, R.layout.notification_expanded_empty)
            expandedView.setTextViewText(R.id.textViewEmptyExpandedHeader, context.getString(R.string.all_cards_done))
            expandedView.setTextViewText(R.id.textViewEmptyExpandedContent, context.getString(R.string.all_cards_done_next))

            builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setStyle(NotificationCompat.DecoratedCustomViewStyle())
                .setCustomContentView(collapsedView)
                .setCustomBigContentView(expandedView)
                .setContentTitle(title)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setSilent(isSilent)
                .setOngoing(false)
        }

        val notification: Notification = builder.build()

        val notificationManager: NotificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (replace) {
            notificationManager.cancel(NOTIFICATION_ID) // Cancel the current notification before sending a new one
        }
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * After this session's review: the same notification, updated in place, shows the answered [card] with
     * "Next card at your next unlock" instead of the answer buttons, then disappears by itself.
     */
    fun showNextCardMessage(context: Context, card: CardInfo, deckName: String) {
        createChannel(context)
        val message = context.getString(R.string.next_card_at_next_unlock)
        val title = context.getString(R.string.notification_title_deck, deckName)
        val collapsedView = RemoteViews(context.packageName, R.layout.notification_collapsed)
        collapsedView.setTextViewText(R.id.textViewCollapsedHeader, message)
        collapsedView.setTextViewText(R.id.textViewCollapsedTitle, title)

        val expandedView = cardView(context, card)
        expandedView.setViewVisibility(R.id.answerGroup, View.VISIBLE)
        expandedView.setViewVisibility(R.id.answerButtons, View.GONE)
        expandedView.setViewVisibility(R.id.textViewNextCard, View.VISIBLE)
        expandedView.setViewVisibility(R.id.buttonShowAnswer, View.GONE)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(collapsedView)
            .setCustomBigContentView(expandedView)
            .setContentTitle(title)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setTimeoutAfter(NEXT_CARD_MESSAGE_TIMEOUT_MS)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .build()
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // No cancel first: the notification is updated in place and stays expanded.
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    /** The expanded view with [card]'s question and answer; the caller chooses what is visible. */
    private fun cardView(context: Context, card: CardInfo): RemoteViews {
        val view = RemoteViews(context.packageName, R.layout.notification_expanded_full)
        view.setTextViewText(R.id.textViewExpandedHeader, CardText.clean(card.q))
        view.setTextViewText(R.id.textViewContent, CardText.clean(card.a))
        return view
    }

    private fun createIntent(context: Context, action: String): PendingIntent {
        val intent = Intent(context, NotificationReceiver::class.java)
        intent.action = action
        // A tap must be handled right away, not wait in the background broadcast queue (busy after boot).
        intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
    }

}
