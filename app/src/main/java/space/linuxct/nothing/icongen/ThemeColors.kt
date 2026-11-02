package space.linuxct.nothing.icongen

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.provider.Settings

/** Native Nothing ink roles and Lucent's real translucent backdrop tint, including its alpha. */
object ThemeColors {
    data class Palette(val foreground: Int, val background: Int, val isDark: Boolean)

    private var resources: Resources? = null
    private var configuration: Configuration? = null
    private var style: Int? = null
    private var light: Palette? = null
    private var night: Palette? = null

    @Synchronized
    fun resolve(context: Context, dark: Boolean? = null): Palette {
        val current = context.resources.configuration
        val currentStyle = Settings.Secure.getInt(context.contentResolver, "nt_theme_style", 0)
        if (resources !== context.resources || configuration != current || style != currentStyle) {
            invalidate()
            configuration = Configuration(current)
            resources = context.resources
            style = currentStyle
        }
        val isDark = dark ?: (current.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)
        (if (isDark) night else light)?.let { return it }
        val configured = context.createConfigurationContext(Configuration(current).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (isDark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        })
        val palette = Palette(
            foreground = if (currentStyle == 2) {
                dynamicColor(
                    configured, "content_on_solid_primary",
                    configured.getColor(if (isDark) android.R.color.system_accent1_100 else android.R.color.system_neutral2_700),
                )
            } else {
                configured.getColor(if (isDark) android.R.color.system_neutral1_50 else android.R.color.system_neutral1_900)
            },
            // PNGs retain the frosted tint and its alpha, not the widget's live wallpaper blur.
            background = dynamicColor(
                configured, "container_frosted_backdrop",
                if (isDark) 0x66000000 else 0x66FFFFFF,
            ),
            isDark = isDark,
        )
        if (isDark) night = palette else light = palette
        return palette
    }

    @Synchronized
    fun invalidate() {
        light = null
        night = null
        configuration = null
        style = null
        resources = null
    }

    private fun dynamicColor(context: Context, token: String, fallback: Int): Int {
        // These OS tokens and Android's system palettes follow the current wallpaper/theme.
        val id = context.resources.getIdentifier(token, "color", "com.nothing.dynamiccolor")
        return if (id == 0) fallback else try {
            context.getColor(id)
        } catch (_: Resources.NotFoundException) {
            fallback
        }
    }
}
