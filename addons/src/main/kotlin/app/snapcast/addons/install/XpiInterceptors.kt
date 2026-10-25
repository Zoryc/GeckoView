package app.snapcast.addons.install

import app.snapcast.addons.AddonsRepository
import app.snapcast.browser.ExternalResponseInterceptor
import app.snapcast.browser.LoadInterceptor
import java.io.IOException
import javax.inject.Inject
import org.mozilla.geckoview.WebResponse

/**
 * Navigations to add-on packages (links to .xpi files, addons.mozilla.org download endpoints) are
 * cancelled and installed instead, which shows the permission prompt first.
 */
internal class XpiLoadInterceptor @Inject constructor(
    private val addons: AddonsRepository,
) : LoadInterceptor {
    override fun interceptLoad(tabId: String, url: String, isTopLevel: Boolean): Boolean {
        if (!XpiUrls.isInstallUrl(url)) return false
        addons.install(url)
        return true
    }
}

/**
 * Downloads that turn out to be add-on packages (Content-Type `application/x-xpinstall`, e.g. after a
 * redirect) are installed instead of saved. GeckoView fetches the package again, so the body is closed.
 */
internal class XpiResponseInterceptor @Inject constructor(
    private val addons: AddonsRepository,
) : ExternalResponseInterceptor {
    override fun interceptResponse(tabId: String, response: WebResponse): Boolean {
        if (!XpiUrls.isXpiResponse(response.uri, response.headers)) return false
        try {
            response.body?.close()
        } catch (e: IOException) {
            // Nothing left to release.
        }
        addons.install(response.uri)
        return true
    }
}
