package space.linuxct.nothing.icongen

import android.app.AppOpsManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.LinearLayout

/** One set of instructions and commands for setup and intentional permission recovery. */
internal object SettingsAccessUi {
    const val EXPLANATION = "These powerful Settings grants let IconGen apply updated icons and refresh theme changes. Grant them only if you trust this app."
    const val BACKGROUND_EXPLANATION = "Android calls this “Display over other apps”. It lets IconGen prepare the launcher’s updated icon data in the background. IconGen never draws an overlay."

    fun status(context: Context): String {
        val grants = WirelessGrantStatus.read(context)
        val secure = if (grants.secureSettings) "enabled" else "not enabled"
        val system = if (grants.writeSettings && grants.writeSettingsMode == AppOpsManager.MODE_ALLOWED) "enabled" else "not enabled"
        return "Secure Settings: $secure\nSystem Settings: $system"
    }

    fun addChoices(
        ui: LucentUi,
        parent: LinearLayout,
        pcInitially: Boolean = false,
        onChoice: (Boolean) -> Unit = {},
        openWireless: () -> Unit,
    ) {
        val context = ui.context
        val commands = "adb shell pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS\n" +
            "adb shell appops set ${context.packageName} WRITE_SETTINGS allow"
        val choices = ui.column()
        ui.add(parent, choices, 14)
        val instructions = ui.column()
        ui.add(parent, instructions, 12)
        val proceed = ui.button("Wireless setup", primary = true) {}
        var pc = pcInitially
        lateinit var wirelessRadio: LucentToggle
        lateinit var pcRadio: LucentToggle
        fun renderChoice() {
            wirelessRadio.isChecked = !pc
            pcRadio.isChecked = pc
            instructions.removeAllViews()
            if (pc) {
                ui.add(instructions, ui.text("On a trusted PC, enable USB debugging, connect this phone and authorize the computer. Run both commands, then return here to check the grants:", 13f, muted = true))
                ui.add(instructions, ui.text(commands, 12f).apply { setTextIsSelectable(true); setHorizontallyScrolling(false) }, 12)
            } else {
                ui.add(instructions, ui.text("Use trusted Wi-Fi. Keep Android’s pairing dialog open and reply with its six-digit code in IconGen’s notification—do not switch apps. The temporary notification is required even if optional notifications are off.", 13f, muted = true))
            }
            proceed.text = if (pc) "Copy commands" else "Wireless setup"
        }
        wirelessRadio = ui.radio(choices, "Wireless · on this phone", !pc) { pc = false; onChoice(false); renderChoice() }
        pcRadio = ui.radio(choices, "USB · from a PC", pc) { pc = true; onChoice(true); renderChoice() }
        proceed.setOnClickListener {
            if (pc) {
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
                    ClipData.newPlainText("IconGen Settings access", commands),
                )
                proceed.text = "Commands copied"
                proceed.announceForAccessibility("Commands copied. Run both commands on your computer; copying does not grant access.")
            } else openWireless()
        }
        renderChoice()
        ui.add(parent, proceed, 20)
    }
}

/** Both optional notification channels must be available for progress and results. */
internal object NotificationAccessUi {
    fun blockedChannel(context: Context): String? {
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        return listOf("iconpack_updates_silent", "iconpack_results_silent").firstOrNull {
            manager.getNotificationChannel(it)?.importance == android.app.NotificationManager.IMPORTANCE_NONE
        }
    }

    fun available(context: Context): Boolean =
        context.getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled() &&
            blockedChannel(context) == null

    fun settingsIntent(context: Context): android.content.Intent {
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        val blocked = blockedChannel(context)
        return if (manager.areNotificationsEnabled() && blocked != null) {
            android.content.Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, blocked)
        } else {
            android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
        }
    }
}
