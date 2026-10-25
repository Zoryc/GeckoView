package app.snapcast.browser.tabs

/** Pure tab-selection rules used when tabs close. */
internal object TabSelection {
    data class Candidate(val id: String, val isPrivate: Boolean, val lastAccessed: Long)

    /**
     * Tab to select after [closedId] closes, staying in the same (private or normal) mode:
     * its parent first, then the next tab to the right, then the one to the left. Null when no
     * other tab of that mode exists.
     *
     * @param tabs the tab order before closing, including the closed tab
     */
    fun afterClose(tabs: List<Candidate>, closedId: String, parentId: String?): String? {
        val index = tabs.indexOfFirst { it.id == closedId }
        if (index < 0) return null
        val mode = tabs[index].isPrivate
        if (parentId != null && parentId != closedId) {
            tabs.firstOrNull { it.id == parentId && it.isPrivate == mode }?.let { return it.id }
        }
        tabs.subList(index + 1, tabs.size).firstOrNull { it.isPrivate == mode }?.let { return it.id }
        return tabs.subList(0, index).lastOrNull { it.isPrivate == mode }?.id
    }

    /** Most recently used tab of the given mode, or null. */
    fun mostRecent(tabs: List<Candidate>, private: Boolean): String? =
        tabs.filter { it.isPrivate == private }.maxByOrNull { it.lastAccessed }?.id
}
