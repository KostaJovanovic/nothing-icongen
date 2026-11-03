package space.linuxct.nothing.icongen

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.CancellationException

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
                    try {
                        val glyphs = pipeline.glyph(app.icon)
                        entries.add(IconPackAssembler.PackEntry(app.componentName, app.label, glyphs))
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        // One unrenderable icon must not block the pack; the app stays unmapped (iconback plate).
                        Log.w(TAG, "Skipping ${app.componentName}: Nothing renderer failed", e)
                    }
                    cancellation.checkpoint()
                }
            }
            check(entries.isNotEmpty()) { "Nothing renderer failed for all ${apps.size} apps" }
            if (entries.size < apps.size) Log.w(TAG, "Skipped ${apps.size - entries.size} of ${apps.size} apps")
            cancellation.checkpoint()
            return IconPackAssembler.assembleZip(context, outputDir, entries, palette, cancellation)
        } finally {
            entries.forEach { it.glyphs.recycle() }
        }
    }
}
