package app.snapcast.addons.actions

import android.content.Context
import android.graphics.Bitmap
import app.snapcast.addons.util.loadBitmap
import app.snapcast.data.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.WeakHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.mozilla.geckoview.Image

/**
 * Toolbar icons of extension actions, decoded on demand and cached per [Image] instance (as GeckoView
 * recommends). Per-tab updates (badge counts) usually re-send the icon as a new [Image]; while it
 * decodes, the action's previous bitmap is shown so the button does not flicker. Main thread only.
 */
@Singleton
internal class ActionIcons @Inject constructor(
    @ApplicationContext context: Context,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val sizePx = (ICON_SIZE_DP * context.resources.displayMetrics.density).roundToInt()
    private val bitmaps = WeakHashMap<Image, Bitmap>()
    private val requested = WeakHashMap<Image, Boolean>()
    private val latest = HashMap<ActionKey, Bitmap>()
    private val _version = MutableStateFlow(0)

    /** Incremented whenever a new bitmap is available; combine with the action state to re-render. */
    val version: StateFlow<Int> = _version.asStateFlow()

    /** The bitmap for [image] if decoded, else the last bitmap shown for [key]; starts decoding when needed. */
    fun bitmapFor(key: ActionKey, image: Image?): Bitmap? {
        if (image == null) return null
        bitmaps[image]?.let { bitmap ->
            latest[key] = bitmap
            return bitmap
        }
        if (requested.put(image, true) == null) load(key, image)
        return latest[key]
    }

    fun forget(extensionId: String) {
        latest.keys.removeAll { it.extensionId == extensionId }
    }

    private fun load(key: ActionKey, image: Image) {
        scope.launch(Dispatchers.Main.immediate) {
            val bitmap = image.loadBitmap(sizePx) ?: return@launch
            bitmaps[image] = bitmap
            latest[key] = bitmap
            _version.update { it + 1 }
        }
    }

    private companion object {
        const val ICON_SIZE_DP = 32
    }
}
