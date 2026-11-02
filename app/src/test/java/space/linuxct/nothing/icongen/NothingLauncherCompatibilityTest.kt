package space.linuxct.nothing.icongen

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [35])
class NothingLauncherCompatibilityTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test fun acceptsBothReportedBuildsWithoutLoadingLauncherCode() {
        for (build in listOf(50006003, 50006009)) {
            install("5.0.6", build)
            assertRendersSupportedVersion()
        }
    }

    @Test fun acceptsMajorBoundaryAndFutureReleasesRegardlessOfBuildNumber() {
        for ((version, build) in listOf("5.0" to 1, "5.12.42" to 7, "6.0" to 2)) {
            install(version, build)
            assertRendersSupportedVersion()
        }
    }

    @Test fun rejectsPreFiveEvenWithNewerLookingBuildCode() {
        for (version in listOf("0.9", "4.99.99")) {
            install(version, 60000000)
            assertUpdateRequired()
        }
    }

    @Test fun rejectsUnknownVersionRatherThanGuessingFromBuildCode() {
        for (version in listOf(null, "", "unknown", "beta", "v5.0")) {
            install(version, 50006009)
            assertUpdateRequired()
        }
    }

    @Test fun missingLauncherExplainsWhatToInstall() {
        shadowOf(context.packageManager).removePackage(NothingLauncherCompatibility.PACKAGE)
        val failure = assertThrows(IllegalStateException::class.java) {
            NothingLauncherCompatibility.requireSupported(context)
        }
        assertTrue(failure.cause is PackageManager.NameNotFoundException)
    }

    private fun assertUpdateRequired() {
        assertThrows(IllegalStateException::class.java) {
            NothingLauncherCompatibility.requireSupported(context)
        }
    }

    private fun assertRendersSupportedVersion() {
        NothingIconRenderer(context).use { renderer ->
            val glyphs = renderer.glyph(ColorDrawable(Color.BLACK))
            try {
                assertEquals(255, Color.alpha(glyphs.normal.getPixel(216, 216)))
                assertEquals(0, Color.alpha(glyphs.normal.getPixel(0, 0)))
            } finally { glyphs.recycle() }
        }
    }

    private fun install(version: String?, build: Int) {
        shadowOf(context.packageManager).installPackage(PackageInfo().apply {
            packageName = NothingLauncherCompatibility.PACKAGE
            versionName = version
            versionCode = build
            applicationInfo = ApplicationInfo().apply { packageName = NothingLauncherCompatibility.PACKAGE }
        })
    }
}
