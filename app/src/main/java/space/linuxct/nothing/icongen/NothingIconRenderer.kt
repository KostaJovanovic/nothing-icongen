package space.linuxct.nothing.icongen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.Drawable

/** Owned bitmap snapshots; scratch and pooled bitmaps must never escape the renderer. */
class LauncherGlyphs(val normal: Bitmap, val light: Bitmap? = null) {
    /** Selects by weighted ink brightness, not the background or night mode. */
    fun forColor(color: Int): Bitmap =
        if (0.2126 * Color.red(color) + 0.7152 * Color.green(color) +
            0.0722 * Color.blue(color) > 128.0
        ) normal else light ?: normal

    fun recycle() {
        normal.recycle()
        if (light !== normal) light?.recycle()
    }
}

/** Renders monochrome glyphs without executing code from the installed launcher. */
class NothingIconRenderer(context: Context) : AutoCloseable {
    init {
        NothingLauncherCompatibility.requireSupported(context)
    }
    private val monochrome = PortableMonochrome()

    private var closed = false

    @Synchronized
    fun glyph(drawable: Drawable): LauncherGlyphs {
        check(!closed) { "NothingIconRenderer is closed" }
        // Resource copies isolate state, tint and child bounds from the caller. Drawables without
        // ConstantState still work: the renderer saves the complete exposed bounds tree.
        val source = drawable.constantState?.newDrawable()?.mutate() ?: drawable
        return if (source === drawable) DrawableBounds.preserve(source) { monochrome.render(source) }
        else monochrome.render(source)
    }

    @Synchronized
    override fun close() {
        closed = true
    }
}
