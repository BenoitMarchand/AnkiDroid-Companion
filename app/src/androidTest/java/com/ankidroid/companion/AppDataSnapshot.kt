package com.ankidroid.companion

import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import java.io.File

/**
 * Saves the app's data (current card, deck selection, lock session, settings) before a test and restores it
 * after, so the device tests don't change the state of a phone in daily use. The card notification is
 * posted again if it was there before.
 */
class AppDataSnapshot private constructor(
    private val context: Context,
    private val prefs: Map<String, Map<String, *>>,
    private val notificationWasPosted: Boolean,
) {
    fun restore() {
        // Files created by the test are emptied; the others get their saved values back.
        (prefsNames(context) + prefs.keys).forEach { name ->
            val editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
            prefs[name].orEmpty().forEach { (key, value) -> editor.putAny(key, value) }
            editor.commit()
        }
        val manager = notificationManager(context)
        manager.cancel(Notifications.NOTIFICATION_ID)
        if (notificationWasPosted) {
            val state = AnkiDroidHelper(context).storedState
            if (state != null) {
                Notifications.create().showNotification(context, state.takeIf { it.hasCard() }?.toCardInfo(), state.deckName, true)
            }
        }
    }

    companion object {
        /** The app's own preference files; system libraries (WorkManager...) are left alone. */
        private val PREFIXES = listOf("com.ankidroid.companion.", "com.ichi2.anki.api.")

        fun take(context: Context): AppDataSnapshot {
            val prefs = prefsNames(context).associateWith { name ->
                context.getSharedPreferences(name, Context.MODE_PRIVATE).all.toMap()
            }
            val posted = notificationManager(context).activeNotifications.any { it.id == Notifications.NOTIFICATION_ID }
            return AppDataSnapshot(context, prefs, posted)
        }

        private fun prefsNames(context: Context): List<String> =
            File(context.applicationInfo.dataDir, "shared_prefs").listFiles().orEmpty()
                .map { it.name.removeSuffix(".xml") }
                .filter { name -> PREFIXES.any { name.startsWith(it) } }

        private fun notificationManager(context: Context) =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        @Suppress("UNCHECKED_CAST")
        private fun SharedPreferences.Editor.putAny(key: String, value: Any?) {
            when (value) {
                is String -> putString(key, value)
                is Int -> putInt(key, value)
                is Long -> putLong(key, value)
                is Float -> putFloat(key, value)
                is Boolean -> putBoolean(key, value)
                is Set<*> -> putStringSet(key, value as Set<String>)
            }
        }
    }
}
