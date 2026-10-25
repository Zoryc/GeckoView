package app.snapcast.browser.web

import app.snapcast.browser.BrowserTabs
import org.mozilla.geckoview.StorageController

/** Translates [BrowserTabs.CLEAR_COOKIES] / [BrowserTabs.CLEAR_CACHE] / [BrowserTabs.CLEAR_SITE_DATA] to Gecko's flags. */
internal object ClearDataFlags {
    fun toGecko(flags: Int): Long {
        var gecko = 0L
        val cookies = flags and BrowserTabs.CLEAR_COOKIES != 0
        val siteData = flags and BrowserTabs.CLEAR_SITE_DATA != 0
        if (cookies) {
            gecko = gecko or StorageController.ClearFlags.COOKIES or StorageController.ClearFlags.AUTH_SESSIONS
        }
        if (flags and BrowserTabs.CLEAR_CACHE != 0) {
            gecko = gecko or StorageController.ClearFlags.ALL_CACHES
        }
        if (siteData) {
            // Local/session storage, IndexedDB, service workers plus per-site permissions and preferences.
            gecko = gecko or StorageController.ClearFlags.DOM_STORAGES or StorageController.ClearFlags.SITE_SETTINGS
        }
        if (cookies && siteData) {
            // Everything a site stored, including data kinds without their own flag.
            gecko = gecko or StorageController.ClearFlags.SITE_DATA
        }
        return gecko
    }
}
