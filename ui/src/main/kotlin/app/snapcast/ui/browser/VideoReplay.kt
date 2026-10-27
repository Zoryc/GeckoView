package app.snapcast.ui.browser

import app.snapcast.core.model.DetectedVideo
import app.snapcast.core.util.UrlUtils

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

    /**
     * The video in [videos] that is [originalUrl] again, found after [since] (the reload), or null: the same URL,
     * else the one renewed link with the same scheme, host and path that keeps the most of the original query
     * (`?v=111&sig=new` over `?v=222&sig=new` for `?v=111&sig=old`). Null while that choice is ambiguous.
     */
    fun find(videos: List<DetectedVideo>, originalUrl: String, since: Long): DetectedVideo? {
        val key = keyOf(originalUrl) ?: return null
        val fresh = videos.filter { it.detectedAt >= since }
        fresh.firstOrNull { it.url == originalUrl }?.let { return it }
        val candidates = fresh.filter { keyOf(it.url) == key }
        if (candidates.size <= 1) return candidates.firstOrNull()
        val original = queryPairs(originalUrl)
        val scored = candidates.map { it to queryPairs(it.url).count { pair -> pair in original } }
        val best = scored.maxOf { it.second }
        return scored.filter { it.second == best }.singleOrNull()?.first
    }

    private fun queryPairs(url: String): Set<String> =
        url.substringBefore('#').substringAfter('?', "").split('&').filter { it.isNotEmpty() }.toSet()
}
