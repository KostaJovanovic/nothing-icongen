package space.linuxct.nothing.icongen

import android.app.AppOpsManager
import android.app.Application
import android.content.Context
import android.os.Process
import android.os.UserManager
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** A legacy target still uses per-source AppOps on the modern devices this app runs on. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class InstallAccessTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val operations: AppOpsManager get() = context.getSystemService(AppOpsManager::class.java)
    private var originalTarget = 0

    @Before fun setUp() {
        originalTarget = context.applicationInfo.targetSdkVersion
        context.applicationInfo.targetSdkVersion = 22
        shadowOf(context.packageManager).setCanRequestPackageInstalls(false)
        for (name in listOf("build_queue", "install_state")) {
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
        BuildCoordinator.reset()
        mode(AppOpsManager.MODE_DEFAULT)
    }

    @After fun tearDown() {
        context.applicationInfo.targetSdkVersion = originalTarget
        BuildCoordinator.reset()
        IconPackInstaller.statusListener = null
    }

    private fun mode(value: Int, packageName: String = context.packageName) {
        shadowOf(operations).setMode("android:request_install_packages",
            context.applicationInfo.uid, packageName, value)
    }

    @Test fun sourceGrantOverridesUnusableLegacyTargetPackageManagerQuery() {
        assertFalse(InstallAccess.canInstall(context))
        mode(AppOpsManager.MODE_ALLOWED)
        assertTrue(InstallAccess.canInstall(context))
        mode(AppOpsManager.MODE_ERRORED)
        assertFalse(InstallAccess.canInstall(context))
        mode(AppOpsManager.MODE_IGNORED)
        assertFalse(InstallAccess.canInstall(context))
        mode(AppOpsManager.MODE_DEFAULT)
        assertFalse(InstallAccess.canInstall(context))
    }

    @Test fun anotherSourcesGrantCannotAuthorizeThisApp() {
        mode(AppOpsManager.MODE_ALLOWED, "example.other.installer")
        assertFalse(InstallAccess.canInstall(context))
    }

    @Test fun managedUnknownSourceRestrictionOverridesSourceGrant() {
        mode(AppOpsManager.MODE_ALLOWED)
        val users = context.getSystemService(UserManager::class.java)
        for (restriction in listOf(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES,
                UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY)) {
            shadowOf(users).setUserRestriction(Process.myUserHandle(), restriction, true)
            assertFalse(InstallAccess.canInstall(context))
            shadowOf(users).setUserRestriction(Process.myUserHandle(), restriction, false)
            assertTrue(InstallAccess.canInstall(context))
        }
    }


    @Test fun deniedSourceRetainsRecoverableApkWithoutSubmittingOrConsumingAnotherRequest() {
        val apk = File(context.filesDir, "source-access-fixture.apk").apply { writeBytes(byteArrayOf(1)) }
        try {
            val install = context.getSharedPreferences("install_state", Context.MODE_PRIVATE)
            install.edit().putString("stage", "permission").putString("apk", apk.absolutePath)
                .putLong("build_request", 1).putString("status", "Install access needed").commit()
            val queue = context.getSharedPreferences("build_queue", Context.MODE_PRIVATE)
            queue.edit().putLong("requested", 2).putLong("completed", 1).commit()
            IconPackInstaller.resumePendingInstall(context)
            IconPackInstaller.resumePendingInstall(context)
            assertEquals(apk, IconPackInstaller.pendingApk(context))
            assertEquals("permission", install.getString("stage", null))
            assertEquals(1L, queue.getLong("completed", 0))
            assertEquals(BuildCoordinator.Phase.WAITING_PERMISSION, BuildCoordinator.state.phase)
            assertTrue(context.packageManager.packageInstaller.mySessions.isEmpty())
            assertNull(shadowOf(context as Application).nextStartedActivity)
        } finally { apk.delete() }
    }
}
