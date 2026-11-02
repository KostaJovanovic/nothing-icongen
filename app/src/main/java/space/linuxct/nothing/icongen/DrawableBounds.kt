package space.linuxct.nothing.icongen

import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableContainer
import android.graphics.drawable.DrawableWrapper
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.ScaleDrawable
import android.os.Build
import java.util.IdentityHashMap

/** Temporary wrappers can change caller-owned drawable properties as well as child bounds. */
internal object DrawableBounds {
    private class Snapshot(val drawable: Drawable) {
        val bounds = Rect(drawable.bounds)
        val state = drawable.state.copyOf()
        val level = drawable.level
        val visible = drawable.isVisible
        val layoutDirection = if (Build.VERSION.SDK_INT >= 23) drawable.layoutDirection else 0
        val callback = drawable.callback

        fun restore() {
            if (!drawable.state.contentEquals(state)) drawable.state = state
            if (drawable.level != level) drawable.level = level
            if (drawable.isVisible != visible) drawable.setVisible(visible, false)
            if (Build.VERSION.SDK_INT >= 23 && drawable.layoutDirection != layoutDirection) {
                drawable.layoutDirection = layoutDirection
            }
            if (drawable.bounds != bounds) drawable.bounds = bounds
            if (drawable.callback !== callback) drawable.callback = callback
        }
    }

    fun <T> preserve(drawable: Drawable, block: () -> T): T {
        val seen = IdentityHashMap<Drawable, Boolean>()
        val snapshots = ArrayList<Snapshot>()
        fun visit(node: Drawable?) {
            if (node == null || seen.put(node, true) != null) return
            val snapshot = Snapshot(node)
            when {
                Build.VERSION.SDK_INT >= 26 && node is AdaptiveIconDrawable -> {
                    visit(node.background)
                    visit(node.foreground)
                    if (Build.VERSION.SDK_INT >= 33) visit(node.monochrome)
                }
                node is LayerDrawable -> repeat(node.numberOfLayers) { visit(node.getDrawable(it)) }
                node is InsetDrawable -> visit(node.drawable)
                node is ScaleDrawable -> visit(node.drawable)
                node is ClipDrawable -> visit(node.drawable)
                Build.VERSION.SDK_INT >= 23 && node is DrawableWrapper -> visit(node.drawable)
                node is DrawableContainer -> {
                    val state = node.constantState as? DrawableContainer.DrawableContainerState
                    if (state != null) repeat(state.childCount) { visit(state.getChild(it)) }
                    else visit(node.current.takeUnless { it === node })
                }
            }
            snapshots.add(snapshot)
        }
        visit(drawable)
        try {
            return block()
        } finally {
            // Reverse postorder restores every parent before its children, including shared children.
            for (index in snapshots.indices.reversed()) snapshots[index].restore()
        }
    }
}
