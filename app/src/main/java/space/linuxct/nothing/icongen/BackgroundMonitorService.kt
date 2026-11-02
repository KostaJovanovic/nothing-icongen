package space.linuxct.nothing.icongen

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.database.ContentObserver
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import java.util.concurrent.Executors
import kotlin.math.max

/** Resident only while the user has completed setup and enabled theme detection. */
class BackgroundMonitorService : Service() {
    companion object {
        private const val TAG = "BackgroundMonitor"
        private const val CHANNEL_ID = "background_monitor_v1"
        internal const val NOTIFICATION_ID = 1024
        private const val EXTRA_REFRESH = "refresh_on_start"
        private const val ACTION_NIGHT_MODE_CHANGED = "android.app.action.NIGHT_MODE_CHANGED"
        private val worker = Executors.newSingleThreadExecutor()
        @Volatile private var running: BackgroundMonitorService? = null

        internal fun isNeeded(context: Context): Boolean =
            OnboardingState.isComplete(context) && ThemeRefreshController.isEnabled(context)

        fun sync(context: Context, refresh: Boolean = false) {
            val app = context.applicationContext
            // Committed installs and mandatory restoration are independent of both toggles/setup.
            IconPackSelectionJobService.schedule(app)
            val intent = Intent(app, BackgroundMonitorService::class.java).putExtra(EXTRA_REFRESH, refresh)
            if (!isNeeded(app)) {
                running?.stopMonitoring()
                app.stopService(intent)
                // Also removes orphan records left by an older process/service implementation.
                app.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
                IconPackBuildService.cancelThemeAccessReminder(app)
                return
            }
            try {
                app.startForegroundService(intent)
            } catch (e: Exception) {
                val message = "Theme monitor could not start: ${e.message ?: e.javaClass.simpleName}"
                ThemeRefreshController.recordStatus(app, message)
                IconPackBuildService.postThemeAccessReminder(app, message)
                Log.e(TAG, message, e)
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val schedule = ThemeRefreshSchedule()
    private val refreshRunnable = Runnable { runScheduledRefresh() }
    private var observer: ContentObserver? = null
    private var receiver: BroadcastReceiver? = null
    private var lastNightMode = Configuration.UI_MODE_NIGHT_UNDEFINED
    private var previousAccess: Boolean? = null
    private var previousPack: Boolean? = null
    private var busy = false
    private var foregroundReady = false
    @Volatile private var destroying = false

    override fun onCreate() {
        super.onCreate()
        running = this
        if (!isNeeded(this)) {
            stopMonitoring()
            stopSelf()
            return
        }
        try {
            getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(
                CHANNEL_ID, "System theme detection", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Detects system theme changes without rebuilding the generated icon pack"
                setSound(null, null)
                enableVibration(false)
                enableLights(false)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            })
            startForeground(NOTIFICATION_ID,
                notification(R.drawable.ic_notification_monitor, "Monitoring the system theme"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            foregroundReady = true
        } catch (e: Exception) {
            fail("Could not enter foreground; check specialUse service permissions", e)
            stopMonitoring()
            stopSelf()
            return
        }
        lastNightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        registerThemeSignals()
    }

    private fun notification(icon: Int, message: String): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(icon)
            .setLargeIcon(NotificationArtwork.state(this, icon))
            .setContentTitle("Theme monitor")
            .setContentText(message)
            .setPriority(Notification.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setOngoing(false)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .setDefaults(0)
            .setSound(null)
            .build()

    private fun updateNotification(icon: Int, message: String) {
        handler.post {
            if (!destroying && foregroundReady && isNeeded(this)) {
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(icon, message))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isNeeded(this) || destroying || !foregroundReady) {
            stopMonitoring()
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val access = ThemeRefreshController.canWriteSettings(this)
        val installed = ThemeRefreshController.isPackInstalled(this)
        val changed = previousAccess != access || previousPack != installed
        previousAccess = access
        previousPack = installed
        if (access && installed) {
            if (changed || intent == null || intent.getBooleanExtra(EXTRA_REFRESH, false)) {
                requestRefresh("start, permission recovery or sticky restart")
            }
        } else recordReadiness()
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        checkNightMode(newConfig)
    }

    private fun checkNightMode(config: Configuration) {
        val current = config.uiMode and Configuration.UI_MODE_NIGHT_MASK
        if (current != lastNightMode) {
            lastNightMode = current
            requestRefresh("configuration night mode changed")
        }
    }

    private fun registerThemeSignals() {
        try {
            observer = object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean) { requestRefresh("Settings.Secure.ui_night_mode") }
            }.also {
                contentResolver.registerContentObserver(Settings.Secure.getUriFor("ui_night_mode"), false, it)
            }
        } catch (e: Exception) { fail("Could not observe secure night mode", e) }
        val listener = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    ACTION_NIGHT_MODE_CHANGED -> requestRefresh("night mode broadcast")
                    Intent.ACTION_CONFIGURATION_CHANGED -> checkNightMode(resources.configuration)
                }
            }
        }
        receiver = listener
        try {
            registerReceiver(listener, IntentFilter().apply {
                addAction(ACTION_NIGHT_MODE_CHANGED)
                addAction(Intent.ACTION_CONFIGURATION_CHANGED)
            }, Context.RECEIVER_EXPORTED)
        } catch (e: Exception) { fail("Could not register theme broadcasts", e) }
    }

    private fun requestRefresh(reason: String) {
        if (destroying || !isNeeded(this)) return
        if (!ThemeRefreshController.canWriteSettings(this) || !ThemeRefreshController.isPackInstalled(this)) {
            recordReadiness()
            return
        }
        Log.d(TAG, "Theme refresh requested: $reason")
        schedule.request(SystemClock.elapsedRealtime())
        armRefresh()
    }

    private fun armRefresh() {
        handler.removeCallbacks(refreshRunnable)
        if (destroying || busy) return
        val due = schedule.nextAt() ?: return
        handler.postDelayed(refreshRunnable, (due - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
    }

    private fun runScheduledRefresh() {
        if (destroying || busy) return
        if (!isNeeded(this)) {
            stopMonitoring()
            stopSelf()
            return
        }
        if (!schedule.take(SystemClock.elapsedRealtime())) {
            armRefresh()
            return
        }
        busy = true
        updateNotification(R.drawable.ic_notification_sync_animated, "Refreshing icons for the system theme")
        worker.execute {
            var succeeded = false
            try {
                succeeded = IconPackSelector.shared.apply(applicationContext,
                    themeRequested = true, cancelled = { destroying })
            } catch (e: Exception) {
                fail("Could not finish theme refresh", e)
            } finally {
                handler.post {
                    busy = false
                    if (!destroying && isNeeded(this)) {
                        updateNotification(if (succeeded) R.drawable.ic_notification_success else R.drawable.ic_notification_waiting,
                            ThemeRefreshController.status(this))
                        armRefresh()
                    }
                }
            }
        }
    }

    private fun recordReadiness() {
        ThemeRefreshController.recordStatus(this, when {
            !ThemeRefreshController.canWriteSettings(this) -> "Theme refresh paused: allow Modify system settings"
            !ThemeRefreshController.isPackInstalled(this) -> "Theme refresh waiting for the generated icon pack to be installed"
            else -> "Theme monitor active"
        })
        if (!busy) updateNotification(R.drawable.ic_notification_waiting, ThemeRefreshController.status(this))
        if (!ThemeRefreshController.canWriteSettings(this)) {
            IconPackBuildService.postThemeAccessReminder(this, "Android does not allow IconGen to write system settings.")
        }
    }

    private fun fail(message: String, error: Exception) {
        ThemeRefreshController.recordStatus(this, "$message: ${error.message ?: error.javaClass.simpleName}")
        if (!busy) updateNotification(R.drawable.ic_notification_error, message)
        IconPackBuildService.postThemeAccessReminder(this, message)
        Log.e(TAG, message, error)
    }

    private fun stopMonitoring() {
        destroying = true
        foregroundReady = false
        handler.removeCallbacksAndMessages(null)
        schedule.clear()
        observer?.let {
            try { contentResolver.unregisterContentObserver(it) }
            catch (e: Exception) { Log.w(TAG, "Could not unregister night mode observer", e) }
        }
        observer = null
        receiver?.let {
            try { unregisterReceiver(it) }
            catch (e: Exception) { Log.w(TAG, "Could not unregister theme receiver", e) }
        }
        receiver = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    override fun onDestroy() {
        stopMonitoring()
        if (running === this) running = null
        // Shared selector work is never interrupted after writing the temporary selector.
        IconPackSelectionJobService.schedule(applicationContext)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

/** Retains the trailing theme event; cross-source serialization/cooldown lives in IconPackSelector. */
internal class ThemeRefreshSchedule {
    private var settleAt: Long? = null
    private var lastStart: Long? = null

    fun request(now: Long) { settleAt = now + 250L }
    fun nextAt(): Long? = settleAt?.let { max(it, lastStart?.plus(5_000L) ?: it) }
    fun take(now: Long): Boolean {
        val due = nextAt() ?: return false
        if (now < due) return false
        settleAt = null
        lastStart = now
        return true
    }
    fun clear() { settleAt = null }
}
