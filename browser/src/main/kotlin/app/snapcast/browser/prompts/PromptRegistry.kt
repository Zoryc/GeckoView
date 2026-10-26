package app.snapcast.browser.prompts

import app.snapcast.browser.BrowserPrompt
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Pending [BrowserPrompt]s, oldest first, exposed as `BrowserTabs.prompts`. */
@Singleton
internal class PromptRegistry @Inject constructor() {
    private val _prompts = MutableStateFlow<List<BrowserPrompt>>(emptyList())

    val prompts: StateFlow<List<BrowserPrompt>> = _prompts.asStateFlow()

    fun add(prompt: BrowserPrompt) {
        _prompts.update { it + prompt }
    }

    fun remove(id: String) {
        _prompts.update { list -> list.filterNot { it.id == id } }
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
