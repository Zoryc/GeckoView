package app.snapcast.ui.local

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import app.snapcast.core.media.M3uParser
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** Why a playlist could not be imported. */
internal enum class PlaylistError { UNREADABLE, TOO_LARGE, HTTP, NETWORK, BAD_URL, EMPTY }

internal class PlaylistException(val error: PlaylistError, val httpCode: Int? = null) : Exception(error.name)

/** Name and MIME type of a document the user picked. */
internal data class DocumentInfo(val displayName: String?, val mimeType: String?)

/** Reads user-picked documents (content:// URIs) on the IO dispatcher. */
internal class LocalDocuments @Inject constructor(@ApplicationContext private val context: Context) {
    suspend fun info(uri: Uri): DocumentInfo = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val name = try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
            }
        } catch (e: SecurityException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
        val mime = try {
            resolver.getType(uri)
        } catch (e: SecurityException) {
            null
        }
        DocumentInfo(displayName = name?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment, mimeType = mime)
    }

    /**
     * Keeps read access to [uri] beyond the lifetime of the picker result (the player may outlive this screen).
     * Documents that do not offer persistable access keep the temporary grant.
     */
    suspend fun keepReadAccess(uri: Uri) {
        withContext(Dispatchers.IO) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: SecurityException) {
                // Not persistable: the grant from the picker still covers this process.
            }
        }
    }

    /** UTF-8 text of [uri]; throws [PlaylistException] when it cannot be read or exceeds [maxBytes]. */
    suspend fun readText(uri: Uri, maxBytes: Int): String = withContext(Dispatchers.IO) {
        val bytes = try {
            val input = context.contentResolver.openInputStream(uri) ?: throw PlaylistException(PlaylistError.UNREADABLE)
            input.use { BoundedReader.readAtMost(it, maxBytes) } ?: throw PlaylistException(PlaylistError.TOO_LARGE)
        } catch (e: IOException) {
            throw PlaylistException(PlaylistError.UNREADABLE)
        } catch (e: SecurityException) {
            throw PlaylistException(PlaylistError.UNREADABLE)
        }
        String(bytes, Charsets.UTF_8)
    }
}

/** Loads and parses .m3u playlists from files or URLs. */
internal class PlaylistLoader @Inject constructor(
    private val client: OkHttpClient,
    private val documents: LocalDocuments,
) {
    suspend fun fromDocument(uri: Uri): IptvPlaylist {
        val name = documents.info(uri).displayName ?: uri.toString()
        val text = documents.readText(uri, MAX_PLAYLIST_BYTES)
        return parse(name, text, baseUrl = null)
    }

    suspend fun fromUrl(url: String): IptvPlaylist {
        val (text, finalUrl) = withContext(Dispatchers.IO) { download(url) }
        return parse(IptvChannels.nameFromUrl(finalUrl), text, baseUrl = finalUrl)
    }

    private fun download(url: String): Pair<String, String> {
        val request = try {
            Request.Builder().url(url).build()
        } catch (e: IllegalArgumentException) {
            throw PlaylistException(PlaylistError.BAD_URL)
        }
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw PlaylistException(PlaylistError.HTTP, response.code)
                val bytes = response.body.byteStream().use { BoundedReader.readAtMost(it, MAX_PLAYLIST_BYTES) }
                    ?: throw PlaylistException(PlaylistError.TOO_LARGE)
                return String(bytes, Charsets.UTF_8) to response.request.url.toString()
            }
        } catch (e: IOException) {
            throw PlaylistException(PlaylistError.NETWORK)
        }
    }

    private suspend fun parse(name: String, text: String, baseUrl: String?): IptvPlaylist = withContext(Dispatchers.Default) {
        val playlist = IptvChannels.playlist(name, M3uParser.parse(text, baseUrl))
        if (playlist.channels.isEmpty()) throw PlaylistException(PlaylistError.EMPTY)
        playlist
    }

    private companion object {
        const val MAX_PLAYLIST_BYTES = 16 * 1024 * 1024
    }
}

/** Keeps the last imported playlist in memory for the lifetime of the process (it is reopened often). */
@Singleton
internal class IptvPlaylistStore @Inject constructor() {
    private val current = MutableStateFlow<IptvPlaylist?>(null)

    val playlist: StateFlow<IptvPlaylist?> = current.asStateFlow()

    fun set(playlist: IptvPlaylist?) {
        current.value = playlist
    }
}
