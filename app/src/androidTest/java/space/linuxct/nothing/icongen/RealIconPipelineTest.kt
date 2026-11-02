package space.linuxct.nothing.icongen

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Installed app drawables and final pullable day/night PNGs. */
@RunWith(AndroidJUnit4::class)
class RealIconPipelineTest {
    @Test
    fun exportEveryLaunchableComponentAndRecordNativeAcceptance() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        NativeLauncherOracle.requireLauncher(context)
        val apps = AppScanner.scanInstalledApps(context)
        assertTrue("scanner returned no launchable activities", apps.isNotEmpty())
        assertEquals("scanner returned duplicate components", apps.size, apps.map { it.componentName }.toSet().size)
        assertEquals("export filename collision", apps.size, apps.map { NativeLauncherOracle.fileName(it.componentName) }.toSet().size)
        val packages = apps.map { it.componentName.substringBefore('/') }.toSet()
        assertTrue("missing required references: ${NativeLauncherOracle.requiredPackages - packages}",
            packages.containsAll(NativeLauncherOracle.requiredPackages))
        val output = File(requireNotNull(context.getExternalFilesDir(null)), "render-verification").apply {
            check(isDirectory || mkdirs())
        }
        val day = ThemeColors.resolve(context, false)
        val night = ThemeColors.resolve(context, true)
        val accepted = mutableListOf<String>()
        val withoutReference = mutableListOf<String>()
        File(output, "metrics.tsv").bufferedWriter().use { report ->
            report.appendLine("component\tfile_id\tstatus\tmask\tink_pixels\topaque_pixels\talpha_levels\tbounds")
            NativeLauncherOracle(context).use { oracle ->
                IconPipeline(context).use { pipeline ->
                    try {
                        for (app in apps) {
                            val component = app.componentName
                            val name = NativeLauncherOracle.fileName(component)
                            try {
                                val native = oracle.glyph(app.icon)
                                if (native == null) {
                                    val glyphs = pipeline.glyph(app.icon)
                                    try {
                                        for ((mode, palette) in listOf("day" to day, "night" to night)) {
                                            val icon = pipeline.compose(palette, glyphs)
                                            try {
                                                NativeLauncherOracle.writePng(icon, File(output, "${name}_$mode.png"))
                                                assertEquals("$component $mode corner leaked", 0, Color.alpha(icon.getPixel(0, 0)))
                                            } finally { icon.recycle() }
                                        }
                                        report.appendLine("$component\t$name\tGENERATED_WITHOUT_REFERENCE\tnormal\t${metrics(glyphs.normal)}")
                                    } finally { glyphs.recycle() }
                                    withoutReference.add(component)
                                    continue
                                }
                                try {
                                    val glyphs = pipeline.glyph(app.icon)
                                    try {
                                        accepted.add(component)
                                        for ((kind, mask) in listOf("normal" to glyphs.normal, "light" to glyphs.light)) {
                                            if (mask == null) continue
                                            val export = Bitmap.createBitmap(mask.width, mask.height, Bitmap.Config.ARGB_8888)
                                            try {
                                                Canvas(export).drawBitmap(mask, 0f, 0f, Paint().apply {
                                                    colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
                                                })
                                                NativeLauncherOracle.writePng(export, File(output, "${name}_glyph_$kind.png"))
                                            } finally { export.recycle() }
                                            report.appendLine("$component\t$name\tNATIVE_ACCEPTED\t$kind\t${metrics(mask)}")
                                        }
                                        for ((mode, palette) in listOf("day" to day, "night" to night)) {
                                            val icon = pipeline.compose(palette, glyphs)
                                            try {
                                                NativeLauncherOracle.writePng(icon, File(output, "${name}_$mode.png"))
                                                assertEquals("$component $mode corner leaked", 0, Color.alpha(icon.getPixel(0, 0)))
                                                if (component.substringBefore('/') == "com.android.vending") {
                                                    assertPlayStoreHasNoOpaqueSquare(icon, "$component $mode")
                                                }
                                            } finally { icon.recycle() }
                                            if (component.substringBefore('/') in NativeLauncherOracle.referencePackages) {
                                                val reference = oracle.drawDelegate(native,
                                                    palette.copy(background = if (palette.isDark) Color.BLACK else Color.WHITE))
                                                try {
                                                    NativeLauncherOracle.writePng(reference, File(output, "${name}_native_opaque_$mode.png"))
                                                } finally { reference.recycle() }
                                            }
                                        }
                                    } finally { glyphs.recycle() }
                                } finally { native.recycle() }
                            } catch (error: Exception) {
                                report.appendLine("$component\t$name\tERROR\t${error.javaClass.name}: ${error.message}")
                                report.flush()
                                throw AssertionError("Native smoke failed for $component; see ${output.absolutePath}", error)
                            }
                        }
                    } finally {
                        File(output, "accepted-components.txt").writeText(accepted.joinToString("\n", postfix = "\n"))
                        File(output, "components-without-reference.txt").writeText(withoutReference.joinToString("\n", postfix = "\n"))
                        File(output, "summary.txt").writeText(
                            "reference_launcher=5.0.6 (50006009)\nscanned=${apps.size}\nnative_accepted=${accepted.size}\ngenerated_without_reference=${withoutReference.size}\n" +
                                "unavailable_optional_references=${NativeLauncherOracle.referencePackages - packages}\n" +
                                "day=$day\nnight=$night\n" +
                                "PNGs are precompensated for the launcher shadow/double draw, targeting native tint alpha; pure-white RGB clipping and wallpaper-position blur remain limitations.\n")
                    }
                }
            }
        }
        assertEquals("unaccounted components", apps.size, accepted.size + withoutReference.size)
        assertTrue("no native-accepted components", accepted.isNotEmpty())
        println("RENDER_VERIFICATION ${output.absolutePath} native_accepted=${accepted.size} generated_without_reference=${withoutReference.size}")
    }


    private fun assertPlayStoreHasNoOpaqueSquare(icon: Bitmap, label: String) {
        // Reference Play Store artwork is a small central triangle, not an adaptive plate.
        // A generous 70%-width square includes AA and native normalizer differences but
        // excludes the full-canvas square that previously leaked from fallback extraction.
        val inset = (icon.width * .15f).toInt()
        var leaked = 0
        for (y in 0 until icon.height) for (x in 0 until icon.width) {
            if ((x < inset || x >= icon.width - inset || y < inset || y >= icon.height - inset) &&
                Color.alpha(icon.getPixel(x, y)) > 240) leaked++
        }
        assertEquals("$label unexpected opaque pixels outside central glyph square", 0, leaked)
    }

    private fun metrics(mask: Bitmap): String {
        var ink = 0
        var opaque = 0
        var minX = mask.width
        var minY = mask.height
        var maxX = -1
        var maxY = -1
        val levels = mutableSetOf<Int>()
        for (y in 0 until mask.height) for (x in 0 until mask.width) {
            val alpha = Color.alpha(mask.getPixel(x, y))
            levels.add(alpha)
            if (alpha > 0) {
                ink++
                minX = minOf(minX, x); minY = minOf(minY, y)
                maxX = maxOf(maxX, x); maxY = maxOf(maxY, y)
            }
            if (alpha > 240) opaque++
        }
        return "$ink\t$opaque\t${levels.size}\t${if (ink == 0) "empty-native-mask" else "$minX,$minY,${maxX + 1},${maxY + 1}"}"
    }
}
