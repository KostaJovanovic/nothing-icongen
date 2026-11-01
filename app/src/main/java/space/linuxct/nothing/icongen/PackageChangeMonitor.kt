package space.linuxct.nothing.icongen

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.os.Process
import android.os.UserManager
import android.util.Log
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject

/** Immediate while resident; persisted identities also detect installs/updates after process death. */
object PackageChangeMonitor {
    const val JOB_ID = 4402
    private const val PREFS = "package_monitor"
    private const val INSTALLED = "installed_fingerprint"
    private const val OBSERVED = "observed_packages"

    fun register(context: Context) {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }
        context.registerReceiver(PackageChangeReceiver(), filter, Context.RECEIVER_EXPORTED)
        // A missing baseline means first installation, not that every existing app is new.
        // An existing baseline survives process death and is reconciled, not overwritten.
        reconcile(context)
        ensureScheduled(context)
        BuildCoordinator.resumeQueued(context)
    }

    fun ensureScheduled(context: Context) {
        if (!context.getSharedPreferences(PackageChangeReceiver.PREFS, Context.MODE_PRIVATE)
                .getBoolean(PackageChangeReceiver.KEY_ENABLED, true)) return
        val scheduler = context.getSystemService(JobScheduler::class.java)
        if (scheduler.getPendingJob(JOB_ID) == null) {
            val result = scheduler.schedule(JobInfo.Builder(JOB_ID, ComponentName(context, IconPackBuildJobService::class.java))
                .setPeriodic(15 * 60 * 1000L).setPersisted(true).build())
            if (result != JobScheduler.RESULT_SUCCESS) {
                BuildCoordinator.update { it.copy(phase = BuildCoordinator.Phase.FAILED,
                    error = "Android rejected automatic package monitoring; enable auto-update to retry") }
            }
        }
    }

    /** Package/profile identities deliberately exclude components, theme and permission state. */
    private fun identities(context: Context): Map<String, String> {
        val records = HashMap<String, String>()
        val users = context.getSystemService(UserManager::class.java)
        val currentUser = Process.myUserHandle()
        val currentProfile = users.getSerialNumberForUser(currentUser)
        @Suppress("DEPRECATION")
        val installed = context.packageManager.getInstalledPackages(0)
        for (info in installed) {
            if (excluded(context, info.packageName)) continue
            records["$currentProfile:${info.packageName}"] = "${info.longVersionCode}:${info.lastUpdateTime}"
        }
        val launcherApps = context.getSystemService(LauncherApps::class.java)
        for (user in launcherApps.profiles) {
            if (user == currentUser) continue
            val profile = users.getSerialNumberForUser(user)
            for (activity in launcherApps.getActivityList(null, user)) {
                val packageName = activity.componentName.packageName
                if (excluded(context, packageName)) continue
                // The current-user PackageManager can report the wrong profile's version.
                // The other profile's APK metadata comes from LauncherApps itself instead.
                val source = File(activity.applicationInfo.sourceDir)
                records["$profile:$packageName"] =
                    "${activity.firstInstallTime}:${source.absolutePath}:${source.lastModified()}:${source.length()}"
            }
        }
        return records
    }

    private fun excluded(context: Context, packageName: String) =
        packageName == context.packageName || packageName == PackageChangeReceiver.PACK_PACKAGE

    fun fingerprint(context: Context): String {
        val bytes = identities(context).toSortedMap().entries.joinToString("\n") { "${it.key}=${it.value}" }
            .toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }

    @Synchronized
    fun reconcile(context: Context) = reconcileSnapshot(context, identities(context))

    /** Toggling monitoring consumes the current baseline without manufacturing an app event. */
    @Synchronized
    fun observeCurrent(context: Context) {
        reconcileSnapshot(context, identities(context), allowBuild = false)
    }

    @Synchronized
    fun packageAdded(context: Context, packageName: String) {
        reconcileSnapshot(context, identities(context), addedPackage = packageName)
    }

    /** Removal must not consume another package's install/update awaiting its broadcast. */
    @Synchronized
    fun packageRemoved(context: Context, packageName: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = prefs.getString(OBSERVED, null) ?: return
        val previous = JSONObject(saved)
        val current = identities(context)
        val removed = previous.keys().asSequence().filter {
            it.substringAfter(':') == packageName && it !in current
        }.toList()
        removed.forEach(previous::remove)
        prefs.edit().putString(OBSERVED, previous.toString()).commit()
    }

    /** Persist observation BEFORE requesting work: stopped/failed requests are not scan-replayed. */
    @Synchronized
    private fun reconcileSnapshot(context: Context, current: Map<String, String>,
                                   addedPackage: String? = null, allowBuild: Boolean = true) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val previous = prefs.getString(OBSERVED, null)?.let(::JSONObject)
        val changed = if (previous == null) {
            // An authoritative live install event can precede the application's first baseline.
            addedPackage != null && current.keys.any { it.substringAfter(':') == addedPackage }
        } else current.any { (key, identity) -> !previous.has(key) || previous.getString(key) != identity }
        prefs.edit().putString(OBSERVED, JSONObject(current).toString()).commit()
        val enabled = context.getSharedPreferences(PackageChangeReceiver.PREFS, Context.MODE_PRIVATE)
            .getBoolean(PackageChangeReceiver.KEY_ENABLED, true)
        Log.d("PackageChangeMonitor", "Observed ${current.size} packages installOrUpdate=$changed enabled=$enabled")
        if (changed && allowBuild && enabled) BuildCoordinator.start(context, "package:install-or-update")
    }

    /** Only the snapshot taken BEFORE the successful build is acknowledged. */
    fun installed(context: Context, fingerprint: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(INSTALLED, fingerprint).commit()
        Log.d("PackageChangeMonitor", "Installed fingerprint=$fingerprint")
    }
}
