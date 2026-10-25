package app.snapcast.browser

import org.mozilla.geckoview.GeckoRuntime

/**
 * Owns the single, process-wide [GeckoRuntime]. The runtime is created lazily on the main thread the
 * first time [runtime] is read (the browser module's [app.snapcast.data.AppInitializer] reads it at startup).
 */
interface GeckoRuntimeProvider {
    val runtime: GeckoRuntime
}
