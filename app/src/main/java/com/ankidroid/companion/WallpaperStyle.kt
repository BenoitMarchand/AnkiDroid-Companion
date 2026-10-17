package com.ankidroid.companion

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.util.Log
import java.io.File

/**
 * How the card looks on the lock screen or on the home screen widget ([Target]: each has its own style). Edited in
 * [SettingsActivity], drawn by [CardRenderer].
 */
data class WallpaperStyle(
    val backgroundColor: Int = DEFAULT_BACKGROUND,
    val textColor: Int = Color.WHITE,
    val textSize: TextSize = TextSize.MEDIUM,
    /** Whether a background image was picked; it is stored in [backgroundFile]. */
    val hasImage: Boolean = false,
    /** Changes when a new image is picked, so the wallpaper reloads it even if [hasImage] was already true. */
    val imageVersion: Long = 0,
    /** How the background image is filtered so the text stays readable. */
    val filter: PictureFilter = PictureFilter.DEFAULT,
    /** Strength of [filter], in percent (0 to [PictureFilter.MAX_INTENSITY]). */
    val filterIntensity: Int = PictureFilter.DEFAULT_INTENSITY,
    /** Whether the card's answer is shown under the question (the notification then shows the answer too). */
    val showAnswer: Boolean = false,
    /** Widget only: no background, the home screen wallpaper shows through (faded by [filter] if it is [PictureFilter.DARKEN]). */
    val seeThrough: Boolean = false,
) {
    enum class TextSize(val scale: Float) { SMALL(0.8f), MEDIUM(1f), LARGE(1.25f) }

    /** What the style is for: each has its own preferences and background picture. */
    enum class Target(val prefs: String, val imageFile: String) {
        LOCK_SCREEN("com.ankidroid.companion.wallpaper_style", "wallpaper_background.jpg"),
        WIDGET("com.ankidroid.companion.widget_style", "widget_background.jpg"),
    }

    /** The picture filters this style can use: see-through, only the fade; on the widget, none drawn around the text. */
    fun availableFilters(target: Target): List<PictureFilter> = PictureFilter.available(widget = target == Target.WIDGET, seeThrough)

    companion object {
        val DEFAULT_BACKGROUND = Color.rgb(0x12, 0x12, 0x12)

        private const val KEY_BACKGROUND = "background_color"
        private const val KEY_TEXT_COLOR = "text_color"
        private const val KEY_TEXT_SIZE = "text_size"
        private const val KEY_HAS_IMAGE = "has_image"
        private const val KEY_IMAGE_VERSION = "image_version"
        private const val KEY_FILTER = "picture_filter"
        private const val KEY_FILTER_INTENSITY = "picture_filter_intensity"
        private const val KEY_SHOW_ANSWER = "show_answer"
        private const val KEY_SEE_THROUGH = "see_through"

        /** Images are scaled down to this size: enough for a phone screen, small enough to keep in memory. */
        private const val MAX_IMAGE_SIZE = 2400

        fun prefs(context: Context, target: Target = Target.LOCK_SCREEN): SharedPreferences =
            context.getSharedPreferences(target.prefs, Context.MODE_PRIVATE)

        fun load(context: Context, target: Target = Target.LOCK_SCREEN): WallpaperStyle {
            val prefs = prefs(context, target)
            // Until changed, the widget keeps the system's light or dark colors.
            val widget = target == Target.WIDGET
            val style = WallpaperStyle(
                backgroundColor = prefs.getInt(KEY_BACKGROUND, if (widget) context.getColor(R.color.widget_background) else DEFAULT_BACKGROUND),
                textColor = prefs.getInt(KEY_TEXT_COLOR, if (widget) context.getColor(R.color.primaryText) else Color.WHITE),
                textSize = TextSize.entries.firstOrNull { it.name == prefs.getString(KEY_TEXT_SIZE, null) } ?: TextSize.MEDIUM,
                hasImage = prefs.getBoolean(KEY_HAS_IMAGE, false) && backgroundFile(context, target).exists(),
                imageVersion = prefs.getLong(KEY_IMAGE_VERSION, 0),
                filter = PictureFilter.entries.firstOrNull { it.name == prefs.getString(KEY_FILTER, null) } ?: PictureFilter.DEFAULT,
                filterIntensity = prefs.getInt(KEY_FILTER_INTENSITY, PictureFilter.DEFAULT_INTENSITY)
                    .coerceIn(0, PictureFilter.MAX_INTENSITY),
                showAnswer = prefs.getBoolean(KEY_SHOW_ANSWER, false),
                seeThrough = widget && prefs.getBoolean(KEY_SEE_THROUGH, false),
            )
            // A filter the style can't use (another background chosen since) falls back to the default one.
            return if (style.filter in style.availableFilters(target)) style else style.copy(filter = PictureFilter.DEFAULT)
        }

        /** Whether the style was ever changed in Settings (the widget keeps the system colors until then). */
        fun isCustomized(context: Context, target: Target): Boolean = prefs(context, target).all.isNotEmpty()

        fun save(context: Context, style: WallpaperStyle, target: Target = Target.LOCK_SCREEN) {
            prefs(context, target).edit()
                .putInt(KEY_BACKGROUND, style.backgroundColor)
                .putInt(KEY_TEXT_COLOR, style.textColor)
                .putString(KEY_TEXT_SIZE, style.textSize.name)
                .putBoolean(KEY_HAS_IMAGE, style.hasImage)
                .putLong(KEY_IMAGE_VERSION, style.imageVersion)
                .putString(KEY_FILTER, style.filter.name)
                .putInt(KEY_FILTER_INTENSITY, style.filterIntensity)
                .putBoolean(KEY_SHOW_ANSWER, style.showAnswer)
                .putBoolean(KEY_SEE_THROUGH, style.seeThrough)
                .apply()
        }

        fun backgroundFile(context: Context, target: Target = Target.LOCK_SCREEN) = File(context.filesDir, target.imageFile)

        /** Decodes the background image, or null if there is none. Call off the main thread. */
        fun loadBackground(context: Context, target: Target = Target.LOCK_SCREEN): Bitmap? {
            val file = backgroundFile(context, target)
            return if (file.exists()) BitmapFactory.decodeFile(file.path) else null
        }

        /**
         * Copies the picked image into the app's storage, scaled down, so it stays available even if the
         * original is deleted. Call off the main thread. Returns false if the image can't be read.
         */
        fun importBackground(context: Context, uri: Uri, target: Target = Target.LOCK_SCREEN): Boolean {
            return try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                var sampleSize = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sampleSize > MAX_IMAGE_SIZE) sampleSize *= 2
                val bitmap = context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sampleSize })
                } ?: return false
                backgroundFile(context, target).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                bitmap.recycle()
                true
            } catch (e: Exception) {
                Log.e("WallpaperStyle", "Could not import the background image", e)
                false
            }
        }

        fun removeBackground(context: Context, target: Target = Target.LOCK_SCREEN) {
            backgroundFile(context, target).delete()
        }
    }
}
