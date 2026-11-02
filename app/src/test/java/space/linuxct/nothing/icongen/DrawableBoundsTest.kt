package space.linuxct.nothing.icongen

import android.app.Application
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.view.View
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [35])
class DrawableBoundsTest {
    @Test
    fun sharedChildIsRestoredAfterBothParentsWithNoncanonicalBounds() {
        val child = RecordingDrawable()
        val first = InsetDrawable(child, 3)
        val second = InsetDrawable(child, 11)
        val root = LayerDrawable(arrayOf(first, second))
        root.state = intArrayOf(android.R.attr.state_selected)
        first.state = intArrayOf(android.R.attr.state_checked)
        second.state = intArrayOf(android.R.attr.state_pressed)
        child.state = intArrayOf(android.R.attr.state_focused)
        root.level = 1000
        first.level = 2000
        second.level = 3000
        child.level = 4000
        root.setBounds(10, 20, 110, 140)
        first.setBounds(13, 17, 83, 99)
        second.setBounds(21, 29, 121, 139)
        child.setBounds(31, 37, 59, 73)
        val owner = OwnerCallback()
        root.callback = owner
        child.callback = owner
        val originals = listOf(root, first, second, child).map(::SavedProperties)

        DrawableBounds.preserve(root) {
            val temporary = InsetDrawable(root, 17)
            temporary.state = intArrayOf(android.R.attr.state_enabled)
            temporary.level = 9000
            temporary.setBounds(0, 0, 432, 432)
            assertNotEquals(originals.last().bounds, child.bounds)
        }

        originals.forEach { it.assertRestored() }
    }

    @Test
    fun temporaryWrappersRestoreTreePropertiesAndCallbacks() {
        assertTemporaryWrappersRestore(throwFromBlock = false)
    }

    @Test
    fun throwingBlockStillRestoresTreePropertiesAndCallbacks() {
        assertTemporaryWrappersRestore(throwFromBlock = true)
    }

    @Test
    fun stateSnapshotDoesNotAliasCallerArrayMutatedInsideBlock() {
        val drawable = RecordingDrawable()
        drawable.state = intArrayOf(android.R.attr.state_checked, android.R.attr.state_enabled)

        DrawableBounds.preserve(drawable) {
            drawable.state[0] = android.R.attr.state_pressed
        }

        assertArrayEquals(
            intArrayOf(android.R.attr.state_checked, android.R.attr.state_enabled), drawable.state
        )
    }


    private fun assertTemporaryWrappersRestore(throwFromBlock: Boolean) {
        val child = RecordingDrawable()
        val root = InsetDrawable(child, 5)
        root.state = intArrayOf(android.R.attr.state_checked, android.R.attr.state_enabled)
        root.level = 1200
        root.setVisible(false, false)
        root.layoutDirection = View.LAYOUT_DIRECTION_RTL
        root.setBounds(7, 11, 107, 131)
        child.state = intArrayOf(android.R.attr.state_selected)
        child.level = 3400
        child.setVisible(false, false)
        child.layoutDirection = View.LAYOUT_DIRECTION_RTL
        child.setBounds(19, 23, 71, 89)
        val owner = OwnerCallback()
        root.callback = owner
        child.callback = owner
        val originals = listOf(root, child).map(::SavedProperties)
        val failure = IllegalStateException("rendering failed")

        try {
            val result = DrawableBounds.preserve(root) {
                val wrapper = InsetDrawable(root, 13)
                wrapper.state = intArrayOf(android.R.attr.state_pressed)
                wrapper.level = 8700
                wrapper.setVisible(true, true)
                wrapper.layoutDirection = View.LAYOUT_DIRECTION_LTR
                wrapper.setBounds(0, 0, 432, 432)
                val childWrapper = InsetDrawable(child, 2)
                childWrapper.state = intArrayOf(android.R.attr.state_focused)
                childWrapper.level = 9600
                childWrapper.setVisible(true, true)
                childWrapper.layoutDirection = View.LAYOUT_DIRECTION_LTR
                childWrapper.setBounds(1, 2, 101, 202)
                assertSame(wrapper, root.callback)
                assertSame(childWrapper, child.callback)
                for (original in originals) original.assertChanged()
                if (throwFromBlock) throw failure
                42
            }
            assertFalse("the throwing block must propagate its exception", throwFromBlock)
            assertEquals(42, result)
        } catch (actual: IllegalStateException) {
            if (!throwFromBlock) throw actual
            assertSame(failure, actual)
        }

        originals.forEach { it.assertRestored() }
    }

    private class SavedProperties(private val drawable: Drawable) {
        val bounds = Rect(drawable.bounds)
        private val state = drawable.state.copyOf()
        private val level = drawable.level
        private val visible = drawable.isVisible
        private val layoutDirection = drawable.layoutDirection
        private val callback = drawable.callback

        fun assertRestored() {
            assertEquals(bounds, drawable.bounds)
            assertArrayEquals(state, drawable.state)
            assertEquals(level, drawable.level)
            assertEquals(visible, drawable.isVisible)
            assertEquals(layoutDirection, drawable.layoutDirection)
            assertSame(callback, drawable.callback)
        }

        fun assertChanged() {
            assertNotEquals(bounds, drawable.bounds)
            assertFalse(state.contentEquals(drawable.state))
            assertNotEquals(level, drawable.level)
            assertNotEquals(visible, drawable.isVisible)
            assertNotEquals(layoutDirection, drawable.layoutDirection)
            assertNotSame(callback, drawable.callback)
        }
    }

    private class OwnerCallback : Drawable.Callback {
        override fun invalidateDrawable(who: Drawable) = Unit
        override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) = Unit
        override fun unscheduleDrawable(who: Drawable, what: Runnable) = Unit
    }

    private class RecordingDrawable : Drawable() {

        override fun isStateful(): Boolean = true
        override fun onStateChange(state: IntArray): Boolean = true
        override fun onLevelChange(level: Int): Boolean = true
        override fun draw(canvas: Canvas) = Unit
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        @Deprecated("Deprecated in Android")
        override fun getOpacity(): Int = PixelFormat.TRANSPARENT
    }
}
