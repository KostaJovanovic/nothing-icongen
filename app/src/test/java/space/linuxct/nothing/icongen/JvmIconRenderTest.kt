package space.linuxct.nothing.icongen

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Composition only: a JVM fixture cannot exercise the installed launcher's renderer. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = android.app.Application::class, sdk = [35])
class JvmIconRenderTest {
    private val light = ThemeColors.Palette(0xFF272B2D.toInt(), 0x66FFFFFF, false)
    private val dark = ThemeColors.Palette(0xFFF3F4F5.toInt(), 0x66000000, true)

    @Test
    fun whiteSourceAlphaIsTintedToActualForegroundInBothModes() {
        val glyph = mask(255)
        try {
            for (palette in listOf(light, dark)) {
                val icon = IconArtwork.buildIcon(palette, glyph)
                try {
                    assertEquals(palette.foreground, icon.getPixel(icon.width / 2, icon.height / 2))
                    assertEquals(0, Color.alpha(icon.getPixel(0, 0)))
                    assertEquals("outside circle, not merely the corner", 0, Color.alpha(icon.getPixel(30, 30)))
                } finally { icon.recycle() }
            }
        } finally { glyph.recycle() }
    }

    @Test
    fun greyAndAntialiasDetailSurviveAsAlphaRatherThanSourceRgb() {
        // Wide patches avoid testing the interpolation at an edge after native geometry mapping.
        val glyph = Bitmap.createBitmap(IconArtwork.ICON_SIZE, IconArtwork.ICON_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(glyph)
        val paint = Paint()
        for ((index, alpha) in listOf(32, 96, 160, 224).withIndex()) {
            paint.color = Color.argb(alpha, 255, 255, 255)
            canvas.drawRect(96f + index * 60, 180f, 144f + index * 60, 252f, paint)
        }
        val transparentPlate = light.copy(background = Color.TRANSPARENT)
        val icon = IconArtwork.buildIcon(transparentPlate, glyph)
        try {
            val levels = listOf(112, 176, 240, 304).map { Color.alpha(icon.getPixel(it, 216)) }
            for ((actual, expected) in levels.zip(listOf(32, 96, 160, 224))) {
                assertEquals("source alpha must not be thresholded", expected, actual)
            }
            assertTrue("alpha detail collapsed: $levels", levels.zipWithNext().all { (a, b) -> a < b })
            // Fully opaque RGB is tested separately; partial alpha incurs premultiplication rounding.
        } finally { icon.recycle(); glyph.recycle() }
    }

    @Test
    fun platePreservesRawTokenAlphaAndHasNoOpaqueSquare() {
        for (alpha in listOf(0, 43, 102, 173, 255)) {
            val palette = light.copy(background = Color.argb(alpha, 61, 109, 157))
            val plate = IconArtwork.buildPlate(palette)
            try {
                assertEquals(alpha, Color.alpha(plate.getPixel(216, 216)))
                assertEquals(0, Color.alpha(plate.getPixel(0, 0)))
                assertEquals(0, Color.alpha(plate.getPixel(431, 431)))
                assertEquals("squircle pixels must not survive outside the circle", 0, Color.alpha(plate.getPixel(30, 30)))
                assertEquals(0, Color.alpha(plate.getPixel(400, 60)))
                assertEquals(alpha, Color.alpha(plate.getPixel(216, 20)))
            } finally { plate.recycle() }
        }
    }

    @Test
    fun nativePolarityChoosesLightMaskForDarkInkNotForDarkModeFlag() {
        val normal = mask(255)
        val alternative = mask(96)
        val glyphs = LauncherGlyphs(normal, alternative)
        try {
            assertSame(alternative, glyphs.forColor(light.foreground))
            assertSame(normal, glyphs.forColor(dark.foreground))
            // Native uses weighted RGB, not red, average RGB, alpha, or the system night flag.
            assertSame(normal, glyphs.forColor(Color.GREEN))
            assertSame(alternative, glyphs.forColor(Color.MAGENTA))
            assertSame(alternative, glyphs.forColor(0xFF808080.toInt()))
            assertSame(normal, glyphs.forColor(0xFF818181.toInt()))
        } finally { glyphs.recycle() }
    }

    @Test
    fun nativePolarityFallsBackToNormalWhenLightMaskIsAbsent() {
        val glyphs = LauncherGlyphs(mask(255))
        try {
            assertSame(glyphs.normal, glyphs.forColor(light.foreground))
            assertSame(glyphs.normal, glyphs.forColor(dark.foreground))
        } finally { glyphs.recycle() }
    }

    private fun mask(alpha: Int): Bitmap =
        Bitmap.createBitmap(IconArtwork.ICON_SIZE, IconArtwork.ICON_SIZE, Bitmap.Config.ARGB_8888).also {
            Canvas(it).drawRect(180f, 180f, 252f, 252f, Paint().apply {
                color = Color.argb(alpha, 255, 255, 255)
            })
        }
}
