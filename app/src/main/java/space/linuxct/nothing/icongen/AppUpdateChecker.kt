package space.linuxct.nothing.icongen

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream

/**
 * Optional daily GitHub release check for IconGen updates.
 * No auto-install; releases point users to GitHub + ADB instructions.
 */
object AppUpdateChecker {
    private const val TAG = "AppUpdateChecker"
    const val CHANNEL_ID = "icongen_releases"
    private const val PREFS_KEY = "app_update_enabled"
    private const val DECISION_KEY = "app_update_decision"
    private const val LAST_CHECK_KEY = "app_update_last_check"
    private const val LAST_RELEASE_KEY = "app_update_last_release"
    private const val JOB_ID = 4405
    private const val INTERVAL_MS = 24L * 60 * 60 * 1000

    private val executor = Executors.newFixedThreadPool(2)

    private fun prefs(context: Context) =
        context.getSharedPreferences("app_update_prefs", Context.MODE_PRIVATE)

    /** Whether update checks are enabled AND user has made a decision. */
    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(PREFS_KEY, false) && hasDecision(context)

    /** Whether the user has explicitly accepted or declined. */
    fun hasDecision(context: Context): Boolean =
        prefs(context).contains(DECISION_KEY)

    /**
     * Persist user choice. Schedules/cancels the periodic job accordingly.
     * Also cancels any pending release notification.
     */
    fun setEnabled(context: Context, enabled: Boolean) {
        val p = prefs(context)
        p.edit().putBoolean(PREFS_KEY, enabled).putBoolean(DECISION_KEY, true).apply()
        if (enabled) {
            schedule(context)
        } else {
            cancel(context)
            cancelNotification(context)
        }
    }

    /** Ensure the periodic job is scheduled if enabled+decided. Idempotent. */
    fun sync(context: Context) {
        if (isEnabled(context)) {
            schedule(context)
        } else {
            cancel(context)
        }
    }

    /** Ensure the release notification channel exists. */
    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        val channel = android.app.NotificationChannel(
            CHANNEL_ID,
            "IconGen updates",
            android.app.NotificationManager.IMPORTANCE_LOW
        ).apply {
            setShowBadge(false)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
        }
        manager.createNotificationChannel(channel)
    }

    /** Cancel any notification for updates. */
    fun cancelNotification(context: Context) {
        context.getSystemService(android.app.NotificationManager::class.java)
            .cancel(JOB_ID)
    }

    private fun schedule(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        val builder = JobInfo.Builder(JOB_ID, ComponentName(context, UpdateCheckJob::class.java))
            .setPeriodic(INTERVAL_MS)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
            .setRequiresDeviceIdle(false)
            .setRequiresCharging(false)
        try {
            scheduler.schedule(builder.build())
        } catch (e: Exception) {
            Log.w(TAG, "Scheduling failed: ${e.message}")
        }
    }

    private fun cancel(context: Context) {
        try {
            context.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
        } catch (_: Exception) {
            // No-op
        }
    }

    /** Check if a scheduled job exists for this app. */
    fun isScheduled(context: Context): Boolean {
        try {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            val jobs = scheduler.getAllPendingJobs()
            for (job in jobs) {
                if (job.id == JOB_ID && job.service?.packageName == context.packageName) {
                    return true
                }
            }
            return false
        } catch (_: Exception) {
            return false
        }
    }

    private fun lastCheckTime(context: Context): Long = prefs(context).getLong(LAST_CHECK_KEY, 0)
    private fun lastReleaseTag(context: Context): String? = prefs(context).getString(LAST_RELEASE_KEY, null)

    /**
     * Release info for display. null means no check yet or failure.
     */
    data class ReleaseInfo(
        val tagName: String,
        val title: String,
        val url: String,
        val releasedAt: String
    )

    /**
     * Fetch latest release from GitHub API.
     * Returns latest non-draft release with an APK asset, or null.
     */
    internal fun fetchLatestRelease(): ReleaseInfo? {
        try {
            val url = URL("https://api.github.com/repos/linuxct/nothing-icongen/releases?per_page=1")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("User-Agent", "IconGen-Android/1.0")
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json")

            val code = conn.responseCode
            if (code != 200) {
                Log.w(TAG, "GitHub API returned: $code")
                return null
            }

            val inputStream = conn.content?.let {
                if (conn.contentEncoding?.contains("gzip", ignoreCase = true) == true) {
                    GZIPInputStream(it as java.io.InputStream)
                } else {
                    it as java.io.InputStream
                }
            }

            val reader = BufferedReader(InputStreamReader(inputStream ?: conn.inputStream as java.io.InputStream))
            val json = reader.readText()

            val releases = parseReleases(json)
            if (releases.isEmpty()) return null

            // Take first (most recent) release with an APK asset
            for (release in releases) {
                if (release.draft) continue
                if (release.apkUrl.isNullOrEmpty()) continue
                return ReleaseInfo(
                    tagName = release.tagName,
                    title = release.title,
                    url = release.url,
                    releasedAt = release.publishedAt
                )
            }
            return null
        } catch (e: Exception) {
            Log.w(TAG, "Fetch failed", e)
            return null
        }
    }

    private data class RawRelease(
        val tagName: String,
        val title: String,
        val url: String,
        val publishedAt: String,
        val draft: Boolean,
        val apkUrl: String?
    )

    private fun parseReleases(json: String): List<RawRelease> {
        val releases = mutableListOf<RawRelease>()
        var depth = 0
        var objStart = -1
        var inString = false

        for (i in json.indices) {
            val ch = json[i]
            when {
                ch == '"' -> {
                    inString = !inString
                }
                inString -> continue
                ch == '{' -> {
                    depth++
                    if (depth == 1) objStart = i
                }
                ch == '}' -> {
                    depth--
                    if (depth == 0 && objStart >= 0) {
                        val obj = json.substring(objStart, i + 1)
                        releases.add(parseSingleRelease(obj))
                        objStart = -1
                    }
                }
            }
        }
        return releases
    }

    private fun parseSingleRelease(obj: String): RawRelease {
        var tagName = ""; var title = ""; var url = ""; var publishedAt = ""; var draft = false; var apkUrl: String? = null

        var i = 0
        while (i < obj.length) {
            val keyIdx = obj.indexOf("\"", i)
            if (keyIdx < 0) break
            val keyEnd = obj.indexOf("\"", keyIdx + 1)
            if (keyEnd < 0) break
            val key = obj.substring(keyIdx + 1, keyEnd)
            i = keyEnd + 1

            while (i < obj.length && obj[i] in " \n\r\t:") i++

            if (i < obj.length && obj[i] == '"') {
                i++
                val valueStart = i
                while (i < obj.length && obj[i] != '"') {
                    if (obj[i] == '\\') i++
                    i++
                }
                val value = obj.substring(valueStart, i)
                when (key) {
                    "tag_name" -> tagName = value
                    "name" -> title = value
                    "html_url" -> url = value
                    "published_at" -> publishedAt = value
                    "draft" -> draft = value.toBoolean()
                }
                i++
            } else {
                val valueStart = i
                while (i < obj.length && obj[i] !in "\"{},] \n\r\t:") i++
                val valueStr = obj.substring(valueStart, i)
                if (key == "draft") draft = valueStr == "true"
            }
        }

        // Extract assets - find the APK download URL
        val assetsIdx = obj.indexOf("\"assets\"")
        if (assetsIdx >= 0) {
            val assetsStart = obj.indexOf("[", assetsIdx)
            if (assetsStart > assetsIdx) {
                val assetsEnd = obj.indexOf("]", assetsStart)
                if (assetsEnd > assetsStart) {
                    val assets = obj.substring(assetsStart, assetsEnd + 1)
                    // Find browser_download_url near .apk
                    val apkIdx = assets.lastIndexOf(".apk")
                    if (apkIdx > 0) {
                        val searchEnd = minOf(apkIdx, assets.length - 20)
                        val urlIdx = assets.lastIndexOf("\"browser_download_url\"", searchEnd)
                        if (urlIdx > 0) {
                            val quoteStart = assets.indexOf("\"", urlIdx + 21)
                            val quoteEnd = assets.indexOf("\"", quoteStart + 1)
                            if (quoteEnd > quoteStart) {
                                apkUrl = assets.substring(quoteStart + 1, quoteEnd)
                            }
                        }
                    }
                }
            }
        }

        return RawRelease(tagName, title, url, publishedAt, draft, apkUrl)
    }

    /**
     * Perform the actual check. Returns true if a new release was found (and notification posted).
     */
    internal fun check(context: Context): Boolean {
        ensureChannel(context)

        if (!NotificationPreferences.isEnabled(context)) {
            prefs(context).edit().putLong(LAST_CHECK_KEY, System.currentTimeMillis()).apply()
            return false
        }

        val currentReleaseTag = lastReleaseTag(context)
        val now = System.currentTimeMillis()
        val lastCheck = lastCheckTime(context)

        // Only check if enough time has passed
        if (now - lastCheck < INTERVAL_MS && lastCheck > 0) {
            return false
        }

        val release = fetchLatestRelease() ?: run {
            prefs(context).edit().putLong(LAST_CHECK_KEY, now).apply()
            return false
        }

        prefs(context).edit()
            .putLong(LAST_CHECK_KEY, now)
            .putString(LAST_RELEASE_KEY, release.tagName)
            .apply()

        if (release.tagName == currentReleaseTag) {
            return false
        }

        postReleaseNotification(context, release)
        return true
    }

    private fun postReleaseNotification(context: Context, release: ReleaseInfo) {
        val notificationManager = context.getSystemService(android.app.NotificationManager::class.java)
        val pendingIntent = android.app.PendingIntent.getActivity(
            context,
            0,
            Intent(Intent.ACTION_VIEW, android.net.Uri.parse(release.url)),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= 31) android.app.PendingIntent.FLAG_MUTABLE else 0
        )

        val notification = android.app.Notification.Builder(context, CHANNEL_ID)
            .setContentTitle("IconGen update available")
            .setContentText(release.title)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(android.app.Notification.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .build()

        notificationManager.notify(JOB_ID, notification)
    }
}

/**
 * JobService that runs the daily release check.
 * Must be registered in manifest with RECEIVE_BOOT_COMPLETED to survive reboots.
 */
class UpdateCheckJob : JobService() {
    companion object {
        private const val TAG = "UpdateCheckJob"
    }
    override fun onStartJob(params: JobParameters): Boolean {
        if (!AppUpdateChecker.isEnabled(this@UpdateCheckJob)) {
            jobFinished(params, false)
            return false
        }

        Thread {
            try {
                AppUpdateChecker.check(this@UpdateCheckJob)
            } catch (e: Exception) {
                Log.e(TAG, "Check failed", e)
            } finally {
                jobFinished(params, false)
            }
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        return false
    }
}