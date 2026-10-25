package app.snapcast.detection.extension

import android.os.Handler
import android.os.Looper
import android.util.Log
import app.snapcast.browser.BrowserTabs
import app.snapcast.browser.GeckoRuntimeProvider
import app.snapcast.core.detection.DETECTOR_EXTENSION_ID
import app.snapcast.core.detection.DETECTOR_EXTENSION_LOCATION
import app.snapcast.core.detection.NATIVE_APP_NAME
import app.snapcast.data.AppInitializer
import app.snapcast.detection.DetectedVideoRepositoryImpl
import javax.inject.Inject
import javax.inject.Singleton
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController

/**
 * Installs the bundled detector extension at startup (`ensureBuiltIn`, which only reinstalls when
 * the bundled version changed) and wires its native messaging to every tab's session, including
 * the sessions that were created before the extension was ready. Main thread only.
 */
@Singleton
internal class DetectorExtensionManager @Inject constructor(
    private val runtimeProvider: dagger.Lazy<GeckoRuntimeProvider>,
    private val browserTabs: dagger.Lazy<BrowserTabs>,
    private val repository: DetectedVideoRepositoryImpl,
) : AppInitializer {
    override val priority: Int get() = AppInitializer.PRIORITY_EXTENSIONS

    private var extension: WebExtension? = null
    private var installing = false
    private var attempts = 0
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /** Sessions created before the extension was installed, by tab id. */
    private val waiting = LinkedHashMap<String, Pair<GeckoSession, Boolean>>()

    override fun initialize() {
        install()
    }

    private fun install() {
        if (installing || extension != null) return
        installing = true
        attempts += 1
        val controller = runtimeProvider.get().runtime.webExtensionController
        controller.ensureBuiltIn(DETECTOR_EXTENSION_LOCATION, DETECTOR_EXTENSION_ID).accept(
            { installed ->
                installing = false
                if (installed != null) {
                    onInstalled(controller, installed)
                } else {
                    onInstallFailed(null)
                }
            },
            { error ->
                installing = false
                onInstallFailed(error)
            },
        )
    }

    /** Installation can fail while the engine is still starting; it is tried again a few times. */
    private fun onInstallFailed(error: Throwable?) {
        Log.e(TAG, "Could not install the detector extension (attempt $attempts)", error)
        if (attempts < MAX_INSTALL_ATTEMPTS) mainHandler.postDelayed({ install() }, RETRY_DELAY_MS * attempts)
    }

    private fun onInstalled(controller: WebExtensionController, installed: WebExtension) {
        extension = installed
        installed.setMessageDelegate(UnboundMessageDelegate, NATIVE_APP_NAME)
        if (!installed.metaData.allowedInPrivateBrowsing) {
            controller.setAllowedInPrivateBrowsing(installed, true).accept(
                { updated -> if (updated != null) extension = updated },
                { error -> Log.w(TAG, "Detector extension not allowed in private tabs", error) },
            )
        }

        val sessions = LinkedHashMap(waiting)
        waiting.clear()
        try {
            val tabs = browserTabs.get()
            for (tab in tabs.tabs.value) {
                if (tab.id in sessions) continue
                val session = tabs.session(tab.id) ?: continue
                sessions[tab.id] = session to tab.isPrivate
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not list the open tabs", e)
        }
        for ((tabId, entry) in sessions) attachNow(installed, tabId, entry.first, entry.second)
        repository.setDetectorReady(true)
    }

    /** Wires [session] (tab [tabId]) to the detector now, or as soon as the extension is installed. */
    fun attach(tabId: String, session: GeckoSession, isPrivate: Boolean) {
        val installed = extension
        if (installed == null) {
            waiting[tabId] = session to isPrivate
        } else {
            attachNow(installed, tabId, session, isPrivate)
        }
    }

    fun detach(tabId: String) {
        waiting.remove(tabId)
    }

    private fun attachNow(installed: WebExtension, tabId: String, session: GeckoSession, isPrivate: Boolean) {
        try {
            session.webExtensionController.setMessageDelegate(
                installed,
                TabMessageDelegate(tabId, isPrivate, repository),
                NATIVE_APP_NAME,
            )
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not attach the detector to tab $tabId", e)
        }
    }

    private companion object {
        const val TAG = "SnapcastDetection"
        const val MAX_INSTALL_ATTEMPTS = 3
        const val RETRY_DELAY_MS = 5_000L
    }
}
