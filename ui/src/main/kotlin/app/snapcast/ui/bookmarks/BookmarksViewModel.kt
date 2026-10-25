package app.snapcast.ui.bookmarks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.snapcast.data.bookmarks.Bookmark
import app.snapcast.data.bookmarks.BookmarkRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class BookmarksUiState(
    val bookmarks: List<Bookmark> = emptyList(),
    val loaded: Boolean = false,
)

/** One-shot results the bookmarks screen reports in a snackbar. */
internal sealed interface BookmarkEvent {
    data class Added(val title: String) : BookmarkEvent

    data object Saved : BookmarkEvent

    /** [bookmark] was removed; the snackbar offers to restore it. */
    data class Deleted(val bookmark: Bookmark) : BookmarkEvent
}

@HiltViewModel
internal class BookmarksViewModel @Inject constructor(
    private val repository: BookmarkRepository,
) : ViewModel() {

    /** Serializes edits that read the current order before writing (moves, restores). */
    private val orderMutex = Mutex()
    private val eventChannel = Channel<BookmarkEvent>(Channel.BUFFERED)

    val events: Flow<BookmarkEvent> = eventChannel.receiveAsFlow()

    val uiState: StateFlow<BookmarksUiState> = repository.observeBookmarks()
        .map { BookmarksUiState(bookmarks = it, loaded = true) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), BookmarksUiState())

    /** Adds a bookmark; [url] must already be normalized ([BookmarkInput.normalizeUrl]). */
    fun add(url: String, title: String, onHome: Boolean) {
        viewModelScope.launch {
            repository.add(url = url, title = title.trim(), onHome = onHome)
            eventChannel.trySend(BookmarkEvent.Added(title.trim().ifEmpty { url }))
        }
    }

    /** Saves the edited fields of the bookmark [id]; [url] must already be normalized. */
    fun save(id: Long, url: String, title: String, onHome: Boolean) {
        viewModelScope.launch {
            val saved = orderMutex.withLock {
                val current = repository.observeBookmarks().first().firstOrNull { it.id == id } ?: return@withLock false
                repository.update(current.copy(url = url, title = title.trim(), onHome = onHome))
                true
            }
            if (saved) eventChannel.trySend(BookmarkEvent.Saved)
        }
    }

    fun setOnHome(bookmark: Bookmark, onHome: Boolean) {
        viewModelScope.launch {
            orderMutex.withLock {
                val current = repository.observeBookmarks().first().firstOrNull { it.id == bookmark.id } ?: return@withLock
                repository.update(current.copy(onHome = onHome))
            }
        }
    }

    fun delete(bookmark: Bookmark) {
        viewModelScope.launch {
            repository.remove(bookmark.id)
            eventChannel.trySend(BookmarkEvent.Deleted(bookmark))
        }
    }

    /** Undoes [delete]: re-adds the bookmark and puts it back at its old position. */
    fun restore(bookmark: Bookmark) {
        viewModelScope.launch {
            orderMutex.withLock {
                val newId = repository.add(url = bookmark.url, title = bookmark.title, onHome = bookmark.onHome)
                if (newId >= 0) {
                    repository.update(bookmark.copy(id = newId))
                }
            }
        }
    }

    fun moveUp(id: Long) = move(id, -1)

    fun moveDown(id: Long) = move(id, 1)

    private fun move(id: Long, delta: Int) {
        viewModelScope.launch {
            orderMutex.withLock {
                val ordered = repository.observeBookmarks().first()
                val moved = BookmarkOrdering.move(ordered, id, delta) ?: return@withLock
                BookmarkOrdering.renumber(moved).forEach { repository.update(it) }
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
