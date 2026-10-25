package app.snapcast.browser.notifications

import app.snapcast.browser.GeckoRuntimeProvider
import app.snapcast.data.AppInitializer
import javax.inject.Inject

/** Routes web notifications from the runtime to [WebNotificationController] right after it is created. */
internal class WebNotificationsInitializer @Inject constructor(
    private val runtimeProvider: GeckoRuntimeProvider,
    private val controller: WebNotificationController,
) : AppInitializer {
    override val priority: Int get() = AppInitializer.PRIORITY_RUNTIME + 1

    override fun initialize() {
        runtimeProvider.runtime.webNotificationDelegate = controller
    }
}
