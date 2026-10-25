package app.snapcast.data

/**
 * Work a module needs to do once when the app's main process starts (never in GeckoView's child
 * processes). Contributed with `@Binds @IntoSet` and run on the main thread by the application, in
 * ascending [priority] order.
 */
interface AppInitializer {
    val priority: Int get() = PRIORITY_DEFAULT

    fun initialize()

    companion object {
        /** GeckoRuntime creation. */
        const val PRIORITY_RUNTIME = 0

        /** Built-in extensions, add-on delegates. */
        const val PRIORITY_EXTENSIONS = 10
        const val PRIORITY_DEFAULT = 100
    }
}
