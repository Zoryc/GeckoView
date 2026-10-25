package app.snapcast.browser.download

import android.content.Context
import androidx.core.content.FileProvider

/**
 * Shares downloads saved on Android 8-9 (app-specific storage) with viewer apps. A dedicated
 * subclass keeps its manifest entry from clashing with other modules' FileProviders.
 */
class BrowserFileProvider : FileProvider() {
    internal companion object {
        fun authority(context: Context): String = context.packageName + ".browser.fileprovider"
    }
}
