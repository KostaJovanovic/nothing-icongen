package space.linuxct.nothing.icongen

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Removes a completion notification that the user never interacted with.
 *
 * Runs independently of the build service, which has already stopped by the time this fires.
 */
class NotificationDismissReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DISMISS) return
        val id = intent.getIntExtra(EXTRA_ID, -1)
        if (id <= 0) return
        context.getSystemService(NotificationManager::class.java).cancel(id)
        Log.d(TAG, "Dismissed notification $id after timeout")
    }

    companion object {
        private const val TAG = "NotificationDismiss"
        const val ACTION_DISMISS = "space.linuxct.nothing.icongen.NOTIFICATION_DISMISS"
        const val EXTRA_ID = "id"
    }
}
