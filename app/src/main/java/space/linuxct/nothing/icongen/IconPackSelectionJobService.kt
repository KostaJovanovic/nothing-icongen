package space.linuxct.nothing.icongen

import android.app.ActivityOptions
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executors

/** Finite install/recovery work, never a foreground service and never a monitor notification. */
class IconPackSelectionJobService : JobService() {
    companion object {
        internal const val JOB_ID = 4403
        internal const val POST_INSTALL_SETTLE_MS = 5_000L
        private val worker = Executors.newSingleThreadExecutor()

        internal fun hasPendingWork(context: Context): Boolean =
            ThemeRefreshController.restorationPending(context) || IconPackInstaller.pendingReapplyVersion(context) != null

        fun schedule(context: Context) = schedule(context, replaceExisting = false)

        internal fun scheduleConfirmedInstall(context: Context) = schedule(context, replaceExisting = true)

        /** Access recovery must replace an old denied-access backoff, not merely ensure it exists. */
        fun scheduleAfterAccessChange(context: Context) {
            if (!ThemeRefreshController.canWriteSettings(context) || !ThemeRefreshController.isPackInstalled(context)) return
            val canRestore = ThemeRefreshController.restorationPending(context)
            val canApply = IconPackInstaller.canReapplyInstalledPack(context) && LauncherRefreshAccess.isGranted(context)
            if (canRestore || canApply) schedule(context, replaceExisting = true)
        }

        private fun schedule(context: Context, replaceExisting: Boolean) {
            if (!hasPendingWork(context)) return
            val scheduler = context.getSystemService(JobScheduler::class.java)
            // New install acknowledgements and recovered access replace a finishing
            // job so its last hasPendingWork check cannot lose work before jobFinished.
            if (!replaceExisting && scheduler.getPendingJob(JOB_ID) != null) return
            // Session success precedes launcher package/model work. Keep this boundary
            // in the persisted job, separate from the selector's 500/500 ms transaction.
            val settle = if (IconPackInstaller.pendingReapply(context) != null) POST_INSTALL_SETTLE_MS else 0L
            val result = scheduler.schedule(JobInfo.Builder(JOB_ID,
                ComponentName(context, IconPackSelectionJobService::class.java))
                .setPersisted(true)
                .setMinimumLatency(settle)
                .setOverrideDeadline(settle)
                .setBackoffCriteria(30_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .build())
            if (result != JobScheduler.RESULT_SUCCESS) {
                ThemeRefreshController.recordStatus(context, "Android could not schedule icon pack reapply; the request is retained")
                Log.w("IconPackSelectionJob", "Android rejected selector recovery job")
            }
        }

        internal fun applyInstalledPack(
            context: Context,
            request: IconPackInstaller.ReapplyRequest,
            cancelled: () -> Boolean,
            selector: IconPackSelector = IconPackSelector.shared,
            prepare: (Context) -> Unit = ::prepareInstalledPack,
        ): Boolean {
            if (cancelled() || IconPackInstaller.pendingReapply(context) != request ||
                !IconPackInstaller.canReapplyInstalledPack(context)) return false
            if (!ThemeRefreshController.canWriteSettings(context)) {
                return selector.apply(context, install = request, cancelled = cancelled)
            }
            if (!LauncherRefreshAccess.isGranted(context)) {
                ThemeRefreshController.recordStatus(context, LauncherRefreshAccess.REQUIRED_MESSAGE)
                return false
            }
            prepare(context)
            return selector.apply(context, install = request, cancelled = cancelled)
        }

        private fun prepareInstalledPack(context: Context) {
            // The picker reloads parsed external-pack entities in onViewCreated.
            // Settings selection alone only reloads bitmaps from those cached entities.
            val intent = Intent("com.nothing.launcher.icon_pack_picker")
                .setPackage("com.nothing.launcher")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT or
                    Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            context.startActivity(intent, ActivityOptions.makeTaskLaunchBehind().toBundle())
        }
    }

    private class Run(val params: JobParameters) {
        @Volatile var stopped = false
    }
    private val handler = Handler(Looper.getMainLooper())
    private var active: Run? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val run = Run(params)
        active = run
        worker.execute {
            try {
                if (!run.stopped && IconPackSelector.shared.restore(applicationContext)) {
                    val request = IconPackInstaller.pendingReapply(applicationContext)
                    if (!run.stopped && request != null) {
                        applyInstalledPack(applicationContext, request, cancelled = { run.stopped })
                    }
                }
            } catch (e: Exception) {
                Log.w("IconPackSelectionJob", "Selector work retained for retry", e)
            } finally {
                handler.post {
                    if (active === run) active = null
                    if (!run.stopped) jobFinished(params, hasPendingWork(applicationContext))
                }
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        active?.takeIf { it.params.jobId == params.jobId }?.stopped = true
        return hasPendingWork(applicationContext)
    }

    override fun onDestroy() {
        active?.stopped = true
        super.onDestroy()
    }
}
