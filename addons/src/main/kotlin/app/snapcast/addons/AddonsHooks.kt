package app.snapcast.addons

import app.snapcast.addons.install.XpiGestures
import app.snapcast.browser.SessionObserver
import app.snapcast.data.AppInitializer
import javax.inject.Inject
import org.mozilla.geckoview.GeckoSession

/** Sets the extension delegates on GeckoView at startup, right after the built-in extensions. */
internal class AddonsInitializer @Inject constructor(
    private val manager: AddonsManager,
) : AppInitializer {
    override val priority: Int get() = AppInitializer.PRIORITY_EXTENSIONS + 1

    override fun initialize() {
        manager.start()
    }
}

/**
 * Gives every tab session the per-tab extension delegates (badge overrides, tabs.update/remove) and keeps
 * [XpiGestures] in step with the tabs.
 */
internal class AddonsSessionObserver @Inject constructor(
    private val manager: AddonsManager,
    private val gestures: XpiGestures,
) : SessionObserver {
    override fun onSessionCreated(tabId: String, session: GeckoSession, isPrivate: Boolean) {
        manager.onSessionCreated(session)
    }

    override fun onSessionClosed(tabId: String, session: GeckoSession) {
        gestures.forget(tabId)
        manager.onSessionClosed(tabId)
    }

    // The tab shows a page now: package responses that follow come from it, not from the user's tap.
    override fun onLocationChange(tabId: String, url: String) {
        gestures.forget(tabId)
    }

    override fun onSelectedTabChanged(tabId: String?) {
        manager.onSelectedTabChanged()
    }
}
