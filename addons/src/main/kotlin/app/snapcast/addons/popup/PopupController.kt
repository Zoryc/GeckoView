package app.snapcast.addons.popup

import app.snapcast.addons.ExtensionPopup
import app.snapcast.addons.displayName
import app.snapcast.addons.util.postToMain
import app.snapcast.browser.BrowserTabs
import app.snapcast.browser.GeckoRuntimeProvider
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension

/**
 * Browser-action popups (e.g. uBlock Origin's panel). Like the GeckoView example app, a popup is a
 * fresh [GeckoSession] opened on the runtime and returned to GeckoView, which loads the popup page
 * into it; the UI shows [popup] in a bottom sheet. At most one popup exists. Main thread only.
 */
@Singleton
internal class PopupController @Inject constructor(
    private val runtimeProvider: GeckoRuntimeProvider,
    private val tabs: dagger.Lazy<BrowserTabs>,
) {
    private val _popup = MutableStateFlow<ExtensionPopup?>(null)
    val popup: StateFlow<ExtensionPopup?> = _popup.asStateFlow()

    private val contentDelegate = object : GeckoSession.ContentDelegate {
        // window.close() from the popup page.
        override fun onCloseRequest(session: GeckoSession) = dismiss(session)

        override fun onCrash(session: GeckoSession) = dismiss(session)

        override fun onKill(session: GeckoSession) = dismiss(session)
    }

    private val navigationDelegate = object : GeckoSession.NavigationDelegate {
        // Links with target=_blank open as a normal tab; the popup then goes away.
        override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
            val (_, newSession) = tabs.get().newTabForEngine(private = false, select = true)
            dismissSoon()
            return GeckoResult.fromValue(newSession)
        }
    }

    /**
     * ActionDelegate.onTogglePopup ([toggle] = true) / onOpenPopup. Toggling the popup that is showing
     * closes it and returns null; opening it again reuses its session.
     */
    fun show(extension: WebExtension, action: WebExtension.Action, toggle: Boolean): GeckoResult<GeckoSession>? {
        val current = _popup.value
        if (current != null && current.extensionId == extension.id) {
            if (toggle) {
                dismiss()
                return null
            }
            return GeckoResult.fromValue(current.session)
        }
        dismiss()
        val session = GeckoSession()
        session.setContentDelegate(contentDelegate)
        session.setNavigationDelegate(navigationDelegate)
        session.open(runtimeProvider.runtime)
        val title = action.title?.takeIf { it.isNotBlank() } ?: extension.displayName
        _popup.value = ExtensionPopup(extensionId = extension.id, title = title, session = session)
        return GeckoResult.fromValue(session)
    }

    /** Closes the popup and its session. */
    fun dismiss() {
        val current = _popup.value ?: return
        _popup.value = null
        if (current.session.isOpen) current.session.close()
    }

    fun dismissFor(extensionId: String) {
        if (_popup.value?.extensionId == extensionId) dismiss()
    }

    /** Closes the current popup after the running engine callback (never close a session inside its own callback chain). */
    fun dismissSoon() {
        val session = _popup.value?.session ?: return
        postToMain { dismiss(session) }
    }

    private fun dismiss(session: GeckoSession) {
        if (_popup.value?.session === session) dismiss()
    }
}
