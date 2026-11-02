package space.linuxct.nothing.icongen

import android.content.Context
import android.content.res.Configuration
import android.provider.Settings

/** Native Nothing ink roles and Lucent's real translucent backdrop tint, including its alpha. */
object ThemeColors {
    data class Palette(val foreground: Int, val background: Int, val isDark: Boolean)

    private var native: NativeColors? = null
    private var configuration: Configuration? = null
    private var style: Int? = null
    private var light: Palette? = null
    private var night: Palette? = null

    @Synchronized
    fun resolve(context: Context, dark: Boolean? = null): Palette {
        val launcher = NothingLauncherRuntime.application(context)
        val current = context.resources.configuration
        // ba.e reads this Secure setting; Lucent is style 2. It is not the selected icon pack.
        val currentStyle = Settings.Secure.getInt(launcher.contentResolver, "nt_theme_style", 0)
        if (configuration != current || style != currentStyle) {
            invalidate()
            configuration = Configuration(current)
            style = currentStyle
        }
        val isDark = dark ?: (current.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)
        (if (isDark) night else light)?.let { return it }
        val colors = native ?: launcherCall("initialize native theme colors") {
            NativeColors(launcher).also { native = it }
        }
        val configured = launcher.createConfigurationContext(Configuration(current).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (isDark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        })
        val palette = launcherCall("resolve native theme colors") {
            Palette(
                foreground = colors.foreground(configured, currentStyle == 2),
                // ThemedIconDelegate's own background is opaque. This pack intentionally uses
                // Lucent's frosted tint instead. A PNG cannot carry the widget's live wallpaper
                // sampling shader; do not counterfeit it with blur, noise, or gradients.
                background = colors.frosted.invoke(colors.lucent, configured, "container_frosted_backdrop") as Int,
                isDark = isDark,
            )
        }
        if (isDark) night = palette else light = palette
        return palette
    }

    @Synchronized
    fun invalidate() {
        light = null
        night = null
        configuration = null
        style = null
    }

    private class NativeColors(context: Context) {
        private val loader = context.classLoader
        private val monoClass = loader.loadClass("x9.f")
        private val companionClass = loader.loadClass("x9.f\$a")
        private val companion = monoClass.getField("l").get(null)
        // Resolve the dynamic token independently of the global selected-pack preference.
        private val dynamicColor = companionClass.getDeclaredMethod(
            "c", Context::class.java, String::class.java, Int::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        private val lucentClass = loader.loadClass("u7.a")
        val lucent: Any = lucentClass.getField("a").get(null)!!
        val frosted = lucentClass.getMethod("b", Context::class.java, String::class.java)
        private val nothingForeground = resource(context, "mono_nothing_foreground_color")
        private val lucentForegroundFallback = resource(context, "mono_color_foreground_color")

        fun foreground(context: Context, isLucent: Boolean): Int =
            if (isLucent) {
                dynamicColor.invoke(companion, context, "content_on_solid_primary", lucentForegroundFallback) as Int
            } else {
                // Keep the neutral foreground role even when an external pack is selected.
                context.getColor(nothingForeground)
            }

        private fun resource(context: Context, name: String): Int =
            context.resources.getIdentifier(name, "color", NothingLauncherRuntime.PACKAGE).also {
                check(it != 0) { "Nothing Launcher 5.0.6 is missing required color $name" }
            }
    }
}
