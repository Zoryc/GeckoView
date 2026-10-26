package app.snapcast.ui.video

import androidx.compose.runtime.Immutable
import app.snapcast.core.model.DetectedVideo
import app.snapcast.core.model.SubtitleTrack
import app.snapcast.core.model.VideoVariant

/** What the user can do with a detected video; implemented by the browser screen. */
@Immutable
class VideoActions(
    /** Cast [video], optionally a single [variant], with a [subtitle], or directly without the proxy. */
    val cast: (video: DetectedVideo, variant: VideoVariant?, subtitle: SubtitleTrack?, direct: Boolean) -> Unit,
    /** Plays [video] on the phone, at the picked quality ([variant]) or adapting when null. */
    val playOnPhone: (video: DetectedVideo, variant: VideoVariant?) -> Unit,
    /** Copies the media URL to the clipboard (the list confirms it inline). */
    val copyLink: (DetectedVideo) -> Unit,
    val openWithOtherApp: (DetectedVideo) -> Unit,
    val addToQueue: (video: DetectedVideo, variant: VideoVariant?) -> Unit,
    /** A disabled action of a DRM-protected video was tapped: explain why. */
    val explainDrm: (DetectedVideo) -> Unit,
    val canCastDirectly: (DetectedVideo) -> Boolean,
)
