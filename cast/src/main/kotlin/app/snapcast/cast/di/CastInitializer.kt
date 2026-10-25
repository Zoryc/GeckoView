package app.snapcast.cast.di

import app.snapcast.cast.chromecast.CastContextProvider
import app.snapcast.cast.controller.CastControllerImpl
import app.snapcast.data.AppInitializer
import dagger.Lazy
import javax.inject.Inject

/**
 * Starts the asynchronous Cast framework initialization at app start and creates the controller, which
 * from then on tracks sessions (pending requests, resume positions, session-end events).
 */
internal class CastInitializer @Inject constructor(
    private val castContextProvider: CastContextProvider,
    private val controller: Lazy<CastControllerImpl>,
) : AppInitializer {
    override val priority: Int get() = AppInitializer.PRIORITY_DEFAULT

    override fun initialize() {
        castContextProvider.start()
        controller.get()
    }
}
