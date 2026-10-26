package app.snapcast.addons.install

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class XpiGesturesTest {
    private val gestures = XpiGestures()

    @Test
    fun `the response to the user's own top-level load is accepted once`() {
        gestures.onTopLevelLoad(TAB, "https://example.com/get?id=7", userInitiated = true)

        assertThat(gestures.consume(TAB, "https://example.com/get?id=7")).isTrue()
        assertThat(gestures.consume(TAB, "https://example.com/get?id=7")).isFalse()
    }

    @Test
    fun `nothing is accepted without a user load`() {
        assertThat(gestures.consume(TAB, "https://example.com/a.xpi")).isFalse()

        gestures.onTopLevelLoad(TAB, "https://example.com/a.xpi", userInitiated = false)
        assertThat(gestures.consume(TAB, "https://example.com/a.xpi")).isFalse()
    }

    @Test
    fun `a load the page starts itself replaces the user's one`() {
        gestures.onTopLevelLoad(TAB, "https://example.com/get", userInitiated = true)
        gestures.onTopLevelLoad(TAB, "https://example.com/get", userInitiated = false)

        assertThat(gestures.consume(TAB, "https://example.com/get")).isFalse()
    }

    @Test
    fun `the last redirect hop is the url the response must have`() {
        gestures.onTopLevelLoad(TAB, "https://example.com/get", userInitiated = true)
        gestures.onTopLevelLoad(TAB, "https://cdn.example.com/file/7", userInitiated = true)

        assertThat(gestures.consume(TAB, "https://example.com/get")).isFalse()
        assertThat(gestures.consume(TAB, "https://cdn.example.com/file/7")).isTrue()
    }

    @Test
    fun `other responses of the tab do not use up the user's load`() {
        gestures.onTopLevelLoad(TAB, "https://example.com/get", userInitiated = true)

        // e.g. a hidden iframe or a scripted <a download> of the same page
        assertThat(gestures.consume(TAB, "https://evil.example/x.xpi")).isFalse()
        assertThat(gestures.consume("other-tab", "https://example.com/get")).isFalse()
        assertThat(gestures.consume(TAB, "https://example.com/get")).isTrue()
    }

    @Test
    fun `a committed page or a closed tab forgets the user's load`() {
        gestures.onTopLevelLoad(TAB, "https://example.com/page", userInitiated = true)
        gestures.forget(TAB)

        assertThat(gestures.consume(TAB, "https://example.com/page")).isFalse()
    }

    private companion object {
        const val TAB = "tab-1"
    }
}
