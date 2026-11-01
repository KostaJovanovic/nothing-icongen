package space.linuxct.nothing.icongen

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** PACKAGE_ADDED is authoritative for install/update; removals only refresh observation. */
class PackageChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val packageName = intent.data?.schemeSpecificPart ?: return
        if (packageName == context.packageName || packageName == PACK_PACKAGE) return
        when (intent.action) {
            Intent.ACTION_PACKAGE_ADDED -> {
                Log.d(TAG, "Package installed/updated ($packageName)")
                PackageChangeMonitor.packageAdded(context, packageName)
            }
            Intent.ACTION_PACKAGE_REMOVED -> {
                if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
                PackageChangeMonitor.packageRemoved(context, packageName)
            }
            else -> return
        }
    }

    companion object {
        private const val TAG = "PackageChangeReceiver"
        const val PACK_PACKAGE = "com.nothing.icongen.pack"
        const val PREFS = "auto_update"
        const val KEY_ENABLED = "enabled"
    }
}
