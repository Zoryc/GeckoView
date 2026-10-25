package app.snapcast.data.tabs

import kotlinx.serialization.Serializable

@Serializable
data class SavedTab(
    val id: String,
    val url: String,
    val title: String,
    val isDesktopMode: Boolean = false,
    val selected: Boolean = false,
    /** Opaque GeckoSession.SessionState JSON for back/forward restore, if available. */
    val sessionState: String? = null,
)

/** Persists open (non-private) tabs so they can be restored on the next launch. */
interface TabSessionStore {
    suspend fun load(): List<SavedTab>

    suspend fun save(tabs: List<SavedTab>)
}
