package space.linuxct.nothing.icongen

import android.content.Context

/** Setup progress is separate from signing, device identity and operational preferences. */
object OnboardingState {
    private const val PREFS = "onboarding"
    private const val COMPLETE = "complete"

    internal enum class Step { WELCOME, FEATURES, INSTALL, BACKGROUND, NOTIFICATIONS, SETTINGS, READY }
    internal data class Draft(
        val appUpdates: Boolean,
        val themeChanges: Boolean,
        val notifications: Boolean,
        val appReleaseUpdates: Boolean = false,
    ) {
        val automaticRefresh: Boolean get() = appUpdates || themeChanges
    }

    fun isComplete(context: Context): Boolean = preferences(context).getBoolean(COMPLETE, false)

    /** Only persists completion; the caller owns applying choices and starting monitors. */
    fun markComplete(context: Context) {
        preferences(context).edit().putBoolean(COMPLETE, true).commit()
    }

    internal fun preferences(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    internal fun draft(context: Context): Draft {
        val prefs = preferences(context)
        if (!prefs.contains("app_updates")) {
            saveDraft(context, Draft(
                context.getSharedPreferences(PackageChangeReceiver.PREFS, Context.MODE_PRIVATE)
                    .getBoolean(PackageChangeReceiver.KEY_ENABLED, true),
                ThemeRefreshController.isEnabled(context),
                NotificationPreferences.isEnabled(context),
                AppUpdateChecker.isEnabled(context),
            ))
        }
        if (!prefs.contains("app_release_updates")) {
            prefs.edit().putBoolean("app_release_updates", AppUpdateChecker.isEnabled(context)).commit()
        }
        return Draft(prefs.getBoolean("app_updates", true), prefs.getBoolean("theme_changes", true),
            prefs.getBoolean("notifications", true), prefs.getBoolean("app_release_updates", false))
    }

    internal fun saveDraft(context: Context, draft: Draft) {
        preferences(context).edit().putBoolean("app_updates", draft.appUpdates)
            .putBoolean("theme_changes", draft.themeChanges).putBoolean("notifications", draft.notifications)
            .putBoolean("app_release_updates", draft.appReleaseUpdates)
            .commit()
    }

    internal fun steps(draft: Draft): List<Step> = buildList {
        add(Step.WELCOME)
        add(Step.FEATURES)
        add(Step.INSTALL)
        if (draft.automaticRefresh) add(Step.BACKGROUND)
        if (draft.notifications) add(Step.NOTIFICATIONS)
        if (draft.automaticRefresh) add(Step.SETTINGS)
        add(Step.READY)
    }

    internal fun step(context: Context): Step {
        val saved = preferences(context).getString("step", null)
        return steps(draft(context)).firstOrNull { it.name == saved } ?: Step.WELCOME
    }

    internal fun moveTo(context: Context, step: Step) {
        preferences(context).edit().putString("step", step.name).commit()
    }

    internal fun restart(context: Context) {
        // Retain completion while reviewing an existing setup; canceling must not disable it.
        preferences(context).edit().remove("app_updates").remove("theme_changes").remove("notifications")
            .remove("app_release_updates")
            .remove("install_deferred").remove("background_deferred").remove("notifications_deferred").remove("settings_deferred")
            .remove("pc").putString("step", Step.WELCOME.name).commit()
    }
}
