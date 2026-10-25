package app.snapcast.core.detection

import app.snapcast.core.model.DetectedVideo
import app.snapcast.core.model.DrmInfo
import app.snapcast.core.model.SubtitleTrack

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
 * - DOM reports with blob: sources are not listed; their poster/duration/title enrich the most recent
 *   adaptive stream of the tab (or are kept pending until one arrives).
 * - Subtitle reports and <track> elements are collected per tab and attached to every video's [DetectedVideo.subtitles].
 * - A tab-level DRM signal ([markDrm]) marks all current and future videos as DRM-protected.
 */
class VideoCollector(
    val tabId: String,
    val isPrivate: Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Current list in first-detected order. */
    val videos: List<DetectedVideo> get() = TODO()

    val pageUrl: String? get() = TODO()

    /**
     * Ingests one report. Returns the added-or-updated video, or null when the report was ignored
     * (not media, a segment, a folded variant, a blob DOM source, a subtitle).
     */
    fun add(report: MediaReport): DetectedVideo? = TODO()

    /** Applies page metadata (title / og:image used as poster fallback). */
    fun onPage(message: PageMessage): Unit = TODO()

    /** Records the result of fetching an HLS playlist / DASH manifest for [videoId]; returns the updated video. */
    fun update(videoId: String, transform: (DetectedVideo) -> DetectedVideo): DetectedVideo? = TODO()

    /**
     * Called after a master playlist for [masterVideoId] was parsed: remembers [variantUrls] (variant and
     * rendition playlist URLs) and removes already-listed entries for them. Returns ids of removed videos.
     */
    fun onMasterParsed(masterVideoId: String, variantUrls: Collection<String>): List<String> = TODO()

    fun addSubtitle(track: SubtitleTrack): Unit = TODO()

    fun markDrm(info: DrmInfo): Unit = TODO()

    /** Forget everything (new document loaded). */
    fun reset(newPageUrl: String?): Unit = TODO()
}
