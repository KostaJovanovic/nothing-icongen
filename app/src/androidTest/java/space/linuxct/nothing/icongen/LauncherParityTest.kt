package space.linuxct.nothing.icongen

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Compares exported artwork with independently rendered reference images. */
@RunWith(AndroidJUnit4::class)
class LauncherParityTest {
    @Test
    fun exportedForegroundMatchesActualNativeDelegateAfterPlateGeometryMapping() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val apps = AppScanner.scanInstalledApps(context)
        val selected = apps.filter { it.componentName.substringBefore('/') in NativeLauncherOracle.referencePackages }
        val installed = selected.map { it.componentName.substringBefore('/') }.toSet()
        assertTrue("missing required smoke fixtures: ${NativeLauncherOracle.requiredPackages - installed}",
            installed.containsAll(NativeLauncherOracle.requiredPackages))
        val output = File(requireNotNull(context.getExternalFilesDir(null)), "render-verification").apply {
            check(isDirectory || mkdirs())
        }
        NativeLauncherOracle(context).use { oracle ->
            IconPipeline(context).use { pipeline ->
                for (app in selected) {
                    val native = requireNotNull(oracle.glyph(app.icon)) { "native rejected reference ${app.componentName}" }
                    val actual = pipeline.glyph(app.icon)
                    try {
                        for (dark in listOf(false, true)) {
                            val palette = ThemeColors.resolve(context, dark).copy(background = if (dark) Color.BLACK else Color.WHITE)
                            val expected = oracle.drawDelegate(native, palette)
                            val ours = IconArtwork.buildIcon(palette, actual.forColor(palette.foreground))
                            val suffix = if (dark) "night" else "day"
                            val name = NativeLauncherOracle.fileName(app.componentName)
                            try {
                                NativeLauncherOracle.writePng(expected, File(output, "${name}_native_opaque_$suffix.png"))
                                NativeLauncherOracle.writePng(ours, File(output, "${name}_ours_opaque_$suffix.png"))
                                // Ignore only a 2px ring at the plate edge: native clipPath
                                // is not the exporter's antialiased drawCircle. Everywhere
                                // else, including foreground AA and greys, is exact.
                                var mismatches = 0
                                var maximumDelta = 0
                                for (y in 0 until ours.height) for (x in 0 until ours.width) {
                                    val dx = x + .5f - ours.width / 2f
                                    val dy = y + .5f - ours.height / 2f
                                    val radius = kotlin.math.sqrt(dx * dx + dy * dy)
                                    if (kotlin.math.abs(radius - ours.width / 2f) <= 2f) continue
                                    val a = expected.getPixel(x, y)
                                    val b = ours.getPixel(x, y)
                                    val delta = maxOf(kotlin.math.abs(Color.red(a) - Color.red(b)),
                                        kotlin.math.abs(Color.green(a) - Color.green(b)),
                                        kotlin.math.abs(Color.blue(a) - Color.blue(b)),
                                        kotlin.math.abs(Color.alpha(a) - Color.alpha(b)))
                                    if (delta != 0) mismatches++
                                    maximumDelta = maxOf(maximumDelta, delta)
                                }
                                assertEquals("${app.componentName} $suffix: delegate mismatch pixels=$mismatches maxDelta=$maximumDelta", 0, mismatches)
                            } finally { expected.recycle(); ours.recycle() }
                        }
                    } finally { native.recycle(); actual.recycle() }
                }
            }
        }
    }
}

/** Test-only reference renderer for pixel comparisons. */
internal class NativeLauncherOracle(context: Context) : AutoCloseable {
    val launcher = requireLauncher(context)
    private val loader = launcher.classLoader
    private val factoryClass = loader.loadClass("com.android.launcher3.icons.BaseIconFactory")
    private val factory = factoryClass.getConstructor(Context::class.java, Int::class.javaPrimitiveType,
        Int::class.javaPrimitiveType).newInstance(launcher, launcher.resources.displayMetrics.densityDpi, IconArtwork.ICON_SIZE)
    private val blank = Bitmap.createBitmap(IconArtwork.ICON_SIZE, IconArtwork.ICON_SIZE, Bitmap.Config.ARGB_8888)
    private val infoClass = loader.loadClass("ea.h")
    private val generatorClass = loader.loadClass("ga.f")

    init {
        val application = object : Application() {
            fun attachLauncher() = attachBaseContext(launcher)
            override fun getApplicationContext(): Context = this
        }.apply { attachLauncher() }
        val globalsClass = loader.loadClass("x9.i")
        globalsClass.getMethod("c", Application::class.java).invoke(globalsClass.getField("a").get(null), application)
    }

    /** null is a native rejection, not permission to substitute a heuristic renderer. */
    fun glyph(drawable: Drawable): Masks? {
        val source = drawable.constantState?.newDrawable()?.mutate() ?: drawable
        val bounds = Rect(drawable.bounds)
        var normal: Bitmap? = null
        var light: Bitmap? = null
        try {
            val normalizer = factoryClass.getMethod("getNormalizer").invoke(factory)
            val scale = normalizer.javaClass.getMethod("getScale", Drawable::class.java,
                Path::class.java, BooleanArray::class.java).invoke(normalizer, source, null, null) as Float
            val mono = factoryClass.getMethod("getMonochromeGenerator").invoke(factory)
            mono.javaClass.getMethod("w", Float::class.javaPrimitiveType, String::class.java).invoke(mono, scale, null)
            // A null cache requests the selected drawable rather than a supplied blank bitmap.
            val info = infoClass.getConstructor(Context::class.java, Drawable::class.java,
                Bitmap::class.java).newInstance(launcher, source, null)
            if (infoClass.getMethod("b").invoke(info) == null) {
                infoClass.getMethod("f", Int::class.javaPrimitiveType).invoke(info, 2)
            }
            val generator = generatorClass.getConstructor(Context::class.java, Float::class.javaPrimitiveType,
                factoryClass).newInstance(launcher, scale, factory)
            val pair = generatorClass.getMethod("b", infoClass).invoke(generator, info) ?: return null
            normal = pair.javaClass.getMethod("c").invoke(pair) as Bitmap
            light = pair.javaClass.getMethod("b").invoke(pair) as Bitmap?
            return Masks(requireNotNull(normal.copy(normal.config ?: Bitmap.Config.ALPHA_8, false)),
                light?.let { requireNotNull(it.copy(it.config ?: Bitmap.Config.ALPHA_8, false)) })
        } finally {
            try {
                val pool = loader.loadClass("y9.b").getField("g").get(null)
                val release = loader.loadClass("y9.f").getMethod("i", Any::class.java)
                normal?.let { release.invoke(pool, it) }
                if (light !== normal) light?.let { release.invoke(pool, it) }
            } finally { drawable.bounds = bounds }
        }
    }

    /** Reference rendering includes clipping, polarity, tint and drawing. */
    fun drawDelegate(masks: Masks, palette: ThemeColors.Palette): Bitmap {
        val size = IconArtwork.ICON_SIZE
        val shapeClass = loader.loadClass("com.android.launcher3.icons.IconShape")
        val shape = shapeClass.getConstructors().single { it.parameterTypes.size == 6 }.newInstance(
            size, Path().apply { addCircle(size / 2f, size / 2f, size / 2f, Path.Direction.CW) }, blank, null, 8, null)
        val bitmapInfoClass = loader.loadClass("com.android.launcher3.icons.BitmapInfo")
        val bitmapInfo = bitmapInfoClass.constructors.single { it.parameterTypes.size == 10 }.newInstance(
            blank, 0, 0, shape, null, null, null, null, 0xF4, null)
        val themedInfoClass = loader.loadClass("com.android.launcher3.icons.mono.ThemedIconInfo")
        val themedInfo = themedInfoClass.getConstructor(Bitmap::class.java, Bitmap::class.java,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).newInstance(
            masks.normal, masks.light, palette.background, palette.foreground)
        val delegateClass = loader.loadClass("com.android.launcher3.icons.mono.ThemedIconDelegate")
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val delegate = delegateClass.getConstructor(themedInfoClass, bitmapInfoClass, Paint::class.java)
            .newInstance(themedInfo, bitmapInfo, paint)
        val result = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        // Compare at the exported full-plate geometry, not the native 0.93-size plate.
        canvas.scale(1f / .93f, 1f / .93f, size / 2f, size / 2f)
        delegateClass.getMethod("drawContent", bitmapInfoClass, shapeClass, Canvas::class.java,
            Rect::class.java, Paint::class.java).invoke(delegate, bitmapInfo, shape, canvas,
            Rect(0, 0, size, size), paint)
        return result
    }

    override fun close() {
        factoryClass.getMethod("close").invoke(factory)
        blank.recycle()
    }

    class Masks(val normal: Bitmap, val light: Bitmap?) {
        fun recycle() { normal.recycle(); if (light !== normal) light?.recycle() }
    }

    companion object {
        val requiredPackages = setOf("com.android.vending", "space.linuxct.glyphworks", "luna.safe.luna", "perkfinance.cobee")
        val referencePackages = requiredPackages + setOf("com.nothing.gallery", "com.nothing.recorder",
            "org.mozilla.fenix", "org.telegram.messenger", "com.whatsapp", "com.nothing.camera",
            "com.google.android.apps.translate", "es.vodafone.mobile.mivodafone")

        fun requireLauncher(context: Context): Context {
            val info = context.packageManager.getPackageInfo("com.nothing.launcher", 0)
            check(info.versionName == "5.0.6" && info.longVersionCode == 50006009L) {
                "Native tests require installed Nothing Launcher 5.0.6 (50006009), found ${info.versionName} (${info.longVersionCode})"
            }
            return context.createPackageContext("com.nothing.launcher", Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY)
        }

        // A bounded full-component digest avoids both package overwrites and NAME_MAX.
        fun fileName(component: String): String = java.security.MessageDigest.getInstance("SHA-256")
            .digest(component.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }

        fun writePng(bitmap: Bitmap, file: File) {
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "PNG export failed: $file" } }
        }
    }
}
