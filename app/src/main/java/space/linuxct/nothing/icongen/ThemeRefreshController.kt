package space.linuxct.nothing.icongen

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings

/** Theme detection and verified installs share selector refresh; neither requests icon generation. */
object ThemeRefreshController {
    private const val PREFS = "theme_refresh"
    private const val ENABLED = "enabled"
    private const val STATUS = "status"
    private const val RESTORE_PENDING = "restore_pending"

    fun isEnabled(context: Context): Boolean = preferences(context).getBoolean(ENABLED, true)

    @Synchronized
    fun setEnabled(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(ENABLED, enabled).commit()
        if (!enabled) IconPackBuildService.cancelThemeAccessReminder(context)
        sync(context, refresh = enabled)
    }

    fun sync(context: Context, refresh: Boolean = false) = BackgroundMonitorService.sync(context, refresh)

    fun canWriteSettings(context: Context): Boolean = Settings.System.canWrite(context)

    fun status(context: Context): String {
        val state = when {
            restorationPending(context) && !canWriteSettings(context) ->
                "Icon selector recovery needs Modify system settings access"
            IconPackInstaller.pendingReapplyVersion(context) != null && !canWriteSettings(context) ->
                "Installed icon pack reapply paused: allow Modify system settings"
            IconPackInstaller.pendingReapplyVersion(context) != null &&
                !IconPackInstaller.canReapplyInstalledPack(context) ->
                "Icon pack reapply waiting for the verified installed update"
            IconPackInstaller.pendingReapplyVersion(context) != null && !LauncherRefreshAccess.isGranted(context) ->
                LauncherRefreshAccess.REQUIRED_MESSAGE
            IconPackInstaller.pendingReapplyVersion(context) != null ->
                "Installed icon pack waiting to be reapplied"
            !isEnabled(context) -> "Theme refresh disabled"
            !canWriteSettings(context) -> "Theme refresh paused: allow Modify system settings"
            !isPackInstalled(context) -> "Theme refresh waiting for the generated icon pack to be installed"
            else -> preferences(context).getString(STATUS, "Theme monitor starting")!!
        }
        return if (!NotificationPreferences.isEnabled(context) && BackgroundMonitorService.isNeeded(context)) {
            "$state. Android still requires the silent theme detection notification"
        } else state
    }

    internal fun isPackInstalled(context: Context): Boolean = try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(PackageChangeReceiver.PACK_PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    internal fun restorationPending(context: Context): Boolean =
        preferences(context).getBoolean(RESTORE_PENDING, false)

    /** Written before selecting the temporary pack, so process death is recoverable. */
    internal fun setRestorationPending(context: Context, pending: Boolean): Boolean =
        preferences(context).edit().putBoolean(RESTORE_PENDING, pending).commit()

    internal fun recordStatus(context: Context, status: String) {
        val prefs = preferences(context)
        if (prefs.getString(STATUS, null) != status) prefs.edit().putString(STATUS, status).apply()
    }

    private fun preferences(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
