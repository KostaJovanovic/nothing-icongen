package space.linuxct.nothing.icongen

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import java.io.File

/** Pipeline invoked only by the serialized JobScheduler worker; installation completes asynchronously. */
object IconPackBuildService {
    private const val TAG = "IconPackBuildService"
    private const val CHANNEL = "iconpack_updates_silent"
    private const val CHANNEL_DONE = "iconpack_results_silent"
    private const val NOTIFICATION_ID = 42
    private const val RESULT_ID = 43

    fun build(context: Context, cancellation: BuildCoordinator.Cancellation): File {
        Log.d(TAG, "Build started")
        ensureChannels(context)
        context.getSystemService(NotificationManager::class.java).cancel(RESULT_ID)
        cancellation.update {
            BuildCoordinator.State(phase = BuildCoordinator.Phase.SCANNING,
                message = "Scanning installed apps", percent = 5, startedAt = System.currentTimeMillis())
        }
        val observer: (BuildCoordinator.State) -> Unit = { state ->
            if (state.isRunning) postProgress(context, state)
        }
        BuildCoordinator.addListener(observer)
        try {
            val outputRoot = context.getExternalFilesDir(null) ?: context.filesDir
            val zip = IconGeneratorEngine.generate(
                context = context.applicationContext,
                outputDir = File(outputRoot, "autobuild_${System.currentTimeMillis()}"),
                maxApps = 2000,
                cancellation = cancellation,
            )
            cancellation.update {
                it.copy(phase = BuildCoordinator.Phase.COMPILING, message = "Compiling APK", percent = 80)
            }
            cancellation.checkpoint()
            return IconPackAssembler.compileApk(context.applicationContext, zip, cancellation)
        } finally {
            BuildCoordinator.removeListener(observer)
            context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        }
    }

    fun cancelNotifications(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.cancel(NOTIFICATION_ID)
        manager.cancel(RESULT_ID)
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun postProgress(context: Context, state: BuildCoordinator.State) {
        if (!canNotify(context)) return
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Updating icon pack")
            .setContentText(state.message)
            .setContentIntent(openApp(context))
            .setPriority(Notification.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setOngoing(false)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .setDefaults(0)
            .setProgress(100, state.percent.coerceIn(0, 100), false)
            .apply {
                if (Build.VERSION.SDK_INT >= 36) {
                    setStyle(Notification.ProgressStyle()
                        .setProgress(state.percent.coerceIn(0, 100))
                        .setProgressSegments(listOf(
                            Notification.ProgressStyle.Segment(5),
                            Notification.ProgressStyle.Segment(75),
                            Notification.ProgressStyle.Segment(12),
                            Notification.ProgressStyle.Segment(8),
                        ))
                        .setStyledByProgress(true))
                }
            }
            .build()
        context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    fun postResult(context: Context, title: String, message: String, action: PendingIntent? = null, transient: Boolean = false) {
        ensureChannels(context)
        val dismiss = PendingIntent.getBroadcast(context, RESULT_ID,
            Intent(context, NotificationDismissReceiver::class.java).apply {
                this.action = NotificationDismissReceiver.ACTION_DISMISS
                putExtra(NotificationDismissReceiver.EXTRA_ID, RESULT_ID)
            }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val alarm = context.getSystemService(AlarmManager::class.java)
        alarm.cancel(dismiss)
        if (!canNotify(context)) return
        val notification = Notification.Builder(context, CHANNEL_DONE)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(message)
            .setContentIntent(action ?: openApp(context))
            .setPriority(Notification.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setOngoing(false)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .setDefaults(0)
            .setAutoCancel(true)
            .apply { if (transient) setTimeoutAfter(60_000L) }
            .build()
        context.getSystemService(NotificationManager::class.java).notify(RESULT_ID, notification)
    }

    private fun canNotify(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED &&
            context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        createSilentChannel(manager, CHANNEL, "Icon pack updates",
            "Silent icon generation progress", "iconpack_updates")
        createSilentChannel(manager, CHANNEL_DONE, "Icon pack results",
            "Silent icon pack installation results and access requests", "iconpack_results")
    }

    private fun createSilentChannel(
        manager: NotificationManager,
        id: String,
        name: String,
        description: String,
        legacyId: String,
    ) {
        val channel = NotificationChannel(id, name, NotificationManager.IMPORTANCE_LOW).apply {
            this.description = description
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_SECRET
        }
        // Existing channel sound settings cannot be changed by recreating that ID.
        // Migrate to the silent channels, preserving an explicit user block.
        if (manager.getNotificationChannel(legacyId)?.importance == NotificationManager.IMPORTANCE_NONE) {
            channel.importance = NotificationManager.IMPORTANCE_NONE
        }
        manager.createNotificationChannel(channel)
        manager.deleteNotificationChannel(legacyId)
    }
}
