package space.linuxct.nothing.icongen

import android.app.Application
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class OnboardingStateTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test fun decliningOptionalFeaturesSkipsOnlyTheirAccessPages() {
        assertEquals(listOf(OnboardingState.Step.WELCOME, OnboardingState.Step.FEATURES,
            OnboardingState.Step.INSTALL, OnboardingState.Step.READY),
            OnboardingState.steps(OnboardingState.Draft(false, false, false)))
        assertEquals(listOf(OnboardingState.Step.WELCOME, OnboardingState.Step.FEATURES,
            OnboardingState.Step.INSTALL, OnboardingState.Step.NOTIFICATIONS, OnboardingState.Step.READY),
            OnboardingState.steps(OnboardingState.Draft(false, false, true)))
    }

    @Test fun eitherAutomaticFeatureNeedsSettingsEvenWithOptionalNotificationsOff() {
        for (draft in listOf(OnboardingState.Draft(true, false, false), OnboardingState.Draft(false, true, false))) {
            val steps = OnboardingState.steps(draft)
            assertTrue(steps.contains(OnboardingState.Step.SETTINGS))
            assertTrue(steps.contains(OnboardingState.Step.BACKGROUND))
            assertFalse(steps.contains(OnboardingState.Step.NOTIFICATIONS))
        }
    }

    @Test fun draftAndCurrentPageSurviveReloadWithoutApplyingFeatureChoices() {
        context.getSharedPreferences(PackageChangeReceiver.PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(PackageChangeReceiver.KEY_ENABLED, false).commit()
        context.getSharedPreferences("theme_refresh", Context.MODE_PRIVATE).edit().putBoolean("enabled", true).commit()
        context.getSharedPreferences("notification_preferences", Context.MODE_PRIVATE).edit().putBoolean("enabled", false).commit()
        val defaults = OnboardingState.draft(context)
        assertEquals(OnboardingState.Draft(false, true, false), defaults)
        val changed = OnboardingState.Draft(true, false, true)
        OnboardingState.saveDraft(context, changed)
        OnboardingState.moveTo(context, OnboardingState.Step.NOTIFICATIONS)
        assertEquals(changed, OnboardingState.draft(context))
        assertEquals(OnboardingState.Step.NOTIFICATIONS, OnboardingState.step(context))
        assertFalse(context.getSharedPreferences(PackageChangeReceiver.PREFS, Context.MODE_PRIVATE)
            .getBoolean(PackageChangeReceiver.KEY_ENABLED, true))
        assertTrue(ThemeRefreshController.isEnabled(context))
        assertFalse(NotificationPreferences.isEnabled(context))
        assertFalse(OnboardingState.isComplete(context))
    }

    @Test fun reviewingSetupKeepsAutomationActiveAndDiscardsAbandonedChoices() {
        context.getSharedPreferences(PackageChangeReceiver.PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(PackageChangeReceiver.KEY_ENABLED, false).commit()
        context.getSharedPreferences("theme_refresh", Context.MODE_PRIVATE).edit().putBoolean("enabled", true).commit()
        context.getSharedPreferences("notification_preferences", Context.MODE_PRIVATE).edit().putBoolean("enabled", false).commit()
        OnboardingState.saveDraft(context, OnboardingState.Draft(true, false, true))
        OnboardingState.moveTo(context, OnboardingState.Step.READY)
        OnboardingState.markComplete(context)
        OnboardingState.restart(context)
        assertTrue(OnboardingState.isComplete(context))
        assertEquals(OnboardingState.Step.WELCOME, OnboardingState.step(context))
        // Restart takes new defaults from operational settings, not an abandoned draft.
        assertEquals(OnboardingState.Draft(false, true, false), OnboardingState.draft(context))
    }

    @Test fun removingAnOptionalPageCannotRestoreAnInapplicableSavedPage() {
        OnboardingState.saveDraft(context, OnboardingState.Draft(true, true, true))
        OnboardingState.moveTo(context, OnboardingState.Step.SETTINGS)
        OnboardingState.saveDraft(context, OnboardingState.Draft(false, false, false))
        assertEquals(OnboardingState.Step.WELCOME, OnboardingState.step(context))
    }
}
