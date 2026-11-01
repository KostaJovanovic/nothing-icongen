package space.linuxct.nothing.icongen

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Inverts Nothing 5.0.6's software pack draw: source, black shadows, source again.
 * Input is the intended single-composite artwork; output is the PNG sent to the launcher.
 * Alpha is the primary constraint. RGB is clamped where a black shadow makes the target
 * mathematically unreachable (in particular, translucent pure white).
 */
internal class PackIconEncoder(private val size: Int = IconArtwork.ICON_SIZE) : AutoCloseable {
    private val source = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    private val shadows = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    private val shadowCanvas = Canvas(shadows)
    private val pixels = IntArray(size * size)
    private val target = IntArray(size * size)
    private val shadowPixels = IntArray(size * size)
    private val offset = IntArray(2)
    // IconOptions.iconScale = .92. Translate native shadow dimensions back into PNG space;
    // do not scale the actual artwork, which the host already positions and scales.
    private val hostScale = (size - 2 * (size * (1f - .92f) / 2f).roundToInt()).toFloat() / size
    private val blurPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        maskFilter = BlurMaskFilter(size * .035f / hostScale, BlurMaskFilter.Blur.NORMAL)
    }
    private val drawPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    fun encode(artwork: Bitmap): Bitmap {
        require(artwork.width == size && artwork.height == size)
        artwork.getPixels(target, 0, size, 0, 0, size, size)
        for (i in pixels.indices) {
            val alpha = inverseAlpha(Color.alpha(target[i]), 0)
            pixels[i] = Color.argb(alpha, 255, 255, 255)
        }
        // The shadow depends on the first draw's alpha, not the target's alpha. Recompute
        // it to a fixed point rather than using a constant interior-opacity correction.
        repeat(6) {
            renderShadows()
            var changed = false
            for (i in pixels.indices) {
                val alpha = inverseAlpha(Color.alpha(target[i]), Color.alpha(shadowPixels[i]))
                if (alpha != Color.alpha(pixels[i])) {
                    pixels[i] = Color.argb(alpha, 255, 255, 255)
                    changed = true
                }
            }
            if (!changed) return finish()
        }
        renderShadows()
        return finish()
    }

    private fun finish(): Bitmap {
        for (i in pixels.indices) {
            val a = Color.alpha(pixels[i]) / 255.0
            if (a == 0.0) {
                pixels[i] = Color.TRANSPARENT
                continue
            }
            val s = Color.alpha(shadowPixels[i]) / 255.0
            // Both source draws contribute colour; the intermediate black shadows do not.
            val colourWeight = a * (1.0 + (1.0 - a) * (1.0 - s))
            val gain = (Color.alpha(target[i]) / 255.0) / colourWeight
            fun channel(value: Int) = (value * gain).roundToInt().coerceIn(0, 255)
            pixels[i] = Color.argb((a * 255).roundToInt(), channel(Color.red(target[i])),
                channel(Color.green(target[i])), channel(Color.blue(target[i])))
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }

    /** Literal ShadowGenerator.drawShadow operations, including blur offsets and both alphas. */
    private fun renderShadows() {
        source.setPixels(pixels, 0, size, 0, 0, size, size)
        shadowCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        val mask = source.extractAlpha(blurPaint, offset)
        try {
            drawPaint.alpha = 25
            shadowCanvas.drawBitmap(mask, offset[0].toFloat(), offset[1].toFloat(), drawPaint)
            drawPaint.alpha = 7
            shadowCanvas.drawBitmap(mask, offset[0].toFloat(), offset[1] + size / 48f / hostScale, drawPaint)
        } finally { mask.recycle() }
        shadows.getPixels(shadowPixels, 0, size, 0, 0, size, size)
    }

    private fun inverseAlpha(target: Int, shadow: Int): Int =
        ALPHA[(target shl 8) or shadow].toInt() and 255

    private companion object {
        // The same 8-bit transfer is evaluated for every pixel of every icon. Resolve
        // square roots once per process, not hundreds of millions of times per build.
        val ALPHA = ByteArray(256 * 256) { index ->
            val target = index ushr 8
            val shadow = index and 255
            when (target) {
                0 -> 0
                255 -> 255
                else -> {
                    val remaining = (255.0 - target) / (255.0 - shadow)
                    ((1.0 - sqrt(remaining.coerceAtMost(1.0))) * 255.0).roundToInt().coerceIn(0, 255)
                }
            }.toByte()
        }
    }

    override fun close() {
        source.recycle()
        shadows.recycle()
    }
}
