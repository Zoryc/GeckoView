package app.snapcast.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.snapcast.data.bookmarks.Bookmark
import app.snapcast.data.bookmarks.BookmarkRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Speed-dial tiles of the built-in home page (bookmarks flagged "on home"). */
@HiltViewModel
internal class HomeViewModel @Inject constructor(
    private val bookmarkRepository: BookmarkRepository,
) : ViewModel() {

    /** Null until the first database read, so the UI does not flash the empty state. */
    val speedDial: StateFlow<List<Bookmark>?> = bookmarkRepository.observeSpeedDial()
        .map<List<Bookmark>, List<Bookmark>?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch { bookmarkRepository.seedDefaultsIfNeeded() }
    }

    /** Long-press "Remove from home": keeps the bookmark, removes its tile. */
    fun removeFromHome(bookmark: Bookmark) {
        viewModelScope.launch { bookmarkRepository.update(bookmark.copy(onHome = false)) }
    }
}
