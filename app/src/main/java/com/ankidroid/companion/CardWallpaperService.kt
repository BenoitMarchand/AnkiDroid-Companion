package com.ankidroid.companion

import android.app.KeyguardManager
import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.service.wallpaper.WallpaperService
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import android.view.SurfaceHolder
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat

/**
 * Live wallpaper that shows the current due card while the device is locked.
 *
 * Meant to be set as the lock screen wallpaper only (Android 14+). When it is also used
 * as the home wallpaper (older Android versions), it only draws the card while the
 * keyguard is shown and a plain background otherwise.
 */
class CardWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = CardEngine()

    inner class CardEngine : Engine() {
        private var showCard = false

        private val screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                Log.i(TAG, "screen event: ${intent?.action}")
                when (intent?.action) {
                    // The screen turning off means the device is about to be locked:
                    // prepare the card now so it is already there when the screen turns on.
                    Intent.ACTION_SCREEN_OFF -> showCard = true
                    Intent.ACTION_USER_PRESENT -> showCard = false
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

        private val statePrefs: SharedPreferences
            get() = getSharedPreferences(AnkiDroidHelper.STATE_DB, Context.MODE_PRIVATE)

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            showCard = isLocked()
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            ContextCompat.registerReceiver(
                this@CardWallpaperService, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
            )
            statePrefs.registerOnSharedPreferenceChangeListener(stateListener)
            Log.i(TAG, "engine created, preview=$isPreview, lockOnly=${isLockScreenOnly()}")
        }

        override fun onDestroy() {
            unregisterReceiver(screenReceiver)
            statePrefs.unregisterOnSharedPreferenceChangeListener(stateListener)
            super.onDestroy()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            if (visible) {
                draw()
            }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
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
                canvas.drawColor(BACKGROUND_COLOR)
                if (isPreview || isLockScreenOnly() || showCard) {
                    drawCard(canvas, currentQuestion())
                }
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
        }

        private fun currentQuestion(): String {
            val state = AnkiDroidHelper(this@CardWallpaperService).storedState
                ?: return "Select a deck in AnkiDroid Companion"
            if (state.noteID == -1L && state.cardOrd == -1) {
                return "Congrats! You've finished the deck!"
            }
            return HtmlCompat.fromHtml(state.question, HtmlCompat.FROM_HTML_MODE_LEGACY).toString().trim()
        }

        private fun drawCard(canvas: Canvas, text: String) {
            val density = resources.displayMetrics.density
            val horizontalMargin = 32 * density
            val textWidth = (canvas.width - 2 * horizontalMargin).toInt()
            // Keep clear of the clock at the top and the notifications/shortcuts at the bottom.
            val areaTop = canvas.height * 0.30f
            val areaHeight = canvas.height * 0.35f

            val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply { color = TEXT_COLOR }
            var textSize = MAX_TEXT_SIZE_DP * density
            var layout: StaticLayout
            do {
                paint.textSize = textSize
                layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, textWidth)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER)
                    .build()
                textSize -= 2 * density
            } while (layout.height > areaHeight && textSize > MIN_TEXT_SIZE_DP * density)

            canvas.save()
            canvas.translate(horizontalMargin, areaTop + (areaHeight - layout.height).coerceAtLeast(0f) / 2)
            layout.draw(canvas)
            canvas.restore()
        }
    }

    companion object {
        private const val TAG = "CardWallpaper"
        private val BACKGROUND_COLOR = Color.rgb(0x12, 0x12, 0x12)
        private const val TEXT_COLOR = Color.WHITE
        private const val MAX_TEXT_SIZE_DP = 40f
        private const val MIN_TEXT_SIZE_DP = 14f
    }
}
