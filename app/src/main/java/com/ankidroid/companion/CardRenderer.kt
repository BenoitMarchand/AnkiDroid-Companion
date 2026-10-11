package com.ankidroid.companion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
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
    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** What [cachedBackground] was computed from: the filter is applied once, not on every redraw. */
    private data class BackgroundKey(
        val source: Bitmap, val width: Int, val height: Int, val filter: PictureFilter, val intensity: Int, val lightText: Boolean,
    )

    private var cachedKey: BackgroundKey? = null
    private var cachedBackground: Bitmap? = null

    /**
     * @param width the drawn area's width, which may differ from the canvas's (preview drawn scaled down)
     * @param content the card to show, or null to draw the background only (home screen)
     */
    fun draw(canvas: Canvas, width: Int, height: Int, style: WallpaperStyle, background: Bitmap?, content: CardContent?) {
        canvas.drawColor(style.backgroundColor)
        if (background != null) {
            canvas.drawBitmap(filteredBackground(width, height, style, background), 0f, 0f, bitmapPaint)
        }
        if (content != null && content.text.isNotEmpty()) {
            drawContent(canvas, width, height, style, background != null, content)
        }
    }

    /**
     * The background image cropped to the screen, with the style's filter applied (except the text panel, which
     * is drawn with the text). Cached until the image, the size or the style changes.
     */
    @Synchronized
    fun filteredBackground(width: Int, height: Int, style: WallpaperStyle, background: Bitmap): Bitmap {
        val key = BackgroundKey(background, width, height, style.filter, style.filterIntensity, isLight(style.textColor))
        cachedBackground?.takeIf { key == cachedKey }?.let { return it }

        val result = Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        drawCropped(canvas, width, height, background)
        val scrimColor = if (key.lightText) Color.BLACK else Color.WHITE
        val alpha = PictureFilter.alpha(style.filterIntensity)
        when (style.filter) {
            PictureFilter.DARKEN -> {
                scrimPaint.shader = null
                scrimPaint.color = ColorUtils.setAlphaComponent(scrimColor, alpha)
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)
            }
            PictureFilter.GRADIENT -> {
                val band = PictureFilters.gradientBand(CardLayout.textArea(width, height, density), height)
                val solid = ColorUtils.setAlphaComponent(scrimColor, alpha)
                val clear = ColorUtils.setAlphaComponent(scrimColor, 0)
                val span = (band.bottom - band.top).coerceAtLeast(1f)
                scrimPaint.shader = LinearGradient(
                    0f, band.top, 0f, band.bottom,
                    intArrayOf(clear, solid, solid, clear),
                    floatArrayOf(0f, (band.solidTop - band.top) / span, (band.solidBottom - band.top) / span, 1f),
                    Shader.TileMode.CLAMP,
                )
                canvas.drawRect(0f, band.top, width.toFloat(), band.bottom, scrimPaint)
                scrimPaint.shader = null
            }
            PictureFilter.BLUR -> blur(canvas, result, style.filterIntensity)
            PictureFilter.PANEL, PictureFilter.NONE -> Unit
        }
        cachedKey = key
        cachedBackground = result
        return result
    }

    /** Center crop, like the system wallpaper. */
    private fun drawCropped(canvas: Canvas, width: Int, height: Int, background: Bitmap) {
        val scale = maxOf(width.toFloat() / background.width, height.toFloat() / background.height)
        val srcWidth = width / scale
        val srcHeight = height / scale
        val left = (background.width - srcWidth) / 2
        val top = (background.height - srcHeight) / 2
        val src = Rect(left.toInt(), top.toInt(), (left + srcWidth).toInt(), (top + srcHeight).toInt())
        canvas.drawBitmap(background, src, RectF(0f, 0f, width.toFloat(), height.toFloat()), bitmapPaint)
    }

    /** Blurs a scaled down copy (fast, no RenderScript) and draws it back scaled up, which smooths it further. */
    private fun blur(canvas: Canvas, image: Bitmap, intensity: Int) {
        val radius = PictureFilter.blurRadius(intensity)
        if (radius == 0) return
        val small = Bitmap.createScaledBitmap(
            image,
            (image.width / PictureFilter.BLUR_DOWNSCALE).coerceAtLeast(1),
            (image.height / PictureFilter.BLUR_DOWNSCALE).coerceAtLeast(1),
            true,
        )
        val pixels = IntArray(small.width * small.height)
        small.getPixels(pixels, 0, small.width, 0, 0, small.width, small.height)
        PictureFilters.boxBlur(pixels, small.width, small.height, radius)
        val blurred = Bitmap.createBitmap(pixels, small.width, small.height, Bitmap.Config.ARGB_8888)
        canvas.drawBitmap(blurred, null, RectF(0f, 0f, image.width.toFloat(), image.height.toFloat()), bitmapPaint)
        small.recycle()
        blurred.recycle()
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
        if (onImage && style.filter == PictureFilter.PANEL) {
            val textBlockWidth = maxOf(question.maxLineWidth(), subtitle?.maxLineWidth() ?: 0f)
            val padding = PANEL_PADDING_DP * density
            val centerX = area.left + textWidth / 2f
            panelPaint.color = ColorUtils.setAlphaComponent(
                if (isLight(style.textColor)) Color.BLACK else Color.WHITE, PictureFilter.alpha(style.filterIntensity),
            )
            val radius = PANEL_RADIUS_DP * density
            canvas.drawRoundRect(
                centerX - textBlockWidth / 2 - padding, top - padding,
                centerX + textBlockWidth / 2 + padding, top + question.height + subtitleHeight + padding,
                radius, radius, panelPaint,
            )
        }
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

    private fun StaticLayout.maxLineWidth(): Float = (0 until lineCount).maxOfOrNull { getLineWidth(it) } ?: 0f

    companion object {
        private const val PANEL_PADDING_DP = 20f
        private const val PANEL_RADIUS_DP = 24f
        private const val SUBTITLE_SIZE_DP = 16f
        private const val SUBTITLE_GAP_DP = 16f
        private const val LINE_SPACING = 1.1f

        fun isLight(color: Int) = ColorUtils.calculateLuminance(color) > 0.5
    }
}
