package space.linuxct.nothing.icongen

import android.content.Context
import android.content.res.Configuration
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThemeColorsTest {
    @Test
    fun bothVariantsMatchInstalledLauncherFrostAndNothingOrLucentInkTokens() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val launcher = NativeLauncherOracle.requireLauncher(context)
        val lucent = Settings.Secure.getInt(context.contentResolver, "nt_theme_style", 0) == 2
        for (dark in listOf(false, true)) {
            val palette = ThemeColors.resolve(context, dark)
            val configured = launcher.createConfigurationContext(Configuration(launcher.resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            })
            val frostClass = launcher.classLoader.loadClass("u7.a")
            val expectedBackground = frostClass.getMethod("b", Context::class.java, String::class.java)
                .invoke(frostClass.getField("a").get(null), configured, "container_frosted_backdrop") as Int
            val expectedForeground = if (lucent) {
                // Invoke the APK's actual dynamic-token lookup, bypassing only its unrelated
                // global external-pack preference. No duplicated color/token fallback logic.
                val companion = launcher.classLoader.loadClass("x9.f").getField("l").get(null)
                val fallback = configured.resources.getIdentifier("mono_color_foreground_color", "color", "com.nothing.launcher")
                check(fallback != 0)
                val lookup = companion.javaClass.getDeclaredMethod("c", Context::class.java, String::class.java,
                    Int::class.javaPrimitiveType).apply { isAccessible = true }
                lookup.invoke(companion, configured, "content_on_solid_primary", fallback) as Int
            } else {
                val id = configured.resources.getIdentifier("mono_nothing_foreground_color", "color", "com.nothing.launcher")
                check(id != 0)
                configured.getColor(id)
            }
            assertEquals("explicit night mode", dark, palette.isDark)
            assertEquals("native frosting token including alpha (dark=$dark)", expectedBackground, palette.background)
            assertEquals("native Nothing/Lucent foreground (dark=$dark)", expectedForeground, palette.foreground)
        }
    }

    @Test
    fun resolvingNightDoesNotPoisonDayOrInvalidatedCache() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        NativeLauncherOracle.requireLauncher(context)
        val day = ThemeColors.resolve(context, false)
        val night = ThemeColors.resolve(context, true)
        assertEquals(day, ThemeColors.resolve(context, false))
        ThemeColors.invalidate()
        assertEquals(day, ThemeColors.resolve(context, false))
        assertEquals(night, ThemeColors.resolve(context, true))
    }
}
