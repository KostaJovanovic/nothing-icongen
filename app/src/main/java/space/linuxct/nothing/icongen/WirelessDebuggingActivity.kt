package space.linuxct.nothing.icongen

import android.Manifest
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** Setup continues in its temporary service while Android's pairing dialog stays in front. */
class WirelessDebuggingActivity : AppCompatActivity() {
    private lateinit var ui: LucentUi
    private lateinit var primary: Button
    private lateinit var notificationRow: View
    private lateinit var developerRow: View
    private lateinit var guidance: LinearLayout
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var permissions: TextView
    private lateinit var setupPrefs: SharedPreferences
    private var resumed = false
    private val setupListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        runOnUiThread { if (resumed) refreshStatus() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        ui = LucentUi(this)
        setupPrefs = getSharedPreferences(WirelessSetupService.PREFS, Context.MODE_PRIVATE)
        val root = ui.screen(this)
        ui.header(root, "Wireless setup", back = { finish() }, menu = ::showHelp)
        ui.add(root, ui.text("Settings access, without a computer.", 14f, muted = true), 8)
        val card = ui.card()
        status = ui.text("Ready to pair", 18f).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        primary = ui.button("Start pairing", primary = true) {
            when {
                WirelessSetupService.isRunning(this) -> WirelessSetupService.stop(this)
                WirelessGrantStatus.read(this).complete -> finish()
                !notificationsAvailable() -> recoverNotifications()
                WirelessSetupService.start(this) -> openDeveloperSettings()
            }
            refreshStatus()
        }
        ui.add(card, primary)
        ui.add(card, status, 16)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            indeterminateTintList = android.content.res.ColorStateList.valueOf(ui.ink)
        }
        card.addView(progress, LinearLayout.LayoutParams(-1, ui.dp(4)).apply { topMargin = ui.dp(16) })
        permissions = ui.text("", 12f, muted = true)
        ui.add(card, permissions, 12)
        ui.add(root, card, 24)

        notificationRow = ui.row("Enable pairing notifications", "Required to enter the code without leaving Settings") { recoverNotifications() }
        ui.add(root, notificationRow, 12)
        guidance = ui.card(0)
        ui.add(guidance, step("1", "Open Wireless debugging", "Tap Start pairing, then enable Wireless debugging in Developer options on trusted Wi-Fi."))
        ui.divider(guidance)
        ui.add(guidance, step("2", "Show the pairing code", "Choose Pair device with pairing code. Keep that Settings dialog open."))
        ui.divider(guidance)
        ui.add(guidance, step("3", "Reply from the notification", "Pull down the shade, expand IconGen’s pairing notification and reply with the six-digit code. Don’t switch apps."))
        ui.add(root, guidance, 16)
        developerRow = ui.row("Return to Developer options", "The pairing service keeps running while you leave this screen") { openDeveloperSettings() }
        ui.add(root, developerRow, 12)
    }

    private fun step(number: String, title: String, description: String): View {
        val row = LinearLayout(this).apply {
            gravity = android.view.Gravity.TOP
            setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(16))
        }
        row.addView(ui.text(number, 22f, heading = true).apply { gravity = android.view.Gravity.CENTER }, LinearLayout.LayoutParams(ui.dp(28), -2))
        val labels = ui.column()
        ui.add(labels, ui.text(title, 15f))
        ui.add(labels, ui.text(description, 13f, muted = true), 5)
        row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ui.dp(12) })
        return row
    }

    private fun showHelp() {
        ui.dialog(this, "Before pairing") { content, dialog ->
            ui.add(content, ui.text("Pair only on trusted Wi-Fi. This setup grants IconGen permission to change secure and system Settings to apply rebuilt icons and refresh theme changes. Continue only if you trust this app.", 14f))
            ui.add(content, ui.text("If Developer options is hidden, open Settings → About phone and tap Build number seven times. Return here and tap Start pairing.", 14f, muted = true), 16)
            ui.add(content, ui.text("The temporary silent notification is required even if optional progress notifications are off. It disappears when setup finishes, is stopped or times out after three minutes. Keep the pairing-code dialog in front; enter its code only through the notification reply.", 13f, muted = true), 16)
            ui.add(content, ui.button("Done") { dialog.dismiss() }, 20)
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        setupPrefs.registerOnSharedPreferenceChangeListener(setupListener)
        refreshStatus()
    }

    override fun onPause() {
        resumed = false
        setupPrefs.unregisterOnSharedPreferenceChangeListener(setupListener)
        super.onPause()
    }

    private fun refreshStatus() {
        val running = WirelessSetupService.isRunning(this)
        val grants = WirelessGrantStatus.read(this)
        val ready = grants.complete && !running
        val available = notificationsAvailable()
        primary.text = when {
            running -> "Stop setup"
            ready -> "Done"
            else -> "Start pairing"
        }
        primary.isEnabled = running || ready || available
        progress.visibility = if (running) View.VISIBLE else View.GONE
        notificationRow.visibility = if (!ready && !available) View.VISIBLE else View.GONE
        guidance.visibility = if (ready) View.GONE else View.VISIBLE
        developerRow.visibility = if (running) View.VISIBLE else View.GONE
        status.text = when {
            ready -> "Settings access is ready"
            running -> WirelessSetupService.status(this).ifBlank { "Waiting for the pairing code…" }
            !available -> "Enable notifications to pair"
            else -> WirelessSetupService.status(this).ifBlank { "Ready to pair" }
        }
        permissions.text = if (ready) "Both grants are enabled. You can return to IconGen." else {
            val secure = if (grants.secureSettings) "enabled" else "not yet enabled"
            val system = if (grants.writeSettings && grants.writeSettingsMode == android.app.AppOpsManager.MODE_ALLOWED) "enabled" else "not yet enabled"
            "Secure Settings: $secure\nSystem Settings: $system"
        }
    }

    private fun notificationsAvailable(): Boolean {
        val manager = getSystemService(NotificationManager::class.java)
        return manager.areNotificationsEnabled() &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED &&
            manager.getNotificationChannel(SETUP_CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    private fun recoverNotifications() {
        val manager = getSystemService(NotificationManager::class.java)
        val intent = if (manager.areNotificationsEnabled() &&
            manager.getNotificationChannel(SETUP_CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE
        ) {
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, SETUP_CHANNEL)
        } else {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        }
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    private fun openDeveloperSettings() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            status.text = "Open Settings → Developer options → Wireless debugging. The pairing notification remains active."
        }
    }

    private companion object {
        const val SETUP_CHANNEL = "wireless_setup_v1"
    }
}
