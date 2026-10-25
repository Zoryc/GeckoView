package app.snapcast.cast.proxy

/** User-Agent strings matching the embedded engine, used when a stream was captured without one. */
internal object UserAgents {
    /** Gecko version of the bundled GeckoView (see the version catalog). */
    private const val GECKO_VERSION = "158.0"
    private const val FALLBACK_ANDROID_VERSION = "14"

    /** GeckoView's mobile UA, e.g. "Mozilla/5.0 (Android 14; Mobile; rv:158.0) Gecko/158.0 Firefox/158.0". */
    fun geckoViewMobile(androidRelease: String?): String {
        val android = androidRelease?.substringBefore('.')?.takeIf { it.isNotBlank() } ?: FALLBACK_ANDROID_VERSION
        return "Mozilla/5.0 (Android $android; Mobile; rv:$GECKO_VERSION) Gecko/$GECKO_VERSION Firefox/$GECKO_VERSION"
    }
}
