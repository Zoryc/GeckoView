package app.snapcast.player.media

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * Cookies the servers set while one video plays (a CDN session cookie on the playlist or key response, for
 * example), sent back where they apply as the browser would. In memory only, gone with the player.
 */
internal class SessionCookieJar : CookieJar {
    /** Least recently set first, each with its creation order (kept when it is set again). */
    private val cookies = ArrayList<Entry>()
    private var nextCreated = 0L

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val now = System.currentTimeMillis()
        this.cookies.removeAll { it.cookie.expiresAt <= now }
        for (cookie in cookies) {
            val index = this.cookies.indexOfFirst { it.cookie.name == cookie.name && it.cookie.domain == cookie.domain && it.cookie.path == cookie.path }
            val created = if (index >= 0) this.cookies.removeAt(index).created else nextCreated++
            if (cookie.expiresAt > now) this.cookies += Entry(cookie, created)
        }
        // Past the limit the least recently set cookie goes, as in a browser (RFC 6265 5.3).
        while (this.cookies.size > MAX_COOKIES) this.cookies.removeAt(0)
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        // Browser order (RFC 6265 5.4: longer paths first, then older), so a name's first value is the one servers read.
        return cookies.filter { it.cookie.expiresAt > now && it.cookie.matches(url) }.sortedWith(BROWSER_ORDER).map { it.cookie }
    }

    private class Entry(val cookie: Cookie, val created: Long)

    private companion object {
        const val MAX_COOKIES = 50
        val BROWSER_ORDER = compareByDescending<Entry> { it.cookie.path.length }.thenBy { it.created }
    }
}
