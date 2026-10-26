package app.snapcast.addons.install

import app.snapcast.addons.AddonsManager
import app.snapcast.browser.ExternalResponseInterceptor
import app.snapcast.browser.LoadInterceptor
import java.io.IOException
import javax.inject.Inject
import org.mozilla.geckoview.WebResponse

/**
 * Navigations to add-on packages (links to .xpi files, addons.mozilla.org download endpoints) are
 * cancelled; the ones the user asked for (a top-level load after a tap, or an app load) are installed
 * instead, which shows the permission prompt first.
 *
 * Package loads a page starts by itself (`location = '…xpi'` on load, hidden iframes) are cancelled
 * without installing, the way Gecko requires user activation for web installs. Letting them through
 * instead would turn them into downloads that [XpiResponseInterceptor] recognises by URL.
 */
internal class XpiLoadInterceptor @Inject constructor(
    private val addons: AddonsManager,
    private val gestures: XpiGestures,
) : LoadInterceptor {
    /** Without the gesture information, nothing counts as the user's. */
    override fun interceptLoad(tabId: String, url: String, isTopLevel: Boolean): Boolean =
        interceptLoad(tabId, url, isTopLevel, userInitiated = false)

    override fun interceptLoad(tabId: String, url: String, isTopLevel: Boolean, userInitiated: Boolean): Boolean {
        val isPackage = XpiUrls.isInstallUrl(url)
        // A package URL is handled here, so only other user loads can still produce a package response.
        if (isTopLevel) gestures.onTopLevelLoad(tabId, url, userInitiated = userInitiated && !isPackage)
        if (!isPackage) return false
        if (isTopLevel && userInitiated) addons.installFromWeb(url)
        return true
    }
}

/**
 * Downloads that turn out to be add-on packages (Content-Type `application/x-xpinstall`, e.g. after a
 * redirect) are installed instead of saved, when they answer a load the user asked for (see [XpiGestures]);
 * any other one is saved like a normal download. GeckoView fetches the package again, so the body is closed.
 */
internal class XpiResponseInterceptor @Inject constructor(
    private val addons: AddonsManager,
    private val gestures: XpiGestures,
) : ExternalResponseInterceptor {
    override fun interceptResponse(tabId: String, response: WebResponse): Boolean {
        if (!XpiUrls.isXpiResponse(response.uri, response.headers)) return false
        if (!gestures.consume(tabId, response.uri)) return false
        try {
            response.body?.close()
        } catch (e: IOException) {
            // Nothing left to release.
        }
        addons.installFromWeb(response.uri)
        return true
    }
}
