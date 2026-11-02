package space.linuxct.nothing.icongen

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ThemePermissionReminderTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()
    private val manager get() = context.getSystemService(NotificationManager::class.java)

    @Before fun setUp() {
        context.getSharedPreferences("theme_refresh", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("notification_preferences", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("onboarding", Context.MODE_PRIVATE).edit().clear().commit()
        OnboardingState.markComplete(context)
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        manager.cancelAll()
    }

    @After fun tearDown() { manager.cancelAll() }

    @Test fun themeReminderDoesNotReplaceOrCancelBuildResult() {
        IconPackBuildService.postResult(context, "Build result", "The build completed", IconPackBuildService.ResultKind.SUCCESS)
        IconPackBuildService.postThemeAccessReminder(context, "Settings access was rejected")
        val active = manager.activeNotifications.associateBy { it.id }
        assertEquals(setOf(43, 44), active.keys)
        ThemeRefreshController.setEnabled(context, false)
        assertEquals(setOf(43), manager.activeNotifications.map { it.id }.toSet())
    }

    @Test fun disablingDetectionClearsReminderAndRejectsLateWorkerFailure() {
        IconPackBuildService.postThemeAccessReminder(context, "Settings access was rejected")
        assertTrue(manager.activeNotifications.any { it.id == 44 })
        ThemeRefreshController.setEnabled(context, false)
        IconPackBuildService.postThemeAccessReminder(context, "An earlier worker finished late")
        assertTrue(manager.activeNotifications.none { it.id == 44 })
    }

    @Test fun disabledDetectionNeverRemindsEvenWhenNotificationsAreEnabled() {
        ThemeRefreshController.setEnabled(context, false)
        IconPackBuildService.postThemeAccessReminder(context, "Settings access was rejected")
        assertTrue(manager.activeNotifications.none { it.id == 44 })
    }

    @Test fun incompleteOnboardingNeverPostsThemeReminder() {
        context.getSharedPreferences("onboarding", Context.MODE_PRIVATE).edit().clear().commit()
        IconPackBuildService.postThemeAccessReminder(context, "Settings access was rejected")
        assertTrue(manager.activeNotifications.none { it.id == 44 })
        assertFalse(BackgroundMonitorService.isNeeded(context))
    }

    @Test fun notificationPreferenceSuppressesReminderWithoutDisablingDetection() {
        IconPackBuildService.postThemeAccessReminder(context, "Settings access was rejected")
        NotificationPreferences.setEnabled(context, false)
        IconPackBuildService.postThemeAccessReminder(context, "Settings access is still rejected")
        assertTrue(ThemeRefreshController.isEnabled(context))
        assertTrue(manager.activeNotifications.none { it.id == 44 })
        NotificationPreferences.setEnabled(context, true)
        assertTrue("Enabling notifications must not replay a stale failure", manager.activeNotifications.none { it.id == 44 })
        IconPackBuildService.postThemeAccessReminder(context, "A new refresh failed")
        assertTrue(manager.activeNotifications.any { it.id == 44 })
    }
}
