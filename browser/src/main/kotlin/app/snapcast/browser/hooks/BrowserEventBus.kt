package app.snapcast.browser.hooks

import app.snapcast.browser.BrowserEvent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** One-off [BrowserEvent]s from tabs, delegates and the download service, exposed as `BrowserTabs.events`. */
@Singleton
internal class BrowserEventBus @Inject constructor() {
    private val _events = MutableSharedFlow<BrowserEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val events: SharedFlow<BrowserEvent> = _events.asSharedFlow()

    fun emit(event: BrowserEvent) {
        _events.tryEmit(event)
    }
}
