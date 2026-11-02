package space.linuxct.nothing.icongen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable

/** Shared rendering pipeline for generation and on-device verification. */
class IconPipeline(context: Context) : AutoCloseable {
    private val renderer = NothingIconRenderer(context)
    private var encoder: PackIconEncoder? = null

    fun glyph(drawable: Drawable): LauncherGlyphs = renderer.glyph(drawable)

    fun icon(drawable: Drawable, palette: ThemeColors.Palette): Bitmap {
        val glyphs = glyph(drawable)
        return try {
            compose(palette, glyphs)
        } finally {
            glyphs.recycle()
        }
    }

    fun compose(palette: ThemeColors.Palette, glyphs: LauncherGlyphs): Bitmap {
        val artwork = IconArtwork.buildIcon(palette, glyphs.forColor(palette.foreground))
        return try {
            (encoder ?: PackIconEncoder().also { encoder = it }).encode(artwork)
        } finally { artwork.recycle() }
    }

    override fun close() {
        try { renderer.close() } finally { encoder?.close() }
    }
}
