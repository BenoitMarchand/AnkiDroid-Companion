package com.ankidroid.companion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.graphics.ColorUtils

/** What the lock screen shows: the question, and an optional smaller line under it (the deck name). */
data class CardContent(val text: String, val subtitle: String? = null)

/** Draws the lock screen card. Used by the live wallpaper and by the preview in the wallpaper settings. */
class CardRenderer(private val density: Float) {

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val scrimPaint = Paint()

    /**
     * @param width the drawn area's width, which may differ from the canvas's (preview drawn scaled down)
     * @param content the card to show, or null to draw the background only (home screen)
     */
    fun draw(canvas: Canvas, width: Int, height: Int, style: WallpaperStyle, background: Bitmap?, content: CardContent?) {
        drawBackground(canvas, width, height, style, background)
        if (content != null && content.text.isNotEmpty()) {
            drawContent(canvas, width, height, style, background != null, content)
        }
    }

    private fun drawBackground(canvas: Canvas, width: Int, height: Int, style: WallpaperStyle, background: Bitmap?) {
        canvas.drawColor(style.backgroundColor)
        if (background == null) return

        // Center crop, like the system wallpaper.
        val scale = maxOf(width.toFloat() / background.width, height.toFloat() / background.height)
        val srcWidth = width / scale
        val srcHeight = height / scale
        val left = (background.width - srcWidth) / 2
        val top = (background.height - srcHeight) / 2
        val src = Rect(left.toInt(), top.toInt(), (left + srcWidth).toInt(), (top + srcHeight).toInt())
        canvas.drawBitmap(background, src, RectF(0f, 0f, width.toFloat(), height.toFloat()), bitmapPaint)

        // Darken (or lighten) the image so the text stays readable.
        scrimPaint.color = if (isLight(style.textColor)) SCRIM_FOR_LIGHT_TEXT else SCRIM_FOR_DARK_TEXT
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)
    }

    private fun drawContent(canvas: Canvas, width: Int, height: Int, style: WallpaperStyle, onImage: Boolean, content: CardContent) {
        val area = CardLayout.textArea(width, height, density)
        val textWidth = area.width.toInt().coerceAtLeast(1)
        val shadowColor = if (isLight(style.textColor)) Color.argb(160, 0, 0, 0) else Color.argb(160, 255, 255, 255)

        val questionPaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            color = style.textColor
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            if (onImage) setShadowLayer(6 * density, 0f, 2 * density, shadowColor)
        }
        val subtitlePaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            color = ColorUtils.setAlphaComponent(style.textColor, 190)
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            textSize = SUBTITLE_SIZE_DP * density * style.textSize.scale
            if (onImage) setShadowLayer(4 * density, 0f, 1 * density, shadowColor)
        }
        val subtitle = content.subtitle?.takeIf { it.isNotEmpty() }?.let { layout(it, subtitlePaint, textWidth, maxLines = 1) }
        val subtitleHeight = subtitle?.let { it.height + SUBTITLE_GAP_DP * density } ?: 0f

        val textSize = CardLayout.fitTextSize(area.height - subtitleHeight, density, style.textSize.scale) { size ->
            questionPaint.textSize = size
            layout(content.text, questionPaint, textWidth).height.toFloat()
        }
        questionPaint.textSize = textSize
        val maxLines = ((area.height - subtitleHeight) / questionPaint.fontSpacing).toInt().coerceAtLeast(1)
        val question = layout(content.text, questionPaint, textWidth, maxLines)

        val top = CardLayout.centeredTop(area, question.height + subtitleHeight)
        canvas.save()
        canvas.translate(area.left, top)
        question.draw(canvas)
        if (subtitle != null) {
            canvas.translate(0f, question.height + SUBTITLE_GAP_DP * density)
            subtitle.draw(canvas)
        }
        canvas.restore()
    }

    private fun layout(text: String, paint: TextPaint, width: Int, maxLines: Int = Int.MAX_VALUE): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, LINE_SPACING)
            .setMaxLines(maxLines)
            .setEllipsize(android.text.TextUtils.TruncateAt.END)
            .build()

    companion object {
        private const val SUBTITLE_SIZE_DP = 16f
        private const val SUBTITLE_GAP_DP = 16f
        private const val LINE_SPACING = 1.1f
        private val SCRIM_FOR_LIGHT_TEXT = Color.argb(90, 0, 0, 0)
        private val SCRIM_FOR_DARK_TEXT = Color.argb(90, 255, 255, 255)

        fun isLight(color: Int) = ColorUtils.calculateLuminance(color) > 0.5
    }
}
