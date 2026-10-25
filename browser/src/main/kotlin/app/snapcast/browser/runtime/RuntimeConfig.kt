package app.snapcast.browser.runtime

import app.snapcast.core.model.ThemeMode
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoRuntimeSettings

/** Pure mapping from app settings to GeckoView runtime values (kept free of Android APIs for tests). */
internal object RuntimeConfig {
    /** `prefers-color-scheme` reported to web content for the app's theme. */
    fun colorSchemeFor(theme: ThemeMode): Int = when (theme) {
        ThemeMode.DARK -> GeckoRuntimeSettings.COLOR_SCHEME_DARK
        ThemeMode.LIGHT -> GeckoRuntimeSettings.COLOR_SCHEME_LIGHT
        ThemeMode.SYSTEM -> GeckoRuntimeSettings.COLOR_SCHEME_SYSTEM
    }

    /**
     * Firefox's "standard" Enhanced Tracking Protection: default tracker lists plus social tracking
     * protection, and Total Cookie Protection (third-party cookies partitioned per site).
     */
    val ANTI_TRACKING_CATEGORIES: Int = ContentBlocking.AntiTracking.DEFAULT or ContentBlocking.AntiTracking.STP

    val COOKIE_BEHAVIOR: Int = ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS

    fun contentBlocking(): ContentBlocking.Settings = ContentBlocking.Settings.Builder()
        .antiTracking(ANTI_TRACKING_CATEGORIES)
        .cookieBehavior(COOKIE_BEHAVIOR)
        .cookieBehaviorPrivateMode(COOKIE_BEHAVIOR)
        .enhancedTrackingProtectionLevel(ContentBlocking.EtpLevel.DEFAULT)
        .build()
}
