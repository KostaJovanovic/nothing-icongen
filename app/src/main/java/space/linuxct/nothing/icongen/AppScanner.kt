package space.linuxct.nothing.icongen

import android.content.Context
import android.content.pm.LauncherApps
import android.graphics.drawable.Drawable
import android.os.UserHandle

/** Launchable components plus the resource-only pack's canonical theme-picker component. */
object AppScanner {
    data class AppIcon(
        val componentName: String,
        val label: String,
        val icon: Drawable,
        val userHandle: UserHandle,
    )

    fun scanInstalledApps(context: Context): List<AppIcon> {
        val launcherApps = context.getSystemService(LauncherApps::class.java)
        val density = context.resources.displayMetrics.densityDpi
        val result = mutableListOf<AppIcon>()
        val seen = HashSet<String>()
        for (user in launcherApps.profiles) {
            for (activity in launcherApps.getActivityList(null, user)) {
                val pkg = activity.componentName.packageName
                if (pkg == IconPackAssembler.PACKAGE_NAME) continue
                val component = activity.componentName.flattenToString()
                if (!seen.add(component)) continue
                result.add(AppIcon(
                    component, activity.label.toString(), activity.getIcon(density), user,
                ))
            }
        }
        // Always use bundled artwork, never the installed pack's already-transformed icon.
        // This also includes the resource-only pack before its first installation.
        result.add(AppIcon(
            "${IconPackAssembler.PACKAGE_NAME}/${IconPackAssembler.PACKAGE_NAME}.IconPackThemeActivity",
            context.getString(R.string.icon_pack_name),
            requireNotNull(context.getDrawable(R.drawable.ic_icon_pack)),
            android.os.Process.myUserHandle(),
        ))
        return result
    }
}
