package space.linuxct.nothing.icongen

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/** Android's background-launch grant is used for launcher preparation; no overlay is displayed. */
object LauncherRefreshAccess {
    internal const val REQUIRED_MESSAGE =
        "Installed icon pack reapply paused: allow background launcher preparation in Display over other apps"

    fun isGranted(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun settingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
}
