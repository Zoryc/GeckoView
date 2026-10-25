package app.snapcast.cast.chromecast

/** Pure queue arithmetic for receiver queues (item ids in queue order). */
internal object QueuePositions {
    /**
     * Id of the item an insertion "after the current item" must be placed before, or null to append at the
     * end (current item is last, or unknown).
     */
    fun insertBeforeId(itemIds: List<Int>, currentItemId: Int?): Int? {
        val index = currentItemId?.let { itemIds.indexOf(it) } ?: return null
        return if (index >= 0 && index + 1 < itemIds.size) itemIds[index + 1] else null
    }
}
