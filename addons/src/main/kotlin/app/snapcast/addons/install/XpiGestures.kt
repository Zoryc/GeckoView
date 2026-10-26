package app.snapcast.addons.install

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which add-on package downloads the user asked for. A `WebResponse` says nothing about the gesture or the
 * frame behind it, so [XpiResponseInterceptor] only installs the response to the top-level load the user
 * started in that tab (a tap or an app load, including its redirect hops), once. A page cannot use it for a
 * download it starts by itself (script, hidden iframe, `<a download>` clicked from script).
 *
 * Per tab, the URL of the user's top-level load that has produced neither a page nor a response yet.
 * Main thread only (browser hooks run there).
 */
@Singleton
internal class XpiGestures @Inject constructor() {
    private val userLoads = HashMap<String, String>()

    /** Every top-level load request of [tabId]; a load the page started itself forgets the user's one. */
    fun onTopLevelLoad(tabId: String, url: String, userInitiated: Boolean) {
        if (userInitiated) userLoads[tabId] = url else userLoads.remove(tabId)
    }

    /** True, once, when [responseUrl] answers the user's pending top-level load in [tabId]. */
    fun consume(tabId: String, responseUrl: String): Boolean {
        val requested = userLoads[tabId] ?: return false
        if (!XpiUrls.sameResource(requested, responseUrl)) return false
        userLoads.remove(tabId)
        return true
    }

    /** The tab committed a page (its later responses come from that page) or closed. */
    fun forget(tabId: String) {
        userLoads.remove(tabId)
    }
}
