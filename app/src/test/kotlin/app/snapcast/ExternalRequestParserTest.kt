package app.snapcast

import android.content.Intent
import app.snapcast.core.browser.UrlInputResolver
import app.snapcast.core.model.SearchEngine
import app.snapcast.ui.ExternalRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExternalRequestParserTest {
    private val search: (String) -> String = { "https://search.example/?q=$it" }

    /** Like the address bar: input with a scheme passes through unchanged, anything else is searched. */
    private val passThrough: (String) -> String = { if (it.contains(':')) it else search(it) }

    private fun parse(
        action: String? = null,
        data: String? = null,
        text: String? = null,
        query: String? = null,
        cast: Boolean = false,
        toUrl: (String) -> String = search,
    ) = ExternalRequestParser.parse(action, data, text, query, cast, toUrl, search)

    @Test
    fun `cast notification opens the controller`() {
        assertEquals(ExternalRequest.OpenCastController, parse(action = Intent.ACTION_MAIN, cast = true))
    }

    @Test
    fun `view intents open http links only`() {
        assertEquals(ExternalRequest.OpenUrl("https://a.example/x"), parse(Intent.ACTION_VIEW, "https://a.example/x"))
        assertEquals(ExternalRequest.OpenUrl("HTTP://a.example/x"), parse(Intent.ACTION_VIEW, "HTTP://a.example/x"))
        assertNull(parse(Intent.ACTION_VIEW, "file:///sdcard/x.mp4"))
    }

    @Test
    fun `shared text uses the first url it contains`() {
        assertEquals(
            ExternalRequest.OpenUrl("https://v.example/watch?id=1"),
            parse(Intent.ACTION_SEND, text = "Look at this (https://v.example/watch?id=1)."),
        )
    }

    @Test
    fun `shared text without url becomes a search`() {
        assertEquals(ExternalRequest.OpenUrl("https://search.example/?q=cats"), parse(Intent.ACTION_SEND, text = "cats"))
    }

    @Test
    fun `shared text resolving to a host opens it`() {
        val toUrl: (String) -> String = { "https://$it" }
        assertEquals(ExternalRequest.OpenUrl("https://example.com"), parse(Intent.ACTION_SEND, text = "example.com", toUrl = toUrl))
    }

    @Test
    fun `shared non web urls become a search`() {
        for (text in listOf("file:///x", "data:text/html,x", "about:config", "view-source:x", "moz-extension://id/p.html")) {
            assertEquals(
                ExternalRequest.OpenUrl(search(text)),
                parse(Intent.ACTION_SEND, text = text, toUrl = passThrough),
                text,
            )
        }
    }

    @Test
    fun `web search intents search`() {
        assertEquals(ExternalRequest.OpenUrl("https://search.example/?q=news"), parse(Intent.ACTION_WEB_SEARCH, query = " news "))
        assertNull(parse(Intent.ACTION_MAIN))
    }

    @Test
    fun `search queries resolving to a non web scheme become a search`() {
        val query = "file:///data/data/app.snapcast/databases/snapcast.db"
        assertEquals(ExternalRequest.OpenUrl(search(query)), parse(Intent.ACTION_WEB_SEARCH, query = query, toUrl = passThrough))
        assertEquals(ExternalRequest.OpenUrl(search("data:text/html,x")), parse(Intent.ACTION_SEARCH, query = "data:text/html,x", toUrl = passThrough))
    }

    @Test
    fun `real resolver never lets other apps open local files`() {
        val engine = SearchEngine.DUCKDUCKGO
        val request = ExternalRequestParser.parse(
            action = Intent.ACTION_SEND,
            dataString = null,
            sharedText = "file:///data/data/app.snapcast/files/tabs.json",
            searchQuery = null,
            openCastController = false,
            toUrl = { UrlInputResolver.resolve(it, engine) },
            searchUrl = { UrlInputResolver.searchUrl(it, engine) },
        )
        assertEquals(
            ExternalRequest.OpenUrl("https://duckduckgo.com/?q=file%3A%2F%2F%2Fdata%2Fdata%2Fapp.snapcast%2Ffiles%2Ftabs.json"),
            request,
        )
    }

    @Test
    fun `a non web search url is dropped`() {
        val request = ExternalRequestParser.parse(
            action = Intent.ACTION_WEB_SEARCH,
            dataString = null,
            sharedText = null,
            searchQuery = "x",
            openCastController = false,
            toUrl = { "about:blank" },
            searchUrl = { "about:blank" },
        )
        assertNull(request)
    }
}
