package app.snapcast.addons

import android.graphics.Bitmap
import app.snapcast.addons.util.loadBitmap
import app.snapcast.data.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.mozilla.geckoview.WebExtension

/** Add-on list icons, decoded once per extension version at [SIZE_PX]. [request] is main-thread only. */
@Singleton
internal class AddonIcons @Inject constructor(
    @ApplicationScope private val scope: CoroutineScope,
) {
    data class Key(val extensionId: String, val version: String)

    private val requested = HashSet<Key>()
    private val _icons = MutableStateFlow<Map<Key, Bitmap>>(emptyMap())
    val icons: StateFlow<Map<Key, Bitmap>> = _icons.asStateFlow()

    fun keyOf(extension: WebExtension): Key = Key(extension.id, extension.metaData.version)

    /** Starts decoding the icons not requested yet. */
    fun request(extensions: List<WebExtension>) {
        for (extension in extensions) {
            val key = keyOf(extension)
            if (!requested.add(key)) continue
            val image = extension.metaData.icon
            scope.launch(Dispatchers.Main.immediate) {
                val bitmap = image.loadBitmap(SIZE_PX) ?: return@launch
                _icons.update { it + (key to bitmap) }
            }
        }
    }

    fun forget(extensionId: String) {
        requested.removeAll { it.extensionId == extensionId }
        _icons.update { icons -> icons.filterKeys { it.extensionId != extensionId } }
    }

    companion object {
        const val SIZE_PX = 96
    }
}
