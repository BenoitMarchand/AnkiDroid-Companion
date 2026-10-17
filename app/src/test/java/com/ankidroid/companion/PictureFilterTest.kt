package com.ankidroid.companion

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PictureFilterTest {

    @Test
    fun available_lockScreenHasEveryFilter() {
        assertEquals(PictureFilter.entries, PictureFilter.available(widget = false, seeThrough = false))
    }

    @Test
    fun available_widgetHasNoneDrawnAroundTheText() {
        assertEquals(
            listOf(PictureFilter.DARKEN, PictureFilter.BLUR, PictureFilter.NONE),
            PictureFilter.available(widget = true, seeThrough = false),
        )
    }

    @Test
    fun available_seeThroughCanOnlyFade() {
        assertEquals(listOf(PictureFilter.DARKEN, PictureFilter.NONE), PictureFilter.available(widget = true, seeThrough = true))
    }

    @Test
    fun defaultIsTheFormerFixedDarkening() {
        assertEquals(PictureFilter.DARKEN, PictureFilter.DEFAULT)
        // The fixed filter used alpha 90 (35%).
        assertEquals(89, PictureFilter.alpha(PictureFilter.DEFAULT_INTENSITY))
    }

    @Test
    fun alphaFollowsTheIntensity() {
        assertEquals(0, PictureFilter.alpha(0))
        assertEquals(204, PictureFilter.alpha(80))
        assertEquals(0, PictureFilter.alpha(-5))
        assertEquals(255, PictureFilter.alpha(150))
    }

    @Test
    fun blurRadiusGrowsWithTheIntensity() {
        assertEquals(0, PictureFilter.blurRadius(0))
        assertEquals(24, PictureFilter.blurRadius(PictureFilter.MAX_INTENSITY))
        assertTrue(PictureFilter.blurRadius(20) < PictureFilter.blurRadius(60))
        assertEquals(24, PictureFilter.blurRadius(100))
    }

    @Test
    fun gradientCoversTheTextAreaAndFadesAroundIt() {
        val area = CardLayout.textArea(width = 1080, height = 2400, density = 2.5f)
        val band = PictureFilters.gradientBand(area, screenHeight = 2400)

        assertEquals(area.top, band.solidTop, 0.01f)
        assertEquals(area.bottom, band.solidBottom, 0.01f)
        assertEquals(area.top - 2400 * PictureFilter.GRADIENT_FADE, band.top, 0.01f)
        assertEquals(area.bottom + 2400 * PictureFilter.GRADIENT_FADE, band.bottom, 0.01f)
    }

    @Test
    fun gradientStaysOnScreen() {
        val band = PictureFilters.gradientBand(CardLayout.Area(0f, 10f, 100f, 195f), screenHeight = 200)

        assertEquals(0f, band.top, 0.01f)
        assertEquals(200f, band.bottom, 0.01f)
    }

    @Test
    fun blurOfAUniformImageChangesNothing() {
        val pixels = IntArray(20 * 10) { 0xFF336699.toInt() }
        PictureFilters.boxBlur(pixels, 20, 10, radius = 3)

        assertTrue(pixels.all { it == 0xFF336699.toInt() })
    }

    @Test
    fun blurSpreadsABrightPixelSymmetrically() {
        val width = 21
        val height = 21
        val pixels = IntArray(width * height) { 0xFF000000.toInt() }
        pixels[10 * width + 10] = 0xFFFFFFFF.toInt()
        PictureFilters.boxBlur(pixels, width, height, radius = 2)

        val red = { x: Int, y: Int -> (pixels[y * width + x] shr 16) and 0xFF }
        assertTrue(red(10, 10) < 255)
        assertTrue(red(10, 10) >= red(11, 10))
        assertEquals(red(9, 10), red(11, 10))
        assertEquals(red(10, 9), red(10, 11))
        assertEquals(0xFF, pixels[0] ushr 24)
    }

    @Test
    fun zeroRadiusLeavesThePixelsUnchanged() {
        val pixels = intArrayOf(1, 2, 3, 4)
        PictureFilters.boxBlur(pixels, 2, 2, radius = 0)

        assertArrayEquals(intArrayOf(1, 2, 3, 4), pixels)
    }
}
