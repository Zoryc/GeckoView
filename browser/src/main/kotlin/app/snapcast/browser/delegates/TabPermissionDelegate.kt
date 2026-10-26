package app.snapcast.browser.delegates

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import app.snapcast.browser.BrowserPrompt
import app.snapcast.browser.R
import app.snapcast.browser.hooks.BrowserHooks
import app.snapcast.browser.prompts.PromptRegistry
import app.snapcast.browser.tabs.TabHost
import app.snapcast.browser.web.UriParts
import java.util.Locale
import java.util.UUID
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.ContentPermission
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.MediaCallback
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.MediaSource

/**
 * Site permissions. EME requests are reported to observers (DRM detection) before the user is
 * asked; inaudible autoplay is allowed and audible autoplay blocked without a prompt; everything
 * else becomes a [BrowserPrompt].
 */
internal class TabPermissionDelegate(
    private val context: Context,
    private val host: TabHost,
    private val hooks: BrowserHooks,
    private val registry: PromptRegistry,
) : GeckoSession.PermissionDelegate {

    override fun onContentPermissionRequest(session: GeckoSession, perm: ContentPermission): GeckoResult<Int>? {
        val tabId = host.tabOf(session)?.id
        val kind = when (perm.permission) {
            GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE -> return GeckoResult.fromValue(ContentPermission.VALUE_ALLOW)
            GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE -> return GeckoResult.fromValue(ContentPermission.VALUE_DENY)
            // Tracking-protection exceptions are managed by the app, never requested by pages.
            GeckoSession.PermissionDelegate.PERMISSION_TRACKING -> return null
            GeckoSession.PermissionDelegate.PERMISSION_MEDIA_KEY_SYSTEM_ACCESS -> {
                if (tabId != null) hooks.notify { it.onMediaKeySystemRequested(tabId, perm.uri) }
                BrowserPrompt.PermissionKind.MEDIA_KEY_SYSTEM
            }
            GeckoSession.PermissionDelegate.PERMISSION_GEOLOCATION -> BrowserPrompt.PermissionKind.GEOLOCATION
            GeckoSession.PermissionDelegate.PERMISSION_DESKTOP_NOTIFICATION -> BrowserPrompt.PermissionKind.NOTIFICATIONS
            GeckoSession.PermissionDelegate.PERMISSION_PERSISTENT_STORAGE -> BrowserPrompt.PermissionKind.PERSISTENT_STORAGE
            GeckoSession.PermissionDelegate.PERMISSION_XR -> BrowserPrompt.PermissionKind.XR
            GeckoSession.PermissionDelegate.PERMISSION_STORAGE_ACCESS -> BrowserPrompt.PermissionKind.STORAGE_ACCESS
            GeckoSession.PermissionDelegate.PERMISSION_LOCAL_NETWORK_ACCESS,
            GeckoSession.PermissionDelegate.PERMISSION_LOCAL_DEVICE_ACCESS,
            -> BrowserPrompt.PermissionKind.LOCAL_NETWORK
            else -> BrowserPrompt.PermissionKind.OTHER
        }
        val result = GeckoResult<Int>()
        val id = UUID.randomUUID().toString()
        val answer = AnswerOnce(registry, id)
        registry.add(
            BrowserPrompt.ContentPermission(
                id = id,
                tabId = tabId,
                kind = kind,
                origin = UriParts.originOf(perm.uri) ?: perm.uri,
                onResult = { allow ->
                    answer {
                        result.complete(if (allow) ContentPermission.VALUE_ALLOW else ContentPermission.VALUE_DENY)
                        if (allow && kind == BrowserPrompt.PermissionKind.NOTIFICATIONS) requestPostNotifications(tabId)
                    }
                },
                // Gecko stores the answer for the site: a prompt closed without a choice keeps
                // "ask" (denied this time only) instead of a permanent block.
                onDismiss = { answer { result.complete(ContentPermission.VALUE_PROMPT) } },
            ),
        )
        return result
    }

    /** A site may notify now; Android 13+ also needs the app-level notification permission. */
    private fun requestPostNotifications(tabId: String?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (hasPermission(Manifest.permission.POST_NOTIFICATIONS)) return
        val id = UUID.randomUUID().toString()
        val answer = AnswerOnce(registry, id)
        registry.add(
            BrowserPrompt.AndroidPermissions(
                id = id,
                tabId = tabId,
                permissions = listOf(Manifest.permission.POST_NOTIFICATIONS),
                onResult = { answer {} },
            ),
        )
    }

    override fun onAndroidPermissionsRequest(
        session: GeckoSession,
        permissions: Array<out String>?,
        callback: GeckoSession.PermissionDelegate.Callback,
    ) {
        val missing = permissions.orEmpty().filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            callback.grant()
            return
        }
        val id = UUID.randomUUID().toString()
        val answer = AnswerOnce(registry, id)
        registry.add(
            BrowserPrompt.AndroidPermissions(
                id = id,
                tabId = host.tabOf(session)?.id,
                permissions = missing,
                onResult = { granted -> answer { if (granted) callback.grant() else callback.reject() } },
            ),
        )
    }

    override fun onMediaPermissionRequest(
        session: GeckoSession,
        uri: String,
        video: Array<out MediaSource>?,
        audio: Array<out MediaSource>?,
        callback: MediaCallback,
    ) {
        // Android permissions were requested through onAndroidPermissionsRequest first; without
        // them Gecko cannot open the devices anyway.
        val videoAllowed = video.isNullOrEmpty() || hasPermission(Manifest.permission.CAMERA)
        val audioAllowed = audio.isNullOrEmpty() || hasPermission(Manifest.permission.RECORD_AUDIO)
        if (!videoAllowed || !audioAllowed || (video.isNullOrEmpty() && audio.isNullOrEmpty())) {
            callback.reject()
            return
        }
        val id = UUID.randomUUID().toString()
        val answer = AnswerOnce(registry, id)
        registry.add(
            BrowserPrompt.MediaPermission(
                id = id,
                tabId = host.tabOf(session)?.id,
                origin = UriParts.originOf(uri) ?: uri,
                videoSources = video.orEmpty().map { BrowserPrompt.MediaSourceInfo(it.id, displayName(it)) },
                audioSources = audio.orEmpty().map { BrowserPrompt.MediaSourceInfo(it.id, displayName(it)) },
                onResult = { videoId, audioId ->
                    answer {
                        val chosenVideo: MediaSource? = video?.firstOrNull { it.id == videoId }
                        val chosenAudio: MediaSource? = audio?.firstOrNull { it.id == audioId }
                        if (chosenVideo == null && chosenAudio == null) callback.reject() else callback.grant(chosenVideo, chosenAudio)
                    }
                },
            ),
        )
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun displayName(source: MediaSource): String {
        val name = source.name?.takeIf { it.isNotBlank() }
        return when (source.source) {
            MediaSource.SOURCE_CAMERA -> when {
                name?.lowercase(Locale.ROOT)?.contains("front") == true -> context.getString(R.string.browser_media_front_camera)
                name?.lowercase(Locale.ROOT)?.contains("back") == true -> context.getString(R.string.browser_media_back_camera)
                else -> name ?: context.getString(R.string.browser_media_camera)
            }
            MediaSource.SOURCE_MICROPHONE -> name ?: context.getString(R.string.browser_media_microphone)
            MediaSource.SOURCE_SCREEN -> name ?: context.getString(R.string.browser_media_screen)
            else -> name ?: context.getString(R.string.browser_media_other)
        }
    }

    /** Runs the answer once and removes the prompt from the registry. */
    private class AnswerOnce(private val registry: PromptRegistry, private val id: String) {
        private var answered = false

        operator fun invoke(block: () -> Unit) {
            if (answered) return
            answered = true
            registry.remove(id)
            block()
        }
    }
}
