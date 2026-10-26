package app.snapcast.browser.hooks

import android.util.Log
import app.snapcast.browser.ExternalResponseInterceptor
import app.snapcast.browser.LoadInterceptor
import app.snapcast.browser.SessionObserver
import javax.inject.Inject
import javax.inject.Singleton
import org.mozilla.geckoview.WebResponse

/**
 * Fans engine events out to the [SessionObserver] / [LoadInterceptor] / [ExternalResponseInterceptor]
 * multibindings contributed by other modules. The sets are resolved lazily because their
 * implementations depend on `BrowserTabs` themselves.
 *
 * A failing hook is logged and skipped so one module cannot break browsing for the others.
 */
@Singleton
internal class BrowserHooks @Inject constructor(
    private val sessionObservers: dagger.Lazy<Set<@JvmSuppressWildcards SessionObserver>>,
    private val loadInterceptors: dagger.Lazy<Set<@JvmSuppressWildcards LoadInterceptor>>,
    private val responseInterceptors: dagger.Lazy<Set<@JvmSuppressWildcards ExternalResponseInterceptor>>,
) {
    fun notify(event: (SessionObserver) -> Unit) {
        for (observer in sessionObservers.get()) {
            try {
                event(observer)
            } catch (e: RuntimeException) {
                Log.e(TAG, "SessionObserver ${observer.javaClass.name} failed", e)
            }
        }
    }

    /**
     * True when one interceptor consumed the load (the rest are not asked). [userInitiated] is passed
     * through unchanged (see [LoadInterceptor.interceptLoad]).
     */
    fun interceptLoad(tabId: String, url: String, isTopLevel: Boolean, userInitiated: Boolean): Boolean =
        loadInterceptors.get().any { interceptor ->
            try {
                interceptor.interceptLoad(tabId, url, isTopLevel, userInitiated)
            } catch (e: RuntimeException) {
                Log.e(TAG, "LoadInterceptor ${interceptor.javaClass.name} failed", e)
                false
            }
        }

    /** True when one interceptor consumed the response (the rest are not asked). */
    fun interceptResponse(tabId: String, response: WebResponse): Boolean =
        responseInterceptors.get().any { interceptor ->
            try {
                interceptor.interceptResponse(tabId, response)
            } catch (e: RuntimeException) {
                Log.e(TAG, "ExternalResponseInterceptor ${interceptor.javaClass.name} failed", e)
                false
            }
        }

    private companion object {
        const val TAG = "SnapcastBrowser"
    }
}
