package space.linuxct.nothing.icongen

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.util.Log

/** One process-wide lock covers theme work, install jobs, cooldown and mandatory restoration. */
internal class IconPackSelector(
    private val now: () -> Long = SystemClock::elapsedRealtime,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) {
    companion object {
        val shared = IconPackSelector()
        private const val SELECTOR = "nothing_icon_pack"
        private const val TEMPORARY_PACK = "THEMED_ICONS_NOTHING"
    }

    private var lastStart: Long? = null

    @Synchronized
    fun apply(
        context: Context,
        install: IconPackInstaller.ReapplyRequest? = null,
        themeRequested: Boolean = false,
        cancelled: () -> Boolean = { false },
    ): Boolean {
        fun permitted(): Boolean = !cancelled() && if (install != null) {
            IconPackInstaller.pendingReapply(context) == install &&
                IconPackInstaller.canReapplyInstalledPack(context)
        } else themeRequested && OnboardingState.isComplete(context) && ThemeRefreshController.isEnabled(context)

        if (!permitted()) return false
        val due = lastStart?.plus(5_000L) ?: now()
        while (now() < due) {
            if (!permitted()) return false
            sleep((due - now()).coerceIn(1L, 100L))
        }
        if (!permitted()) return false
        if (!ThemeRefreshController.canWriteSettings(context) || !ThemeRefreshController.isPackInstalled(context)) {
            failure(context, "Icon pack reapply paused: Modify system settings access and the installed pack are required")
            return false
        }
        if (ThemeRefreshController.restorationPending(context) && !restore(context)) return false
        lastStart = now()
        var restorationRequired = false
        var firstWriteSucceeded = false
        var interrupted = false
        var failed = false
        var restored = false
        try {
            sleep(500L)
            if (!permitted()) return false
            check(ThemeRefreshController.setRestorationPending(context, true)) { "Could not persist icon selector recovery" }
            restorationRequired = true
            firstWriteSucceeded = Settings.System.putString(context.contentResolver, SELECTOR, TEMPORARY_PACK)
            check(firstWriteSucceeded) { "Android rejected the temporary icon selector" }
            // Cancellation never interrupts this interval or the mandatory restore below.
            sleep(500L)
        } catch (e: InterruptedException) {
            interrupted = true
            failure(context, "Icon pack refresh interrupted; restoring the generated pack", e)
        } catch (e: Exception) {
            failed = true
            failure(context, "Android could not select the icon pack", e)
        } finally {
            if (restorationRequired) restored = restore(context, force = firstWriteSucceeded)
            if (interrupted) Thread.currentThread().interrupt()
        }
        val succeeded = firstWriteSucceeded && restored && !interrupted && !failed
        if (succeeded) {
            if (install != null) IconPackInstaller.completeReapply(context, install)
            IconPackBuildService.cancelThemeAccessReminder(context)
            ThemeRefreshController.recordStatus(context, if (ThemeRefreshController.isEnabled(context)) {
                "Theme monitor active; generated icon pack reapplied"
            } else "Generated icon pack reapplied; theme detection is off")
        }
        return succeeded
    }

    /** Recovery is allowed before onboarding and after disable; it only undoes OUR temporary write. */
    @Synchronized
    fun restore(context: Context, force: Boolean = false): Boolean {
        if (!force && !ThemeRefreshController.restorationPending(context)) return true
        return try {
            if (force || Settings.System.getString(context.contentResolver, SELECTOR) == TEMPORARY_PACK) {
                check(ThemeRefreshController.canWriteSettings(context)) { "Modify system settings access is needed to restore the pack" }
                check(ThemeRefreshController.isPackInstalled(context)) { "The generated pack is not installed" }
                check(Settings.System.putString(context.contentResolver, SELECTOR, PackageChangeReceiver.PACK_PACKAGE)) {
                    "Android rejected restoring the generated icon pack"
                }
            }
            check(ThemeRefreshController.setRestorationPending(context, false)) { "Could not clear selector recovery" }
            true
        } catch (e: Exception) {
            failure(context, "Icon selector recovery pending", e)
            false
        }
    }

    private fun failure(context: Context, message: String, error: Exception? = null) {
        val detail = error?.let { ": ${it.message ?: it.javaClass.simpleName}" }.orEmpty()
        ThemeRefreshController.recordStatus(context, message + detail)
        Log.w("IconPackSelector", message, error)
        IconPackBuildService.postThemeAccessReminder(context, message + detail)
    }
}
