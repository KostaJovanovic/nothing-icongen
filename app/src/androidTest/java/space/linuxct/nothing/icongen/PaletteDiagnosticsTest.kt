package space.linuxct.nothing.icongen

import android.graphics.Color
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Pullable evidence for native tint values, not guessed wallpaper or Material accent colors. */
@RunWith(AndroidJUnit4::class)
class PaletteDiagnosticsTest {
    @Test
    fun exportRawNativeTokensAndTheirActualPlatePixels() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val launcher = NativeLauncherOracle.requireLauncher(context)
        val output = File(requireNotNull(context.getExternalFilesDir(null)), "render-verification").apply {
            check(isDirectory || mkdirs())
        }
        val report = StringBuilder("launcher=${launcher.packageName}\nnt_theme_style=${Settings.Secure.getInt(context.contentResolver, "nt_theme_style", 0)}\n")
        for (dark in listOf(false, true)) {
            val palette = ThemeColors.resolve(context, dark)
            val plate = IconArtwork.buildPlate(palette)
            try {
                val center = plate.getPixel(plate.width / 2, plate.height / 2)
                assertEquals("plate must preserve the native raw alpha", Color.alpha(palette.background), Color.alpha(center))
                val mode = if (dark) "night" else "day"
                report.appendLine("$mode foreground=${hex(palette.foreground)} background=${hex(palette.background)} center=${hex(center)}")
                NativeLauncherOracle.writePng(plate, File(output, "plate_$mode.png"))
            } finally { plate.recycle() }
        }
        report.appendLine("Static PNG includes container_frosted_backdrop tint only; the widget's wallpaper-position blur shader is not available to icon packs.")
        File(output, "palette.txt").writeText(report.toString())
        println(report)
    }

    private fun hex(color: Int) = "#%08X".format(color)
}
