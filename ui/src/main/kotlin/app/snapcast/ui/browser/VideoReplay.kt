package app.snapcast.ui.browser

import app.snapcast.core.model.DetectedVideo
import app.snapcast.core.model.VideoResolution
import app.snapcast.core.model.VideoVariant
import app.snapcast.core.util.UrlUtils
import app.snapcast.player.PlaybackQuality

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

    /** The video in [videos] that is [originalUrl] again, found after [since] (the reload), or null. */
    fun find(videos: List<DetectedVideo>, originalUrl: String, since: Long): DetectedVideo? {
        val key = keyOf(originalUrl) ?: return null
        return videos.firstOrNull { it.detectedAt >= since && keyOf(it.url) == key }
    }

    /** The variant of [video] closest to the [quality] that was picked before the reload, or null. */
    fun variantFor(video: DetectedVideo, quality: PlaybackQuality?): VideoVariant? {
        quality ?: return null
        val wanted = VideoResolution.classOf(quality.width, quality.height)
        return video.variants.firstOrNull { wanted != null && VideoResolution.classOf(it.width, it.height) == wanted }
            ?: video.variants.firstOrNull { quality.bandwidth != null && it.bandwidth == quality.bandwidth }
    }
}
