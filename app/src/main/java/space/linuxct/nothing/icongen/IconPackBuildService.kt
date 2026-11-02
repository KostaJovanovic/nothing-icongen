package space.linuxct.nothing.icongen

import android.Manifest
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
    private const val THEME_ACCESS_ID = 44
    private const val NOTIFICATION_TIMEOUT_MS = 60_000L

    enum class ResultKind { SUCCESS, ERROR, ACTION_REQUIRED }

    fun build(context: Context, cancellation: BuildCoordinator.Cancellation): File {
        Log.d(TAG, "Build started")
        ensureChannels(context)
        context.getSystemService(NotificationManager::class.java).cancel(RESULT_ID)
        cancellation.update {
            BuildCoordinator.State(phase = BuildCoordinator.Phase.SCANNING,
                message = "Scanning installed apps", percent = 5, startedAt = System.currentTimeMillis())
        }
        val observer: (BuildCoordinator.State) -> Unit = { state ->
            updateProgress(context, state)
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
            cancelProgress(context)
        }
    }

    fun updateProgress(context: Context, state: BuildCoordinator.State) = synchronized(BuildCoordinator) {
        if (state != BuildCoordinator.state) return@synchronized
        if (state.isRunning && !BuildCoordinator.isStopped(context)) postProgress(context, state)
        else cancelProgress(context)
    }

    fun cancelProgress(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    fun cancelNotifications(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.cancel(NOTIFICATION_ID)
        manager.cancel(RESULT_ID)
        manager.cancel(THEME_ACCESS_ID)
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun postProgress(context: Context, state: BuildCoordinator.State) = synchronized(NotificationPreferences) {
        if (!canNotify(context)) return@synchronized
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification_sync_animated)
            .setLargeIcon(NotificationArtwork.state(context, R.drawable.ic_notification_sync_animated))
            .setContentTitle("Updating icon pack")
            .setContentText(state.message)
            .setContentIntent(openApp(context))
            .setPriority(Notification.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setOngoing(false)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .setDefaults(0)
            .setSound(null)
            .setProgress(100, state.percent.coerceIn(0, 100), false)
            .setTimeoutAfter(NOTIFICATION_TIMEOUT_MS)
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

    fun cancelThemeAccessReminder(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(THEME_ACCESS_ID)
    }

    fun postThemeAccessReminder(context: Context, failure: String) = synchronized(ThemeRefreshController) {
        if (!OnboardingState.isComplete(context) || !ThemeRefreshController.isEnabled(context)) return@synchronized
        val action = PendingIntent.getActivity(
            context, THEME_ACCESS_ID,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(MainActivity.EXTRA_THEME_ACCESS, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        postNotice(context, THEME_ACCESS_ID, "Automatic theme refresh needs Settings access",
            "$failure\nRun the PC commands or use IconGen's built-in wireless ADB setup. " +
                "Automatic light/dark icon refresh cannot work until Settings access is available.",
            ResultKind.ACTION_REQUIRED, action)
    }

    fun postResult(
        context: Context,
        title: String,
        message: String,
        kind: ResultKind,
        action: PendingIntent? = null,
    ) = postNotice(context, RESULT_ID, title, message, kind, action)

    private fun postNotice(
        context: Context,
        id: Int,
        title: String,
        message: String,
        kind: ResultKind,
        action: PendingIntent?,
    ) = synchronized(NotificationPreferences) {
        if (!canNotify(context)) return@synchronized
        ensureChannels(context)
        val icon = when (kind) {
            ResultKind.SUCCESS -> R.drawable.ic_notification_success
            ResultKind.ERROR -> R.drawable.ic_notification_error
            ResultKind.ACTION_REQUIRED -> R.drawable.ic_notification_waiting
        }
        val notification = Notification.Builder(context, CHANNEL_DONE)
            .setSmallIcon(icon)
            .setLargeIcon(NotificationArtwork.state(context, icon))
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setContentIntent(action ?: openApp(context))
            .setPriority(Notification.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setOngoing(false)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .setDefaults(0)
            .setSound(null)
            .setAutoCancel(true)
            .setTimeoutAfter(NOTIFICATION_TIMEOUT_MS)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(id, notification)
    }

    private fun canNotify(context: Context): Boolean =
        NotificationPreferences.isEnabled(context) &&
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
