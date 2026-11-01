package space.linuxct.nothing.icongen

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.File
import java.util.concurrent.CancellationException

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvResult: TextView
    private lateinit var step1Indicator: TextView
    private lateinit var step1Status: TextView
    private lateinit var step2Indicator: TextView
    private lateinit var step2Status: TextView
    private lateinit var step3Indicator: TextView
    private lateinit var step3Status: TextView
    private lateinit var step4Indicator: TextView
    private lateinit var step4Status: TextView
    private lateinit var btnGenerate: Button
    private lateinit var btnStopGeneration: Button
    private lateinit var btnInstall: Button
    private lateinit var btnSelectInLauncher: Button
    private lateinit var tvPermissions: TextView
    private lateinit var btnNotificationAccess: Button
    private lateinit var btnInstallAccess: Button

    private var builtApk: File? = null
    private var activityResumed = false
    private var resumingInstall = false
    private val setupPrefs by lazy { getSharedPreferences("permission_setup", MODE_PRIVATE) }

    companion object {
        private const val TAG = "MainActivity"
        private const val KEY_NOTIFICATION_ASKED = "notification_asked"
        private const val KEY_SOURCE_ASKED = "source_asked"
        private const val KEY_FLOW_ACTIVE = "flow_active"
        private const val KEY_AWAITING = "awaiting"
        private const val KEY_ACTION = "action"
        private const val KEY_APK = "built_apk"
        private const val AWAITING_NOTIFICATION = "notification"
        private const val AWAITING_SOURCE = "source"
        private const val ACTION_GENERATE = "generate"
        private const val ACTION_INSTALL = "install"
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        setupPrefs.edit().remove(KEY_AWAITING).apply()
        advancePermissionSetup()
    }

    private val installAccessLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        setupPrefs.edit().remove(KEY_AWAITING).putBoolean(KEY_FLOW_ACTIVE, false).apply()
        refreshPermissionStatus()
        if (InstallAccess.canInstall(this)) {
            resumeRequestedOperation()
        }
    }

    private val notificationSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { refreshPermissionStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // With targetSdk < 33, Android owns the notification permission prompt.
        // Creating channels during Activity startup lets the system present it.
        IconPackBuildService.ensureChannels(this)

        tvStatus = findViewById(R.id.tvStatus)
        tvResult = findViewById(R.id.tvResult)
        step1Indicator = findViewById(R.id.step1Indicator)
        step1Status = findViewById(R.id.step1Status)
        step2Indicator = findViewById(R.id.step2Indicator)
        step2Status = findViewById(R.id.step2Status)
        step3Indicator = findViewById(R.id.step3Indicator)
        step3Status = findViewById(R.id.step3Status)
        step4Indicator = findViewById(R.id.step4Indicator)
        step4Status = findViewById(R.id.step4Status)
        btnGenerate = findViewById(R.id.btnGenerate)
        btnStopGeneration = findViewById(R.id.btnStopGeneration)
        btnInstall = findViewById(R.id.btnInstall)
        btnSelectInLauncher = findViewById(R.id.btnSelectInLauncher)
        tvPermissions = findViewById(R.id.tvPermissions)
        btnNotificationAccess = findViewById(R.id.btnNotificationAccess)
        btnInstallAccess = findViewById(R.id.btnInstallAccess)
        builtApk = setupPrefs.getString(KEY_APK, null)?.let(::File)?.takeIf { it.exists() }
        // ActivityResultRegistry restores in-flight prompts when there is saved state.
        // A genuinely new Activity must not remain stuck waiting for a lost result.
        if (savedInstanceState == null) setupPrefs.edit().remove(KEY_AWAITING).apply()

        tvResult.setTextIsSelectable(true)
        tvResult.typeface = android.graphics.Typeface.MONOSPACE
        tvResult.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10f)

        applyWindowInsets()

        updateStepIndicators()
        findViewById<android.widget.Switch>(R.id.switchAutoUpdate)?.let { toggle ->
            val prefs = getSharedPreferences(PackageChangeReceiver.PREFS, MODE_PRIVATE)
            toggle.isChecked = prefs.getBoolean(PackageChangeReceiver.KEY_ENABLED, true)
            toggle.setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(PackageChangeReceiver.KEY_ENABLED, checked).apply()
                tvStatus.text = if (checked) "Auto-update enabled" else "Auto-update disabled"
                if (checked) {
                    requestOperation("auto_update")
                } else {
                    if (setupPrefs.getString(KEY_ACTION, null) == "auto_update") {
                        setupPrefs.edit().remove(KEY_ACTION).apply()
                    }
                    BuildCoordinator.setAutoUpdateEnabled(this, false)
                }
            }
        }

        btnGenerate.setOnClickListener { requestOperation(ACTION_GENERATE) }
        btnStopGeneration.setOnClickListener {
            setupPrefs.edit()
                .remove(KEY_ACTION)
                .remove(KEY_AWAITING)
                .putBoolean(KEY_FLOW_ACTIVE, false)
                .apply()
            BuildCoordinator.stopAll(this)
            renderBuildState(BuildCoordinator.state)
        }
        btnInstall.setOnClickListener { requestOperation(ACTION_INSTALL) }
        btnInstallAccess.setOnClickListener {
            setupPrefs.edit().putBoolean(KEY_FLOW_ACTIVE, true).apply()
            openInstallAccessSettings()
        }
        btnNotificationAccess.setOnClickListener { requestNotificationAccess() }
        IconPackInstaller.statusListener = {
            onUi {
                syncInstallState()
                refreshPermissionStatus()
            }
        }
        btnSelectInLauncher.setOnClickListener { selectInLauncherSettings() }
        if (!setupPrefs.getBoolean(KEY_NOTIFICATION_ASKED, false) ||
            !setupPrefs.getBoolean(KEY_SOURCE_ASKED, false)
        ) {
            setupPrefs.edit().putBoolean(KEY_FLOW_ACTIVE, true).apply()
        }
    }

    private fun updateStepIndicators() {
        // Every phase starts from a clean slate, including a new build or reattachment.
        setStep(1, false)
        setStep(2, false)
        setStep(3, false)
        setStep(4, false)
    }

    private fun setStep(index: Int, completed: Boolean) {
        val res = if (completed) R.drawable.step_circle_complete else R.drawable.step_circle_inactive
        when (index) {
            1 -> { step1Indicator.background = getDrawable(res); step1Status.text = if (completed) "Complete" else "Not started" }
            2 -> { step2Indicator.background = getDrawable(res); step2Status.text = if (completed) "Complete" else "Not started" }
            3 -> { step3Indicator.background = getDrawable(res); step3Status.text = if (completed) "Complete" else "Not started" }
            4 -> { step4Indicator.background = getDrawable(res); step4Status.text = if (completed) "Installed" else "Not started" }
        }
    }

    private fun setStepActive(index: Int) {
        val res = R.drawable.step_circle_active
        when (index) {
            1 -> step1Indicator.background = getDrawable(res)
            2 -> step2Indicator.background = getDrawable(res)
            3 -> step3Indicator.background = getDrawable(res)
            4 -> step4Indicator.background = getDrawable(res)
        }
    }

    private fun requestOperation(action: String) {
        setupPrefs.edit()
            .putString(KEY_ACTION, action)
            .putBoolean(KEY_FLOW_ACTIVE, true)
            .apply()
        advancePermissionSetup()
    }

    private fun advancePermissionSetup() {
        refreshPermissionStatus()
        if (!activityResumed || setupPrefs.contains(KEY_AWAITING)) return
        if (!setupPrefs.getBoolean(KEY_FLOW_ACTIVE, false)) {
            if (InstallAccess.canInstall(this)) resumeRequestedOperation()
            return
        }
        if (!setupPrefs.getBoolean(KEY_NOTIFICATION_ASKED, false)) {
            setupPrefs.edit().putBoolean(KEY_NOTIFICATION_ASKED, true).apply()
            // Legacy-target apps cannot explicitly launch POST_NOTIFICATIONS requests.
            if (Build.VERSION.SDK_INT >= 33 && applicationInfo.targetSdkVersion >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                setupPrefs.edit().putString(KEY_AWAITING, AWAITING_NOTIFICATION).apply()
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        if (!InstallAccess.canInstall(this)) {
            // Only an initial setup or a deliberate operation opens this screen.
            openInstallAccessSettings()
            return
        }
        setupPrefs.edit()
            .putBoolean(KEY_SOURCE_ASKED, true)
            .putBoolean(KEY_FLOW_ACTIVE, false)
            .apply()
        resumeRequestedOperation()
    }

    private fun openInstallAccessSettings() {
        if (setupPrefs.contains(KEY_AWAITING)) return
        if (InstallAccess.canInstall(this)) {
            setupPrefs.edit().putBoolean(KEY_FLOW_ACTIVE, false).apply()
            resumeRequestedOperation()
            refreshPermissionStatus()
            return
        }
        setupPrefs.edit()
            .putBoolean(KEY_SOURCE_ASKED, true)
            .putString(KEY_AWAITING, AWAITING_SOURCE)
            .apply()
        try {
            installAccessLauncher.launch(InstallAccess.settingsIntent(this))
        } catch (e: android.content.ActivityNotFoundException) {
            setupPrefs.edit().remove(KEY_AWAITING).putBoolean(KEY_FLOW_ACTIVE, false).apply()
            tvStatus.text = "Cannot open install access settings: ${e.message}"
        }
    }

    private fun requestNotificationAccess() {
        if (setupPrefs.contains(KEY_AWAITING)) return
        val runtimeMissing = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (runtimeMissing && applicationInfo.targetSdkVersion >= 33 &&
            (!setupPrefs.getBoolean(KEY_NOTIFICATION_ASKED, false) ||
                shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS))
        ) {
            setupPrefs.edit()
                .putBoolean(KEY_NOTIFICATION_ASKED, true)
                .putString(KEY_AWAITING, AWAITING_NOTIFICATION)
                .apply()
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        val manager = getSystemService(NotificationManager::class.java)
        val blockedChannel = blockedNotificationChannel()
        val intent = if (manager.areNotificationsEnabled() && blockedChannel != null) {
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, blockedChannel)
        } else {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        }
        notificationSettingsLauncher.launch(intent)
    }

    private fun blockedNotificationChannel(): String? {
        val manager = getSystemService(NotificationManager::class.java)
        return listOf("iconpack_updates_silent", "iconpack_results_silent").firstOrNull {
            manager.getNotificationChannel(it)?.importance == NotificationManager.IMPORTANCE_NONE
        }
    }

    private fun refreshPermissionStatus() {
        val notificationsEnabled = getSystemService(NotificationManager::class.java)
            .areNotificationsEnabled() && blockedNotificationChannel() == null
        val installAllowed = InstallAccess.canInstall(this)
        val warnings = buildList {
            if (!notificationsEnabled) {
                add("Notifications blocked: builds can run, but progress, results and install prompts may not be visible. Open the app to finish pending installs.")
            }
            if (!installAllowed) {
                add("Install access required: allow this app to install icon packs. A requested operation will wait for approval.")
            }
        }
        tvPermissions.text = warnings.joinToString("\n")
        tvPermissions.visibility = if (warnings.isEmpty()) View.GONE else View.VISIBLE
        btnNotificationAccess.visibility = if (notificationsEnabled) View.GONE else View.VISIBLE
        btnInstallAccess.visibility = if (installAllowed) View.GONE else View.VISIBLE
    }

    private fun resumeRequestedOperation() {
        if (!activityResumed || !InstallAccess.canInstall(this)) return
        val action = setupPrefs.getString(KEY_ACTION, null)
        // Consume the durable intent before starting work so another onResume cannot replay it.
        setupPrefs.edit().remove(KEY_ACTION).apply()
        when (action) {
            "auto_update" -> BuildCoordinator.setAutoUpdateEnabled(this, true)
        }
        if (BuildCoordinator.state.phase == BuildCoordinator.Phase.STOPPED &&
            action != ACTION_GENERATE && action != ACTION_INSTALL
        ) return
        if (action == ACTION_INSTALL &&
            BuildCoordinator.state.phase == BuildCoordinator.Phase.WAITING_CONFIRMATION
        ) {
            IconPackInstaller.resumePendingInstall(this)
            return
        }
        if (IconPackInstaller.pendingApk(this) != null) {
            if (resumingInstall) return
            val cancellation = BuildCoordinator.cancellation(this)
            resumingInstall = true
            Thread {
                try {
                    IconPackInstaller.resumePendingInstall(
                        applicationContext,
                        cancellation = cancellation,
                    )
                } catch (_: CancellationException) {
                    onUi { renderBuildState(BuildCoordinator.state) }
                } catch (t: Throwable) {
                    Log.e(TAG, "Resuming install failed", t)
                    onUi {
                        if (BuildCoordinator.state.phase == BuildCoordinator.Phase.STOPPED) {
                            renderBuildState(BuildCoordinator.state)
                        } else {
                            tvStatus.text = "Install failed: ${t.message}"
                        }
                    }
                } finally {
                    onUi {
                        resumingInstall = false
                        syncInstallState()
                    }
                }
            }.start()
            return
        }
        when (action) {
            ACTION_GENERATE -> BuildCoordinator.start(this, trigger = "ui")
            ACTION_INSTALL -> installIconPack()
        }
    }

    private val buildListener: (BuildCoordinator.State) -> Unit = {
        // A queued UI callback must not repaint a phase that Stop has superseded.
        runOnUiThread { renderBuildState(BuildCoordinator.state) }
    }

    private fun renderBuildState(state: BuildCoordinator.State) {
        updateStepIndicators()
        tvResult.text = state.error.orEmpty()
        btnInstall.text = if (state.phase == BuildCoordinator.Phase.WAITING_CONFIRMATION) {
            "Confirm Icon Pack Install"
        } else {
            "Install Icon Pack"
        }
        state.apkPath?.let {
            builtApk = File(it).takeIf { apk -> apk.exists() }
            setupPrefs.edit().putString(KEY_APK, it).apply()
        }
        when (state.phase) {
            BuildCoordinator.Phase.IDLE -> {
                tvStatus.text = "Ready"
                btnGenerate.isEnabled = true
                btnInstall.isEnabled = builtApk != null && builtApk!!.exists()
            }
            BuildCoordinator.Phase.STOPPED -> {
                tvStatus.text = state.message.ifBlank { "All generation stopped" }
                btnGenerate.isEnabled = true
                btnInstall.isEnabled = builtApk?.exists() == true
            }
            BuildCoordinator.Phase.FAILED -> {
                tvStatus.text = "FAILED: ${state.error?.lineSequence()?.firstOrNull() ?: "unknown"}"
                tvResult.text = state.error ?: ""
                btnGenerate.isEnabled = true
                btnInstall.isEnabled = builtApk?.exists() == true
            }
            BuildCoordinator.Phase.DONE -> {
                tvStatus.text = state.message
                setStep(1, true); setStep(2, true); setStep(3, true); setStep(4, true)
                btnGenerate.isEnabled = true
                state.apkPath?.let { builtApk = File(it) }
                btnInstall.isEnabled = false
            }
            BuildCoordinator.Phase.WAITING_PERMISSION,
            BuildCoordinator.Phase.WAITING_CONFIRMATION -> {
                tvStatus.text = state.message
                setStep(1, true); setStep(2, true); setStep(3, true)
                setStepActive(4)
                step4Status.text = state.message
                btnGenerate.isEnabled = false
                btnInstall.isEnabled = true
                refreshPermissionStatus()
            }
            else -> {
                btnGenerate.isEnabled = false
                btnInstall.isEnabled = false
                tvStatus.text = state.message
                when (state.phase) {
                    BuildCoordinator.Phase.SCANNING -> {
                        setStepActive(1); step1Status.text = "Scanning..."
                    }
                    BuildCoordinator.Phase.GENERATING -> {
                        setStep(1, true); setStepActive(2); step2Status.text = "Generating..."
                    }
                    BuildCoordinator.Phase.COMPILING -> {
                        setStep(1, true); setStep(2, true); setStepActive(3); step3Status.text = "Compiling..."
                    }
                    BuildCoordinator.Phase.INSTALLING -> {
                        setStep(1, true); setStep(2, true); setStep(3, true); setStepActive(4); step4Status.text = "Installing..."
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun installIconPack() {
        val apk = builtApk
        if (apk == null || !apk.exists()) {
            tvStatus.text = "FAILED: no built APK found"
            tvResult.text = "Build the APK first (step 3)."
            return
        }
        btnInstall.isEnabled = false
        tvStatus.text = "Installing..."
        val cancellation = BuildCoordinator.cancellation(this)
        Thread {
            try {
                IconPackInstaller.install(this, apk, cancellation = cancellation)
                onUi { syncInstallState() }
            } catch (_: CancellationException) {
                onUi { syncInstallState() }
            } catch (t: Throwable) {
                Log.e(TAG, "Install failed", t)
                onUi {
                    if (BuildCoordinator.state.phase == BuildCoordinator.Phase.STOPPED) {
                        renderBuildState(BuildCoordinator.state)
                    } else {
                        tvStatus.text = "FAILED: ${t.message ?: t.javaClass.name}"
                        tvResult.text = t.stackTraceToString()
                        btnInstall.isEnabled = true
                    }
                }
            }
        }.start()
    }

    private fun syncInstallState() {
        val state = BuildCoordinator.state
        if (state.phase != BuildCoordinator.Phase.STOPPED) {
            IconPackInstaller.pendingApk(this)?.let { builtApk = it }
        }
        renderBuildState(state)
    }

    override fun onResume() {
        super.onResume()
        activityResumed = true
        IconPackInstaller.restoreState(this)
        // Re-attach: the build may have started while this Activity did not exist.
        BuildCoordinator.addListener(buildListener)
        syncInstallState()
        advancePermissionSetup()
    }

    override fun onPause() {
        activityResumed = false
        BuildCoordinator.removeListener(buildListener)
        super.onPause()
    }

    override fun onDestroy() {
        IconPackInstaller.statusListener = null
        super.onDestroy()
    }

    private fun onUi(block: () -> Unit) = runOnUiThread(block)

    private fun applyWindowInsets() {
        val root = findViewById<View>(R.id.rootScroll)
        val density = resources.displayMetrics.density
        val base = (16 * density).toInt()
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                    androidx.core.view.WindowInsetsCompat.Type.displayCutout(),
            )
            v.setPadding(bars.left + base, bars.top + base, bars.right + base, bars.bottom + base)
            insets
        }
        androidx.core.view.ViewCompat.requestApplyInsets(root)
    }

    private fun selectInLauncherSettings() {
        val intent = Intent().apply {
            setComponent(
                android.content.ComponentName(
                    "com.nothing.launcher",
                    "com.nothing.launcher.setting.iconpack.IconPackPickerActivity",
                ),
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "Cannot open icon pack settings. Manually select in launcher settings.",
                Toast.LENGTH_LONG,
            ).show()
        }
    }
}