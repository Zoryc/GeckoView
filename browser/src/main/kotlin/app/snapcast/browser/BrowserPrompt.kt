package app.snapcast.browser

import android.net.Uri

/**
 * A dialog the UI must show on behalf of web content or the engine. Prompts are published in
 * [BrowserTabs.prompts] (oldest first) and removed automatically once resolved through one of the
 * callbacks or dismissed by the engine. Every callback must be invoked at most once, on the main thread.
 */
sealed class BrowserPrompt {
    abstract val id: String

    /** Tab that raised the prompt; null for engine-wide prompts. */
    abstract val tabId: String?

    /** Resolves the prompt with the "cancel"/"deny" answer. */
    abstract fun dismiss()

    class Alert(
        override val id: String,
        override val tabId: String?,
        val title: String?,
        val message: String?,
        val onDismiss: () -> Unit,
    ) : BrowserPrompt() {
        override fun dismiss() = onDismiss()
    }

    /** window.confirm() and other button prompts. */
    class Confirm(
        override val id: String,
        override val tabId: String?,
        val title: String?,
        val message: String?,
        val onResult: (confirmed: Boolean?) -> Unit,
    ) : BrowserPrompt() {
        override fun dismiss() = onResult(null)
    }

    /** window.prompt() */
    class TextInput(
        override val id: String,
        override val tabId: String?,
        val title: String?,
        val message: String?,
        val defaultValue: String?,
        val onResult: (String?) -> Unit,
    ) : BrowserPrompt() {
        override fun dismiss() = onResult(null)
    }

    /** HTTP authentication. */
    class Auth(
        override val id: String,
        override val tabId: String?,
        val title: String?,
        val message: String?,
        val defaultUsername: String?,
        val passwordOnly: Boolean,
        val onResult: (username: String?, password: String?) -> Unit,
    ) : BrowserPrompt() {
        override fun dismiss() = onResult(null, null)
    }

    data class ChoiceItem(
        val id: String,
        val label: String,
        val selected: Boolean,
        val enabled: Boolean,
        val isGroup: Boolean,
        val isSeparator: Boolean,
        val children: List<ChoiceItem> = emptyList(),
    )

    /** <select> elements and menus. */
    class Choice(
        override val id: String,
        override val tabId: String?,
        val title: String?,
        val message: String?,
        val items: List<ChoiceItem>,
        val multiple: Boolean,
        /** Ids of the chosen items, or null when cancelled. */
        val onResult: (List<String>?) -> Unit,
    ) : BrowserPrompt() {
        override fun dismiss() = onResult(null)
    }

    /** <input type=color>; value is "#rrggbb". */
    class ColorPicker(
        override val id: String,
        override val tabId: String?,
        val defaultValue: String?,
        val onResult: (String?) -> Unit,
    ) : BrowserPrompt() {
        override fun dismiss() = onResult(null)
    }

    enum class DateTimeType { DATE, MONTH, WEEK, TIME, DATETIME_LOCAL }

    /** Date/time inputs; values use the HTML formats (yyyy-MM-dd, HH:mm, yyyy-MM-ddTHH:mm, ...). */
    class DateTime(
        override val id: String,
        override val tabId: String?,
        val type: DateTimeType,
        val defaultValue: String?,
        val minValue: String?,
        val maxValue: String?,
        val onResult: (String?) -> Unit,
    ) : BrowserPrompt() {
        override fun dismiss() = onResult(null)
    }

    /** <input type=file>; the UI launches a document picker and returns content URIs. */
    class FilePicker(
        override val id: String,
        override val tabId: String?,
        val mimeTypes: List<String>,
        val multiple: Boolean,
        /** True when the page asked to capture from camera/mic (capture attribute). */
        val capture: Boolean,
        val onResult: (List<Uri>?) -> Unit,
    ) : BrowserPrompt() {
        override fun dismiss() = onResult(null)
    }

    /** A blocked popup window; allow=true opens it in a new tab. */
    class Popup(
        override val id: String,
        override val tabId: String?,
        val targetUrl: String?,
        val onResult: (allow: Boolean) -> Unit,
    ) : BrowserPrompt() {
        override fun dismiss() = onResult(false)
    }

    class BeforeUnload(
        override val id: String,
        override val tabId: String?,
        val onResult: (leave: Boolean) -> Unit,
    ) : BrowserPrompt() {
        override fun dismiss() = onResult(false)
    }

    class Repost(
        override val id: String,
        override val tabId: String?,
        val onResult: (resend: Boolean) -> Unit,
    ) : BrowserPrompt() {
        override fun dismiss() = onResult(false)
    }

    /** navigator.share(); the UI shows the Android share sheet then reports whether it was shown. */
    class Share(
        override val id: String,
        override val tabId: String?,
        val title: String?,
        val text: String?,
        val url: String?,
        val onResult: (shared: Boolean) -> Unit,
    ) : BrowserPrompt() {
        override fun dismiss() = onResult(false)
    }

    enum class PermissionKind { GEOLOCATION, NOTIFICATIONS, PERSISTENT_STORAGE, XR, AUTOPLAY, MEDIA_KEY_SYSTEM, STORAGE_ACCESS, LOCAL_NETWORK, OTHER }

    /** Site permission (geolocation, notifications, ...). */
    class ContentPermission(
        override val id: String,
        override val tabId: String?,
        val kind: PermissionKind,
        val origin: String,
        val onResult: (allow: Boolean) -> Unit,
    ) : BrowserPrompt() {
        override fun dismiss() = onResult(false)
    }

    data class MediaSourceInfo(val id: String, val name: String)

    /** getUserMedia(): pick a camera and/or microphone, or reject. */
    class MediaPermission(
        override val id: String,
        override val tabId: String?,
        val origin: String,
        val videoSources: List<MediaSourceInfo>,
        val audioSources: List<MediaSourceInfo>,
        /** Chosen source ids (null for none); reject when both are null. */
        val onResult: (videoSourceId: String?, audioSourceId: String?) -> Unit,
    ) : BrowserPrompt() {
        override fun dismiss() = onResult(null, null)
    }

    /** The engine needs Android runtime permissions (e.g. location, camera) before it can continue. */
    class AndroidPermissions(
        override val id: String,
        override val tabId: String?,
        val permissions: List<String>,
        val onResult: (granted: Boolean) -> Unit,
    ) : BrowserPrompt() {
        override fun dismiss() = onResult(false)
    }
}
