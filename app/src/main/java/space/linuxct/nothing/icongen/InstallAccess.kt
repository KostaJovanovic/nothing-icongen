package space.linuxct.nothing.icongen

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.UserManager
import android.provider.Settings

/** Install-source access on modern Android, including this application's legacy target SDK. */
object InstallAccess {
    fun canInstall(context: Context): Boolean {
        // PackageManager rejects targets below O before consulting the user's source grant.
        if (context.applicationInfo.targetSdkVersion >= 26) {
            return context.packageManager.canRequestPackageInstalls()
        }
        val users = context.getSystemService(UserManager::class.java)
        if (users.hasUserRestriction(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES) ||
            users.hasUserRestriction(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY)) {
            return false
        }
        // The modern system installer checks this same source AppOp, even for a legacy target.
        // MODE_DEFAULT is not permission: the installer treats it as a blocked source.
        val operations = context.getSystemService(AppOpsManager::class.java)
        return operations.checkOpNoThrow("android:request_install_packages",
            context.applicationInfo.uid, context.packageName) == AppOpsManager.MODE_ALLOWED
    }

    fun settingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
}
