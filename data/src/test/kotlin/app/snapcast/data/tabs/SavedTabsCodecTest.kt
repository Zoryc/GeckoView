package app.snapcast.data.tabs

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SavedTabsCodecTest {
    private val tabs = listOf(
        SavedTab(id = "1", url = "https://vimeo.com/", title = "Vimeo", selected = true, sessionState = "{\"history\":[]}"),
        SavedTab(id = "2", url = "https://example.com/", title = "", isDesktopMode = true),
    )

    @Test
    fun roundTrips() {
        assertThat(SavedTabsCodec.decode(SavedTabsCodec.encode(tabs))).isEqualTo(tabs)
        assertThat(SavedTabsCodec.decode(SavedTabsCodec.encode(emptyList()))).isEmpty()
    }

    @Test
    fun corruptInputDecodesToEmptyList() {
        listOf(
            "",
            "   ",
            "{not json",
            "{\"id\":\"1\"}",
            "[{\"id\":\"1\"}]",
            "[{\"id\":\"1\",\"url\":\"u\",\"title\":\"t\",\"selected\":\"maybe\"}]",
            "null",
        ).forEach { text -> assertThat(SavedTabsCodec.decode(text)).isEmpty() }
    }

    @Test
    fun unknownKeysAreIgnored() {
        val text = "[{\"id\":\"1\",\"url\":\"https://a.test/\",\"title\":\"A\",\"pinned\":true}]"
        assertThat(SavedTabsCodec.decode(text)).containsExactly(SavedTab(id = "1", url = "https://a.test/", title = "A"))
    }
}
