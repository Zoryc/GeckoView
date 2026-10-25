package app.snapcast.browser.runtime

import app.snapcast.browser.GeckoRuntimeProvider
import app.snapcast.data.AppInitializer
import javax.inject.Inject

/** Creates the GeckoRuntime at startup so Gecko warms up before the first tab is shown. */
internal class RuntimeInitializer @Inject constructor(
    private val runtimeProvider: GeckoRuntimeProvider,
) : AppInitializer {
    override val priority: Int get() = AppInitializer.PRIORITY_RUNTIME

    override fun initialize() {
        runtimeProvider.runtime
    }
}
