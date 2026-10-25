package app.snapcast.addons

import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import app.snapcast.addons.actions.ActionRegistry
import app.snapcast.addons.install.InstallError
import app.snapcast.addons.install.InstallErrors
import app.snapcast.addons.install.InstallSourceKind
import app.snapcast.addons.install.InstallSources
import app.snapcast.addons.install.XpiFileCopier
import app.snapcast.addons.popup.PopupController
import app.snapcast.addons.prompts.InstallPrompts
import app.snapcast.addons.status.DisabledReasons
import app.snapcast.addons.tabs.ExtensionTabsDelegate
import app.snapcast.addons.util.LOG_TAG
import app.snapcast.browser.BrowserTabs
import app.snapcast.browser.GeckoRuntimeProvider
import app.snapcast.browser.util.await
import app.snapcast.data.di.ApplicationScope
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController

/**
 * [AddonsRepository] on top of GeckoView's [WebExtensionController].
 *
 * At startup ([start]) it installs the controller-wide delegates (permission prompts, add-on manager
 * events), lists the installed extensions and registers the action/tab delegates on each of them and on
 * every tab session (new sessions get them through [AddonsSessionObserver]). Built-in extensions such as
 * the video detector are left alone. Signature enforcement is GeckoView's default and never relaxed.
 *
 * Engine state is only touched on the main thread; public methods may be called from any thread.
 */
@Singleton
internal class AddonsManager @Inject constructor(
    private val runtimeProvider: GeckoRuntimeProvider,
    private val tabs: dagger.Lazy<BrowserTabs>,
    private val installed: InstalledExtensions,
    private val actionRegistry: ActionRegistry,
    private val tabsDelegate: ExtensionTabsDelegate,
    private val popups: PopupController,
    private val prompts: InstallPrompts,
    private val icons: AddonIcons,
    private val preferences: AddonsPreferences,
    private val messages: AddonMessages,
    private val fileCopier: XpiFileCopier,
    @ApplicationScope private val scope: CoroutineScope,
) : AddonsRepository {

    private val _installState = MutableStateFlow<AddonInstallState>(AddonInstallState.Idle)
    override val installState: StateFlow<AddonInstallState> = _installState.asStateFlow()

    override val permissionPrompt: StateFlow<AddonPermissionPrompt?> = prompts.prompt

    override val popup: StateFlow<ExtensionPopup?> = popups.popup

    override val addons: StateFlow<List<AddonInfo>> = combine(
        installed.list,
        preferences.knownActionIds,
        actionRegistry.extensionsWithDefaultAction,
        icons.icons,
    ) { extensions, knownActions, defaultActions, iconMap ->
        extensions
            .map { extension ->
                toAddonInfo(
                    extension = extension,
                    hasAction = extension.id in knownActions || extension.id in defaultActions,
                    icon = iconMap[icons.keyOf(extension)],
                )
            }
            .sortedWith(ADDON_ORDER)
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    // Main-thread state.
    private var started = false
    private val registeredIds = HashSet<String>()

    /** Sources of installs this app started; their own result reports success or failure. */
    private val activeInstalls = HashSet<String>()

    /** Extensions being updated in the background: their install events must not show any UI. */
    private val updatingIds = HashSet<String>()

    /** Installs started by a web page (addons.mozilla.org through mozAddonManager). */
    private val pageInstalls = HashSet<String>()
    private var listingGeneration = 0
    private var appliedGeneration = 0

    private val controller: WebExtensionController get() = runtimeProvider.runtime.webExtensionController

    private val addonManagerDelegate = object : WebExtensionController.AddonManagerDelegate {
        override fun onInstalling(extension: WebExtension) {
            if (extension.isHiddenBuiltIn || activeInstalls.isNotEmpty() || extension.id in updatingIds) return
            pageInstalls.add(extension.id)
            _installState.value = AddonInstallState.Installing(extension.displayName)
        }

        override fun onInstalled(extension: WebExtension) {
            if (extension.isHiddenBuiltIn) return
            register(extension, force = true)
            refresh()
            if (pageInstalls.remove(extension.id)) {
                _installState.value = AddonInstallState.Installed(extension.displayName)
            }
        }

        override fun onInstallationFailed(extension: WebExtension?, installException: WebExtension.InstallException) {
            if (extension?.isHiddenBuiltIn == true) return
            val pageInstall = extension != null && pageInstalls.remove(extension.id)
            val untracked = activeInstalls.isEmpty() && updatingIds.isEmpty()
            if (pageInstall || untracked) applyInstallFailure(installException)
        }

        override fun onUninstalled(extension: WebExtension) {
            forgetExtension(extension.id)
            refresh()
        }

        override fun onEnabled(extension: WebExtension) {
            refresh()
        }

        override fun onDisabled(extension: WebExtension) {
            actionRegistry.forgetExtension(extension.id)
            popups.dismissFor(extension.id)
            refresh()
        }

        // optionsPageUrl and other startup-dependent metadata are only complete from here on.
        override fun onReady(extension: WebExtension) {
            refresh()
        }

        override fun onOptionalPermissionsChanged(extension: WebExtension) {
            refresh()
        }
    }

    private val debuggerDelegate = object : WebExtensionController.DebuggerDelegate {
        override fun onExtensionListUpdated() {
            refresh()
        }
    }

    /** Called once by [AddonsInitializer] on the main thread, after the runtime exists. */
    fun start() {
        if (started) return
        started = true
        val controller = controller
        controller.setPromptDelegate(prompts)
        controller.setAddonManagerDelegate(addonManagerDelegate)
        controller.setDebuggerDelegate(debuggerDelegate)
        scope.launch(Dispatchers.IO) {
            preferences.load()
            fileCopier.clearCache()
        }
        refresh()
        scheduleUpdateCheck()
    }

    // region SessionObserver hooks (main thread)

    fun onSessionCreated(session: GeckoSession) {
        installed.list.value.forEach { attach(session, it) }
    }

    fun onSessionClosed(tabId: String) {
        actionRegistry.forgetTab(tabId)
    }

    /** A popup belongs to the tab it was opened for. */
    fun onSelectedTabChanged() {
        popups.dismissSoon()
    }

    // endregion

    // region AddonsRepository

    override fun actions(tabId: String?): Flow<List<ExtensionAction>> = actionRegistry.actions(tabId)

    override fun refresh() {
        scope.launch(Dispatchers.Main.immediate) { reload() }
    }

    override fun install(url: String) {
        val source = url.trim()
        if (source.isEmpty()) return
        scope.launch(Dispatchers.Main.immediate) { performInstall(source) }
    }

    override fun installUBlockOrigin() {
        scope.launch(Dispatchers.Main.immediate) {
            val existing = installed.byId(AddonsRepository.UBLOCK_ORIGIN_ID)
            when {
                existing == null -> performInstall(AddonsRepository.UBLOCK_ORIGIN_XPI)
                !existing.metaData.enabled -> setEnabled(existing.id, enabled = true)
                else -> _installState.value = AddonInstallState.Installed(existing.displayName)
            }
        }
    }

    override fun setEnabled(id: String, enabled: Boolean) {
        if (enabled) {
            modify(id, AddonOperation.ENABLE) { controller, extension ->
                controller.enable(extension, WebExtensionController.EnableSource.USER)
            }
        } else {
            modify(id, AddonOperation.DISABLE) { controller, extension ->
                controller.disable(extension, WebExtensionController.EnableSource.USER)
            }
        }
    }

    override fun uninstall(id: String) {
        modify(id, AddonOperation.UNINSTALL, onSuccess = { forgetExtension(id) }) { controller, extension ->
            controller.uninstall(extension)
        }
    }

    override fun setAllowedInPrivateBrowsing(id: String, allowed: Boolean) {
        modify(id, AddonOperation.PRIVATE_BROWSING) { controller, extension ->
            controller.setAllowedInPrivateBrowsing(extension, allowed)
        }
    }

    override fun openOptionsPage(id: String) {
        scope.launch(Dispatchers.Main.immediate) {
            val extension = installed.byId(id) ?: return@launch
            popups.dismiss()
            tabsDelegate.openOptionsPage(extension)
        }
    }

    override fun clickAction(extensionId: String, tabId: String?) {
        scope.launch(Dispatchers.Main.immediate) { actionRegistry.click(extensionId, tabId) }
    }

    override fun dismissPopup() {
        scope.launch(Dispatchers.Main.immediate) { popups.dismiss() }
    }

    override fun resetInstallState() {
        _installState.value = AddonInstallState.Idle
    }

    // endregion

    /** Lists the extensions and (re)registers delegates; an older listing never overwrites a newer one. */
    private suspend fun reload() {
        val generation = ++listingGeneration
        val all = try {
            controller.list().await().orEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Listing add-ons failed", e)
            return
        }
        if (generation < appliedGeneration) return
        appliedGeneration = generation
        val visible = all.filterNot { it.isHiddenBuiltIn }
        installed.replace(visible)
        visible.forEach { register(it, force = false) }
        val enabledIds = visible.filter { it.metaData.enabled }.mapTo(HashSet()) { it.id }
        actionRegistry.retainExtensions(enabledIds)
        popups.popup.value?.let { if (it.extensionId !in enabledIds) popups.dismiss() }
        icons.request(visible)
    }

    /**
     * Extension-wide delegates (default actions, popups, tabs.create, options page) once per id, or
     * again with [force] after an install or update; per-tab delegates on every open tab session.
     */
    private fun register(extension: WebExtension, force: Boolean) {
        if (registeredIds.add(extension.id) || force) {
            extension.setActionDelegate(actionRegistry)
            extension.setTabDelegate(tabsDelegate)
        }
        val browserTabs = tabs.get()
        browserTabs.tabs.value.forEach { tab ->
            browserTabs.session(tab.id)?.let { attach(it, extension) }
        }
    }

    private fun attach(session: GeckoSession, extension: WebExtension) {
        val sessionController = session.webExtensionController
        sessionController.setActionDelegate(extension, actionRegistry)
        sessionController.setTabDelegate(extension, tabsDelegate)
    }

    private fun forgetExtension(extensionId: String) {
        registeredIds.remove(extensionId)
        actionRegistry.forgetExtension(extensionId)
        popups.dismissFor(extensionId)
        icons.forget(extensionId)
        preferences.removeKnownAction(extensionId)
    }

    private suspend fun performInstall(source: String) {
        if (!activeInstalls.add(source)) return
        _installState.value = AddonInstallState.Installing(source)
        var copy: File? = null
        try {
            val kind = InstallSources.kindOf(source)
            if (kind == InstallSourceKind.UNSUPPORTED) {
                _installState.value = AddonInstallState.Failed(messages.installError(InstallError.UNSUPPORTED_SOURCE))
                return
            }
            val uri = if (kind == InstallSourceKind.CONTENT) {
                val file = withContext(Dispatchers.IO) { fileCopier.copyToCache(source) }
                copy = file
                Uri.fromFile(file).toString()
            } else {
                source
            }
            val method = if (kind == InstallSourceKind.REMOTE) {
                WebExtensionController.INSTALLATION_METHOD_MANAGER
            } else {
                WebExtensionController.INSTALLATION_METHOD_FROM_FILE
            }
            val extension = controller.install(uri, method).await()
                ?: throw IllegalStateException("GeckoView returned no extension for $source")
            register(extension, force = true)
            reload()
            _installState.value = AddonInstallState.Installed(extension.displayName)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Installing add-on from $source failed: ${e.message}")
            applyInstallFailure(e)
        } finally {
            activeInstalls.remove(source)
            copy?.let { file -> scope.launch(Dispatchers.IO) { file.delete() } }
        }
    }

    /** Failed(message), or back to Idle when the user cancelled the prompt. */
    private fun applyInstallFailure(error: Throwable) {
        val kind = when (error) {
            is WebExtension.InstallException -> InstallErrors.classify(error.code, error.message)
            is IOException -> InstallError.FILE_ACCESS
            else -> InstallErrors.classify(null, error.message)
        }
        if (kind == null) {
            if (_installState.value is AddonInstallState.Installing) _installState.value = AddonInstallState.Idle
        } else {
            _installState.value = AddonInstallState.Failed(messages.installError(kind))
        }
    }

    /** Runs an engine operation on an installed add-on, reports failures and refreshes the list. */
    private fun <T> modify(
        id: String,
        operation: AddonOperation,
        onSuccess: () -> Unit = {},
        action: (WebExtensionController, WebExtension) -> GeckoResult<T>,
    ) {
        scope.launch(Dispatchers.Main.immediate) {
            val extension = installed.byId(id)
            if (extension != null) {
                try {
                    action(controller, extension).await()
                    onSuccess()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(LOG_TAG, "${operation.name} failed for $id", e)
                    _installState.value = AddonInstallState.Failed(messages.operationFailed(operation, extension.displayName))
                }
            }
            reload()
        }
    }

    /** Looks for add-on updates once a day, shortly after startup. New permissions are prompted for. */
    private fun scheduleUpdateCheck() {
        scope.launch(Dispatchers.IO) {
            delay(UPDATE_CHECK_DELAY_MS)
            val now = System.currentTimeMillis()
            if (now - preferences.lastUpdateCheck() in 0L until UPDATE_INTERVAL_MS) return@launch
            preferences.setLastUpdateCheck(now)
            withContext(Dispatchers.Main.immediate) { updateAll() }
        }
    }

    private suspend fun updateAll() {
        val candidates = installed.list.value.filterNot { it.metaData.temporary }
        for (extension in candidates) {
            val id = extension.id
            updatingIds.add(id)
            try {
                controller.update(extension).await()?.let { register(it, force = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.i(LOG_TAG, "Update check failed for $id: ${e.message}")
            } finally {
                // Install events of the update may arrive after its result.
                scope.launch(Dispatchers.Main.immediate) {
                    delay(UPDATE_EVENT_GRACE_MS)
                    updatingIds.remove(id)
                }
            }
        }
        reload()
    }

    private fun toAddonInfo(extension: WebExtension, hasAction: Boolean, icon: Bitmap?): AddonInfo {
        val meta = extension.metaData
        return AddonInfo(
            id = extension.id,
            name = extension.displayName,
            description = meta.description?.takeIf { it.isNotBlank() },
            version = meta.version,
            creatorName = meta.creatorName?.takeIf { it.isNotBlank() },
            homepageUrl = meta.homepageUrl?.takeIf { it.isNotBlank() },
            icon = icon,
            isEnabled = meta.enabled,
            disabledReason = if (meta.enabled) {
                null
            } else {
                DisabledReasons.displayReason(meta.disabledFlags)?.let(messages::disabledReason)
            },
            allowedInPrivateBrowsing = meta.allowedInPrivateBrowsing,
            optionsPageUrl = meta.optionsPageUrl?.takeIf { it.isNotBlank() },
            openOptionsPageInTab = meta.openOptionsPageInTab,
            permissions = (meta.requiredPermissions.asList() + meta.grantedOptionalPermissions.asList()).distinct(),
            origins = (meta.requiredOrigins.asList() + meta.grantedOptionalOrigins.asList()).distinct(),
            hasBrowserAction = hasAction,
        )
    }

    private companion object {
        val ADDON_ORDER: Comparator<AddonInfo> =
            compareBy<AddonInfo, String>(String.CASE_INSENSITIVE_ORDER) { it.name }.thenBy { it.id }

        const val UPDATE_CHECK_DELAY_MS = 30_000L
        const val UPDATE_INTERVAL_MS = 24L * 60 * 60 * 1000
        const val UPDATE_EVENT_GRACE_MS = 10_000L
    }
}
