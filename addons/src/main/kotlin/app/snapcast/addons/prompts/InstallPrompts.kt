package app.snapcast.addons.prompts

import android.content.Context
import android.util.Log
import app.snapcast.addons.AddonPermissionPrompt
import app.snapcast.addons.displayName
import app.snapcast.addons.impersonatesBuiltIn
import app.snapcast.addons.isHiddenBuiltIn
import app.snapcast.addons.util.LOG_TAG
import app.snapcast.addons.util.loadBitmap
import app.snapcast.addons.util.runOnMain
import app.snapcast.data.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.Image
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController

/**
 * GeckoView's add-on permission prompts (install, update with new permissions, optional permissions)
 * published one at a time as [AddonPermissionPrompt]s; the engine waits on a [GeckoResult] until the
 * user answers. Engine callbacks run on the main thread.
 */
@Singleton
internal class InstallPrompts @Inject constructor(
    @ApplicationContext context: Context,
    private val describer: PermissionDescriber,
    @ApplicationScope private val scope: CoroutineScope,
) : WebExtensionController.PromptDelegate {

    private val iconSizePx = (ICON_SIZE_DP * context.resources.displayMetrics.density).roundToInt()
    private val queue = ArrayList<AddonPermissionPrompt>()
    private val _prompt = MutableStateFlow<AddonPermissionPrompt?>(null)

    /** The prompt to show now (oldest first); null when none is pending. */
    val prompt: StateFlow<AddonPermissionPrompt?> = _prompt.asStateFlow()

    override fun onInstallPromptRequest(
        extension: WebExtension,
        permissions: Array<String>,
        origins: Array<String>,
        dataCollectionPermissions: Array<String>,
    ): GeckoResult<WebExtension.PermissionPromptResponse>? {
        // Gecko rejects the install (ERROR_USER_CANCELED), which leaves the install state Idle.
        if (extension.impersonatesBuiltIn) {
            Log.w(LOG_TAG, "Refusing an add-on that claims the built-in id ${extension.id}")
            return GeckoResult.fromValue(WebExtension.PermissionPromptResponse(false, false, false))
        }
        if (extension.isHiddenBuiltIn) {
            return GeckoResult.fromValue(WebExtension.PermissionPromptResponse(true, true, false))
        }
        val result = GeckoResult<WebExtension.PermissionPromptResponse>()
        val offerPrivateBrowsing = extension.metaData.incognito != INCOGNITO_NOT_ALLOWED
        enqueue(
            extension = extension,
            permissions = permissions.asList(),
            origins = origins.asList(),
            // Optional technical data is never granted at install (answered with false below).
            dataCollection = dataCollectionPermissions.filterNot { it == TECHNICAL_AND_INTERACTION },
            offerPrivateBrowsing = offerPrivateBrowsing,
        ) { allow, allowInPrivate ->
            result.complete(
                WebExtension.PermissionPromptResponse(allow, allow && offerPrivateBrowsing && allowInPrivate, false),
            )
        }
        return result
    }

    override fun onUpdatePrompt(
        extension: WebExtension,
        newPermissions: Array<String>,
        newOrigins: Array<String>,
        newDataCollectionPermissions: Array<String>,
    ): GeckoResult<AllowOrDeny>? = askAllowOrDeny(extension, newPermissions, newOrigins, newDataCollectionPermissions)

    override fun onOptionalPrompt(
        extension: WebExtension,
        permissions: Array<String>,
        origins: Array<String>,
        dataCollectionPermissions: Array<String>,
    ): GeckoResult<AllowOrDeny>? = askAllowOrDeny(extension, permissions, origins, dataCollectionPermissions)

    private fun askAllowOrDeny(
        extension: WebExtension,
        permissions: Array<String>,
        origins: Array<String>,
        dataCollection: Array<String>,
    ): GeckoResult<AllowOrDeny> {
        if (extension.impersonatesBuiltIn) return GeckoResult.deny()
        if (extension.isHiddenBuiltIn) return GeckoResult.allow()
        val result = GeckoResult<AllowOrDeny>()
        enqueue(
            extension = extension,
            permissions = permissions.asList(),
            origins = origins.asList(),
            dataCollection = dataCollection.asList(),
            offerPrivateBrowsing = false,
        ) { allow, _ ->
            result.complete(if (allow) AllowOrDeny.ALLOW else AllowOrDeny.DENY)
        }
        return result
    }

    private fun enqueue(
        extension: WebExtension,
        permissions: List<String>,
        origins: List<String>,
        dataCollection: List<String>,
        offerPrivateBrowsing: Boolean,
        respond: (allow: Boolean, allowInPrivate: Boolean) -> Unit,
    ) {
        val id = UUID.randomUUID().toString()
        val answered = AtomicBoolean(false)
        val prompt = AddonPermissionPrompt(
            id = id,
            addonName = extension.displayName,
            icon = null,
            permissions = describer.describePermissions(permissions, dataCollection),
            origins = describer.describeOrigins(origins),
            offerPrivateBrowsing = offerPrivateBrowsing,
            onResult = { allow, allowInPrivate ->
                if (answered.compareAndSet(false, true)) {
                    runOnMain {
                        remove(id)
                        respond(allow, allowInPrivate)
                    }
                }
            },
        )
        queue.add(prompt)
        publish()
        loadIcon(id, extension.metaData.icon)
    }

    private fun loadIcon(promptId: String, image: Image) {
        scope.launch(Dispatchers.Main.immediate) {
            val bitmap = image.loadBitmap(iconSizePx) ?: return@launch
            val index = queue.indexOfFirst { it.id == promptId }
            if (index < 0) return@launch
            queue[index] = queue[index].copy(icon = bitmap)
            publish()
        }
    }

    private fun remove(promptId: String) {
        queue.removeAll { it.id == promptId }
        publish()
    }

    private fun publish() {
        _prompt.value = queue.firstOrNull()
    }

    private companion object {
        const val ICON_SIZE_DP = 48
        const val INCOGNITO_NOT_ALLOWED = "not_allowed"
        const val TECHNICAL_AND_INTERACTION = "technicalAndInteraction"
    }
}
