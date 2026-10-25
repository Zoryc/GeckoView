package app.snapcast.detection

import app.snapcast.browser.SessionObserver
import app.snapcast.core.model.DrmInfo
import app.snapcast.detection.extension.DetectorExtensionManager
import javax.inject.Inject
import javax.inject.Singleton
import org.mozilla.geckoview.GeckoSession

/** Connects tab lifecycle and page events from the browser module to detection. Main thread. */
@Singleton
internal class DetectionSessionObserver @Inject constructor(
    private val extensionManager: DetectorExtensionManager,
    private val repository: DetectedVideoRepositoryImpl,
) : SessionObserver {
    override fun onSessionCreated(tabId: String, session: GeckoSession, isPrivate: Boolean) {
        repository.onTabOpened(tabId, isPrivate)
        extensionManager.attach(tabId, session, isPrivate)
    }

    override fun onSessionClosed(tabId: String, session: GeckoSession) {
        extensionManager.detach(tabId)
        repository.onTabClosed(tabId)
    }

    override fun onPageStart(tabId: String, url: String) {
        repository.onPageStart(tabId, url)
    }

    override fun onLocationChange(tabId: String, url: String) {
        repository.onLocationChange(tabId, url)
    }

    override fun onMediaKeySystemRequested(tabId: String, origin: String) {
        repository.markDrm(tabId, DrmInfo(system = null, reason = DRM_REASON_EME_REQUEST))
    }

    override fun onMediaMetadata(tabId: String, title: String?, artist: String?, album: String?) {
        repository.onMediaTitle(tabId, title)
    }

    private companion object {
        const val DRM_REASON_EME_REQUEST = "EME request"
    }
}
