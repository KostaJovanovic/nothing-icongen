package space.linuxct.nothing.icongen

import android.graphics.Color
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IconArtworkTest {
    @Test
    fun nativePoolReuseDoesNotErasePreviouslyReturnedGlyphsOrChangeSourceBounds() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        NativeLauncherOracle.requireLauncher(context)
        val apps = AppScanner.scanInstalledApps(context)
        val play = requireNotNull(apps.firstOrNull { it.componentName.substringBefore('/') == "com.android.vending" })
        val sourceBounds = Rect(7, 11, 103, 109)
        val original = Rect(play.icon.bounds)
        play.icon.bounds = sourceBounds
        try {
            IconPipeline(context).use { pipeline ->
                val first = pipeline.glyph(play.icon)
                try {
                    assertEquals("renderer mutated caller's bounds", sourceBounds, play.icon.bounds)
                    val normal = IntArray(first.normal.width * first.normal.height)
                    first.normal.getPixels(normal, 0, first.normal.width, 0, 0, first.normal.width, first.normal.height)
                    val light = first.light?.let { mask ->
                        IntArray(mask.width * mask.height).also { mask.getPixels(it, 0, mask.width, 0, 0, mask.width, mask.height) }
                    }
                    // Reusing the native allocator used to silently clear the previous result.
                    val glyphworks = requireNotNull(apps.firstOrNull { it.componentName.substringBefore('/') == "space.linuxct.glyphworks" })
                    repeat(4) { pipeline.glyph(glyphworks.icon).recycle() }
                    val after = IntArray(normal.size)
                    first.normal.getPixels(after, 0, first.normal.width, 0, 0, first.normal.width, first.normal.height)
                    assertArrayEquals("normal glyph retained a native pool lease", normal, after)
                    first.light?.let { mask ->
                        val afterLight = IntArray(mask.width * mask.height)
                        mask.getPixels(afterLight, 0, mask.width, 0, 0, mask.width, mask.height)
                        assertArrayEquals("light glyph retained a native pool lease", requireNotNull(light), afterLight)
                    }
                } finally { first.recycle() }
            }
        } finally { play.icon.bounds = original }
    }

}
