package app.snapcast.cast.controller

import app.snapcast.cast.PendingQueueItem
import app.snapcast.core.model.DetectedVideo

/** Pure operations on the app-side queue kept while nothing is casting. */
internal object PendingQueue {
    /** Key made of the video id and a timestamp, bumped until unique within [existing]. */
    fun newKey(video: DetectedVideo, nowMs: Long, existing: List<PendingQueueItem>): String {
        val keys = existing.mapTo(HashSet()) { it.key }
        var stamp = nowMs
        while ("${video.id}-$stamp" in keys) stamp++
        return "${video.id}-$stamp"
    }

    fun add(queue: List<PendingQueueItem>, item: PendingQueueItem, playNext: Boolean): List<PendingQueueItem> =
        if (playNext) listOf(item) + queue else queue + item

    fun remove(queue: List<PendingQueueItem>, key: String): List<PendingQueueItem> = queue.filterNot { it.key == key }

    /** Moves [key] to [toIndex] (clamped); unknown keys leave the queue unchanged. */
    fun move(queue: List<PendingQueueItem>, key: String, toIndex: Int): List<PendingQueueItem> {
        val from = queue.indexOfFirst { it.key == key }
        if (from < 0) return queue
        val result = queue.toMutableList()
        val item = result.removeAt(from)
        result.add(toIndex.coerceIn(0, result.size), item)
        return result
    }
}
