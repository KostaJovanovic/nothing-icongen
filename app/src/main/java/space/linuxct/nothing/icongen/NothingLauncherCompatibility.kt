package space.linuxct.nothing.icongen

import android.content.Context
import android.content.pm.PackageManager

internal object NothingLauncherCompatibility {
    const val PACKAGE = "com.nothing.launcher"

    fun requireSupported(context: Context) {
        val installed = try {
            context.packageManager.getPackageInfo(PACKAGE, 0)
        } catch (failure: PackageManager.NameNotFoundException) {
            throw IllegalStateException("Install Nothing Launcher 5.0 or newer to generate icons", failure)
        }
        val major = installed.versionName?.substringBefore('.')?.toIntOrNull()
        check(major != null && major >= 5) {
            "Unsupported Nothing Launcher version ${installed.versionName ?: "unknown"}; " +
                "install or update to Nothing Launcher 5.0 or newer to generate icons"
        }
    }
}
