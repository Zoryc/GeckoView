package app.snapcast.ui.prompts

import app.snapcast.browser.BrowserPrompt
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VisiblePromptTest {
    private fun alert(id: String, tabId: String?) =
        BrowserPrompt.Alert(id = id, tabId = tabId, title = null, message = null, onDismiss = {})

    @Test
    fun `prompts of other tabs are not shown over the selected tab`() {
        val background = alert("1", tabId = "background")
        val privateTab = alert("2", tabId = "private")
        assertThat(visiblePrompt(listOf(background, privateTab), selectedTabId = "front")).isNull()
    }

    @Test
    fun `the selected tab's oldest prompt is shown even behind older prompts of other tabs`() {
        val background = alert("1", tabId = "background")
        val own = alert("2", tabId = "front")
        val ownLater = alert("3", tabId = "front")
        assertThat(visiblePrompt(listOf(background, own, ownLater), selectedTabId = "front")).isSameInstanceAs(own)
    }

    @Test
    fun `a hidden prompt appears once its tab is selected`() {
        val background = alert("1", tabId = "background")
        val prompts = listOf(background)
        assertThat(visiblePrompt(prompts, selectedTabId = "front")).isNull()
        assertThat(visiblePrompt(prompts, selectedTabId = "background")).isSameInstanceAs(background)
    }

    @Test
    fun `prompts not tied to a tab are shown everywhere`() {
        val engine = alert("1", tabId = null)
        assertThat(visiblePrompt(listOf(alert("0", "other"), engine), selectedTabId = "front")).isSameInstanceAs(engine)
        assertThat(visiblePrompt(listOf(engine), selectedTabId = null)).isSameInstanceAs(engine)
    }
}
