package space.linuxct.nothing.icongen

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter

/** Builds intended single-pass artwork; PackIconEncoder adapts it to the host's repeated draw. */
object IconArtwork {
    const val ICON_SIZE = 432

    // ThemedIconDelegate.drawContent paints its background at 0.93, then restores the canvas
    // before drawing the mono bitmap. Our pack fills the circular plate, so expand the native
    // glyph coordinate space by the same ratio. The host scales the finished PNG uniformly.
    private const val NATIVE_BACKGROUND_SCALE = 0.93f

    fun buildPlate(palette: ThemeColors.Palette): Bitmap =
        Bitmap.createBitmap(ICON_SIZE, ICON_SIZE, Bitmap.Config.ARGB_8888).also {
            Canvas(it).drawCircle(
                ICON_SIZE / 2f, ICON_SIZE / 2f, ICON_SIZE / 2f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.background },
            )
        }

    /** Same SRC_IN foreground tint as GraphicsUtils.getColorMultipliedFilter(color, null). */
    fun buildIcon(palette: ThemeColors.Palette, glyph: Bitmap): Bitmap {
        val out = buildPlate(palette)
        val canvas = Canvas(out)
        canvas.clipPath(Path().apply {
            addCircle(ICON_SIZE / 2f, ICON_SIZE / 2f, ICON_SIZE / 2f, Path.Direction.CW)
        })
        canvas.scale(
            1f / NATIVE_BACKGROUND_SCALE, 1f / NATIVE_BACKGROUND_SCALE,
            ICON_SIZE / 2f, ICON_SIZE / 2f,
        )
        canvas.drawBitmap(glyph, null, android.graphics.Rect(0, 0, ICON_SIZE, ICON_SIZE),
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                colorFilter = PorterDuffColorFilter(palette.foreground, PorterDuff.Mode.SRC_IN)
            },
        )
        return out
    }
}
