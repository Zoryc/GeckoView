package app.snapcast.ui.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class IntentUriParserTest {
    private val youtube =
        "intent://www.youtube.com/watch?v=abc#Intent;scheme=https;package=com.google.android.youtube;" +
            "S.browser_fallback_url=https%3A%2F%2Fm.youtube.com%2Fwatch%3Fv%3Dabc;end"

    @Test
    fun `recognizes intent uris`() {
        assertThat(IntentUriParser.isIntentUri(youtube)).isTrue()
        assertThat(IntentUriParser.isIntentUri("INTENT://x#Intent;end")).isTrue()
        assertThat(IntentUriParser.isIntentUri("mailto:a@b.c")).isFalse()
    }

    @Test
    fun `decodes the browser fallback url`() {
        assertThat(IntentUriParser.fallbackUrl(youtube)).isEqualTo("https://m.youtube.com/watch?v=abc")
    }

    @Test
    fun `reads the package`() {
        assertThat(IntentUriParser.packageName(youtube)).isEqualTo("com.google.android.youtube")
    }

    @Test
    fun `non http fallbacks are ignored`() {
        val uri = "intent://x#Intent;scheme=foo;S.browser_fallback_url=javascript%3Aalert(1);end"
        assertThat(IntentUriParser.fallbackUrl(uri)).isNull()
    }

    @Test
    fun `missing fragment or attribute gives null`() {
        assertThat(IntentUriParser.fallbackUrl("intent://x")).isNull()
        assertThat(IntentUriParser.fallbackUrl("intent://x#Intent;scheme=https;end")).isNull()
        assertThat(IntentUriParser.packageName("intent://x#Intent;scheme=https;end")).isNull()
    }

    @Test
    fun `plain uris have no attributes`() {
        assertThat(IntentUriParser.fallbackUrl("https://example.com/#Intent;S.browser_fallback_url=https%3A%2F%2Fa.b;end"))
            .isNull()
    }

    @Test
    fun `attributes after end are ignored`() {
        val uri = "intent://x#Intent;scheme=https;end;S.browser_fallback_url=https%3A%2F%2Fevil.example"
        assertThat(IntentUriParser.fallbackUrl(uri)).isNull()
    }

    @Test
    fun `malformed percent encoding gives null`() {
        val uri = "intent://x#Intent;S.browser_fallback_url=https%3A%2F%2Fa.b%G1;end"
        assertThat(IntentUriParser.fallbackUrl(uri)).isNull()
    }
}
