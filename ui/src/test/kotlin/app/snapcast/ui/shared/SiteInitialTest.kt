package app.snapcast.ui.shared

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SiteInitialTest {
    @Test
    fun `uses the host without www`() {
        assertThat(siteInitial("https://www.youtube.com/watch", "Video")).isEqualTo("Y")
        assertThat(siteInitial("https://9gag.com/", "")).isEqualTo("9")
    }

    @Test
    fun `falls back to the title, then a bullet`() {
        assertThat(siteInitial("about:blank", "  my page")).isEqualTo("M")
        assertThat(siteInitial("about:blank", "")).isEqualTo("•")
    }
}
