package app.snapcast.addons

import app.snapcast.core.detection.DETECTOR_EXTENSION_ID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.mozilla.geckoview.WebExtension

/** The installed, user-visible extensions (built-ins excluded) as last listed from GeckoView. */
@Singleton
internal class InstalledExtensions @Inject constructor() {
    private val _list = MutableStateFlow<List<WebExtension>>(emptyList())
    val list: StateFlow<List<WebExtension>> = _list.asStateFlow()

    fun replace(extensions: List<WebExtension>) {
        _list.value = extensions
    }

    fun byId(id: String): WebExtension? = _list.value.firstOrNull { it.id == id }
}

/** Extensions shipped with the app (the video detector) are hidden from the add-ons UI and get no toolbar UI. */
internal object BuiltInExtensions {
    fun isBuiltIn(id: String, builtInFlag: Boolean): Boolean = builtInFlag || id == DETECTOR_EXTENSION_ID
}

internal val WebExtension.isHiddenBuiltIn: Boolean
    get() = BuiltInExtensions.isBuiltIn(id, isBuiltIn)

/** Manifest name, or the id when the add-on has none. */
internal val WebExtension.displayName: String
    get() = metaData.name?.takeIf { it.isNotBlank() } ?: id
