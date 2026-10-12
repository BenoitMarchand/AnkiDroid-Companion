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
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
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
 * The service also drives [LockSession] (an unused notification is removed on unlock and posted again at the
 * next lock). The screen events are received once per service, not per engine: the system can run several
 * engines at the same time (a preview next to the real one, or an old engine not destroyed yet), and each
 * event must be handled only once.
 */
class CardWallpaperService : WallpaperService() {

    private val engines = mutableSetOf<CardEngine>()

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            val active = engines.filter { !it.isPreview }
            Log.i(TAG, "screen event $action, ${active.size} active engine(s)")
            // Only the wallpaper actually set follows the lock session, not a preview in the wallpaper picker.
            if (active.isEmpty()) return
            when (action) {
                // The screen turning off means the device is about to be locked:
                // prepare the card now so it is already there when the screen turns on.
                Intent.ACTION_SCREEN_OFF -> {
                    ReviewTimer.clear(this@CardWallpaperService)
                    onLocked()
                }
                Intent.ACTION_USER_PRESENT -> onUnlocked()
                // Turned on again before the keyguard locked (lock delay): no USER_PRESENT will come.
                // Otherwise it's the lock screen: SCREEN_OFF may not have been delivered. Android 14+ holds the
                // screen broadcasts back while the process is cached and keeps only the most recent one, so a
                // SCREEN_ON can replace it. The session must still start (onLocked() does nothing the second time).
                Intent.ACTION_SCREEN_ON -> if (isKeyguardLocked()) {
                    onLocked()
                    onCardVisible()
                } else {
                    onUnlocked()
                }
            }
            active.forEach { it.onScreenEvent(action) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onDestroy() {
        unregisterReceiver(screenReceiver)
        super.onDestroy()
    }

    override fun onCreateEngine(): Engine = CardEngine()

    private fun onLocked() {
        if (LockSession.onLocked(this)) {
            // The notification was removed at the last unlock (or held back): show the current card again.
            val state = AnkiDroidHelper(this).storedState
            if (state != null) {
                Log.i(TAG, "posting the card notification again")
                Notifications.create().showNotification(this, state.takeIf { it.hasCard() }?.toCardInfo(), state.deckName, true)
            }
        }
        // AnkiDroid doesn't report changes: check the displayed card is still the right one (throttled).
        CardStateController.syncOnLock(this)
    }

    /** The screen is on with the lock screen shown: the card is seen. */
    private fun onCardVisible() {
        LockSession.onCardVisible(this)
        val card = AnkiDroidHelper(this).storedState?.takeIf { it.hasCard() } ?: return
        ReviewTimer.onCardSeen(this, CardSync.CardKey(card.noteID, card.cardOrd))
    }

    private fun onUnlocked() {
        // Unlocked without an answer: the time spent looking at the card doesn't count.
        ReviewTimer.clear(this)
        val started = AnkiDroidHelper(this).storedState != null
        val unlock = LockSession.onUnlocked(this, started)
        if (unlock.hide) {
            // The notification is only visible while the phone is locked.
            Log.i(TAG, "unlocked, removing the notification")
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(Notifications.NOTIFICATION_ID)
        }
        // "Change the card at every unlock": the card was seen but not answered, show another one at the next lock.
        if (unlock.rotate) CardStateController.rotateOnUnlock(this)
    }

    private fun isInteractive(): Boolean = (getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive

    private fun isKeyguardLocked(): Boolean =
        (getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked

    inner class CardEngine : Engine() {
        private var showCard = false
        private val handler = Handler(Looper.getMainLooper())
        private val renderer = CardRenderer(resources.displayMetrics.density)
        private var style = WallpaperStyle()
        private var background: Bitmap? = null

        /** Identifies this engine in the logs: the system can run a preview engine next to the real one. */
        private val engineId get() = "${Integer.toHexString(System.identityHashCode(this))}${if (isPreview) " (preview)" else ""}"

        fun onScreenEvent(action: String) {
            Log.i(TAG, "engine $engineId: screen event $action")
            showCard = when (action) {
                Intent.ACTION_SCREEN_OFF -> true
                Intent.ACTION_USER_PRESENT -> false
                else -> isLocked()
            }
            draw()
        }

        private val stateListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == AnkiDroidHelper.KEY_CURRENT_STATE) {
                Log.i(TAG, "current card changed, redrawing")
                draw()
            }
        }

        private val styleListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> reloadStyle() }

        private val sessionListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == LockSession.KEY_WAITING) draw()
        }

        private val statePrefs: SharedPreferences
            get() = getSharedPreferences(AnkiDroidHelper.STATE_DB, Context.MODE_PRIVATE)

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            showCard = isLocked()
            engines.add(this)
            statePrefs.registerOnSharedPreferenceChangeListener(stateListener)
            LockSession.prefs(this@CardWallpaperService).registerOnSharedPreferenceChangeListener(sessionListener)
            WallpaperStyle.prefs(this@CardWallpaperService).registerOnSharedPreferenceChangeListener(styleListener)
            loadStyle()
            Log.i(TAG, "engine $engineId created, lockOnly=${isLockScreenOnly()}")
        }

        override fun onDestroy() {
            Log.i(TAG, "engine $engineId destroyed")
            handler.removeCallbacksAndMessages(null)
            engines.remove(this)
            statePrefs.unregisterOnSharedPreferenceChangeListener(stateListener)
            LockSession.prefs(this@CardWallpaperService).unregisterOnSharedPreferenceChangeListener(sessionListener)
            WallpaperStyle.prefs(this@CardWallpaperService).unregisterOnSharedPreferenceChangeListener(styleListener)
            super.onDestroy()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            if (visible) {
                // Shown on the lock screen: the session starts, even if the screen broadcasts were held back.
                // Unlike them, the visibility comes straight from the system.
                if (!isPreview && isLocked()) {
                    Log.i(TAG, "engine $engineId visible on the lock screen")
                    showCard = true
                    onLocked()
                    // Visible with the screen off too (the lock screen is prepared when the screen turns off).
                    if (isInteractive()) onCardVisible()
                }
                draw()
            } else if (!isPreview) {
                // Screen off: the card is no longer seen (SCREEN_OFF may be held back on Android 14+).
                if (!isInteractive()) ReviewTimer.clear(this@CardWallpaperService)
                // Hidden while the keyguard is gone: the phone was unlocked. USER_PRESENT can't be relied on: on
                // Android 14+ the process is cached as soon as this lock screen wallpaper is hidden, and the
                // held-back broadcast can be lost. The keyguard may still be going away: check again shortly after.
                checkUnlocked()
                handler.postDelayed(::checkUnlocked, UNLOCK_CHECK_DELAY_MS)
            }
        }

        private fun checkUnlocked() {
            if (!isLocked()) {
                Log.i(TAG, "engine $engineId hidden, keyguard gone")
                onUnlocked()
            }
        }

        /** Lets the system pick readable colors (e.g. for the clock) on top of our background. */
        @RequiresApi(Build.VERSION_CODES.O_MR1)
        override fun onComputeColors(): WallpaperColors {
            val image = background ?: return WallpaperColors(Color.valueOf(style.backgroundColor), null, null)
            // Computed from the filtered picture, so the clock color matches what is displayed.
            val frame = surfaceHolder.surfaceFrame
            val shown = if (frame.width() > 0 && frame.height() > 0) {
                renderer.filteredBackground(frame.width(), frame.height(), style, image)
            } else {
                image
            }
            return WallpaperColors.fromBitmap(shown)
        }

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
            // This session's card was answered: the next one comes at the next lock.
            if (!isPreview && LockSession.isWaitingForNextLock(this@CardWallpaperService)) {
                return CardContent(getString(R.string.next_card_at_next_unlock))
            }
            if (!state.hasCard()) {
                return CardContent(getString(R.string.all_cards_done))
            }
            // A card stored by an older version may have nothing left to show (e.g. a sound-only question).
            val question = CardText.clean(state.question).ifEmpty { return CardContent(getString(R.string.wallpaper_not_started)) }
            return CardContent(question, state.deckName.replace("::", " › "), CardText.clean(state.answer))
        }
    }

    companion object {
        private const val TAG = "CardWallpaper"
        private const val UNLOCK_CHECK_DELAY_MS = 500L

        /** Whether the card wallpaper is set on the lock screen (Android 14+: lock only, or on both screens). */
        @JvmStatic
        fun isActive(context: Context): Boolean {
            val wallpaperManager = WallpaperManager.getInstance(context)
            // On Android 14+ the lock screen can have its own live wallpaper; null means it shows the home one.
            val lockWallpaper = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                wallpaperManager.getWallpaperInfo(WallpaperManager.FLAG_LOCK) ?: wallpaperManager.wallpaperInfo
            } else {
                wallpaperManager.wallpaperInfo
            }
            return lockWallpaper?.packageName == context.packageName &&
                lockWallpaper.serviceName == CardWallpaperService::class.java.name
        }
    }
}
