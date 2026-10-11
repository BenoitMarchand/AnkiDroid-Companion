package com.ankidroid.companion

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.util.Log
import java.io.File

/** How the lock screen card looks. Edited in [WallpaperSettingsActivity], drawn by [CardRenderer]. */
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
) {
    enum class TextSize(val scale: Float) { SMALL(0.8f), MEDIUM(1f), LARGE(1.25f) }

    companion object {
        val DEFAULT_BACKGROUND = Color.rgb(0x12, 0x12, 0x12)

        const val PREFS = "com.ankidroid.companion.wallpaper_style"
        private const val KEY_BACKGROUND = "background_color"
        private const val KEY_TEXT_COLOR = "text_color"
        private const val KEY_TEXT_SIZE = "text_size"
        private const val KEY_HAS_IMAGE = "has_image"
        private const val KEY_IMAGE_VERSION = "image_version"
        private const val KEY_FILTER = "picture_filter"
        private const val KEY_FILTER_INTENSITY = "picture_filter_intensity"
        private const val IMAGE_FILE = "wallpaper_background.jpg"

        /** Images are scaled down to this size: enough for a phone screen, small enough to keep in memory. */
        private const val MAX_IMAGE_SIZE = 2400

        fun prefs(context: Context): SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        fun load(context: Context): WallpaperStyle {
            val prefs = prefs(context)
            return WallpaperStyle(
                backgroundColor = prefs.getInt(KEY_BACKGROUND, DEFAULT_BACKGROUND),
                textColor = prefs.getInt(KEY_TEXT_COLOR, Color.WHITE),
                textSize = TextSize.entries.firstOrNull { it.name == prefs.getString(KEY_TEXT_SIZE, null) } ?: TextSize.MEDIUM,
                hasImage = prefs.getBoolean(KEY_HAS_IMAGE, false) && backgroundFile(context).exists(),
                imageVersion = prefs.getLong(KEY_IMAGE_VERSION, 0),
                filter = PictureFilter.entries.firstOrNull { it.name == prefs.getString(KEY_FILTER, null) } ?: PictureFilter.DEFAULT,
                filterIntensity = prefs.getInt(KEY_FILTER_INTENSITY, PictureFilter.DEFAULT_INTENSITY)
                    .coerceIn(0, PictureFilter.MAX_INTENSITY),
            )
        }

        fun save(context: Context, style: WallpaperStyle) {
            prefs(context).edit()
                .putInt(KEY_BACKGROUND, style.backgroundColor)
                .putInt(KEY_TEXT_COLOR, style.textColor)
                .putString(KEY_TEXT_SIZE, style.textSize.name)
                .putBoolean(KEY_HAS_IMAGE, style.hasImage)
                .putLong(KEY_IMAGE_VERSION, style.imageVersion)
                .putString(KEY_FILTER, style.filter.name)
                .putInt(KEY_FILTER_INTENSITY, style.filterIntensity)
                .apply()
        }

        fun backgroundFile(context: Context) = File(context.filesDir, IMAGE_FILE)

        /** Decodes the background image, or null if there is none. Call off the main thread. */
        fun loadBackground(context: Context): Bitmap? {
            val file = backgroundFile(context)
            return if (file.exists()) BitmapFactory.decodeFile(file.path) else null
        }

        /**
         * Copies the picked image into the app's storage, scaled down, so it stays available even if the
         * original is deleted. Call off the main thread. Returns false if the image can't be read.
         */
        fun importBackground(context: Context, uri: Uri): Boolean {
            return try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                var sampleSize = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sampleSize > MAX_IMAGE_SIZE) sampleSize *= 2
                val bitmap = context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sampleSize })
                } ?: return false
                backgroundFile(context).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                bitmap.recycle()
                true
            } catch (e: Exception) {
                Log.e("WallpaperStyle", "Could not import the background image", e)
                false
            }
        }

        fun removeBackground(context: Context) {
            backgroundFile(context).delete()
        }
    }
}
