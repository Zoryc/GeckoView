package app.snapcast.player.media

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * Cookies the servers set while one video plays (a CDN session cookie on the playlist or key response, for
 * example), sent back where they apply as the browser would. In memory only, gone with the player.
 */
internal class SessionCookieJar : CookieJar {
    private val cookies = ArrayList<Cookie>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val now = System.currentTimeMillis()
        for (cookie in cookies) {
            this.cookies.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
            if (cookie.expiresAt > now) this.cookies += cookie
        }
        while (this.cookies.size > MAX_COOKIES) this.cookies.removeAt(0)
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        return cookies.filter { it.expiresAt > now && it.matches(url) }
    }

    private companion object {
        const val MAX_COOKIES = 50
    }
}
