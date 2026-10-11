package com.ankidroid.companion

import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.SurfaceHolder
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat

/**
 * Live wallpaper that shows the current due card while the device is locked.
 *
 * Meant to be set as the lock screen wallpaper only (Android 14+). When it is also used
 * as the home wallpaper (older Android versions), it only draws the card while the
 * keyguard is shown and the background otherwise.
 *
 * The real (non-preview) engine also drives [LockSession]: an unused notification is removed on
 * unlock and posted again at the next lock.
 */
class CardWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = CardEngine()

    inner class CardEngine : Engine() {
        private var showCard = false
        private var receiverRegistered = false
        private val renderer = CardRenderer(resources.displayMetrics.density)
        private var style = WallpaperStyle()
        private var background: Bitmap? = null

        /** Identifies this engine in the logs: the system can run a preview engine next to the real one. */
        private val engineId get() = "${Integer.toHexString(System.identityHashCode(this))}${if (isPreview) " (preview)" else ""}"

        private val screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                Log.i(TAG, "engine $engineId: screen event ${intent?.action}")
                when (intent?.action) {
                    // The screen turning off means the device is about to be locked:
                    // prepare the card now so it is already there when the screen turns on.
                    Intent.ACTION_SCREEN_OFF -> {
                        showCard = true
                        onLocked()
                    }
                    Intent.ACTION_USER_PRESENT -> {
                        showCard = false
                        onUnlocked()
                    }
                    Intent.ACTION_SCREEN_ON -> showCard = isLocked()
                }
                draw()
            }
        }

        private val stateListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == AnkiDroidHelper.KEY_CURRENT_STATE) {
                Log.i(TAG, "current card changed, redrawing")
                draw()
            }
        }

        private val styleListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> reloadStyle() }

        private val statePrefs: SharedPreferences
            get() = getSharedPreferences(AnkiDroidHelper.STATE_DB, Context.MODE_PRIVATE)

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            showCard = isLocked()
            // Previews (wallpaper picker) always show the card and don't need to follow lock/unlock events;
            // listening there too made every event handled twice.
            if (!isPreview) {
                val filter = IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_USER_PRESENT)
                }
                ContextCompat.registerReceiver(
                    this@CardWallpaperService, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
                )
                receiverRegistered = true
            }
            statePrefs.registerOnSharedPreferenceChangeListener(stateListener)
            WallpaperStyle.prefs(this@CardWallpaperService).registerOnSharedPreferenceChangeListener(styleListener)
            loadStyle()
            Log.i(TAG, "engine $engineId created, lockOnly=${isLockScreenOnly()}")
        }

        override fun onDestroy() {
            Log.i(TAG, "engine $engineId destroyed")
            if (receiverRegistered) {
                unregisterReceiver(screenReceiver)
                receiverRegistered = false
            }
            statePrefs.unregisterOnSharedPreferenceChangeListener(stateListener)
            WallpaperStyle.prefs(this@CardWallpaperService).unregisterOnSharedPreferenceChangeListener(styleListener)
            super.onDestroy()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            if (visible) {
                draw()
            }
        }

        /** Lets the system pick readable colors (e.g. for the clock) on top of our background. */
        @RequiresApi(Build.VERSION_CODES.O_MR1)
        override fun onComputeColors(): WallpaperColors =
            background?.let { WallpaperColors.fromBitmap(it) }
                ?: WallpaperColors(Color.valueOf(style.backgroundColor), null, null)

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            draw()
        }

        private fun loadStyle() {
            style = WallpaperStyle.load(this@CardWallpaperService)
            background = if (style.hasImage) WallpaperStyle.loadBackground(this@CardWallpaperService) else null
        }

        private fun reloadStyle() {
            Log.i(TAG, "engine $engineId: style changed")
            loadStyle()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                notifyColorsChanged()
            }
            draw()
        }

        private fun isLocked(): Boolean =
            (getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked

        private fun isLockScreenOnly(): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                wallpaperFlags == WallpaperManager.FLAG_LOCK

        private fun draw() {
            val holder = surfaceHolder
            val canvas = try {
                holder.lockCanvas()
            } catch (e: IllegalStateException) {
                null
            } ?: return

            try {
                val content = if (isPreview || isLockScreenOnly() || showCard) currentContent() else null
                renderer.draw(canvas, canvas.width, canvas.height, style, background, content)
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
        }

        private fun currentContent(): CardContent {
            val state = AnkiDroidHelper(this@CardWallpaperService).storedState
                ?: return CardContent(getString(R.string.wallpaper_not_started))
            if (!state.hasCard()) {
                return CardContent(getString(R.string.all_cards_done))
            }
            // A card stored by an older version may have nothing left to show (e.g. a sound-only question).
            val question = CardText.clean(state.question).ifEmpty { return CardContent(getString(R.string.wallpaper_not_started)) }
            return CardContent(question, state.deckName.replace("::", " › "))
        }

        private fun onLocked() {
            if (!LockSession.onLocked(this@CardWallpaperService)) return
            // The notification was removed at the last unlock: show the current card again.
            val state = AnkiDroidHelper(this@CardWallpaperService).storedState ?: return
            if (!state.hasCard()) return
            Log.i(TAG, "posting the card notification again")
            Notifications.create().showNotification(this@CardWallpaperService, state.toCardInfo(), state.deckName, true)
        }

        private fun onUnlocked() {
            val hasCard = AnkiDroidHelper(this@CardWallpaperService).storedState?.hasCard() == true
            if (LockSession.onUnlocked(this@CardWallpaperService, hasCard)) {
                Log.i(TAG, "card not answered while locked, removing the notification")
                (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(Notifications.NOTIFICATION_ID)
            }
        }
    }

    companion object {
        private const val TAG = "CardWallpaper"
    }
}
