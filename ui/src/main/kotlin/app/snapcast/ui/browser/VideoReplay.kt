package app.snapcast.ui.browser

import app.snapcast.core.model.DetectedVideo
import app.snapcast.core.util.UrlUtils
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Finding a video again after its page was reloaded to renew an expired link: the new link usually differs only
 * in its query (token, signature, expiry), so videos are compared by scheme, host and path.
 */
internal object VideoReplay {
    /** The part of [url] a renewed link keeps: scheme, host (lower case) and path; null for non-http(s) URLs. */
    fun keyOf(url: String): String? {
        if (!UrlUtils.isHttp(url)) return null
        val host = UrlUtils.hostOf(url) ?: return null
        val afterScheme = url.substringAfter("://")
        val path = afterScheme.substringAfter('/', "").substringBefore('?').substringBefore('#')
        return "${url.substringBefore("://").lowercase()}://$host/$path"
    }

    /** The videos of [videos] found after [since] (the reload) that may be [originalUrl] again: same scheme, host and path. */
    fun candidates(videos: List<DetectedVideo>, originalUrl: String, since: Long): List<DetectedVideo> {
        val key = keyOf(originalUrl) ?: return emptyList()
        return videos.filter { it.detectedAt >= since && keyOf(it.url) == key }
    }

    /**
     * The one of [candidates] that is [originalUrl] again, or null: the same URL, else the renewed link that keeps
     * the most of the original query (`?v=111&sig=new` over `?v=222&sig=new` for `?v=111&sig=old`). Null while
     * that choice is ambiguous.
     */
    fun pick(candidates: List<DetectedVideo>, originalUrl: String): DetectedVideo? {
        candidates.firstOrNull { it.url == originalUrl }?.let { return it }
        if (candidates.size <= 1) return candidates.firstOrNull()
        val original = queryPairs(originalUrl)
        val scored = candidates.map { it to queryPairs(it.url).count { pair -> pair in original } }
        val best = scored.maxOf { it.second }
        return scored.filter { it.second == best }.singleOrNull()?.first
    }

    fun find(videos: List<DetectedVideo>, originalUrl: String, since: Long): DetectedVideo? =
        pick(candidates(videos, originalUrl, since), originalUrl)

    /**
     * Waits in [lists] (a tab's video list) for [originalUrl] to show up again after [since], and decides once the
     * candidates have stayed the same for [settleMs]: another video on the same path (a preview, say) can show up
     * before the one that was playing. Null when the choice is ambiguous.
     */
    @OptIn(FlowPreview::class)
    suspend fun await(lists: Flow<List<DetectedVideo>>, originalUrl: String, since: Long, settleMs: Long): DetectedVideo? =
        lists.map { candidates(it, originalUrl, since) }
            .filter { it.isNotEmpty() }
            .distinctUntilChanged { old, new -> old.map { it.id } == new.map { it.id } }
            .debounce(settleMs)
            .map { pick(it, originalUrl) }
            .first()

    private fun queryPairs(url: String): Set<String> =
        url.substringBefore('#').substringAfter('?', "").split('&').filter { it.isNotEmpty() }.toSet()
}
