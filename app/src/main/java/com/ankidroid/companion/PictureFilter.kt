package com.ankidroid.companion

/**
 * How the background picture is filtered so the card text stays readable on it. Kept free of Android classes
 * so it can be unit tested; [CardRenderer] applies it.
 */
enum class PictureFilter {
    /** Darkens (or lightens, for dark text) the whole picture. */
    DARKEN,

    /** Blurs the picture. */
    BLUR,

    /** Darkens (or lightens) only a band behind the text. */
    GRADIENT,

    /** A rounded, semi-transparent panel behind the question and the deck name. */
    PANEL,

    /** The picture as is: the text only has a shadow. */
    NONE;

    companion object {
        val DEFAULT = DARKEN
        const val DEFAULT_INTENSITY = 35
        const val MAX_INTENSITY = 80

        /** The blur is computed on a copy scaled down by this factor: much faster, and smoother once scaled up. */
        const val BLUR_DOWNSCALE = 4

        /** Blur radius at the maximum intensity, in pixels of the scaled down copy. */
        private const val MAX_BLUR_RADIUS = 24

        /** Height of the gradient's fade above and below the text area, as a fraction of the screen height. */
        const val GRADIENT_FADE = 0.12f

        /** Opacity (0–255) of the darkening, gradient or panel color for an intensity in percent. */
        fun alpha(intensity: Int): Int = intensity.coerceIn(0, 100) * 255 / 100

        /** Blur radius, in pixels of the scaled down copy, for an intensity in percent. */
        fun blurRadius(intensity: Int): Int =
            intensity.coerceIn(0, MAX_INTENSITY) * MAX_BLUR_RADIUS / MAX_INTENSITY
    }
}

/** A vertical gradient: transparent at [top], full from [solidTop] to [solidBottom], transparent at [bottom]. */
data class GradientBand(val top: Float, val solidTop: Float, val solidBottom: Float, val bottom: Float)

object PictureFilters {
    /** The gradient band behind the text area of a screen of the given height. */
    fun gradientBand(area: CardLayout.Area, screenHeight: Int): GradientBand {
        val fade = screenHeight * PictureFilter.GRADIENT_FADE
        return GradientBand(
            top = (area.top - fade).coerceAtLeast(0f),
            solidTop = area.top,
            solidBottom = area.bottom,
            bottom = (area.bottom + fade).coerceAtMost(screenHeight.toFloat()),
        )
    }

    /**
     * Blurs ARGB [pixels] in place with three box blur passes in each direction (close to a Gaussian blur).
     * Edges are clamped, so the borders don't darken.
     */
    fun boxBlur(pixels: IntArray, width: Int, height: Int, radius: Int) {
        if (radius <= 0 || width <= 0 || height <= 0) return
        val buffer = IntArray(pixels.size)
        repeat(3) {
            blurPass(pixels, buffer, width, height, radius, horizontal = true)
            blurPass(buffer, pixels, width, height, radius, horizontal = false)
        }
    }

    /** One box blur pass with a running sum, from [src] to [dst]. */
    private fun blurPass(src: IntArray, dst: IntArray, width: Int, height: Int, radius: Int, horizontal: Boolean) {
        val lines = if (horizontal) height else width
        val length = if (horizontal) width else height
        val window = 2 * radius + 1
        for (line in 0 until lines) {
            fun index(i: Int): Int {
                val clamped = i.coerceIn(0, length - 1)
                return if (horizontal) line * width + clamped else clamped * width + line
            }
            var a = 0
            var r = 0
            var g = 0
            var b = 0
            for (i in -radius..radius) {
                val p = src[index(i)]
                a += p ushr 24; r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF
            }
            for (i in 0 until length) {
                dst[index(i)] = ((a / window) shl 24) or ((r / window) shl 16) or ((g / window) shl 8) or (b / window)
                val removed = src[index(i - radius)]
                val added = src[index(i + radius + 1)]
                a += (added ushr 24) - (removed ushr 24)
                r += ((added shr 16) and 0xFF) - ((removed shr 16) and 0xFF)
                g += ((added shr 8) and 0xFF) - ((removed shr 8) and 0xFF)
                b += (added and 0xFF) - (removed and 0xFF)
            }
        }
    }
}
