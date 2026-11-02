package space.linuxct.nothing.icongen

import android.content.Context
import android.util.Log
import java.io.File

/** Generates mapped monochrome PNG resources. */
object IconGeneratorEngine {
    private const val TAG = "IconGeneratorEngine"

    fun generate(context: Context, outputDir: File, maxApps: Int = 2000,
                 cancellation: BuildCoordinator.Cancellation = BuildCoordinator.cancellation(context)): File {
        cancellation.checkpoint()
        ThemeColors.invalidate()
        val palette = ThemeColors.resolve(context)
        val apps = AppScanner.scanInstalledApps(context).take(maxApps)
        cancellation.checkpoint()
        check(apps.isNotEmpty()) { "No launchable apps found to generate icons for" }
        val entries = ArrayList<IconPackAssembler.PackEntry>(apps.size)
        try {
            IconPipeline(context).use { pipeline ->
                for ((index, app) in apps.withIndex()) {
                    cancellation.update {
                        it.copy(
                            phase = BuildCoordinator.Phase.GENERATING,
                            message = "Generating icons (${index + 1}/${apps.size})",
                            percent = 10 + 70 * index / apps.size,
                        )
                    }
                    Log.d(TAG, "Rendering ${app.componentName}")
                    val glyphs = try {
                        pipeline.glyph(app.icon)
                    } catch (e: Exception) {
                        throw IllegalStateException("Nothing renderer failed for ${app.componentName}", e)
                    }
                    entries.add(IconPackAssembler.PackEntry(app.componentName, app.label, glyphs))
                    cancellation.checkpoint()
                }
            }
            cancellation.checkpoint()
            return IconPackAssembler.assembleZip(context, outputDir, entries, palette, cancellation)
        } finally {
            entries.forEach { it.glyphs.recycle() }
        }
    }
}
