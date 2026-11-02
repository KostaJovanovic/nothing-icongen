package space.linuxct.nothing.icongen

import android.app.Application
import android.content.Context

/** Version-specific baseline execution belongs only to instrumentation comparisons. */
internal object NativeLauncherTestRuntime {
    private const val PACKAGE = "com.nothing.launcher"
    private var application: Application? = null

    @Synchronized
    fun application(context: Context): Application {
        val owner = context.applicationContext
        val installed = owner.packageManager.getPackageInfo(PACKAGE, 0)
        check(installed.versionName == "5.0.6" && installed.longVersionCode == 50006009L) {
            "Native tests require installed Nothing Launcher 5.0.6 (50006009), " +
                "found ${installed.versionName} (${installed.longVersionCode})"
        }
        application?.let { return it }
        val code = owner.createPackageContext(PACKAGE, Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY)
        val app = LauncherApplication(code)
        val globalsClass = code.classLoader.loadClass("x9.i")
        globalsClass.getMethod("c", Application::class.java)
            .invoke(globalsClass.getField("a").get(null), app)
        application = app
        return app
    }

    private class LauncherApplication(context: Context) : Application() {
        init { attachBaseContext(context) }
        override fun getApplicationContext(): Context = this
    }
}
