package space.linuxct.nothing.icongen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/** Consumer-visible regression: compare after the installed launcher rasterizes our PNG. */
class PackBackdropTest {
    @Test fun backgroundSurvivesActualPackRasterizationInBothModes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val out = File(context.getExternalFilesDir(null), "backdrop-verification").apply { mkdirs() }
        PackIconEncoder().use { encoder ->
            for (size in listOf(176, 432)) NativePackRasterizer(context, size).use { host ->
                for (dark in listOf(false, true)) {
                    val palette = ThemeColors.resolve(context, dark)
                    val target = IconArtwork.buildPlate(palette)
                    val source = encoder.encode(target)
                    val expected = host.render(target, shadows = false)
                    val displayed = host.render(source)
                    try {
                        val expectedPixel = expected.getPixel(size / 2, size / 3)
                        val actualPixel = displayed.getPixel(size / 2, size / 3)
                        println("BACKDROP_VERIFIED size=$size dark=$dark source=${Integer.toHexString(source.getPixel(216, 144))} expected=${Integer.toHexString(expectedPixel)} actual=${Integer.toHexString(actualPixel)}")
                        assertDisplayedPixel("background $size/$dark", expectedPixel, actualPixel)
                        File(out, "${size}_${dark}_source.png").outputStream().use { source.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        File(out, "${size}_${dark}_displayed.png").outputStream().use { displayed.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    } finally { target.recycle(); source.recycle(); expected.recycle(); displayed.recycle() }
                }
            }
        }
    }

    @Test fun partialAlphaGlyphDetailSurvivesTheHostDoubleDraw() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val glyph = Bitmap.createBitmap(432, 432, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(glyph)
        for ((index, alpha) in listOf(32, 96, 160, 224).withIndex()) {
            canvas.drawRect(96f + index * 60, 180f, 144f + index * 60, 252f,
                Paint().apply { color = Color.argb(alpha, 255, 255, 255) })
        }
        try {
            PackIconEncoder().use { encoder ->
                NativePackRasterizer(context, 432).use { host ->
                    for (dark in listOf(false, true)) {
                        val target = IconArtwork.buildIcon(ThemeColors.resolve(context, dark), glyph)
                        val source = encoder.encode(target)
                        val expected = host.render(target, shadows = false)
                        val actual = host.render(source)
                        try {
                            for (x in listOf(112, 176, 240, 304)) {
                                assertDisplayedPixel("glyph alpha $x/$dark", expected.getPixel(x, 216), actual.getPixel(x, 216))
                            }
                        } finally { target.recycle(); source.recycle(); expected.recycle(); actual.recycle() }
                    }
                }
            }
        } finally { glyph.recycle() }
    }
}

/** Executes the same factory/options the launcher uses for mapped external drawables. */
internal class NativePackRasterizer(context: Context, size: Int) : AutoCloseable {
    private val launcher = NothingLauncherRuntime.application(context)
    private val factoryClass = launcher.classLoader.loadClass("com.android.launcher3.icons.BaseIconFactory")
    private val optionsClass = launcher.classLoader.loadClass("com.android.launcher3.icons.BaseIconFactory\$IconOptions")
    private val factory = factoryClass.getConstructor(Context::class.java, Int::class.javaPrimitiveType,
        Int::class.javaPrimitiveType).newInstance(launcher, launcher.resources.displayMetrics.densityDpi, size)
    private val options = optionsClass.getConstructor().newInstance().also {
        optionsClass.getMethod("setWrapNonAdaptiveIcon", Boolean::class.javaPrimitiveType).invoke(it, false)
        optionsClass.getMethod("setDrawFullBleed", Boolean::class.javaPrimitiveType).invoke(it, false)
    }
    private val shadowSetter = optionsClass.getMethod("setAddShadows", Boolean::class.javaPrimitiveType)
    private val create = factoryClass.getMethod("createBadgedIconBitmap", Drawable::class.java, optionsClass)
    private val close = factoryClass.getMethod("close")

    fun render(bitmap: Bitmap, shadows: Boolean = true): Bitmap {
        shadowSetter.invoke(options, shadows)
        val info = create.invoke(factory, BitmapDrawable(launcher.resources, bitmap), options)
        return info.javaClass.getField("icon").get(info) as Bitmap
    }

    override fun close() { close.invoke(factory) }
}

internal fun assertDisplayedPixel(label: String, expected: Int, actual: Int) {
    assertTrue("$label alpha changed: ${Color.alpha(expected)} -> ${Color.alpha(actual)}",
        abs(Color.alpha(expected) - Color.alpha(actual)) <= 2)
    // Black shadows make translucent pure white unreachable. Bound displayed error over
    // both extreme backdrops; comparing unpremultiplied PNG RGB alone is misleading.
    for (backdrop in listOf(0, 255)) for (channel in listOf(16, 8, 0)) {
        fun composite(pixel: Int): Int {
            val a = Color.alpha(pixel) / 255.0
            return (((pixel ushr channel) and 255) * a + backdrop * (1 - a)).toInt()
        }
        assertTrue("$label colour error exceeds 6/255: ${Integer.toHexString(expected)} -> ${Integer.toHexString(actual)}",
            abs(composite(expected) - composite(actual)) <= 6)
    }
}
