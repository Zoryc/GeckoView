package app.snapcast.addons.actions

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** One extension's browser action or page action (an extension may define both). */
internal data class ActionKey(val extensionId: String, val isPageAction: Boolean)

/**
 * Extension defaults plus per-tab overrides (tabId -> key -> action), as delivered by GeckoView's
 * `ActionDelegate`: a null session carries the default, a session carries that tab's override.
 */
internal data class ActionSnapshot<A : Any>(
    val defaults: Map<ActionKey, A> = emptyMap(),
    val overrides: Map<String, Map<ActionKey, A>> = emptyMap(),
) {
    /**
     * The effective actions for [tabId] (defaults only when null): an override is combined with its
     * default through [merge]; an override without a default is used as is.
     */
    fun resolve(tabId: String?, merge: (override: A, default: A) -> A): Map<ActionKey, A> {
        val tabOverrides = tabId?.let { overrides[it] }.orEmpty()
        if (tabOverrides.isEmpty()) return defaults
        val resolved = LinkedHashMap<ActionKey, A>(defaults)
        for ((key, override) in tabOverrides) {
            val default = defaults[key]
            resolved[key] = if (default == null) override else merge(override, default)
        }
        return resolved
    }
}

/** Thread-safe holder of an [ActionSnapshot]; generic over the action type so it is JVM-testable. */
internal class ActionStore<A : Any> {
    private val _state = MutableStateFlow(ActionSnapshot<A>())
    val state: StateFlow<ActionSnapshot<A>> = _state.asStateFlow()

    fun setDefault(key: ActionKey, action: A) {
        _state.update { it.copy(defaults = it.defaults + (key to action)) }
    }

    fun setOverride(tabId: String, key: ActionKey, action: A) {
        _state.update { snapshot ->
            val forTab = snapshot.overrides[tabId].orEmpty() + (key to action)
            snapshot.copy(overrides = snapshot.overrides + (tabId to forTab))
        }
    }

    /** Drops the overrides of a closed tab. */
    fun clearTab(tabId: String) {
        _state.update { if (tabId in it.overrides) it.copy(overrides = it.overrides - tabId) else it }
    }

    /** Drops everything an extension contributed (disabled or uninstalled). */
    fun clearExtension(extensionId: String) = retain { it != extensionId }

    /** Keeps only the actions of [extensionIds]. */
    fun retainExtensions(extensionIds: Set<String>) = retain { it in extensionIds }

    private fun retain(keep: (String) -> Boolean) {
        _state.update { snapshot ->
            val defaults = snapshot.defaults.filterKeys { keep(it.extensionId) }
            val overrides = snapshot.overrides
                .mapValues { (_, actions) -> actions.filterKeys { keep(it.extensionId) } }
                .filterValues { it.isNotEmpty() }
            if (defaults == snapshot.defaults && overrides == snapshot.overrides) snapshot else ActionSnapshot(defaults, overrides)
        }
    }
}
