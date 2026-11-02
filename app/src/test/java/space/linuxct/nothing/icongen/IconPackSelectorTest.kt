package space.linuxct.nothing.icongen

import android.Manifest
import android.app.AppOpsManager
import android.app.Application
import android.app.NotificationManager
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Looper
import android.os.Process
import android.provider.Settings
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class IconPackSelectorTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private fun prefs(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    private fun selected(): String? = Settings.System.getString(context.contentResolver, "nothing_icon_pack")
    private fun setWriteAccess(allowed: Boolean) {
        shadowOf(context.getSystemService(AppOpsManager::class.java)).setMode(
            AppOpsManager.OPSTR_WRITE_SETTINGS, Process.myUid(), context.packageName,
            if (allowed) AppOpsManager.MODE_ALLOWED else AppOpsManager.MODE_IGNORED,
        )
    }
    private var time = 0L
    private val temporaryIntervals = ArrayList<Long>()
    private val sleeps = ArrayList<Long>()
    private var duringSleep: (Long) -> Unit = {}
    private lateinit var selector: IconPackSelector

    @Before fun setUp() {
        for (name in listOf("theme_refresh", "install_state", "onboarding", "auto_update", "notification_preferences")) {
            prefs(name).edit().clear().commit()
        }
        OnboardingState.markComplete(context)
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        setWriteAccess(true)
        ShadowSettings.setCanDrawOverlays(true)
        Settings.System.putString(context.contentResolver, "nothing_icon_pack", null)
        installPack(43)
        selector = IconPackSelector(now = { time }, sleep = { delay ->
            sleeps.add(delay)
            if (selected() == "THEMED_ICONS_NOTHING") temporaryIntervals.add(time)
            time += delay
            duringSleep(delay)
        })
        manager.cancelAll()
        context.getSystemService(JobScheduler::class.java).cancelAll()
    }

    @After fun tearDown() {
        manager.cancelAll()
        context.getSystemService(JobScheduler::class.java).cancelAll()
    }

    private fun installPack(version: Long) {
        shadowOf(context.packageManager).installPackage(PackageInfo().apply {
            packageName = PackageChangeReceiver.PACK_PACKAGE
            applicationInfo = ApplicationInfo().apply { packageName = PackageChangeReceiver.PACK_PACKAGE }
            setLongVersionCode(version)
        })
    }

    private fun pending(version: Long = 43, request: Long = 1): IconPackInstaller.ReapplyRequest {
        prefs("install_state").edit().putLong("reapply_version", version).putLong("reapply_request", request).commit()
        return IconPackInstaller.pendingReapply(context)!!
    }

    @Test fun confirmedInstallAppliesWithBothTogglesOffWithoutMonitorOrReminder() {
        prefs("theme_refresh").edit().putBoolean("enabled", false).commit()
        prefs("auto_update").edit().putBoolean("enabled", false).commit()
        assertTrue(selector.apply(context, install = pending()))
        assertEquals(listOf(500L, 500L), sleeps)
        assertEquals(listOf(500L), temporaryIntervals)
        assertEquals(PackageChangeReceiver.PACK_PACKAGE, selected())
        assertNull(IconPackInstaller.pendingReapply(context))
        assertFalse(ThemeRefreshController.restorationPending(context))
        assertFalse(BackgroundMonitorService.isNeeded(context))
        assertTrue(manager.activeNotifications.none { it.id == 1024 || it.id == 44 })
    }

    @Test fun rejectedPreparationRetainsExactInstallRequestWithoutSelectorWrites() {
        val request = pending()
        assertThrows(SecurityException::class.java) {
            IconPackSelectionJobService.applyInstalledPack(context, request, cancelled = { false },
                selector = selector, prepare = { throw SecurityException("Launcher preparation rejected") })
        }
        assertEquals(request, IconPackInstaller.pendingReapply(context))
        assertNull(selected())
        assertTrue(sleeps.isEmpty())
        assertFalse(ThemeRefreshController.restorationPending(context))
    }

    @Test fun staleInstallCannotPrepareLauncherOrConsumeNewerRequest() {
        val stale = pending()
        val current = pending(request = 2)
        assertFalse(IconPackSelectionJobService.applyInstalledPack(context, stale,
            cancelled = { false }, selector = selector, prepare = { fail("Stale request prepared launcher") }))
        assertEquals(current, IconPackInstaller.pendingReapply(context))
        assertNull(selected())
        assertTrue(sleeps.isEmpty())
    }

    @Test fun missingPreparationGrantRetainsInstallWithoutLaunchingOrSelecting() {
        prefs("theme_refresh").edit().putBoolean("enabled", false).commit()
        ShadowSettings.setCanDrawOverlays(false)
        val request = pending()
        assertFalse(IconPackSelectionJobService.applyInstalledPack(context, request,
            cancelled = { false }, selector = selector, prepare = { fail("Preparation launched without access") }))
        assertEquals(request, IconPackInstaller.pendingReapply(context))
        assertNull(selected())
        assertTrue(sleeps.isEmpty())
        assertNull(shadowOf(context).nextStartedActivity)
        assertNull(shadowOf(context).nextStartedService)
        assertTrue(manager.activeNotifications.none { it.id == 1024 || it.id == 44 })
    }

    @Test fun themeOnlyRefreshNeverLaunchesPackPicker() {
        ShadowSettings.setCanDrawOverlays(false)
        assertTrue(selector.apply(context, themeRequested = true))
        assertNull(shadowOf(context).nextStartedActivity)
    }

    @Test fun newInstallAcknowledgementReplacesExistingRestorationJobWithSettledInstall() {
        ThemeRefreshController.setRestorationPending(context, true)
        IconPackSelectionJobService.schedule(context)
        val scheduler = context.getSystemService(JobScheduler::class.java)
        assertEquals(0L, requireNotNull(scheduler.getPendingJob(IconPackSelectionJobService.JOB_ID)).minLatencyMillis)
        pending()
        IconPackSelectionJobService.schedule(context)
        assertEquals(0L, requireNotNull(scheduler.getPendingJob(IconPackSelectionJobService.JOB_ID)).minLatencyMillis)
        IconPackSelectionJobService.scheduleConfirmedInstall(context)
        assertEquals(IconPackSelectionJobService.POST_INSTALL_SETTLE_MS,
            requireNotNull(scheduler.getPendingJob(IconPackSelectionJobService.JOB_ID)).minLatencyMillis)
    }

    @Test fun recoveredAccessReplacesDeniedBackoffOnlyWhenInstallRefreshIsUsable() {
        val request = pending()
        val scheduler = context.getSystemService(JobScheduler::class.java)
        scheduler.schedule(JobInfo.Builder(IconPackSelectionJobService.JOB_ID,
            ComponentName(context, IconPackSelectionJobService::class.java))
            .setMinimumLatency(60_000L).setOverrideDeadline(60_000L).build())
        ShadowSettings.setCanDrawOverlays(false)
        IconPackSelectionJobService.scheduleAfterAccessChange(context)
        assertEquals(60_000L, requireNotNull(scheduler.getPendingJob(IconPackSelectionJobService.JOB_ID)).minLatencyMillis)
        ShadowSettings.setCanDrawOverlays(true)
        setWriteAccess(false)
        IconPackSelectionJobService.scheduleAfterAccessChange(context)
        assertEquals(60_000L, requireNotNull(scheduler.getPendingJob(IconPackSelectionJobService.JOB_ID)).minLatencyMillis)
        setWriteAccess(true)
        IconPackSelectionJobService.scheduleAfterAccessChange(context)
        assertEquals(IconPackSelectionJobService.POST_INSTALL_SETTLE_MS,
            requireNotNull(scheduler.getPendingJob(IconPackSelectionJobService.JOB_ID)).minLatencyMillis)
        assertEquals(request, IconPackInstaller.pendingReapply(context))
        assertNull(selected())
    }

    @Test fun restorationAccessRecoveryNeverRequiresPickerPreparationGrant() {
        ShadowSettings.setCanDrawOverlays(false)
        ThemeRefreshController.setRestorationPending(context, true)
        IconPackSelectionJobService.scheduleAfterAccessChange(context)
        val job = requireNotNull(context.getSystemService(JobScheduler::class.java)
            .getPendingJob(IconPackSelectionJobService.JOB_ID))
        assertEquals(0L, job.minLatencyMillis)
        assertTrue(ThemeRefreshController.restorationPending(context))
        assertNull(shadowOf(context).nextStartedActivity)
    }

    @Test fun themeAndInstallTransactionsShareActualStartCooldown() {
        assertTrue(selector.apply(context, themeRequested = true))
        assertTrue(selector.apply(context, install = pending()))
        assertTrue(selector.apply(context, themeRequested = true))
        assertEquals(listOf(500L, 5_500L, 10_500L), temporaryIntervals)
        assertEquals(11_000L, time)
    }

    @Test fun disableBeforeFirstWriteCancelsThemeWithoutChangingSelector() {
        duringSleep = { prefs("theme_refresh").edit().putBoolean("enabled", false).commit() }
        assertFalse(selector.apply(context, themeRequested = true))
        assertNull(selected())
        assertFalse(ThemeRefreshController.restorationPending(context))
        assertTrue(temporaryIntervals.isEmpty())
    }

    @Test fun disableAndJobCancellationAfterTemporaryWriteStillRestorePack() {
        var cancelled = false
        duringSleep = {
            if (selected() == "THEMED_ICONS_NOTHING") {
                cancelled = true
                prefs("theme_refresh").edit().putBoolean("enabled", false).commit()
            }
        }
        assertTrue(selector.apply(context, install = pending(), cancelled = { cancelled }))
        assertTrue(cancelled)
        assertEquals(PackageChangeReceiver.PACK_PACKAGE, selected())
        assertFalse(ThemeRefreshController.restorationPending(context))
        assertNull(IconPackInstaller.pendingReapply(context))
        assertTrue(manager.activeNotifications.none { it.id == 1024 || it.id == 44 })
    }

    @Test fun stoppedJobBeforeTemporaryWriteRetainsInstallRequest() {
        val request = pending()
        var stopped = false
        duringSleep = { stopped = true }
        assertFalse(selector.apply(context, install = request, cancelled = { stopped }))
        assertNull(selected())
        assertEquals(request, IconPackInstaller.pendingReapply(context))
        assertFalse(ThemeRefreshController.restorationPending(context))
    }

    @Test fun missingAccessRetainsVerifiedInstallWithoutThemeOffReminder() {
        prefs("theme_refresh").edit().putBoolean("enabled", false).commit()
        setWriteAccess(false)
        val request = pending()
        assertFalse(selector.apply(context, install = request))
        assertEquals(request, IconPackInstaller.pendingReapply(context))
        assertNull(selected())
        assertTrue(manager.activeNotifications.none { it.id == 1024 || it.id == 44 })
        setWriteAccess(true)
        assertTrue(selector.apply(context, install = request))
        assertNull(IconPackInstaller.pendingReapply(context))
    }

    @Test fun wrongInstalledVersionNeverStartsSelectorTransactionOrConsumesRequest() {
        val request = pending(version = 44)
        assertFalse(selector.apply(context, install = request))
        assertEquals(request, IconPackInstaller.pendingReapply(context))
        assertNull(selected())
        assertTrue(sleeps.isEmpty())
    }

    @Test fun newerConfirmedVersionDuringTemporaryIntervalSurvivesOldCompletion() {
        val first = pending()
        duringSleep = {
            if (selected() == "THEMED_ICONS_NOTHING") {
                installPack(44)
                pending(version = 44, request = 2)
            }
        }
        assertTrue(selector.apply(context, install = first))
        assertEquals(IconPackInstaller.ReapplyRequest(44, 2), IconPackInstaller.pendingReapply(context))
        assertEquals(PackageChangeReceiver.PACK_PACKAGE, selected())
    }

    @Test fun freshBackendRecoversTemporarySelectorBeforeOnboardingWithThemeOff() {
        prefs("onboarding").edit().clear().commit()
        prefs("theme_refresh").edit().putBoolean("enabled", false).putBoolean("restore_pending", true).commit()
        Settings.System.putString(context.contentResolver, "nothing_icon_pack", "THEMED_ICONS_NOTHING")
        setWriteAccess(false)
        assertFalse(IconPackSelector().restore(context))
        assertTrue(ThemeRefreshController.restorationPending(context))
        assertEquals("THEMED_ICONS_NOTHING", selected())
        assertTrue(manager.activeNotifications.none { it.id == 1024 || it.id == 44 })
        setWriteAccess(true)
        assertTrue(IconPackSelector().restore(context))
        assertEquals(PackageChangeReceiver.PACK_PACKAGE, selected())
        assertFalse(ThemeRefreshController.restorationPending(context))
        assertFalse(BackgroundMonitorService.isNeeded(context))
    }

    @Test fun disablingServiceRemovesForegroundAndRejectsQueuedReadinessNotification() {
        // No installed pack means readiness posts a notification, but no selector worker starts.
        shadowOf(context.packageManager).removePackage(PackageChangeReceiver.PACK_PACKAGE)
        val service = Robolectric.buildService(BackgroundMonitorService::class.java).create().startCommand(0, 1)
        ThemeRefreshController.setEnabled(context, false)
        service.destroy()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(manager.activeNotifications.none { it.id == 1024 || it.id == 44 })
        assertFalse(BackgroundMonitorService.isNeeded(context))
        assertNull(shadowOf(context).nextStartedService)
    }

    @Test fun syncOffCleansOrphanDespiteAutomaticUpdatesAndPendingRecovery() {
        manager.createNotificationChannel(android.app.NotificationChannel("orphan", "Old monitor", NotificationManager.IMPORTANCE_LOW))
        manager.notify(1024, android.app.Notification.Builder(context, "orphan").setSmallIcon(R.drawable.ic_notification_monitor).build())
        prefs("theme_refresh").edit().putBoolean("enabled", false).commit()
        pending()
        ThemeRefreshController.setRestorationPending(context, true)
        BackgroundMonitorService.sync(context)
        assertTrue(manager.activeNotifications.none { it.id == 1024 })
        assertNotNull(context.getSystemService(JobScheduler::class.java).getPendingJob(IconPackSelectionJobService.JOB_ID))
        assertNull(shadowOf(context).nextStartedService)
    }
}
