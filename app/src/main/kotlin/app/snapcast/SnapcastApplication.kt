package app.snapcast

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import app.snapcast.data.AppInitializer
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.components.SingletonComponent

@HiltAndroidApp
class SnapcastApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // GeckoView runs content, GPU and extension code in child processes that also create the
        // Application. Only the main process owns the runtime, the proxy and the databases.
        if (!isMainProcess()) return

        EntryPointAccessors.fromApplication(this, StartupEntryPoint::class.java)
            .initializers()
            .sortedBy { it.priority }
            .forEach { initializer ->
                runCatching { initializer.initialize() }
                    .onFailure { Log.e(TAG, "Startup step ${initializer.javaClass.simpleName} failed", it) }
            }
    }

    private fun isMainProcess(): Boolean {
        val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getProcessName()
        } else {
            val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            manager.runningAppProcesses?.firstOrNull { it.pid == Process.myPid() }?.processName
        }
        return name == null || name == packageName
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface StartupEntryPoint {
        fun initializers(): Set<@JvmSuppressWildcards AppInitializer>
    }

    private companion object {
        const val TAG = "Snapcast"
    }
}
