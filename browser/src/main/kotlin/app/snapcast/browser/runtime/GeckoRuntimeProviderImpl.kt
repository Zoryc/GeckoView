package app.snapcast.browser.runtime

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Looper
import android.os.Process
import android.util.Log
import app.snapcast.browser.GeckoRuntimeProvider
import app.snapcast.data.di.ApplicationScope
import app.snapcast.data.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings

/**
 * Creates the process-wide [GeckoRuntime] on first access (main thread only) and keeps its
 * `prefers-color-scheme` in sync with the app theme.
 *
 * Debug-only switches (remote debugging, console output, about:config) follow the app's
 * `FLAG_DEBUGGABLE`. Add-on signature enforcement is never relaxed: no prefs or arguments that
 * affect `xpinstall.signatures.required` are passed to Gecko.
 */
@Singleton
internal class GeckoRuntimeProviderImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    @ApplicationScope private val appScope: CoroutineScope,
) : GeckoRuntimeProvider {

    private var instance: GeckoRuntime? = null

    override val runtime: GeckoRuntime
        get() {
            check(Looper.getMainLooper().isCurrentThread) { "GeckoRuntime must be accessed on the main thread" }
            return instance ?: createRuntime().also {
                instance = it
                followTheme(it)
            }
        }

    private fun createRuntime(): GeckoRuntime {
        val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val theme = settingsRepository.settings.value.themeMode
        val settings = GeckoRuntimeSettings.Builder()
            .javaScriptEnabled(true)
            .remoteDebuggingEnabled(debuggable)
            .consoleOutput(debuggable)
            .aboutConfigEnabled(debuggable)
            // Lets addons.mozilla.org's install button hand the .xpi to Gecko (mozAddonManager).
            .extensionsWebAPIEnabled(true)
            .preferredColorScheme(RuntimeConfig.colorSchemeFor(theme))
            .contentBlocking(RuntimeConfig.contentBlocking())
            .webManifest(true)
            .build()
        return GeckoRuntime.create(context, settings).apply {
            // Gecko's parent process died. A process can only ever create one runtime, so start over.
            delegate = GeckoRuntime.Delegate {
                Log.e(TAG, "GeckoRuntime shut down unexpectedly; restarting the app process")
                Process.killProcess(Process.myPid())
            }
        }
    }

    private fun followTheme(runtime: GeckoRuntime) {
        appScope.launch(Dispatchers.Main.immediate) {
            settingsRepository.settings
                .map { it.themeMode }
                .distinctUntilChanged()
                .collect { theme ->
                    val scheme = RuntimeConfig.colorSchemeFor(theme)
                    if (runtime.settings.preferredColorScheme != scheme) {
                        runtime.settings.setPreferredColorScheme(scheme)
                    }
                }
        }
    }

    private companion object {
        const val TAG = "SnapcastBrowser"
    }
}
