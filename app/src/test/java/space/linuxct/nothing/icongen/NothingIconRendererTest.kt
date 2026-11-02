package space.linuxct.nothing.icongen

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [35])
class NothingIconRendererTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before
    fun installLauncherMetadataWithoutLauncherCode() {
        shadowOf(context.packageManager).installPackage(PackageInfo().apply {
            packageName = "com.nothing.launcher"
            versionName = "5.0.6"
            longVersionCode = 50006003L
            applicationInfo = ApplicationInfo().apply { packageName = "com.nothing.launcher" }
        })
    }

    @Test
    fun opaqueColoredLegacyBitmapKeepsEmblemContrastAndNativeGeometry() {
        val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(20, 80, 160))
        Canvas(bitmap).drawRect(48f, 32f, 80f, 96f, paint(Color.WHITE))
        val icon = BitmapDrawable(context.resources, bitmap)
        val original = pixels(bitmap)
        val bounds = Rect(7, 11, 103, 109)
        icon.bounds = bounds
        try {
            NothingIconRenderer(context).use { renderer ->
                val glyph = renderer.glyph(icon)
                try {
                    assertEquals(432, glyph.normal.width)
                    assertEquals(432, glyph.normal.height)
                    assertTrue("white emblem must differ from the blue background",
                        alpha(glyph.normal, 160, 216) - alpha(glyph.normal, 216, 216) > 150)
                    assertEquals("background must not become a full-size opaque square", 0, alpha(glyph.normal, 100, 216))
                    assertEquals(0, alpha(glyph.normal, 216, 100))
                    assertEquals(0, alpha(glyph.normal, 132, 132))
                    assertTrue(alpha(glyph.normal, 216, 140) > 200)
                    assertEquals(bounds, icon.bounds)
                    assertArrayEquals(original, pixels(bitmap))
                } finally { glyph.recycle() }
                assertFalse("input bitmap is caller-owned", bitmap.isRecycled)
            }
        } finally { bitmap.recycle() }
    }

    @Test
    fun transparentLegacyBitmapPreservesSilhouetteAndPartialAlpha() {
        val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawRect(24f, 32f, 104f, 96f, paint(Color.BLACK))
            drawRect(48f, 48f, 80f, 80f, paint(Color.argb(64, 0, 0, 0)).apply {
                xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC)
            })
        }
        try {
            NothingIconRenderer(context).use { renderer ->
                val glyph = renderer.glyph(BitmapDrawable(context.resources, bitmap))
                try {
                    assertEquals(64, alpha(glyph.normal, 216, 216))
                    assertEquals(255, alpha(glyph.normal, 160, 216))
                    assertTrue("rectangular silhouette must not turn into a disk", alpha(glyph.normal, 216, 145) < 10)
                    assertEquals(0, alpha(glyph.normal, 130, 216))
                } finally { glyph.recycle() }
            }
        } finally { bitmap.recycle() }
    }

    @Test
    fun declaredAdaptiveMonoUsesItsAlphaNotColoredAdaptiveLayersOrLuminance() {
        val mono = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        Canvas(mono).apply {
            drawRect(40f, 32f, 64f, 96f, paint(Color.argb(96, 250, 20, 10)))
            drawRect(64f, 32f, 88f, 96f, paint(Color.argb(224, 10, 20, 250)))
        }
        val adaptive = AdaptiveIconDrawable(ColorDrawable(Color.GREEN), ColorDrawable(Color.MAGENTA),
            BitmapDrawable(context.resources, mono))
        adaptive.setBounds(13, 17, 91, 123)
        val outer = Rect(adaptive.bounds)
        val foreground = Rect(adaptive.foreground.bounds)
        val declared = Rect(adaptive.monochrome!!.bounds)
        try {
            NothingIconRenderer(context).use { renderer ->
                val glyph = renderer.glyph(adaptive)
                try {
                    assertEquals(96, alpha(glyph.normal, 195, 216))
                    assertEquals(224, alpha(glyph.normal, 237, 216))
                    assertEquals(0, alpha(glyph.normal, 140, 216))
                    assertEquals(0, alpha(glyph.normal, 216, 130))
                    assertNull("declared masks must not acquire an inverted plate", glyph.light)
                    assertEquals(outer, adaptive.bounds)
                    assertEquals(foreground, adaptive.foreground.bounds)
                    assertEquals(declared, adaptive.monochrome!!.bounds)
                } finally { glyph.recycle() }
                assertFalse(mono.isRecycled)
            }
        } finally { mono.recycle() }
    }

    @Test
    fun complexSparseLegacyArtworkIsRenderedRatherThanRejectedByCoverageHeuristic() {
        val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        // More than 34 quantized RGB buckets, but much less than 60% central coverage.
        for (y in 0 until 8) for (x in 0 until 8) {
            canvas.drawRect(32f + x * 8, 48f + y * 4, 40f + x * 8, 52f + y * 4,
                paint(Color.rgb(x * 32, y * 32, (x + y) % 8 * 32)))
        }
        try {
            NothingIconRenderer(context).use { renderer ->
                val glyph = renderer.glyph(BitmapDrawable(context.resources, bitmap))
                try {
                    assertEquals(0, alpha(glyph.normal, 216, 150))
                    assertTrue(alpha(glyph.normal, 190, 216) > 40)
                    assertNotNull("complex artwork has the dark-ink alternative", glyph.light)
                    assertSame(glyph.normal, glyph.forColor(Color.WHITE))
                    assertSame(glyph.light, glyph.forColor(Color.BLACK))
                    assertTrue("tonal detail must remain visible", uniqueAlpha(glyph.normal).size > 8)
                } finally { glyph.recycle() }
            }
        } finally { bitmap.recycle() }
    }

    @Test
    fun sparseComplexAdaptiveForegroundUsesItsRealBackdropInsteadOfLosingThePlate() {
        val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        for (y in 0 until 8) for (x in 0 until 8) {
            canvas.drawRect(32f + x * 8, 48f + y * 4, 40f + x * 8, 52f + y * 4,
                paint(Color.rgb(x * 32, y * 32, (x + y) % 8 * 32)))
        }
        val adaptive = AdaptiveIconDrawable(ColorDrawable(Color.rgb(20, 80, 160)),
            BitmapDrawable(context.resources, bitmap))
        try {
            NothingIconRenderer(context).use { renderer ->
                val glyph = renderer.glyph(adaptive)
                try {
                    assertTrue("the accepted adaptive plate must survive above the sparse emblem",
                        alpha(glyph.normal, 216, 145) > 40)
                    assertEquals(0, alpha(glyph.normal, 216, 120))
                    assertTrue("foreground tonal detail must survive the backdrop retry",
                        uniqueAlpha(glyph.normal).size > 8)
                    assertNotNull(glyph.light)
                } finally { glyph.recycle() }
            }
        } finally { bitmap.recycle() }
    }

    @Test
    fun directComplexitySamplingDoesNotInventAnInvertedMaskFromInterpolatedEdges() {
        val bitmap = Bitmap.createBitmap(50, 50, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        // All 25 cells align to the 50px sampling grid and stay inside its circular clip.
        // RGB values sit on bucket boundaries, so an extra interpolation pass invents
        // buckets even though the direct drawable has only 25 flat colors.
        for (y in 0 until 5) for (x in 0 until 5) {
            canvas.drawRect(10f + x * 6, 10f + y * 6, 16f + x * 6, 16f + y * 6,
                paint(Color.rgb(x * 32, y * 32, ((x * 3 + y * 5) % 7 + 1) * 32)))
        }
        val enlarged = Bitmap.createScaledBitmap(bitmap, 432, 432, true)
        val doubleSample = Bitmap.createScaledBitmap(enlarged, 50, 50, true)
        try {
            val direct = complexityCounts(bitmap)
            val interpolated = complexityCounts(doubleSample)
            assertTrue("fixture must be accepted by the native direct RGB-bucket test: $direct", direct.first <= 34)
            assertTrue("fixture must be accepted by the native direct edge test: $direct", direct.second <= 750)
            assertTrue("fixture must expose double-interpolation bucket inflation: $interpolated",
                interpolated.first > 34)
            NothingIconRenderer(context).use { renderer ->
                val source = BitmapDrawable(context.resources, bitmap).apply { isFilterBitmap = true }
                val glyph = renderer.glyph(source)
                try {
                    assertNull("direct sample is not complex; interpolated sample must not select polarity", glyph.light)
                    assertSame(glyph.normal, glyph.forColor(Color.BLACK))
                    assertTrue("flat-color cells still need tonal contrast", uniqueAlpha(glyph.normal).size > 4)
                    assertEquals(0, alpha(glyph.normal, 100, 100))
                } finally { glyph.recycle() }
            }
        } finally { doubleSample.recycle(); enlarged.recycle(); bitmap.recycle() }
    }

    @Test
    fun noConstantStateDrawableRestoresNestedChildAndOpticalBounds() {
        val child = ShapeWithoutState()
        val inset = InsetDrawable(child, 0.2f)
        val outer = AdaptiveIconDrawable(null, inset, inset)
        outer.setBounds(7, 9, 107, 149)
        // Deliberately noncanonical child bounds detect restoring only the parent's Rect.
        child.setBounds(31, 37, 71, 97)
        val beforeOuter = Rect(outer.bounds)
        val beforeInset = Rect(inset.bounds)
        val beforeChild = Rect(child.bounds)
        val beforeOptical = inset.opticalInsets
        NothingIconRenderer(context).use { renderer ->
            val glyph = renderer.glyph(outer)
            try {
                assertEquals(255, alpha(glyph.normal, 216, 216))
                assertEquals(0, alpha(glyph.normal, 100, 100))
                assertEquals(beforeOuter, outer.bounds)
                assertEquals(beforeInset, inset.bounds)
                assertEquals(beforeChild, child.bounds)
                assertEquals(beforeOptical, inset.opticalInsets)
            } finally { glyph.recycle() }
        }
    }

    @Test
    fun alreadyNormalizedDeclaredMonoKeepsItsOriginalSizeWithoutAnotherAdaptiveScale() {
        // A 36px silhouette plus the 2px measurement margin on each side measures .40.
        // The declared normalization dead band deliberately preserves this artwork as-is.
        val mono = InsetDrawable(ShapeWithoutState(), 0.32f)
        val adaptive = AdaptiveIconDrawable(ColorDrawable(Color.RED), ColorDrawable(Color.BLUE), mono)
        val expected = Bitmap.createBitmap(432, 432, Bitmap.Config.ALPHA_8)
        try {
            NothingIconRenderer(context).use { renderer ->
                val glyph = renderer.glyph(adaptive)
                try {
                    mono.setBounds(0, 0, 432, 432)
                    mono.draw(Canvas(expected))
                    assertArrayEquals("already-normalized artwork was resized", pixels(expected), pixels(glyph.normal))
                    assertEquals(255, alpha(glyph.normal, 140, 216))
                    assertEquals(0, alpha(glyph.normal, 130, 216))
                } finally { glyph.recycle() }
            }
        } finally { expected.recycle() }
    }

    @Test
    fun lowAlphaLegacyArtworkKeepsItsRealAlphaEvenBelowNativeRejectionThreshold() {
        val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawRect(32f, 32f, 96f, 96f, paint(Color.argb(35, 255, 255, 255)))
        try {
            NothingIconRenderer(context).use { renderer ->
                val glyph = renderer.glyph(BitmapDrawable(context.resources, bitmap))
                try {
                    assertEquals(35, alpha(glyph.normal, 216, 216))
                    assertEquals(35, alpha(glyph.normal, 180, 180))
                    assertEquals(0, alpha(glyph.normal, 120, 216))
                    assertTrue("do not replace the source with an opaque fallback", uniqueAlpha(glyph.normal).max() <= 35)
                } finally { glyph.recycle() }
            }
        } finally { bitmap.recycle() }
    }

    @Test
    fun acceptedComplexArtworkKeepsItsLightMaskWhenFittingDilutesAllSupport() {
        val bitmap = Bitmap.createBitmap(432, 432, Bitmap.Config.ARGB_8888)
        val sourcePixels = IntArray(432 * 432) { index ->
            val x = index % 432 / 54
            val y = index / 432 / 54
            Color.argb(30, x * 32 + 16, y * 32 + 16, (x + y) % 8 * 32 + 16)
        }
        bitmap.setPixels(sourcePixels, 0, 432, 0, 0, 432, 432)
        bitmap.setPixel(48, 216, Color.BLACK)
        bitmap.setPixel(383, 216, Color.BLACK)
        try {
            NothingIconRenderer(context).use { renderer ->
                val glyph = renderer.glyph(BitmapDrawable(context.resources, bitmap))
                try {
                    assertTrue(pixels(glyph.normal).all { Color.alpha(it) <= 40 })
                    val light = requireNotNull(glyph.light)
                    assertEquals(0, alpha(glyph.normal, 216, 100))
                    assertEquals(255, alpha(light, 216, 100))
                    assertSame(light, glyph.forColor(Color.BLACK))
                } finally { glyph.recycle() }
            }
        } finally { bitmap.recycle() }
    }

    @Test
    fun declaredInsetNormalizationDoesNotReplaceZeroIntrinsicWidthWithASquare() {
        val fill = object : Drawable() {
            private val paint = paint(Color.WHITE)
            override fun getIntrinsicWidth(): Int = 0
            override fun getIntrinsicHeight(): Int = 100
            override fun draw(canvas: Canvas) { canvas.drawRect(bounds, paint) }
            override fun setAlpha(alpha: Int) { paint.alpha = alpha }
            override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
            @Suppress("DEPRECATION")
            override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        }
        val source = AdaptiveIconDrawable(ColorDrawable(Color.RED), ColorDrawable(Color.BLUE),
            InsetDrawable(fill, 0))
        NothingIconRenderer(context).use { renderer ->
            val glyph = renderer.glyph(source)
            try {
                assertTrue("zero-width normalization must remain transparent",
                    pixels(glyph.normal).all { Color.alpha(it) == 0 })
                assertNull(glyph.light)
            } finally { glyph.recycle() }
        }
    }

    @Test
    fun subsequentRenderingRecyclingAndCloseCannotMutateEarlierGlyphOrSource() {
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
        val source = BitmapDrawable(context.resources, bitmap)
        val renderer = NothingIconRenderer(context)
        val first = renderer.glyph(source)
        val normal = pixels(first.normal)
        try {
            repeat(3) { renderer.glyph(source).recycle() }
            renderer.close()
            assertArrayEquals(normal, pixels(first.normal))
            assertTrue(alpha(first.normal, 216, 216) > 200)
            assertFalse(bitmap.isRecycled)
            assertEquals(Color.BLACK, bitmap.getPixel(32, 32))
            assertThrows(IllegalStateException::class.java) { renderer.glyph(source) }
        } finally { renderer.close(); first.recycle(); bitmap.recycle() }
    }

    private fun alpha(bitmap: Bitmap, x: Int, y: Int) = Color.alpha(bitmap.getPixel(x, y))
    private fun pixels(bitmap: Bitmap) = IntArray(bitmap.width * bitmap.height).also {
        bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }
    private fun uniqueAlpha(bitmap: Bitmap) = pixels(bitmap).map { Color.alpha(it) }.toSet()
    private fun paint(color: Int) = Paint().apply { this.color = color }

    /** Same bucket/neighbor thresholds as the native 50px complexity detector. */
    private fun complexityCounts(bitmap: Bitmap): Pair<Int, Int> {
        val pixels = pixels(bitmap)
        val colors = HashSet<Int>()
        var edges = 0
        fun distance(a: Int, b: Int): Int =
            kotlin.math.abs(Color.red(a) - Color.red(b)) +
                kotlin.math.abs(Color.green(a) - Color.green(b)) +
                kotlin.math.abs(Color.blue(a) - Color.blue(b))
        for (y in 0 until 50) for (x in 0 until 50) {
            val pixel = pixels[y * 50 + x]
            colors.add(Color.red(pixel) / 32 * 64 + Color.green(pixel) / 32 * 8 + Color.blue(pixel) / 32)
            if (x < 49 && y < 49 &&
                distance(pixel, pixels[y * 50 + x + 1]) + distance(pixel, pixels[(y + 1) * 50 + x]) > 10
            ) edges++
        }
        return colors.size to edges
    }

    private class ShapeWithoutState : Drawable() {
        override fun draw(canvas: Canvas) {
            canvas.drawRect(bounds, Paint().apply { color = Color.BLACK })
        }
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.OPAQUE
        override fun getIntrinsicWidth() = 100
        override fun getIntrinsicHeight() = 100
    }
}
