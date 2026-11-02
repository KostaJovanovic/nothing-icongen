package space.linuxct.nothing.icongen

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Color
import android.provider.Settings
import androidx.core.graphics.ColorUtils
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ThemeColorsStateTest {
    private val base: Context get() = RuntimeEnvironment.getApplication()

    @Before fun setUp() {
        ThemeColors.invalidate()
        Settings.Secure.putInt(base.contentResolver, "nt_theme_style", 0)
    }

    @After fun tearDown() {
        ThemeColors.invalidate()
        Settings.Secure.putInt(base.contentResolver, "nt_theme_style", 0)
    }

    @Test fun neutralStyleUsesWallpaperSystemTonesInBothModesWithoutLauncherCode() {
        val context = TokenContext(base)
        val day = ThemeColors.resolve(context, false)
        val night = ThemeColors.resolve(context, true)
        assertEquals(base.getColor(android.R.color.system_neutral1_900), day.foreground)
        assertEquals(base.getColor(android.R.color.system_neutral1_50), night.foreground)
        assertTrue(ColorUtils.calculateLuminance(day.foreground) < ColorUtils.calculateLuminance(night.foreground))
        assertEquals(0x66FFFFFF, day.background)
        assertEquals(0x66000000, night.background)
        assertEquals(102, Color.alpha(day.background))
        assertEquals(102, Color.alpha(night.background))
    }

    @Test fun lucentUsesConfiguredOsTokensAndPreservesTheirActualAlpha() {
        Settings.Secure.putInt(base.contentResolver, "nt_theme_style", 2)
        val context = TokenContext(base, mutableMapOf(
            (INK to false) to 0xFF284E57.toInt(), (INK to true) to 0xFFD6F4EC.toInt(),
            (FROST to false) to 0x5393D2C1, (FROST to true) to 0x8917282A.toInt(),
        ))
        assertEquals(ThemeColors.Palette(0xFF284E57.toInt(), 0x5393D2C1, false), ThemeColors.resolve(context, false))
        assertEquals(ThemeColors.Palette(0xFFD6F4EC.toInt(), 0x8917282A.toInt(), true), ThemeColors.resolve(context, true))
        assertEquals(83, Color.alpha(ThemeColors.resolve(context, false).background))
        assertEquals(137, Color.alpha(ThemeColors.resolve(context, true).background))
    }

    @Test fun absentOrUnavailableTokensUseNativeAndroidTonalFallbacks() {
        Settings.Secure.putInt(base.contentResolver, "nt_theme_style", 2)
        // The ink identifier exists, but its night variant cannot be read.
        val context = TokenContext(base, mutableMapOf((INK to false) to 0xFF123456.toInt()))
        assertEquals(0xFF123456.toInt(), ThemeColors.resolve(context, false).foreground)
        val night = ThemeColors.resolve(context, true)
        assertEquals(base.getColor(android.R.color.system_accent1_100), night.foreground)
        assertEquals(0x66000000, night.background)
        ThemeColors.invalidate()
        val missing = TokenContext(base)
        val day = ThemeColors.resolve(missing, false)
        assertEquals(base.getColor(android.R.color.system_neutral2_700), day.foreground)
        assertEquals(0x66FFFFFF, day.background)
        assertTrue(ColorUtils.calculateLuminance(day.foreground) < ColorUtils.calculateLuminance(night.foreground))
    }

    @Test fun selectedPackAndOtherStylesDoNotReplaceNeutralInkWithLucentInk() {
        val context = TokenContext(base, mutableMapOf(
            (INK to false) to Color.RED, (FROST to false) to 0x73334455,
        ))
        for (style in listOf(0, 1, 3)) {
            Settings.Secure.putInt(base.contentResolver, "nt_theme_style", style)
            val palette = ThemeColors.resolve(context, false)
            assertEquals(base.getColor(android.R.color.system_neutral1_900), palette.foreground)
            assertEquals(0x73334455, palette.background)
        }
        Settings.Secure.putInt(base.contentResolver, "nt_theme_style", 2)
        assertEquals(Color.RED, ThemeColors.resolve(context, false).foreground)
    }

    @Test fun implicitModeTracksConfigurationWhileExplicitVariantsStayIndependent() {
        val context = TokenContext(base)
        context.resources.configuration.uiMode = Configuration.UI_MODE_NIGHT_NO
        val day = ThemeColors.resolve(context)
        assertEquals(false, day.isDark)
        val night = ThemeColors.resolve(context, true)
        assertEquals(true, night.isDark)
        assertEquals(day, ThemeColors.resolve(context, false))
        context.resources.configuration.uiMode = Configuration.UI_MODE_NIGHT_YES
        assertEquals(night, ThemeColors.resolve(context))
        assertEquals(day, ThemeColors.resolve(context, false))
    }

    @Test fun invalidationReloadsWallpaperTokensWithoutChangingModeOrStyle() {
        Settings.Secure.putInt(base.contentResolver, "nt_theme_style", 2)
        val tokens = mutableMapOf((INK to false) to Color.RED, (FROST to false) to 0x45223344)
        val context = TokenContext(base, tokens)
        assertEquals(Color.RED, ThemeColors.resolve(context, false).foreground)
        tokens[INK to false] = Color.BLUE
        tokens[FROST to false] = 0x91223344.toInt()
        ThemeColors.invalidate()
        val changed = ThemeColors.resolve(context, false)
        assertEquals(Color.BLUE, changed.foreground)
        assertEquals(145, Color.alpha(changed.background))
    }

    private class TokenContext(
        base: Context,
        private val tokens: MutableMap<Pair<String, Boolean>, Int> = mutableMapOf(),
        configuration: Configuration = Configuration(base.resources.configuration),
    ) : ContextWrapper(base) {
        private val colors = object : Resources(base.assets, base.resources.displayMetrics, configuration) {
            private val dark get() = configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

            override fun getIdentifier(name: String, defType: String?, defPackage: String?): Int {
                if (defPackage == "com.nothing.dynamiccolor" && defType == "color") {
                    return if (tokens.keys.any { it.first == name }) when (name) {
                        INK -> INK_ID
                        FROST -> FROST_ID
                        else -> 0
                    } else 0
                }
                return super.getIdentifier(name, defType, defPackage)
            }

            override fun getColor(id: Int, theme: Resources.Theme?): Int {
                val token = when (id) {
                    INK_ID -> INK
                    FROST_ID -> FROST
                    else -> return super.getColor(id, theme)
                }
                return tokens[token to dark] ?: throw Resources.NotFoundException("Missing synthetic $token/$dark")
            }
        }

        override fun getResources(): Resources = colors
        override fun createConfigurationContext(overrideConfiguration: Configuration): Context =
            TokenContext(baseContext, tokens, overrideConfiguration)
        override fun getClassLoader(): ClassLoader = throw AssertionError("Palette must not load launcher code")
    }

    companion object {
        private const val INK = "content_on_solid_primary"
        private const val FROST = "container_frosted_backdrop"
        private const val INK_ID = 0x7E010001
        private const val FROST_ID = 0x7E010002
    }
}
