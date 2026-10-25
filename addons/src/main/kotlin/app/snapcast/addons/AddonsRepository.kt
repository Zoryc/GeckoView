package app.snapcast.addons

import android.graphics.Bitmap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import org.mozilla.geckoview.GeckoSession

data class AddonInfo(
    val id: String,
    val name: String,
    val description: String?,
    val version: String,
    val creatorName: String?,
    val homepageUrl: String?,
    val icon: Bitmap?,
    val isEnabled: Boolean,
    /** Why the add-on is disabled when not by the user (e.g. "Not signed", "Incompatible"), else null. */
    val disabledReason: String?,
    val allowedInPrivateBrowsing: Boolean,
    val optionsPageUrl: String?,
    val openOptionsPageInTab: Boolean,
    /** Required API permissions, e.g. "tabs", "storage". */
    val permissions: List<String>,
    /** Host permissions, e.g. "<all_urls>". */
    val origins: List<String>,
    val hasBrowserAction: Boolean,
)

/** A toolbar button contributed by an extension's browser_action / page_action. */
data class ExtensionAction(
    val extensionId: String,
    val extensionName: String,
    val title: String?,
    val icon: Bitmap?,
    val badgeText: String?,
    val badgeBackgroundColor: Int?,
    val badgeTextColor: Int?,
    val enabled: Boolean,
    val isPageAction: Boolean,
)

/** Shown before installing (or when an add-on asks for more permissions). */
data class AddonPermissionPrompt(
    val id: String,
    val addonName: String,
    val icon: Bitmap?,
    /** Human-readable permission descriptions. */
    val permissions: List<String>,
    val origins: List<String>,
    /** False for optional/update prompts where the private-browsing choice does not apply. */
    val offerPrivateBrowsing: Boolean,
    val onResult: (allow: Boolean, allowInPrivate: Boolean) -> Unit,
)

sealed interface AddonInstallState {
    data object Idle : AddonInstallState

    data class Installing(val source: String) : AddonInstallState

    data class Installed(val addonName: String) : AddonInstallState

    data class Failed(val message: String) : AddonInstallState
}

/** An extension popup (browser_action default_popup) to show in a bottom sheet. */
data class ExtensionPopup(
    val extensionId: String,
    val title: String,
    val session: GeckoSession,
)

interface AddonsRepository {
    /** Installed add-ons (built-in ones such as the video detector are excluded), sorted by name. */
    val addons: StateFlow<List<AddonInfo>>

    val installState: StateFlow<AddonInstallState>

    val permissionPrompt: StateFlow<AddonPermissionPrompt?>

    val popup: StateFlow<ExtensionPopup?>

    /** Toolbar actions for the tab (tab-specific overrides applied over the extension defaults). */
    fun actions(tabId: String?): Flow<List<ExtensionAction>>

    fun refresh()

    /** Installs from an https .xpi URL (AMO) or a file:// URI. Permission prompt is shown first. */
    fun install(url: String)

    fun installUBlockOrigin()

    fun setEnabled(id: String, enabled: Boolean)

    fun uninstall(id: String)

    fun setAllowedInPrivateBrowsing(id: String, allowed: Boolean)

    /** Opens the add-on's options page (in a new tab). */
    fun openOptionsPage(id: String)

    /** Toolbar action tap: opens the popup ([popup]) or fires browserAction.onClicked. */
    fun clickAction(extensionId: String, tabId: String?)

    fun dismissPopup()

    fun resetInstallState()

    companion object {
        const val UBLOCK_ORIGIN_ID = "uBlock0@raymondhill.net"
        const val UBLOCK_ORIGIN_XPI = "https://addons.mozilla.org/firefox/downloads/latest/ublock-origin/latest.xpi"
    }
}
