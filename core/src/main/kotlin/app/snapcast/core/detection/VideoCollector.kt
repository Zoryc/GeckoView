package app.snapcast.core.detection

import app.snapcast.core.media.MediaClassifier
import app.snapcast.core.media.MediaKind
import app.snapcast.core.model.DetectedVideo
import app.snapcast.core.model.DetectionSource
import app.snapcast.core.model.DrmInfo
import app.snapcast.core.model.MediaHeaders
import app.snapcast.core.model.StreamType
import app.snapcast.core.model.SubtitleFormat
import app.snapcast.core.model.SubtitleTrack
import app.snapcast.core.util.UrlUtils

/**
 * Per-tab, single-threaded accumulator that turns raw [MediaReport]s into a de-duplicated list of
 * [DetectedVideo]s. Not thread-safe: callers serialize access (the detection module uses one
 * collector per tab guarded by its own dispatcher/lock).
 *
 * Rules:
 * - De-duplicates by [app.snapcast.core.util.UrlUtils.normalizeForDedupe]; later reports enrich earlier ones
 *   (fill missing size/duration/poster/title/headers; source becomes BOTH when seen by network and DOM).
 * - Segments ([app.snapcast.core.media.MediaKind.Segment]) are never listed.
 * - HLS media playlists whose URL is a variant (or rendition) of a known master in this tab are folded
 *   into that master instead of being listed; when a master arrives after its variants, the variants'
 *   entries are removed ([onMasterParsed]).
 * - DOM reports with blob: sources are not listed; their poster/duration/title (not their size) enrich the
 *   most recent adaptive stream of the tab (or are kept pending until one arrives).
 * - Subtitle reports and <track> elements are collected per tab and attached to every video's [DetectedVideo.subtitles].
 * - A tab-level DRM signal ([markDrm]) marks all current and future videos as DRM-protected.
 *
 * Further details:
 * - DOM reports with http(s) URLs are listed even without a recognisable extension (type [StreamType.OTHER]);
 *   DOM URLs that look like segments are not.
 * - Network reports for redirects (301/302/303/307/308) and failed responses (status >= 400) are ignored:
 *   the redirect target is reported on its own, and a failed URL cannot be played.
 * - LL-HLS delivery directives (`_HLS_msn`, `_HLS_part`, `_HLS_skip`, ...) are removed from reported URLs, so the
 *   reloads of a low-latency live playlist merge into (or fold like) the plain playlist URL.
 * - Headers captured on a network request are authoritative: once one arrived, DOM reports never change an
 *   entry's headers, and the first network report replaces the DOM ones. A DOM report only carries the
 *   Referer its page would send (the content script applies the page's referrer policy); none is invented.
 * - Cookies seen anywhere in the tab ([MediaReport.cookiesByHost], newest value per host) are applied to every
 *   entry, including entries only seen in the DOM and masters whose variants brought newer cookies.
 * - Titles and posters taken from the media element are preferred over the page title / og:image fallbacks.
 *   Each poster keeps the Referer of the document that showed it ([DetectedVideo.posterReferer]).
 */
class VideoCollector(
    val tabId: String,
    val isPrivate: Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Entry(
        var video: DetectedVideo,
        /** Headers came from a network request (preferred over DOM-derived ones). */
        var networkHeaders: Boolean,
        /** Title came from the media element / report rather than the page title fallback. */
        var explicitTitle: Boolean,
        /** Poster came from the media element rather than the og:image fallback. */
        var explicitPoster: Boolean,
    )

    /**
     * Metadata of a blob: (MediaSource) element, applied to the adaptive stream feeding it. The element's
     * size is left out: it is only the rendition the page player is decoding right now, not the stream's.
     */
    private data class Enrichment(
        val title: String?,
        val posterUrl: String?,
        val posterReferer: String?,
        val durationSec: Double?,
        val thumbnail: String?,
    ) {
        val isEmpty: Boolean get() = title == null && posterUrl == null && durationSec == null && thumbnail == null

        /** This enrichment with values missing from it taken from [older]. */
        fun orElse(older: Enrichment): Enrichment = Enrichment(
            title = title ?: older.title,
            posterUrl = posterUrl ?: older.posterUrl,
            posterReferer = if (posterUrl != null) posterReferer else older.posterReferer,
            durationSec = durationSec ?: older.durationSec,
            thumbnail = thumbnail ?: older.thumbnail,
        )
    }

    private val entries = LinkedHashMap<String, Entry>()
    private val tabSubtitles = LinkedHashMap<String, SubtitleTrack>()
    /** Newest Cookie header seen per host in this tab, most recently changed last. */
    private val tabCookies = LinkedHashMap<String, String>()
    private val foldedIds = HashSet<String>()
    private var tabDrm: DrmInfo? = null
    private var pendingEnrichment: Enrichment? = null
    private var currentPageUrl: String? = null
    private var pageTitle: String? = null
    private var ogTitle: String? = null
    private var ogImage: String? = null

    /** Current list in first-detected order. */
    val videos: List<DetectedVideo> get() = entries.values.map { it.video }

    val pageUrl: String? get() = currentPageUrl

    /**
     * Ingests one report. Returns the added-or-updated video, or null when the report was ignored
     * (not media, a segment, a folded variant, a blob DOM source, a subtitle).
     */
    fun add(report: MediaReport): DetectedVideo? {
        // LL-HLS reloads carry _HLS_msn/_HLS_part/_HLS_skip; store the plain playlist URL so a cast or
        // inspection never replays a stale blocking-reload request.
        val url = report.url.trim().let(UrlUtils::withoutHlsDirectives)
        val isDom = report.source.trim().equals(SOURCE_DOM, ignoreCase = true)
        if (currentPageUrl == null) currentPageUrl = report.pageUrl.clean()
        collectCookies(report)
        collectTracks(report)

        if (isDom && (report.isBlob || url.startsWith("blob:", ignoreCase = true))) {
            enrichFromBlob(report)
            return null
        }
        if (!UrlUtils.isHttp(url)) return null
        if (!isDom && isRedirectOrError(report.status)) return null

        val type = when (val kind = MediaClassifier.classify(url, report.mime, report.resourceType, report.size)) {
            is MediaKind.Stream -> kind.type
            is MediaKind.Subtitle -> {
                addReportedSubtitle(url, kind.format, report)
                return null
            }
            MediaKind.Segment -> return null
            MediaKind.NotMedia -> if (isDom) MediaClassifier.typeFromUrl(url) ?: StreamType.OTHER else return null
        }

        val id = UrlUtils.stableId(url)
        if (id in foldedIds) return null
        val existing = entries[id]
        return if (existing == null) create(id, url, type, report, isDom) else merge(existing, type, report, isDom)
    }

    private fun create(id: String, url: String, type: StreamType, report: MediaReport, isDom: Boolean): DetectedVideo {
        val reportTitle = report.title.clean()
        val reportPoster = report.poster.clean()
        val reportPageTitle = report.pageTitle.clean()
        val poster = if (reportPoster != null) reportPoster to posterRefererOf(report, reportPoster) else pagePoster()
        val video = DetectedVideo(
            id = id,
            tabId = tabId,
            url = url,
            type = type,
            mimeType = usableMime(report.mime, type),
            pageUrl = report.pageUrl.clean() ?: currentPageUrl,
            pageTitle = reportPageTitle ?: pageTitle,
            title = reportTitle ?: ogTitle ?: reportPageTitle ?: pageTitle,
            headers = headersOf(report),
            cookiesByHost = report.cookiesByHost + tabCookies,
            // A playlist's own length says nothing about the video.
            sizeBytes = report.size.positive().takeUnless { type.isAdaptive },
            durationSec = report.duration.validDuration(),
            width = report.width.positive(),
            height = report.height.positive(),
            posterUrl = poster?.first,
            posterReferer = poster?.second,
            frameThumbnail = report.thumbnail.validThumbnail(),
            subtitles = tabSubtitles.values.toList(),
            drm = tabDrm,
            source = if (isDom) DetectionSource.DOM else DetectionSource.NETWORK,
            detectedAt = clock(),
            isPrivate = isPrivate,
        )
        val entry = Entry(video, networkHeaders = !isDom, explicitTitle = reportTitle != null, explicitPoster = reportPoster != null)
        entries[id] = entry
        if (type.isAdaptive) {
            pendingEnrichment?.let { applyEnrichment(entry, it) }
            pendingEnrichment = null
        }
        return entry.video
    }

    private fun merge(entry: Entry, type: StreamType, report: MediaReport, isDom: Boolean): DetectedVideo {
        val old = entry.video
        val reportHeaders = headersOf(report)
        val headers = when {
            // What the browser really sent wins over what a DOM report says it would send.
            isDom && entry.networkHeaders -> old.headers
            !isDom && !entry.networkHeaders -> reportHeaders
            // The latest DOM report says which Referer the page sends now, none included (its policy became known).
            isDom -> reportHeaders.filledFrom(old.headers).copy(referer = reportHeaders.referer)
            else -> reportHeaders.filledFrom(old.headers)
        }
        if (!isDom) entry.networkHeaders = true

        val reportTitle = report.title.clean()
        val title = if (reportTitle != null && !entry.explicitTitle) {
            entry.explicitTitle = true
            reportTitle
        } else {
            old.title ?: ogTitle ?: report.pageTitle.clean() ?: pageTitle
        }
        val reportPoster = report.poster.clean()
        val poster = if (reportPoster != null && !entry.explicitPoster) {
            entry.explicitPoster = true
            reportPoster to posterRefererOf(report, reportPoster)
        } else if (old.posterUrl != null) {
            old.posterUrl to old.posterReferer
        } else {
            pagePoster()
        }

        val mergedType = if (old.type == StreamType.OTHER && type != StreamType.OTHER) type else old.type
        val reportMime = usableMime(report.mime, mergedType)
        val reportSource = if (isDom) DetectionSource.DOM else DetectionSource.NETWORK
        entry.video = old.copy(
            type = mergedType,
            mimeType = if (isDom) old.mimeType ?: reportMime else reportMime ?: old.mimeType,
            pageUrl = old.pageUrl ?: report.pageUrl.clean() ?: currentPageUrl,
            pageTitle = old.pageTitle ?: report.pageTitle.clean() ?: pageTitle,
            title = title,
            headers = headers,
            cookiesByHost = old.cookiesByHost + report.cookiesByHost + tabCookies,
            sizeBytes = if (mergedType.isAdaptive) null else maxOfKnown(old.sizeBytes, report.size.positive()),
            durationSec = old.durationSec ?: report.duration.validDuration(),
            width = old.width ?: report.width.positive(),
            height = old.height ?: report.height.positive(),
            posterUrl = poster?.first,
            posterReferer = poster?.second,
            frameThumbnail = old.frameThumbnail ?: report.thumbnail.validThumbnail(),
            subtitles = withTabSubtitles(old.subtitles),
            drm = old.drm ?: tabDrm,
            source = if (old.source == reportSource) old.source else DetectionSource.BOTH,
        )
        return entry.video
    }

    /** Applies page metadata (title / og:image used as poster fallback). */
    fun onPage(message: PageMessage) {
        message.url.clean()?.let { currentPageUrl = it }
        message.title.clean()?.let { pageTitle = it }
        message.ogTitle.clean()?.let { ogTitle = it }
        message.ogImage.clean()?.let { image ->
            ogImage = currentPageUrl?.takeIf(UrlUtils::isHttp)?.let { UrlUtils.resolve(it, image) } ?: image
        }
        val pagePoster = pagePoster()
        for (entry in entries.values) {
            val video = entry.video
            val usePagePoster = !entry.explicitPoster && pagePoster != null
            entry.video = video.copy(
                pageTitle = video.pageTitle ?: pageTitle,
                title = if (entry.explicitTitle) video.title else ogTitle ?: video.title ?: pageTitle,
                posterUrl = if (usePagePoster) pagePoster?.first else video.posterUrl,
                posterReferer = if (usePagePoster) pagePoster?.second else video.posterReferer,
            )
        }
    }

    /** Records the result of fetching an HLS playlist / DASH manifest for [videoId]; returns the updated video. */
    fun update(videoId: String, transform: (DetectedVideo) -> DetectedVideo): DetectedVideo? {
        val entry = entries[videoId] ?: return null
        val updated = transform(entry.video)
        entry.video = updated.copy(
            id = entry.video.id,
            tabId = tabId,
            subtitles = withTabSubtitles(updated.subtitles),
            drm = updated.drm ?: tabDrm,
        )
        return entry.video
    }

    /**
     * Called after a master playlist for [masterVideoId] was parsed: remembers [variantUrls] (variant and
     * rendition playlist URLs) and removes already-listed entries for them. Returns ids of removed videos.
     */
    fun onMasterParsed(masterVideoId: String, variantUrls: Collection<String>): List<String> {
        val ids = variantUrls.map { UrlUtils.stableId(it) }.filter { it != masterVideoId }.distinct()
        foldedIds += ids
        val master = entries[masterVideoId]
        val removed = ids.filter { it in entries }
        for (id in removed) {
            val variant = entries.remove(id) ?: continue
            if (master != null) absorb(master, variant)
        }
        return removed
    }

    /** Keeps element metadata (e.g. from a blob enrichment) that landed on a variant before it was folded. */
    private fun absorb(master: Entry, variant: Entry) {
        val m = master.video
        val v = variant.video
        val takeTitle = variant.explicitTitle && !master.explicitTitle
        val takePoster = variant.explicitPoster && !master.explicitPoster
        master.video = m.copy(
            title = if (takeTitle) v.title else m.title,
            posterUrl = if (takePoster) v.posterUrl else m.posterUrl,
            posterReferer = if (takePoster) v.posterReferer else m.posterReferer,
            durationSec = m.durationSec ?: v.durationSec,
            frameThumbnail = m.frameThumbnail ?: v.frameThumbnail,
        )
        if (takeTitle) master.explicitTitle = true
        if (takePoster) master.explicitPoster = true
    }

    fun addSubtitle(track: SubtitleTrack) {
        val existing = tabSubtitles[track.id]
        val merged = existing?.copy(
            label = existing.label ?: track.label,
            language = existing.language ?: track.language,
        ) ?: track
        if (merged == existing) return
        tabSubtitles[track.id] = merged
        for (entry in entries.values) {
            val own = entry.video.subtitles.map { if (it == existing) merged else it }
            entry.video = entry.video.copy(subtitles = withTabSubtitles(own))
        }
    }

    fun markDrm(info: DrmInfo) {
        val current = tabDrm
        tabDrm = if (current == null || (current.system == null && info.system != null)) info else current
        for (entry in entries.values) {
            if (entry.video.drm == null) entry.video = entry.video.copy(drm = tabDrm)
        }
    }

    /** Forget everything (new document loaded). */
    fun reset(newPageUrl: String?) {
        entries.clear()
        tabSubtitles.clear()
        tabCookies.clear()
        foldedIds.clear()
        tabDrm = null
        pendingEnrichment = null
        currentPageUrl = newPageUrl.clean()
        pageTitle = null
        ogTitle = null
        ogImage = null
    }

    private fun collectTracks(report: MediaReport) {
        if (report.tracks.isEmpty()) return
        val base = report.frameUrl.clean() ?: report.pageUrl.clean() ?: currentPageUrl
        for (track in report.tracks) {
            val kind = track.kind.clean()?.lowercase()
            if (kind != null && kind !in TEXT_TRACK_KINDS) continue
            val raw = track.url.trim()
            if (raw.isEmpty()) continue
            val url = if (base != null) UrlUtils.resolve(base, raw) else raw
            if (!UrlUtils.isHttp(url) || MediaClassifier.isSegmentUrl(url)) continue
            addSubtitle(
                SubtitleTrack(
                    id = UrlUtils.stableId(url),
                    url = url,
                    label = track.label.clean(),
                    language = track.lang.clean(),
                    // <track> elements are WebVTT by the HTML spec unless the URL says otherwise.
                    format = MediaClassifier.subtitleFormatFromUrl(url) ?: SubtitleFormat.VTT,
                ),
            )
        }
    }

    private fun addReportedSubtitle(url: String, format: SubtitleFormat, report: MediaReport) {
        if (MediaClassifier.isSegmentUrl(url)) return
        addSubtitle(
            SubtitleTrack(
                id = UrlUtils.stableId(url),
                url = url,
                label = report.title.clean() ?: UrlUtils.fileNameOf(url),
                format = format,
            ),
        )
    }

    private fun enrichFromBlob(report: MediaReport) {
        val poster = report.poster.clean()
        val enrichment = Enrichment(
            title = report.title.clean(),
            posterUrl = poster,
            posterReferer = poster?.let { posterRefererOf(report, it) },
            durationSec = report.duration.validDuration(),
            thumbnail = report.thumbnail.validThumbnail(),
        )
        if (enrichment.isEmpty) return
        val target = entries.values.lastOrNull { it.video.type.isAdaptive }
        if (target != null) {
            applyEnrichment(target, enrichment)
        } else {
            pendingEnrichment = pendingEnrichment?.let { enrichment.orElse(it) } ?: enrichment
        }
    }

    /** Fills missing values only; titles/posters that are mere page fallbacks count as missing. */
    private fun applyEnrichment(entry: Entry, enrichment: Enrichment) {
        val video = entry.video
        val takeTitle = enrichment.title != null && !entry.explicitTitle
        val takePoster = enrichment.posterUrl != null && !entry.explicitPoster
        entry.video = video.copy(
            title = if (takeTitle) enrichment.title else video.title,
            posterUrl = if (takePoster) enrichment.posterUrl else video.posterUrl,
            posterReferer = if (takePoster) enrichment.posterReferer else video.posterReferer,
            durationSec = video.durationSec ?: enrichment.durationSec,
            frameThumbnail = video.frameThumbnail ?: enrichment.thumbnail,
        )
        if (takeTitle) entry.explicitTitle = true
        if (takePoster) entry.explicitPoster = true
    }

    /** Records the tab's newest cookies per host and applies them to every entry when they changed. */
    private fun collectCookies(report: MediaReport) {
        var changed = false
        for ((rawHost, rawValue) in report.cookiesByHost) {
            val host = rawHost.trim().lowercase()
            val value = rawValue.trim()
            if (host.isEmpty() || value.isEmpty() || tabCookies[host] == value) continue
            tabCookies.remove(host)
            tabCookies[host] = value
            changed = true
        }
        if (!changed) return
        while (tabCookies.size > MAX_COOKIE_HOSTS) tabCookies.remove(tabCookies.keys.first())
        for (entry in entries.values) {
            entry.video = entry.video.copy(cookiesByHost = entry.video.cookiesByHost + tabCookies)
        }
    }

    /** The page's og:image with the Referer the page would send for it, or null. */
    private fun pagePoster(): Pair<String, String?>? {
        val image = ogImage ?: return null
        return image to currentPageUrl?.let { UrlUtils.referrerFor(it, image) }
    }

    /** Referer for a poster found in a report's document (the player's frame, else the page). */
    private fun posterRefererOf(report: MediaReport, poster: String): String? {
        val document = report.frameUrl.clean() ?: report.pageUrl.clean() ?: currentPageUrl ?: return null
        return UrlUtils.referrerFor(document, poster)
    }

    /** [own] followed by the tab's subtitles it does not already contain. */
    private fun withTabSubtitles(own: List<SubtitleTrack>): List<SubtitleTrack> {
        if (tabSubtitles.isEmpty()) return own
        val ids = own.mapTo(HashSet()) { it.id }
        return own + tabSubtitles.values.filter { it.id !in ids }
    }

    private fun headersOf(report: MediaReport): MediaHeaders {
        val byName = HashMap<String, String>()
        for ((name, value) in report.requestHeaders) {
            val v = value.trim()
            if (v.isNotEmpty()) byName[name.trim().lowercase()] = v
        }
        return MediaHeaders(
            referer = byName["referer"],
            userAgent = byName["user-agent"],
            origin = byName["origin"],
            cookie = byName["cookie"],
            extra = buildMap {
                for ((lower, name) in EXTRA_HEADERS) byName[lower]?.let { put(name, it) }
            },
        )
    }

    private fun MediaHeaders.filledFrom(other: MediaHeaders): MediaHeaders = MediaHeaders(
        referer = referer ?: other.referer,
        userAgent = userAgent ?: other.userAgent,
        origin = origin ?: other.origin,
        cookie = cookie ?: other.cookie,
        extra = other.extra + extra,
    )

    private companion object {
        const val SOURCE_DOM = "dom"
        const val MAX_COOKIE_HOSTS = 64
        /** Other captured headers replayed as sent: Authorization (origin host only), and content negotiation. */
        val EXTRA_HEADERS = listOf("authorization" to "Authorization", "accept" to "Accept", "accept-language" to "Accept-Language")
        const val THUMBNAIL_PREFIX = "data:image/jpeg;base64,"
        /** About 150 KB of JPEG; a 320-pixel-wide capture is a tenth of that. */
        const val MAX_THUMBNAIL_LENGTH = 200_000
        val TEXT_TRACK_KINDS = setOf("subtitles", "captions")
        val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)

        fun isRedirectOrError(status: Int?): Boolean = status != null && (status in REDIRECT_STATUSES || status >= 400)

        /** The MIME type to keep for a stream: only when it agrees with the detected type. */
        fun usableMime(mime: String?, type: StreamType): String? {
            val base = mime?.substringBefore(';')?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
            return when {
                MediaClassifier.typeFromContentType(base) == type -> base
                type == StreamType.OTHER && base.startsWith("video/") -> base
                else -> null
            }
        }

        fun maxOfKnown(a: Long?, b: Long?): Long? = when {
            a == null -> b
            b == null -> a
            else -> maxOf(a, b)
        }

        fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

        fun Long?.positive(): Long? = this?.takeIf { it > 0 }

        fun Int?.positive(): Int? = this?.takeIf { it > 0 }

        fun Double?.validDuration(): Double? = this?.takeIf { it.isFinite() && it > 0 }

        /** A frame capture as the content script sends it: a JPEG data URL of bounded size. */
        fun String?.validThumbnail(): String? =
            this?.takeIf { it.startsWith(THUMBNAIL_PREFIX) && it.length in (THUMBNAIL_PREFIX.length + 1)..MAX_THUMBNAIL_LENGTH }
    }
}
