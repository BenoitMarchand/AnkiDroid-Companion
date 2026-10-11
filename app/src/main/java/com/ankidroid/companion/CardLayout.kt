package com.ankidroid.companion

/**
 * Geometry of the lock screen card, kept free of Android classes so it can be unit tested.
 * Sizes are in pixels.
 */
object CardLayout {
    /** Top of the text area, as a fraction of the screen height: below the lock screen clock. */
    const val AREA_TOP = 0.30f

    /** Bottom of the text area: above the notifications and the bottom shortcuts. */
    const val AREA_BOTTOM = 0.68f

    const val HORIZONTAL_MARGIN_DP = 32f
    const val MAX_TEXT_SIZE_DP = 40f
    const val MIN_TEXT_SIZE_DP = 14f
    private const val TEXT_SIZE_STEP_DP = 2f

    data class Area(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width get() = right - left
        val height get() = bottom - top
    }

    fun textArea(width: Int, height: Int, density: Float): Area {
        val margin = HORIZONTAL_MARGIN_DP * density
        return Area(margin, height * AREA_TOP, width - margin, height * AREA_BOTTOM)
    }

    /**
     * The largest text size, from the maximum down to the minimum (times [scale]), at which the text fits in
     * [availableHeight]. [measureHeight] returns the height of the laid out text at a given size. If the text
     * doesn't fit even at the minimum size, the minimum is returned (the text is then cut).
     */
    fun fitTextSize(availableHeight: Float, density: Float, scale: Float, measureHeight: (textSize: Float) -> Float): Float {
        val min = MIN_TEXT_SIZE_DP * density * scale
        var size = MAX_TEXT_SIZE_DP * density * scale
        while (size > min && measureHeight(size) > availableHeight) {
            size -= TEXT_SIZE_STEP_DP * density * scale
        }
        return size.coerceAtLeast(min)
    }

    /** Top of the text block, so it is centered vertically in the area (or starts at its top if too tall). */
    fun centeredTop(area: Area, blockHeight: Float): Float = area.top + ((area.height - blockHeight) / 2).coerceAtLeast(0f)
}
