package app.snapcast.browser.tabs

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TabSelectionTest {
    private fun tab(id: String, private: Boolean = false, lastAccessed: Long = 0) =
        TabSelection.Candidate(id, private, lastAccessed)

    @Test
    fun `parent is preferred`() {
        val tabs = listOf(tab("parent"), tab("a"), tab("child"), tab("b"))
        assertThat(TabSelection.afterClose(tabs, "child", parentId = "parent")).isEqualTo("parent")
    }

    @Test
    fun `missing or other-mode parent falls back to neighbours`() {
        val tabs = listOf(tab("normalParent"), tab("p1", private = true), tab("p2", private = true))
        assertThat(TabSelection.afterClose(tabs, "p1", parentId = "normalParent")).isEqualTo("p2")
        assertThat(TabSelection.afterClose(tabs, "p1", parentId = "gone")).isEqualTo("p2")
    }

    @Test
    fun `right neighbour first, then left, same mode only`() {
        val tabs = listOf(tab("a"), tab("x", private = true), tab("b"), tab("y", private = true), tab("c"))
        assertThat(TabSelection.afterClose(tabs, "b", parentId = null)).isEqualTo("c")
        assertThat(TabSelection.afterClose(tabs, "c", parentId = null)).isEqualTo("b")
        assertThat(TabSelection.afterClose(tabs, "x", parentId = null)).isEqualTo("y")
        assertThat(TabSelection.afterClose(tabs, "y", parentId = null)).isEqualTo("x")
    }

    @Test
    fun `last tab of a mode gives null`() {
        val tabs = listOf(tab("a"), tab("p", private = true))
        assertThat(TabSelection.afterClose(tabs, "p", parentId = null)).isNull()
        assertThat(TabSelection.afterClose(tabs, "a", parentId = null)).isNull()
        assertThat(TabSelection.afterClose(tabs, "unknown", parentId = null)).isNull()
    }

    @Test
    fun `most recent tab of a mode`() {
        val tabs = listOf(tab("a", lastAccessed = 5), tab("b", lastAccessed = 9), tab("p", private = true, lastAccessed = 99))
        assertThat(TabSelection.mostRecent(tabs, private = false)).isEqualTo("b")
        assertThat(TabSelection.mostRecent(tabs, private = true)).isEqualTo("p")
        assertThat(TabSelection.mostRecent(listOf(tab("a")), private = true)).isNull()
    }
}
