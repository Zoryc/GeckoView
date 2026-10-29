package app.snapcast.detection

import app.snapcast.core.model.DetectedVideo
import app.snapcast.core.model.MediaHeaders
import app.snapcast.core.util.UrlUtils

/**
 * Decides when a tab's videos are fetched by the stream inspector: once per video, once more after a
 * failure when a later report brought different request headers or cookies (a DOM report usually
 * arrives before the network one that carries the cookies), and again when a later report showed a
 * file to be an HLS or DASH stream (an extension-less URL first seen in the page). Main thread only.
 */
internal class InspectionTracker {
    private class Attempt(var headers: MediaHeaders, var cookies: Map<String, String>, val adaptive: Boolean, var count: Int) {
        var running = true
        var succeeded = false
    }

    private val attempts = HashMap<String, Attempt>()

    /** True when an inspection of [video] should start now; it is then recorded as running. */
    fun shouldStart(video: DetectedVideo): Boolean {
        if (!needsInspection(video)) return false
        val previous = attempts[video.id]
        if (previous == null || (!previous.running && video.type.isAdaptive && !previous.adaptive)) {
            attempts[video.id] = Attempt(video.headers, video.cookiesByHost, video.type.isAdaptive, count = 1)
            return true
        }
        if (previous.running || previous.succeeded || previous.count >= MAX_ATTEMPTS) return false
        if (previous.headers == video.headers && previous.cookies == video.cookiesByHost) return false
        previous.headers = video.headers
        previous.cookies = video.cookiesByHost
        previous.count += 1
        previous.running = true
        return true
    }

    fun finished(videoId: String, succeeded: Boolean) {
        val attempt = attempts[videoId] ?: return
        attempt.running = false
        attempt.succeeded = succeeded
    }

    fun clear() = attempts.clear()

    companion object {
        const val MAX_ATTEMPTS = 2

        /** HLS/DASH manifests are always worth reading; progressive files only to learn an unknown size. */
        fun needsInspection(video: DetectedVideo): Boolean = when {
            video.isDrmProtected || !UrlUtils.isHttp(video.url) -> false
            video.type.isAdaptive -> true
            else -> video.sizeBytes == null
        }
    }
}
