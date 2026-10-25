package app.snapcast

import android.content.Intent
import app.snapcast.ui.ExternalRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExternalRequestParserTest {
    private val search: (String) -> String = { "https://search.example/?q=$it" }

    private fun parse(
        action: String? = null,
        data: String? = null,
        text: String? = null,
        query: String? = null,
        cast: Boolean = false,
    ) = ExternalRequestParser.parse(action, data, text, query, cast, search)

    @Test
    fun `cast notification opens the controller`() {
        assertEquals(ExternalRequest.OpenCastController, parse(action = Intent.ACTION_MAIN, cast = true))
    }

    @Test
    fun `view intents open http links only`() {
        assertEquals(ExternalRequest.OpenUrl("https://a.example/x"), parse(Intent.ACTION_VIEW, "https://a.example/x"))
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
    fun `web search intents search`() {
        assertEquals(ExternalRequest.OpenUrl("https://search.example/?q=news"), parse(Intent.ACTION_WEB_SEARCH, query = " news "))
        assertNull(parse(Intent.ACTION_MAIN))
    }
}
