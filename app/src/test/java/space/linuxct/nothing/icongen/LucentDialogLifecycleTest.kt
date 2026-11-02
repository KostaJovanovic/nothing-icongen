package space.linuxct.nothing.icongen

import android.app.Application
import android.os.Looper
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class LucentDialogLifecycleTest {
    @Test fun destroyingOwnerImmediatelyRemovesEverySharedDialogWindow() {
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        controller.get().setTheme(R.style.Theme_NothingIconGenerator)
        val activity = controller.setup().get()
        val ui = LucentUi(activity)
        val dialogs = listOf("Setup", "Settings access", "Icon pack", "Build details", "Before pairing").map { title ->
            ui.dialog(activity, title) { content, _ ->
                ui.add(content, TextView(activity).apply { text = title })
            }
        }
        shadowOf(Looper.getMainLooper()).idle()
        val windows = dialogs.map { requireNotNull(it.window).decorView }
        try {
            dialogs.forEach { assertTrue(it.isShowing) }
            windows.forEach { assertTrue(it.isAttachedToWindow) }
        } finally {
            controller.pause().stop().destroy()
        }
        dialogs.forEach { assertFalse(it.isShowing) }
        windows.forEach { assertFalse(it.isAttachedToWindow) }
    }

    @Test fun destroyingOwnerInterruptsAnimatedDismissWithoutWaitingForItsEndAction() {
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        controller.get().setTheme(R.style.Theme_NothingIconGenerator)
        val activity = controller.setup().get()
        val dialog = LucentUi(activity).dialog(activity, "Setup") { _, _ -> }
        val window = requireNotNull(dialog.window).decorView
        try {
            dialog.dismiss()
            assertTrue(dialog.isShowing)
        } finally {
            controller.pause().stop().destroy()
        }
        assertFalse(dialog.isShowing)
        assertFalse(window.isAttachedToWindow)
    }
}
