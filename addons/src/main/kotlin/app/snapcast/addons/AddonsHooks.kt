package app.snapcast.addons

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

/** Gives every tab session the per-tab extension delegates (badge overrides, tabs.update/remove). */
internal class AddonsSessionObserver @Inject constructor(
    private val manager: AddonsManager,
) : SessionObserver {
    override fun onSessionCreated(tabId: String, session: GeckoSession, isPrivate: Boolean) {
        manager.onSessionCreated(session)
    }

    override fun onSessionClosed(tabId: String, session: GeckoSession) {
        manager.onSessionClosed(tabId)
    }

    override fun onSelectedTabChanged(tabId: String?) {
        manager.onSelectedTabChanged()
    }
}
