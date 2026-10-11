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

    fun showNotification(context: Context, card: CardInfo?, deckName: String, isSilent: Boolean) {
        Log.i("Notifications", "showNotification called")
        createChannel(context)
        val builder: NotificationCompat.Builder
        val collapsedView = RemoteViews(context.packageName, R.layout.notification_collapsed)

        // There is a card to show, show a notification with the expanded view.
        if (card != null) {
            LockSession.onNotificationPosted(context)
            val question = CardText.clean(card.q)
            val answer = CardText.clean(card.a)
            collapsedView.setTextViewText(R.id.textViewCollapsedHeader, question)
            collapsedView.setTextViewText(R.id.textViewCollapsedTitle, "Anki • $deckName")

            val expandedView = RemoteViews(context.packageName, R.layout.notification_expanded_full)
            expandedView.setTextViewText(R.id.textViewExpandedHeader, question)
            expandedView.setTextViewText(R.id.textViewContent, answer)

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
                .setContentTitle("Anki • $deckName")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setSilent(isSilent)
                .setOngoing(true)
        } else {
            collapsedView.setTextViewText(R.id.textViewCollapsedHeader, context.getString(R.string.all_cards_done))
            collapsedView.setTextViewText(R.id.textViewCollapsedTitle, "Anki")

            val expandedView = RemoteViews(context.packageName, R.layout.notification_expanded_empty)
            expandedView.setTextViewText(R.id.textViewEmptyExpandedHeader, context.getString(R.string.all_cards_done))
            expandedView.setTextViewText(R.id.textViewEmptyExpandedContent, context.getString(R.string.all_cards_done_next))

            builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setStyle(NotificationCompat.DecoratedCustomViewStyle())
                .setCustomContentView(collapsedView)
                .setCustomBigContentView(expandedView)
                .setContentTitle("Anki")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setSilent(isSilent)
                .setOngoing(false)
        }

        val notification: Notification = builder.build()

        val notificationManager: NotificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        notificationManager.cancel(NOTIFICATION_ID) // Cancel the current notification before sending a new one
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun createIntent(context: Context, action: String): PendingIntent {
        val intent = Intent(context, NotificationReceiver::class.java)
        intent.action = action
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
    }
}