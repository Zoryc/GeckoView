package app.snapcast.addons.actions

import app.snapcast.addons.AddonsPreferences
import app.snapcast.addons.ExtensionAction
import app.snapcast.addons.InstalledExtensions
import app.snapcast.addons.displayName
import app.snapcast.addons.isHiddenBuiltIn
import app.snapcast.addons.popup.PopupController
import app.snapcast.browser.BrowserTabs
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension

/**
 * Receives browser/page actions from GeckoView (registered both per extension, for defaults and popups,
 * and per tab session, for overrides such as badge counts) and exposes them as [ExtensionAction]s.
 * Engine callbacks and [click] run on the main thread.
 */
@Singleton
internal class ActionRegistry @Inject constructor(
    private val tabs: dagger.Lazy<BrowserTabs>,
    private val popups: PopupController,
    private val installed: InstalledExtensions,
    private val preferences: AddonsPreferences,
    private val icons: ActionIcons,
) : WebExtension.ActionDelegate {

    private val store = ActionStore<WebExtension.Action>()

    /** Extensions that currently have a default browser or page action. */
    val extensionsWithDefaultAction: Flow<Set<String>> = store.state
        .map { snapshot -> snapshot.defaults.keys.mapTo(HashSet()) { it.extensionId } }
        .distinctUntilChanged()

    override fun onBrowserAction(extension: WebExtension, session: GeckoSession?, action: WebExtension.Action) {
        onActionUpdate(extension, session, action, isPageAction = false)
    }

    override fun onPageAction(extension: WebExtension, session: GeckoSession?, action: WebExtension.Action) {
        onActionUpdate(extension, session, action, isPageAction = true)
    }

    override fun onTogglePopup(extension: WebExtension, action: WebExtension.Action): GeckoResult<GeckoSession>? =
        popups.show(extension, action, toggle = true)

    override fun onOpenPopup(extension: WebExtension, action: WebExtension.Action): GeckoResult<GeckoSession>? =
        popups.show(extension, action, toggle = false)

    /** Toolbar actions for [tabId] (defaults only when null), sorted by extension name. */
    fun actions(tabId: String?): Flow<List<ExtensionAction>> =
        combine(store.state, installed.list, icons.version) { snapshot, extensions, _ ->
            build(tabId, snapshot, extensions)
        }
            .distinctUntilChanged()
            // Icon decoding and BrowserTabs lookups are main-thread only.
            .flowOn(Dispatchers.Main.immediate)

    /** Taps the effective action: GeckoView then opens its popup or fires `browserAction.onClicked`. */
    fun click(extensionId: String, tabId: String?) {
        val resolved = store.state.value.resolve(tabId, ::merge)
        val action = resolved[ActionKey(extensionId, isPageAction = false)]
            ?: resolved[ActionKey(extensionId, isPageAction = true)]
            ?: return
        action.click()
    }

    fun forgetTab(tabId: String) {
        store.clearTab(tabId)
    }

    fun forgetExtension(extensionId: String) {
        store.clearExtension(extensionId)
        icons.forget(extensionId)
    }

    /** Drops actions of extensions that are no longer installed and enabled. */
    fun retainExtensions(extensionIds: Set<String>) {
        store.retainExtensions(extensionIds)
    }

    private fun onActionUpdate(
        extension: WebExtension,
        session: GeckoSession?,
        action: WebExtension.Action,
        isPageAction: Boolean,
    ) {
        if (extension.isHiddenBuiltIn) return
        val key = ActionKey(extension.id, isPageAction)
        if (session == null) {
            store.setDefault(key, action)
            preferences.addKnownAction(extension.id)
        } else {
            val tabId = tabs.get().tabIdOf(session) ?: return
            store.setOverride(tabId, key, action)
        }
    }

    private fun build(
        tabId: String?,
        snapshot: ActionSnapshot<WebExtension.Action>,
        extensions: List<WebExtension>,
    ): List<ExtensionAction> {
        val active = extensions.filter { it.metaData.enabled }.associateBy { it.id }
        val privateTab = tabId != null && tabs.get().tabs.value.any { it.id == tabId && it.isPrivate }
        return snapshot.resolve(tabId, ::merge).mapNotNull { (key, action) ->
            val extension = active[key.extensionId] ?: return@mapNotNull null
            // Extensions only run in private tabs when the user allowed it.
            if (privateTab && !extension.metaData.allowedInPrivateBrowsing) return@mapNotNull null
            // Page actions are hidden unless shown for the page; browser actions default to enabled.
            val enabled = action.enabled ?: !key.isPageAction
            if (key.isPageAction && !enabled) return@mapNotNull null
            ExtensionAction(
                extensionId = extension.id,
                extensionName = extension.displayName,
                title = action.title?.takeIf { it.isNotBlank() },
                icon = icons.bitmapFor(key, action.icon),
                badgeText = action.badgeText?.takeIf { it.isNotEmpty() },
                badgeBackgroundColor = action.badgeBackgroundColor,
                badgeTextColor = action.badgeTextColor,
                enabled = enabled,
                isPageAction = key.isPageAction,
            )
        }.sortedWith(ACTION_ORDER)
    }

    /** Tab override over the extension default (both come from the same extension and action type). */
    private fun merge(override: WebExtension.Action, default: WebExtension.Action): WebExtension.Action = try {
        override.withDefault(default)
    } catch (e: IllegalArgumentException) {
        override
    }

    private companion object {
        val ACTION_ORDER: Comparator<ExtensionAction> =
            compareBy<ExtensionAction, String>(String.CASE_INSENSITIVE_ORDER) { it.extensionName }
                .thenBy { it.isPageAction }
    }
}
