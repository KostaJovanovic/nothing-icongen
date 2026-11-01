package space.linuxct.nothing.icongen

import android.app.job.JobParameters
import android.app.job.JobService
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Package broadcasts cannot start a data-sync FGS from the background on target 35. */
class IconPackBuildJobService : JobService() {
    private class Run(val params: JobParameters) {
        @Volatile var stopped = false
    }
    private val runs = ConcurrentHashMap<Int, Run>()

    override fun onStartJob(params: JobParameters): Boolean {
        Log.d("IconPackBuildJobService", "Job started id=${params.jobId}")
        val run = Run(params)
        runs[params.jobId] = run
        worker.execute {
            try {
                if (params.jobId == PackageChangeMonitor.JOB_ID) {
                    if (!run.stopped) PackageChangeMonitor.reconcile(applicationContext)
                } else {
                    while (!run.stopped) {
                        val work = params.dequeueWork() ?: break
                        BuildCoordinator.runPending(applicationContext) { run.stopped }
                        if (!run.stopped) params.completeWork(work)
                    }
                }
            } catch (expired: SecurityException) {
                // Android can revoke the job callback before onStopJob reaches our main thread.
                // Durable requests remain queued; do not use that callback or commit more work.
                run.stopped = true
                Log.w("IconPackBuildJobService", "Job callback expired id=${params.jobId}; retaining queued work", expired)
                BuildCoordinator.waitingForScheduler(applicationContext)
            } finally {
                runs.remove(params.jobId, run)
                if (!run.stopped) jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        Log.d("IconPackBuildJobService", "Job stopped id=${params.jobId}; rescheduling unfinished work")
        runs[params.jobId]?.stopped = true
        if (params.jobId == BuildCoordinator.JOB_ID) BuildCoordinator.waitingForScheduler(applicationContext)
        return true
    }

    override fun onDestroy() {
        runs.values.forEach { it.stopped = true }
        super.onDestroy()
    }

    companion object {
        // A stopped job can be redelivered before its CPU-bound generation returns.
        // One process-wide executor serializes both redeliveries and periodic reconciliation.
        private val worker = Executors.newSingleThreadExecutor()
    }
}
