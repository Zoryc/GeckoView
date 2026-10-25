package app.snapcast.addons.actions

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ActionStoreTest {

    /** Stand-in for WebExtension.Action: null fields fall back to the default like Action.withDefault. */
    private data class FakeAction(val title: String? = null, val badge: String? = null)

    private fun merge(tabAction: FakeAction, defaultAction: FakeAction) =
        FakeAction(title = tabAction.title ?: defaultAction.title, badge = tabAction.badge ?: defaultAction.badge)

    private val ublock = ActionKey("uBlock0@raymondhill.net", isPageAction = false)
    private val other = ActionKey("other@example.com", isPageAction = false)
    private val otherPage = ActionKey("other@example.com", isPageAction = true)

    @Test
    fun `defaults apply to every tab`() {
        val store = ActionStore<FakeAction>()
        store.setDefault(ublock, FakeAction(title = "uBlock Origin"))

        assertThat(store.state.value.resolve(null, ::merge)).containsExactly(ublock, FakeAction(title = "uBlock Origin"))
        assertThat(store.state.value.resolve("tab-1", ::merge)).containsExactly(ublock, FakeAction(title = "uBlock Origin"))
    }

    @Test
    fun `tab overrides merge over the default for that tab only`() {
        val store = ActionStore<FakeAction>()
        store.setDefault(ublock, FakeAction(title = "uBlock Origin"))
        store.setOverride("tab-1", ublock, FakeAction(badge = "12"))

        assertThat(store.state.value.resolve("tab-1", ::merge)[ublock]).isEqualTo(FakeAction("uBlock Origin", "12"))
        assertThat(store.state.value.resolve("tab-2", ::merge)[ublock]).isEqualTo(FakeAction("uBlock Origin", null))
        assertThat(store.state.value.resolve(null, ::merge)[ublock]).isEqualTo(FakeAction("uBlock Origin", null))
    }

    @Test
    fun `an override without a default is used as is`() {
        val store = ActionStore<FakeAction>()
        store.setOverride("tab-1", otherPage, FakeAction(title = "Page"))

        assertThat(store.state.value.resolve("tab-1", ::merge)).containsExactly(otherPage, FakeAction(title = "Page"))
        assertThat(store.state.value.resolve("tab-2", ::merge)).isEmpty()
    }

    @Test
    fun `later updates replace earlier ones`() {
        val store = ActionStore<FakeAction>()
        store.setDefault(ublock, FakeAction(title = "old"))
        store.setDefault(ublock, FakeAction(title = "new"))
        store.setOverride("tab-1", ublock, FakeAction(badge = "1"))
        store.setOverride("tab-1", ublock, FakeAction(badge = "2"))

        assertThat(store.state.value.resolve("tab-1", ::merge)[ublock]).isEqualTo(FakeAction("new", "2"))
    }

    @Test
    fun `closing a tab drops its overrides`() {
        val store = ActionStore<FakeAction>()
        store.setDefault(ublock, FakeAction(title = "uBlock Origin"))
        store.setOverride("tab-1", ublock, FakeAction(badge = "3"))
        store.clearTab("tab-1")

        assertThat(store.state.value.overrides).isEmpty()
        assertThat(store.state.value.resolve("tab-1", ::merge)[ublock]).isEqualTo(FakeAction("uBlock Origin", null))
    }

    @Test
    fun `clearing an extension removes its defaults and overrides`() {
        val store = ActionStore<FakeAction>()
        store.setDefault(ublock, FakeAction(title = "uBlock Origin"))
        store.setDefault(other, FakeAction(title = "Other"))
        store.setOverride("tab-1", ublock, FakeAction(badge = "3"))
        store.setOverride("tab-1", otherPage, FakeAction(title = "Page"))
        store.clearExtension(ublock.extensionId)

        val snapshot = store.state.value
        assertThat(snapshot.defaults.keys).containsExactly(other)
        assertThat(snapshot.overrides["tab-1"]?.keys).containsExactly(otherPage)
    }

    @Test
    fun `retain keeps only the given extensions and prunes empty tabs`() {
        val store = ActionStore<FakeAction>()
        store.setDefault(ublock, FakeAction(title = "uBlock Origin"))
        store.setDefault(other, FakeAction(title = "Other"))
        store.setOverride("tab-1", other, FakeAction(badge = "1"))
        store.retainExtensions(setOf(ublock.extensionId))

        val snapshot = store.state.value
        assertThat(snapshot.defaults.keys).containsExactly(ublock)
        assertThat(snapshot.overrides).isEmpty()
    }

    @Test
    fun `retain without changes keeps the same snapshot`() {
        val store = ActionStore<FakeAction>()
        store.setDefault(ublock, FakeAction(title = "uBlock Origin"))
        val before = store.state.value
        store.retainExtensions(setOf(ublock.extensionId))

        assertThat(store.state.value).isSameInstanceAs(before)
    }
}
