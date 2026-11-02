package space.linuxct.nothing.icongen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.VectorDrawable
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import space.linuxct.nothing.icongen.test.R as TestR
import kotlin.math.abs

/** Platform-drawn synthetic probes of every monochrome rendering route. */
@RunWith(AndroidJUnit4::class)
class PortablePipelineParityTest {
    @Test
    fun syntheticIconsMatchNativeMasksAndDayNightDelegatesExactly() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        NativeLauncherOracle.requireLauncher(context)
        val failures = mutableListOf<String>()
        val accepted = mutableListOf<String>()
        val rejected = mutableListOf<String>()
        val palettes = listOf(false, true).map { dark ->
            (if (dark) "night" else "day") to ThemeColors.resolve(context, dark).copy(
                background = if (dark) Color.BLACK else Color.WHITE,
            )
        }
        NativeLauncherOracle(context).use { oracle ->
            IconPipeline(context).use { pipeline ->
                for (fixture in PortablePipelineFixtures.all()) {
                    try {
                        // Separate graphs prevent native normalization from changing the local input.
                        fixture.create(context).use { referenceSource ->
                            fixture.create(context).use { localSource ->
                                val native = oracle.glyph(referenceSource.drawable)
                                try {
                                    val local = pipeline.glyph(localSource.drawable)
                                    try {
                                        if (fixture.expectsRejection) {
                                            rejected.add(fixture.name)
                                            if (native != null) failures.add("${fixture.name} acceptance: native unexpectedly accepted")
                                            requireEmptyMask("${fixture.name} raw-normal/local-after-rejection", local.normal, failures)
                                            if (local.light != null) failures.add("${fixture.name} raw-light/local-after-rejection: expected absent")
                                        } else if (native == null) {
                                            failures.add("${fixture.name} acceptance: native rejected REQUIRED accepted fixture")
                                        } else {
                                            accepted.add(fixture.name)
                                            compare("${fixture.name} raw-normal", native.normal, local.normal, alphaOnly = true, failures)
                                            compare("${fixture.name} raw-light", native.light, local.light, alphaOnly = true, failures)
                                            for ((mode, palette) in palettes) {
                                                val expected = oracle.drawDelegate(native, palette)
                                                try {
                                                    val actual = IconArtwork.buildIcon(palette, local.forColor(palette.foreground))
                                                    try {
                                                        // Only plate rasterization differs: native clipPath vs exported AA circle.
                                                        compare("${fixture.name} final-$mode-common-interior", expected, actual,
                                                            alphaOnly = false, failures, excludePlateBoundary = true)
                                                    } finally { actual.recycle() }
                                                } finally { expected.recycle() }
                                            }
                                        }
                                    } finally { local.recycle() }
                                } finally { native?.recycle() }
                            }
                        }
                    } catch (error: Exception) {
                        failures.add("${fixture.name} exception: ${error.javaClass.name}: ${error.message}")
                    }
                }
            }
        }
        println("SYNTHETIC_PARITY accepted=${accepted.size} rejected=${rejected.size} acceptedFixtures=${accepted.joinToString()} rejectedFixtures=${rejected.joinToString()}")
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private fun compare(
        label: String,
        expected: Bitmap?,
        actual: Bitmap?,
        alphaOnly: Boolean,
        failures: MutableList<String>,
        excludePlateBoundary: Boolean = false,
    ) {
        if (expected == null || actual == null) {
            if (expected !== actual) failures.add("$label availability: native=${expected != null} local=${actual != null}")
            return
        }
        if (expected.width != actual.width || expected.height != actual.height) {
            failures.add("$label dimensions: native=${expected.width}x${expected.height} local=${actual.width}x${actual.height}")
            return
        }
        val reference = IntArray(expected.width * expected.height)
        val rendered = IntArray(reference.size)
        expected.getPixels(reference, 0, expected.width, 0, 0, expected.width, expected.height)
        actual.getPixels(rendered, 0, actual.width, 0, 0, actual.width, actual.height)
        var different = 0
        var maximum = 0
        var first = ""
        for (index in reference.indices) {
            if (excludePlateBoundary) {
                val dx = index % expected.width + .5f - expected.width / 2f
                val dy = index / expected.width + .5f - expected.height / 2f
                // Same common comparison domain as LauncherParityTest, not a glyph tolerance.
                if (abs(kotlin.math.sqrt(dx * dx + dy * dy) - expected.width / 2f) <= 2f) continue
            }
            val a = reference[index]
            val b = rendered[index]
            val delta = if (alphaOnly) abs(Color.alpha(a) - Color.alpha(b)) else maxOf(
                abs(Color.alpha(a) - Color.alpha(b)), abs(Color.red(a) - Color.red(b)),
                abs(Color.green(a) - Color.green(b)), abs(Color.blue(a) - Color.blue(b)),
            )
            if (delta != 0) {
                if (different == 0) first = "(${index % expected.width},${index / expected.width}) native=${pixel(a, alphaOnly)} local=${pixel(b, alphaOnly)}"
                different++
                maximum = maxOf(maximum, delta)
            }
        }
        println("SYNTHETIC_PARITY $label pixels=$different maxDelta=$maximum")
        if (different != 0) failures.add("$label pixels=$different maxDelta=$maximum first=$first")
    }

    private fun pixel(color: Int, alphaOnly: Boolean): String =
        if (alphaOnly) Color.alpha(color).toString() else "0x${Integer.toHexString(color).padStart(8, '0')}"

    private fun requireEmptyMask(label: String, mask: Bitmap, failures: MutableList<String>) {
        val pixels = IntArray(mask.width * mask.height)
        mask.getPixels(pixels, 0, mask.width, 0, 0, mask.width, mask.height)
        val count = pixels.count { Color.alpha(it) != 0 }
        if (count != 0) failures.add("$label expected transparent mask, inkPixels=$count")
    }

}

/** Shared platform-rendered fixtures; also usable by the host's native-graphics test source set. */
internal object PortablePipelineFixtures {
    data class Fixture(
        val name: String,
        val expectsRejection: Boolean = false,
        val create: (Context) -> Source,
    )

    class Source(val drawable: Drawable, private val bitmaps: List<Bitmap> = emptyList()) : AutoCloseable {
        override fun close() { bitmaps.forEach { it.recycle() } }
    }

    fun all(): List<Fixture> = listOf(
        Fixture("declared-bitmap-odd-square-semi-alpha") { context -> declaredBitmap(context, 63, 63) },
        Fixture("declared-bitmap-odd-wide-semi-alpha") { context -> declaredBitmap(context, 79, 43) },
        Fixture("declared-bitmap-odd-tall-semi-alpha") { context -> declaredBitmap(context, 43, 79) },
        Fixture("declared-bitmap-odd-mask-clipped-semi-alpha") { context -> declaredBitmap(context, 67, 67, oversized = true) },
        Fixture("declared-custom-odd-square-semi-alpha") { Source(declared(Shape(73, 73, partialAlpha = true))) },
        Fixture("declared-custom-odd-wide-semi-alpha") { Source(declared(Shape(97, 53, partialAlpha = true))) },
        Fixture("declared-custom-odd-tall-semi-alpha") { Source(declared(Shape(53, 97, partialAlpha = true))) },
        Fixture("declared-custom-odd-mask-clipped-semi-alpha") {
            Source(declared(Shape(75, 75, partialAlpha = true, oversized = true)))
        },
        Fixture("declared-vector-normalize-small") { Source(declared(vector(22))) },
        Fixture("declared-vector-dead-band-low") { Source(declared(vector(32))) },
        Fixture("declared-vector-dead-band-high") { Source(declared(vector(38))) },
        Fixture("declared-vector-normalize-large") { Source(declared(vector(68))) },
        Fixture("declared-inset-vector-normalize") {
            Source(declared(InsetDrawable(vector(68), .12f, .08f, .16f, .10f)))
        },
        Fixture("declared-inset-vector-dead-band") {
            Source(declared(InsetDrawable(vector(40), .10f)))
        },
        Fixture("declared-inset-odd-non-square-custom") {
            Source(declared(InsetDrawable(Shape(71, 95, partialAlpha = true), 7, 11, 9, 13)))
        },
        Fixture("declared-inset-mixed-zero-intrinsic-dimensions") {
            Source(declared(InsetDrawable(Shape(0, 100), 0)))
        },
        Fixture("generated-null-backdrop-colored-gradient") {
            Source(AdaptiveIconDrawable(null, Shape(101, 101, gradient = true)))
        },
        Fixture("generated-transparent-backdrop-colored-gradient") {
            Source(AdaptiveIconDrawable(ColorDrawable(Color.TRANSPARENT), Shape(101, 101, gradient = true)))
        },
        Fixture("generated-opaque-backdrop-colored-gradient") {
            Source(AdaptiveIconDrawable(ColorDrawable(Color.rgb(37, 53, 71)), Shape(101, 101, gradient = true)))
        },
        Fixture("generated-null-backdrop-colored-semi-alpha") {
            Source(AdaptiveIconDrawable(null, Shape(103, 79, partialAlpha = true)))
        },
        Fixture("generated-sparse-gradient-backdrop-retry") {
            Source(AdaptiveIconDrawable(Backdrop(), Shape(101, 101, gradient = true, sparse = true)))
        },
        Fixture("generated-empty-foreground-backdrop-retry") {
            Source(AdaptiveIconDrawable(Backdrop(), ColorDrawable(Color.TRANSPARENT)))
        },
        Fixture("generated-empty-foreground-without-backdrop", expectsRejection = true) {
            Source(AdaptiveIconDrawable(null, ColorDrawable(Color.TRANSPARENT)))
        },
        Fixture("generated-bitmap-opaque-unfiltered") { context ->
            generatedBitmap(context, 31, 31, null, filterBitmap = false, opaque = true)
        },
        Fixture("generated-bitmap-opaque-filtered") { context ->
            generatedBitmap(context, 31, 31, null, filterBitmap = true, opaque = true)
        },
        Fixture("generated-bitmap-transparent-unfiltered") { context ->
            generatedBitmap(context, 47, 47, ColorDrawable(Color.TRANSPARENT), filterBitmap = false)
        },
        Fixture("generated-bitmap-transparent-filtered") { context ->
            generatedBitmap(context, 47, 47, ColorDrawable(Color.TRANSPARENT), filterBitmap = true)
        },
        Fixture("generated-bitmap-non-square-semi-alpha-unfiltered") { context ->
            generatedBitmap(context, 91, 53, ColorDrawable(Color.rgb(37, 53, 71)),
                filterBitmap = false, partialAlpha = true)
        },
        Fixture("generated-bitmap-non-square-semi-alpha-filtered") { context ->
            generatedBitmap(context, 91, 53, ColorDrawable(Color.rgb(37, 53, 71)),
                filterBitmap = true, partialAlpha = true)
        },
        Fixture("generated-vector-gradient-null-backdrop") {
            Source(AdaptiveIconDrawable(null, coloredVector()))
        },
        Fixture("generated-vector-gradient-transparent-backdrop") {
            Source(AdaptiveIconDrawable(ColorDrawable(Color.TRANSPARENT), coloredVector()))
        },
        Fixture("generated-vector-gradient-opaque-backdrop") {
            Source(AdaptiveIconDrawable(ColorDrawable(Color.rgb(37, 53, 71)), coloredVector()))
        },
        Fixture("legacy-bitmap-partial-alpha") { context ->
            val bitmap = bitmap(87, 65, Shape(87, 65, partialAlpha = true))
            bitmap.density = context.resources.displayMetrics.densityDpi
            Source(BitmapDrawable(context.resources, bitmap), listOf(bitmap))
        },
        Fixture("legacy-complex-alpha-diluted-after-fit") { context ->
            val bitmap = Bitmap.createBitmap(432, 432, Bitmap.Config.ARGB_8888)
            bitmap.density = context.resources.displayMetrics.densityDpi
            val pixels = IntArray(432 * 432) { index ->
                val x = index % 432 / 54
                val y = index / 432 / 54
                Color.argb(30, x * 32 + 16, y * 32 + 16, (x + y) % 8 * 32 + 16)
            }
            bitmap.setPixels(pixels, 0, 432, 0, 0, 432, 432)
            bitmap.setPixel(48, 216, Color.BLACK)
            bitmap.setPixel(383, 216, Color.BLACK)
            Source(BitmapDrawable(context.resources, bitmap), listOf(bitmap))
        },
    )

    private fun declared(mono: Drawable): AdaptiveIconDrawable = AdaptiveIconDrawable(
        ColorDrawable(Color.rgb(43, 61, 79)), Shape(101, 101), mono,
    )

    private fun declaredBitmap(context: Context, width: Int, height: Int, oversized: Boolean = false): Source {
        val bitmap = bitmap(width, height, Shape(width, height, partialAlpha = true, oversized = oversized))
        bitmap.density = context.resources.displayMetrics.densityDpi
        return Source(declared(BitmapDrawable(context.resources, bitmap)), listOf(bitmap))
    }

    private fun generatedBitmap(
        context: Context,
        width: Int,
        height: Int,
        background: Drawable?,
        filterBitmap: Boolean,
        opaque: Boolean = false,
        partialAlpha: Boolean = false,
    ): Source {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.density = context.resources.displayMetrics.densityDpi
        if (opaque) bitmap.eraseColor(Color.rgb(223, 231, 239))
        Shape(width, height, partialAlpha = partialAlpha, gradient = !partialAlpha).apply {
            setBounds(0, 0, width, height)
            draw(Canvas(bitmap))
        }
        val foreground = BitmapDrawable(context.resources, bitmap).apply {
            isFilterBitmap = filterBitmap
        }
        return Source(AdaptiveIconDrawable(background, foreground), listOf(bitmap))
    }

    private fun bitmap(width: Int, height: Int, drawable: Drawable): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            drawable.setBounds(0, 0, width, height)
            drawable.draw(Canvas(it))
        }

    private fun vector(extent: Int): VectorDrawable {
        val resource = when (extent) {
            22 -> TestR.drawable.parity_mono_extent_22
            32 -> TestR.drawable.parity_mono_extent_32
            38 -> TestR.drawable.parity_mono_extent_38
            40 -> TestR.drawable.parity_mono_extent_40
            68 -> TestR.drawable.parity_mono_extent_68
            else -> error("No compiled vector fixture for extent $extent")
        }
        // Platform VectorDrawable requires compiled XML attributes, not a text XmlPullParser.
        return InstrumentationRegistry.getInstrumentation().context.getDrawable(resource) as VectorDrawable
    }

    private fun coloredVector(): VectorDrawable =
        InstrumentationRegistry.getInstrumentation().context.getDrawable(
            TestR.drawable.parity_generated_colored_gradient,
        ) as VectorDrawable

    /** Real Canvas geometry/shaders, not a stand-in for either monochrome implementation. */
    private class Shape(
        private val width: Int,
        private val height: Int,
        private val partialAlpha: Boolean = false,
        private val gradient: Boolean = false,
        private val sparse: Boolean = false,
        private val oversized: Boolean = false,
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var opacity = 255
        override fun getIntrinsicWidth(): Int = width
        override fun getIntrinsicHeight(): Int = height
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        override fun setAlpha(alpha: Int) { opacity = alpha; invalidateSelf() }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }

        override fun draw(canvas: Canvas) {
            val save = canvas.save()
            try {
                canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
                canvas.scale(bounds.width() / 100f, bounds.height() / 100f)
                if (sparse) canvas.scale(.32f, .32f, 50f, 50f)
                if (oversized) canvas.scale(1.8f, 1.8f, 50f, 50f)
                paint.shader = if (gradient) LinearGradient(
                    17f, 21f, 83f, 79f,
                    intArrayOf(Color.rgb(232, 35, 91), Color.rgb(29, 211, 103), Color.rgb(47, 71, 239)),
                    floatArrayOf(0f, .47f, 1f), Shader.TileMode.CLAMP,
                ) else null
                paint.color = Color.rgb(27, 109, 213)
                paint.alpha = if (partialAlpha) opacity * 181 / 255 else opacity
                canvas.drawOval(RectF(19.3f, 21.7f, 80.1f, 78.9f), paint)
                paint.shader = null
                paint.color = Color.rgb(237, 173, 43)
                paint.alpha = if (partialAlpha) opacity * 83 / 255 else opacity
                canvas.drawPath(Path().apply {
                    moveTo(27.2f, 25.1f); lineTo(69.7f, 34.3f)
                    lineTo(60.6f, 74.8f); lineTo(23.9f, 61.2f); close()
                }, paint)
            } finally { canvas.restoreToCount(save) }
        }
    }

    private class Backdrop : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var opacity = 255
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        override fun setAlpha(alpha: Int) { opacity = alpha; invalidateSelf() }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
        override fun draw(canvas: Canvas) {
            paint.color = Color.rgb(223, 231, 239)
            paint.alpha = opacity
            canvas.drawRect(bounds, paint)
            paint.color = Color.rgb(31, 57, 89)
            paint.alpha = opacity
            canvas.drawOval(RectF(
                bounds.left + bounds.width() * .30f, bounds.top + bounds.height() * .25f,
                bounds.left + bounds.width() * .70f, bounds.top + bounds.height() * .75f,
            ), paint)
        }
    }
}
