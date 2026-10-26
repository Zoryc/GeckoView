package app.snapcast.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade

/**
 * Image request for the poster of a cast or queued item. It is kept in memory only, never in Coil's disk
 * cache: the playback state does not say whether the item came from a private tab, and a private tab's
 * poster must not stay on disk after the tab is closed.
 */
@Composable
internal fun rememberCastPosterRequest(posterUrl: String, crossfade: Boolean = false): ImageRequest {
    val context = LocalContext.current
    return remember(context, posterUrl, crossfade) {
        ImageRequest.Builder(context)
            .data(posterUrl)
            .diskCachePolicy(CachePolicy.DISABLED)
            .crossfade(crossfade)
            .build()
    }
}
