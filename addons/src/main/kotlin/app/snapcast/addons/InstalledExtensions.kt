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

/**
 * Extensions shipped with the app (the video detector) are hidden from the add-ons UI and get no toolbar UI.
 *
 * Only Gecko's own flag decides ([WebExtension.isBuiltIn]: installed from the app's built-in location with
 * `ensureBuiltIn` / `installBuiltIn`), never the id: anyone can get an add-on signed under an unclaimed
 * email-style id, so an id match alone would let a downloaded add-on skip the install prompt and hide itself.
 */
internal object BuiltInExtensions {
    fun isBuiltIn(builtInFlag: Boolean): Boolean = builtInFlag

    /**
     * A downloaded add-on that claims the bundled detector's id. Gecko gives the profile location
     * precedence over the built-in one, so it would replace the real detector (and its wiring): such
     * add-ons are refused at every permission prompt.
     */
    fun impersonatesBuiltIn(id: String, builtInFlag: Boolean): Boolean = !builtInFlag && id == DETECTOR_EXTENSION_ID
}

internal val WebExtension.isHiddenBuiltIn: Boolean
    get() = BuiltInExtensions.isBuiltIn(isBuiltIn)

internal val WebExtension.impersonatesBuiltIn: Boolean
    get() = BuiltInExtensions.impersonatesBuiltIn(id, isBuiltIn)

/** Manifest name, or the id when the add-on has none. */
internal val WebExtension.displayName: String
    get() = metaData.name?.takeIf { it.isNotBlank() } ?: id
