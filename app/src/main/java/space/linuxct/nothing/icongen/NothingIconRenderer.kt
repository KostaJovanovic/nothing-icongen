package space.linuxct.nothing.icongen

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.drawable.Drawable
import java.lang.reflect.InvocationTargetException

/** Owned snapshots of ga.c; native scratch/pool bitmaps must never escape the renderer. */
class LauncherGlyphs(val normal: Bitmap, val light: Bitmap? = null) {
    /** Exact ga.c$a / ia.d.e selection, based on the ink, not the background or night mode. */
    fun forColor(color: Int): Bitmap =
        if (0.2126 * Color.red(color) + 0.7152 * Color.green(color) +
            0.0722 * Color.blue(color) > 128.0
        ) normal else light ?: normal

    fun recycle() {
        normal.recycle()
        if (light !== normal) light?.recycle()
    }
}

/** Executes the installed 5.0.6 mono pipeline; there is deliberately no substitute extractor. */
class NothingIconRenderer(context: Context) : AutoCloseable {
    private val launcher = NothingLauncherRuntime.application(context)
    private val api = launcherCall("initialize the monochrome renderer") { NativeApi(launcher) }
    private var closed = false

    @Synchronized
    fun glyph(drawable: Drawable): LauncherGlyphs {
        check(!closed) { "NothingIconRenderer is closed" }
        // InsetDrawable's optical insets depend on its current bounds. Native measurement
        // changes child bounds as well as the outer adaptive drawable, so restoring only the
        // outer Rect is insufficient. Use an independent resource instance for each render.
        val source = drawable.constantState?.newDrawable()?.mutate() ?: drawable
        // Launcher methods change bounds while rasterizing. Do not alter the caller's drawable.
        val left = drawable.bounds.left
        val top = drawable.bounds.top
        val right = drawable.bounds.right
        val bottom = drawable.bounds.bottom
        var nativeNormal: Bitmap? = null
        var nativeLight: Bitmap? = null
        var normal: Bitmap? = null
        var light: Bitmap? = null
        try {
            return launcherCall("generate a monochrome glyph for ${drawable.javaClass.name}") {
                val scale = api.scale.invoke(api.normalizer, source, null, null) as Float
                // aa.a.b: reset the mono generator with exactly the source normalizer's scale.
                api.prepare.invoke(api.monochromeGenerator, scale, null)
                // ea.h accepts a nullable cached bitmap (BaseIconFactory.createMonoInfo does
                // not). With no cache, ga.i.k renders the native selected drawable itself via
                // BaseIconFactory.createNormalizedBitmap into its own pool lease. Never give
                // it a blank cache or approximate its selected-drawable rasterization here.
                val info = api.monoInfo.newInstance(launcher, source, null)
                if (api.declaredMono.invoke(info) == null) {
                    // ea.i's constructor performs this same force-2 selection. Do not let the
                    // currently selected external icon pack disable Nothing's native fallback.
                    api.forceMode.invoke(info, 2)
                }
                val generator = api.generator.newInstance(launcher, scale, api.factory)
                val pair = api.generate.invoke(generator, info)
                    ?: throw IllegalStateException(
                        "Nothing Launcher 5.0.6 did not generate a monochrome glyph for ${drawable.javaClass.name}",
                    )
                nativeNormal = api.normal.invoke(pair) as Bitmap
                nativeLight = api.light.invoke(pair) as Bitmap?
                normal = snapshot(nativeNormal!!)
                light = nativeLight?.let { if (it === nativeNormal) normal else snapshot(it) }
                LauncherGlyphs(normal!!, light)
            }
        } catch (failure: Throwable) {
            normal?.recycle()
            if (light !== normal) light?.recycle()
            throw failure
        } finally {
            // Return only native results, after both snapshots. Pool return erases their pixels.
            // The native pipeline handles its own intermediate leases; never clear its global pool.
            try {
                nativeNormal?.let { api.release.invoke(api.pool, it) }
                if (nativeLight !== nativeNormal) nativeLight?.let { api.release.invoke(api.pool, it) }
            } finally {
                drawable.setBounds(left, top, right, bottom)
            }
        }
    }

    private fun snapshot(source: Bitmap): Bitmap =
        checkNotNull(source.copy(source.config ?: Bitmap.Config.ALPHA_8, false)) {
            "Could not retain the native monochrome bitmap"
        }

    @Synchronized
    override fun close() {
        if (!closed) {
            closed = true
            launcherCall("close the monochrome renderer") { api.close.invoke(api.factory) }
        }
    }

    /** Every reflective member is resolved once, never inside the per-icon path. */
    private class NativeApi(context: Context) {
        private val loader = context.classLoader
        private val factoryClass = loader.loadClass("com.android.launcher3.icons.BaseIconFactory")
        private val infoClass = loader.loadClass("ea.h")
        private val generatorClass = loader.loadClass("ga.f")
        private val pairClass = loader.loadClass("ga.c")
        private val monoClass = loader.loadClass("x9.f")
        private val normalizerClass = loader.loadClass("com.android.launcher3.icons.IconNormalizer")
        private val poolClass = loader.loadClass("y9.b")
        val monoInfo = infoClass.getConstructor(Context::class.java, Drawable::class.java, Bitmap::class.java)
        val scale = normalizerClass.getMethod("getScale", Drawable::class.java, Path::class.java, BooleanArray::class.java)
        val prepare = monoClass.getMethod("w", Float::class.javaPrimitiveType, String::class.java)
        val declaredMono = infoClass.getMethod("b")
        val forceMode = infoClass.getMethod("f", Int::class.javaPrimitiveType)
        val generator = generatorClass.getConstructor(Context::class.java, Float::class.javaPrimitiveType, factoryClass)
        val generate = generatorClass.getMethod("b", infoClass)
        val normal = pairClass.getMethod("c")
        val light = pairClass.getMethod("b")
        val close = factoryClass.getMethod("close")
        val release = loader.loadClass("y9.f").getMethod("i", Any::class.java)
        val pool: Any = poolClass.getField("g").get(null)!!
        val factory: Any = factoryClass.getConstructor(
            Context::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
        ).newInstance(context, context.resources.displayMetrics.densityDpi, IconArtwork.ICON_SIZE)
        val normalizer: Any = factoryClass.getMethod("getNormalizer").invoke(factory)!!
        val monochromeGenerator: Any = factoryClass.getMethod("getMonochromeGenerator").invoke(factory)!!
    }
}

/** Process-lifetime launcher globals must retain a launcher Application, never an Activity. */
internal object NothingLauncherRuntime {
    const val PACKAGE = "com.nothing.launcher"
    private const val VERSION = 50006009L
    private var application: Application? = null

    @Synchronized
    fun application(context: Context): Application {
        val owner = context.applicationContext
        val installed = try {
            owner.packageManager.getPackageInfo(PACKAGE, 0)
        } catch (failure: PackageManager.NameNotFoundException) {
            throw IllegalStateException("Install Nothing Launcher 5.0.6 (50006009) to generate icons", failure)
        }
        check(installed.longVersionCode == VERSION) {
            "Unsupported Nothing Launcher ${installed.versionName} (${installed.longVersionCode}); " +
                "icon generation requires Nothing Launcher 5.0.6 (50006009)"
        }
        application?.let { return it }
        return launcherCall("initialize the launcher application context") {
            val code = owner.createPackageContext(PACKAGE, Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY)
            val app = LauncherApplication(code)
            val globalsClass = code.classLoader.loadClass("x9.i")
            globalsClass.getMethod("c", Application::class.java)
                .invoke(globalsClass.getField("a").get(null), app)
            application = app
            app
        }
    }

    private class LauncherApplication(context: Context) : Application() {
        init {
            attachBaseContext(context)
        }

        override fun getApplicationContext(): Context = this
    }
}

internal inline fun <T> launcherCall(operation: String, block: () -> T): T = try {
    block()
} catch (failure: ReflectiveOperationException) {
    val cause = if (failure is InvocationTargetException) failure.targetException else failure
    throw IllegalStateException("Nothing Launcher 5.0.6 could not $operation: ${cause.message}", cause)
} catch (failure: LinkageError) {
    throw IllegalStateException("Nothing Launcher 5.0.6 could not $operation: ${failure.message}", failure)
}
