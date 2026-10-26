package app.snapcast.browser.prompts

import app.snapcast.browser.BrowserPrompt
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Pending [BrowserPrompt]s, oldest first, exposed as `BrowserTabs.prompts`. Main thread only. */
@Singleton
internal class PromptRegistry @Inject constructor() {
    private val _prompts = MutableStateFlow<List<BrowserPrompt>>(emptyList())

    val prompts: StateFlow<List<BrowserPrompt>> = _prompts.asStateFlow()

    /** Prompt id -> owner of prompts raised by sessions that are not tabs (an extension popup). */
    private val owners = HashMap<String, String>()

    /** @param owner set for prompts of a session that is not a tab, so [dismissOwnedBy] can cancel them */
    fun add(prompt: BrowserPrompt, owner: String? = null) {
        if (owner != null) owners[prompt.id] = owner
        _prompts.update { it + prompt }
    }

    fun remove(id: String) {
        owners.remove(id)
        _prompts.update { list -> list.filterNot { it.id == id } }
    }

    /** Answers every prompt added with [owner] with its "cancel" result (its session is going away). */
    fun dismissOwnedBy(owner: String) {
        val ids = owners.filterValues { it == owner }.keys.toSet()
        if (ids.isEmpty()) return
        _prompts.value.filter { it.id in ids }.forEach { it.dismiss() }
        _prompts.update { list -> list.filterNot { it.id in ids } }
        owners.keys.removeAll(ids)
    }

    /** Dismisses the site-permission prompts of a tab that navigated to another site. */
    fun dismissPermissionPrompts(tabId: String) {
        _prompts.value
            .filter { it.tabId == tabId && it.isPermissionRequest() }
            .forEach { it.dismiss() }
        _prompts.update { list -> list.filterNot { it.tabId == tabId && it.isPermissionRequest() } }
    }

    private fun BrowserPrompt.isPermissionRequest(): Boolean =
        this is BrowserPrompt.ContentPermission ||
            this is BrowserPrompt.MediaPermission ||
            this is BrowserPrompt.AndroidPermissions

    /** Answers every prompt of a closing tab with its "cancel" result. */
    fun dismissAll(tabId: String) {
        _prompts.value.filter { it.tabId == tabId }.forEach { it.dismiss() }
        _prompts.update { list -> list.filterNot { it.tabId == tabId } }
    }
}
