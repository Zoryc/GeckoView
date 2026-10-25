package app.snapcast.ui.local

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.snapcast.cast.CastController
import app.snapcast.cast.CastErrorKind
import app.snapcast.cast.CastResult
import app.snapcast.core.media.M3uEntry
import app.snapcast.core.media.MediaClassifier
import app.snapcast.core.model.StreamType
import app.snapcast.player.PlaybackRequest
import app.snapcast.player.PlayerLauncher
import app.snapcast.ui.R
import app.snapcast.ui.shared.UiMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

internal data class ChannelListState(
    val playlist: IptvPlaylist? = null,
    val query: String = "",
    val groups: List<ChannelGroup> = emptyList(),
    val collapsedGroups: Set<String> = emptySet(),
) {
    val matchCount: Int get() = groups.sumOf { it.channels.size }
}

/** One-shot requests the screen carries out with the activity. */
internal sealed interface LocalEffect {
    /** Not connected to a receiver: open the device chooser; the cast starts once a device connects. */
    data object ChooseDevice : LocalEffect

    data class LaunchPlayer(val request: PlaybackRequest) : LocalEffect

    data class Message(val message: UiMessage) : LocalEffect
}

/**
 * Local media screen logic: cast or play a video file from the phone, import an IPTV playlist from a file
 * or URL and cast / play its channels. The last playlist lives in [IptvPlaylistStore] so it survives
 * leaving the screen.
 */
@OptIn(FlowPreview::class)
@HiltViewModel
internal class LocalMediaViewModel @Inject constructor(
    private val controller: CastController,
    private val playerLauncher: PlayerLauncher,
    private val documents: LocalDocuments,
    private val loader: PlaylistLoader,
    private val store: IptvPlaylistStore,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val collapsed = MutableStateFlow<Set<String>>(emptySet())
    private val loading = MutableStateFlow(false)
    private val effectChannel = Channel<LocalEffect>(Channel.BUFFERED)

    val effects: Flow<LocalEffect> = effectChannel.receiveAsFlow()

    /** True while a playlist is being read or downloaded. */
    val loadingPlaylist: StateFlow<Boolean> = loading.asStateFlow()

    val channels: StateFlow<ChannelListState> =
        combine(store.playlist, query.debounce(QUERY_DEBOUNCE_MS), collapsed, ::channelState)
            .flowOn(Dispatchers.Default)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = channelState(store.playlist.value, query.value, collapsed.value),
            )

    // Videos stored on the phone

    fun castLocalVideo(uri: Uri) {
        viewModelScope.launch {
            val info = documents.info(uri)
            val result = guarded { controller.castLocalFile(uri, info.displayName, info.mimeType) }
            handleCastResult(result, info.displayName ?: uri.toString())
        }
    }

    fun playLocalVideo(uri: Uri) {
        viewModelScope.launch {
            documents.keepReadAccess(uri)
            val info = documents.info(uri)
            val name = info.displayName ?: uri.toString()
            val type = MediaClassifier.typeFromContentType(info.mimeType)
                ?: MediaClassifier.typeFromUrl(name)
                ?: StreamType.OTHER
            val request = PlaybackRequest(url = uri.toString(), title = name, type = type, mimeType = info.mimeType)
            effectChannel.trySend(LocalEffect.LaunchPlayer(request))
        }
    }

    // IPTV playlists

    fun importPlaylist(uri: Uri) = load { loader.fromDocument(uri) }

    /** Downloads the playlist at [text]; returns false (and does nothing) when it is not an http(s) URL. */
    fun importPlaylistFromUrl(text: String): Boolean {
        val url = IptvChannels.playlistUrl(text) ?: return false
        load { loader.fromUrl(url) }
        return true
    }

    fun clearPlaylist() {
        store.set(null)
        collapsed.value = emptySet()
    }

    fun setQuery(text: String) {
        query.value = text
    }

    fun toggleGroup(key: String) {
        collapsed.value = collapsed.value.let { if (key in it) it - key else it + key }
    }

    fun castChannel(channel: M3uEntry) {
        viewModelScope.launch {
            val video = IptvChannels.toDetectedVideo(channel, store.playlist.value?.name, System.currentTimeMillis())
            handleCastResult(guarded { controller.cast(video) }, channel.name)
        }
    }

    fun queueChannel(channel: M3uEntry) {
        viewModelScope.launch {
            val video = IptvChannels.toDetectedVideo(channel, store.playlist.value?.name, System.currentTimeMillis())
            when (val result = guarded { controller.addToQueue(video) }) {
                CastResult.Success -> message(UiMessage.res(R.string.ui_screens_local_queued, channel.name))
                CastResult.NeedsDevice -> effectChannel.trySend(LocalEffect.ChooseDevice)
                is CastResult.Error -> message(errorMessage(result))
            }
        }
    }

    fun playChannel(channel: M3uEntry) {
        val video = IptvChannels.toDetectedVideo(channel, store.playlist.value?.name, System.currentTimeMillis())
        val request = PlaybackRequest(
            url = video.url,
            title = video.displayTitle,
            type = video.type,
            headers = video.headers.toMap(),
            posterUrl = video.posterUrl,
        )
        effectChannel.trySend(LocalEffect.LaunchPlayer(request))
    }

    // Activity-bound actions requested through [effects]

    fun launchPlayer(context: Context, request: PlaybackRequest) = playerLauncher.play(context, request)

    fun showDeviceChooser(activity: FragmentActivity) = controller.showDeviceChooser(activity)

    private fun channelState(playlist: IptvPlaylist?, text: String, collapsedGroups: Set<String>) = ChannelListState(
        playlist = playlist,
        query = text,
        groups = playlist?.let { IptvChannels.group(it.channels, text) }.orEmpty(),
        collapsedGroups = collapsedGroups,
    )

    private fun load(block: suspend () -> IptvPlaylist) {
        if (loading.value) return
        loading.value = true
        viewModelScope.launch {
            try {
                val playlist = block()
                store.set(playlist)
                collapsed.value = emptySet()
                message(
                    UiMessage.plural(
                        R.plurals.ui_screens_local_playlist_loaded,
                        playlist.channels.size,
                        playlist.channels.size,
                        playlist.name,
                    ),
                )
            } catch (e: PlaylistException) {
                message(playlistErrorMessage(e))
            } finally {
                loading.value = false
            }
        }
    }

    private fun playlistErrorMessage(e: PlaylistException): UiMessage = when (e.error) {
        PlaylistError.UNREADABLE -> UiMessage.res(R.string.ui_screens_local_error_unreadable)
        PlaylistError.TOO_LARGE -> UiMessage.res(R.string.ui_screens_local_error_too_large)
        PlaylistError.HTTP -> UiMessage.res(R.string.ui_screens_local_error_http, e.httpCode ?: 0)
        PlaylistError.NETWORK -> UiMessage.res(R.string.ui_screens_local_error_network)
        PlaylistError.BAD_URL -> UiMessage.res(R.string.ui_screens_local_url_invalid)
        PlaylistError.EMPTY -> UiMessage.res(R.string.ui_screens_local_error_empty)
    }

    private fun handleCastResult(result: CastResult, title: String) {
        when (result) {
            CastResult.Success -> message(UiMessage.res(R.string.ui_screens_local_casting, title))
            CastResult.NeedsDevice -> effectChannel.trySend(LocalEffect.ChooseDevice)
            is CastResult.Error -> message(errorMessage(result))
        }
    }

    /** Runs a cast call, turning unexpected runtime failures into a generic error result. */
    private suspend fun guarded(block: suspend () -> CastResult): CastResult = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: RuntimeException) {
        Log.w(TAG, "Cast request failed", e)
        CastResult.Error(CastErrorKind.LOAD_FAILED, message = "")
    }

    private fun errorMessage(error: CastResult.Error): UiMessage =
        if (error.message.isBlank()) UiMessage.res(R.string.ui_screens_cast_failed) else UiMessage.Text(error.message)

    private fun message(message: UiMessage) {
        effectChannel.trySend(LocalEffect.Message(message))
    }

    private companion object {
        const val TAG = "SnapcastLocal"
        const val QUERY_DEBOUNCE_MS = 150L
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
