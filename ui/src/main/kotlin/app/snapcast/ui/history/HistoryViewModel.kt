package app.snapcast.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.snapcast.data.history.HistoryRepository
import app.snapcast.ui.R
import app.snapcast.ui.shared.UiMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the history screen shows for the current search. */
internal data class HistoryUiState(
    val query: String = "",
    val sections: List<HistorySection> = emptyList(),
    /** False until the first result for the current query arrived. */
    val loaded: Boolean = false,
) {
    val isEmpty: Boolean get() = sections.isEmpty()
}

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
internal class HistoryViewModel @Inject constructor(
    private val history: HistoryRepository,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val messageChannel = Channel<UiMessage>(Channel.BUFFERED)

    val messages: Flow<UiMessage> = messageChannel.receiveAsFlow()

    val uiState: StateFlow<HistoryUiState> = query
        .map { it.trim() }
        .distinctUntilChanged()
        .debounce { text -> if (text.isEmpty()) 0L else SEARCH_DEBOUNCE_MS }
        .flatMapLatest { text ->
            history.observeHistory(query = text, limit = HISTORY_LIMIT).map { entries ->
                val zone = ZoneId.systemDefault()
                HistoryUiState(
                    query = text,
                    sections = HistoryGrouping.group(entries, LocalDate.now(zone), zone),
                    loaded = true,
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), HistoryUiState())

    fun setQuery(text: String) {
        query.value = text
    }

    fun delete(id: Long) {
        viewModelScope.launch { history.delete(id) }
    }

    fun clearAll() {
        viewModelScope.launch {
            history.clear()
            messageChannel.trySend(UiMessage.res(R.string.ui_screens_history_cleared))
        }
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 250L
        const val HISTORY_LIMIT = 1_000
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
