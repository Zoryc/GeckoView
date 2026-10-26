package app.snapcast.browser.delegates

import android.content.Context
import android.net.Uri
import app.snapcast.browser.BrowserPrompt
import app.snapcast.browser.prompts.PromptRegistry
import app.snapcast.browser.tabs.TabHost
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.PromptDelegate.AlertPrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.AuthPrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.BasePrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.BeforeUnloadPrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.ButtonPrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.ChoicePrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.ColorPrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.DateTimePrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.FilePrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.PopupPrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.PromptInstanceDelegate
import org.mozilla.geckoview.GeckoSession.PromptDelegate.PromptResponse
import org.mozilla.geckoview.GeckoSession.PromptDelegate.RepostConfirmPrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.SharePrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.TextPrompt

/**
 * Publishes web-content dialogs as [BrowserPrompt]s and answers Gecko when the UI resolves them.
 * Each prompt leaves [PromptRegistry] when answered or when Gecko dismisses it (e.g. the page
 * navigated away); late answers are ignored.
 *
 * @param ownerId set for a session that is not a tab (an extension popup): its prompts have no
 *   [BrowserPrompt.tabId], so the UI shows them whatever tab is selected, and are registered under this
 *   id so [PromptRegistry.dismissOwnedBy] can cancel them when the session goes away
 */
internal class TabPromptDelegate(
    private val context: Context,
    private val host: TabHost,
    private val registry: PromptRegistry,
    private val uploads: UploadFileCopier,
    private val appScope: CoroutineScope,
    private val ownerId: String? = null,
) : GeckoSession.PromptDelegate {

    override fun onAlertPrompt(session: GeckoSession, prompt: AlertPrompt): GeckoResult<PromptResponse> {
        val pending = Pending(prompt)
        return pending.publish(
            BrowserPrompt.Alert(
                id = pending.id,
                tabId = tabIdOf(session),
                title = prompt.title,
                message = prompt.message,
                onDismiss = { pending.answer { it.dismiss() } },
            ),
        )
    }

    override fun onButtonPrompt(session: GeckoSession, prompt: ButtonPrompt): GeckoResult<PromptResponse> {
        val pending = Pending(prompt)
        return pending.publish(
            BrowserPrompt.Confirm(
                id = pending.id,
                tabId = tabIdOf(session),
                title = prompt.title,
                message = prompt.message,
                onResult = { confirmed ->
                    pending.answer {
                        when (confirmed) {
                            true -> it.confirm(ButtonPrompt.Type.POSITIVE)
                            false -> it.confirm(ButtonPrompt.Type.NEGATIVE)
                            null -> it.dismiss()
                        }
                    }
                },
            ),
        )
    }

    override fun onTextPrompt(session: GeckoSession, prompt: TextPrompt): GeckoResult<PromptResponse> {
        val pending = Pending(prompt)
        return pending.publish(
            BrowserPrompt.TextInput(
                id = pending.id,
                tabId = tabIdOf(session),
                title = prompt.title,
                message = prompt.message,
                defaultValue = prompt.defaultValue,
                onResult = { text -> pending.answer { if (text == null) it.dismiss() else it.confirm(text) } },
            ),
        )
    }

    override fun onAuthPrompt(session: GeckoSession, prompt: AuthPrompt): GeckoResult<PromptResponse> {
        val pending = Pending(prompt)
        val passwordOnly = prompt.authOptions.flags and AuthPrompt.AuthOptions.Flags.ONLY_PASSWORD != 0
        return pending.publish(
            BrowserPrompt.Auth(
                id = pending.id,
                tabId = tabIdOf(session),
                title = prompt.title,
                message = prompt.message,
                defaultUsername = prompt.authOptions.username,
                passwordOnly = passwordOnly,
                onResult = { username, password ->
                    pending.answer {
                        when {
                            password == null -> it.dismiss()
                            passwordOnly -> it.confirm(password)
                            else -> it.confirm(username.orEmpty(), password)
                        }
                    }
                },
            ),
        )
    }

    override fun onChoicePrompt(session: GeckoSession, prompt: ChoicePrompt): GeckoResult<PromptResponse> {
        val pending = Pending(prompt)
        val multiple = prompt.type == ChoicePrompt.Type.MULTIPLE
        return pending.publish(
            BrowserPrompt.Choice(
                id = pending.id,
                tabId = tabIdOf(session),
                title = prompt.title,
                message = prompt.message,
                items = prompt.choices.map(::toChoiceItem),
                multiple = multiple,
                onResult = { ids ->
                    pending.answer {
                        when {
                            ids == null -> it.dismiss()
                            multiple -> it.confirm(ids.toTypedArray())
                            ids.isEmpty() -> it.dismiss()
                            else -> it.confirm(ids.first())
                        }
                    }
                },
            ),
        )
    }

    override fun onColorPrompt(session: GeckoSession, prompt: ColorPrompt): GeckoResult<PromptResponse> {
        val pending = Pending(prompt)
        return pending.publish(
            BrowserPrompt.ColorPicker(
                id = pending.id,
                tabId = tabIdOf(session),
                defaultValue = prompt.defaultValue,
                onResult = { color -> pending.answer { if (color == null) it.dismiss() else it.confirm(color) } },
            ),
        )
    }

    override fun onDateTimePrompt(session: GeckoSession, prompt: DateTimePrompt): GeckoResult<PromptResponse> {
        val type = when (prompt.type) {
            DateTimePrompt.Type.DATE -> BrowserPrompt.DateTimeType.DATE
            DateTimePrompt.Type.MONTH -> BrowserPrompt.DateTimeType.MONTH
            DateTimePrompt.Type.WEEK -> BrowserPrompt.DateTimeType.WEEK
            DateTimePrompt.Type.TIME -> BrowserPrompt.DateTimeType.TIME
            DateTimePrompt.Type.DATETIME_LOCAL -> BrowserPrompt.DateTimeType.DATETIME_LOCAL
            else -> return GeckoResult.fromValue(prompt.dismiss())
        }
        val pending = Pending(prompt)
        return pending.publish(
            BrowserPrompt.DateTime(
                id = pending.id,
                tabId = tabIdOf(session),
                type = type,
                defaultValue = prompt.defaultValue,
                minValue = prompt.minValue,
                maxValue = prompt.maxValue,
                onResult = { value -> pending.answer { if (value == null) it.dismiss() else it.confirm(value) } },
            ),
        )
    }

    override fun onFilePrompt(session: GeckoSession, prompt: FilePrompt): GeckoResult<PromptResponse> {
        // Directory uploads (webkitdirectory) are not offered by the UI.
        if (prompt.type == FilePrompt.Type.FOLDER) return GeckoResult.fromValue(prompt.dismiss())
        val pending = Pending(prompt)
        val multiple = prompt.type == FilePrompt.Type.MULTIPLE
        return pending.publish(
            BrowserPrompt.FilePicker(
                id = pending.id,
                tabId = tabIdOf(session),
                mimeTypes = prompt.mimeTypes?.filterNotNull()?.filter { it.isNotBlank() }.orEmpty(),
                multiple = multiple,
                capture = prompt.capture != FilePrompt.Capture.NONE,
                onResult = { uris -> onFilesPicked(pending, uris, multiple) },
            ),
        )
    }

    private fun onFilesPicked(pending: Pending<FilePrompt>, uris: List<Uri>?, multiple: Boolean) {
        if (!pending.take()) return
        if (uris.isNullOrEmpty()) {
            pending.complete { it.dismiss() }
            return
        }
        appScope.launch(Dispatchers.Main.immediate) {
            val files = withContext(Dispatchers.IO) { uploads.toFileUris(if (multiple) uris else uris.take(1)) }
            pending.complete { prompt ->
                when {
                    files.isEmpty() -> prompt.dismiss()
                    multiple -> prompt.confirm(context, files.toTypedArray())
                    else -> prompt.confirm(context, files.first())
                }
            }
        }
    }

    override fun onPopupPrompt(session: GeckoSession, prompt: PopupPrompt): GeckoResult<PromptResponse> {
        val pending = Pending(prompt)
        return pending.publish(
            BrowserPrompt.Popup(
                id = pending.id,
                tabId = tabIdOf(session),
                targetUrl = prompt.targetUri,
                onResult = { allow -> pending.answer { it.confirm(if (allow) AllowOrDeny.ALLOW else AllowOrDeny.DENY) } },
            ),
        )
    }

    override fun onBeforeUnloadPrompt(session: GeckoSession, prompt: BeforeUnloadPrompt): GeckoResult<PromptResponse> {
        val pending = Pending(prompt)
        return pending.publish(
            BrowserPrompt.BeforeUnload(
                id = pending.id,
                tabId = tabIdOf(session),
                onResult = { leave -> pending.answer { it.confirm(if (leave) AllowOrDeny.ALLOW else AllowOrDeny.DENY) } },
            ),
        )
    }

    override fun onRepostConfirmPrompt(session: GeckoSession, prompt: RepostConfirmPrompt): GeckoResult<PromptResponse> {
        val pending = Pending(prompt)
        return pending.publish(
            BrowserPrompt.Repost(
                id = pending.id,
                tabId = tabIdOf(session),
                onResult = { resend -> pending.answer { it.confirm(if (resend) AllowOrDeny.ALLOW else AllowOrDeny.DENY) } },
            ),
        )
    }

    override fun onSharePrompt(session: GeckoSession, prompt: SharePrompt): GeckoResult<PromptResponse> {
        val pending = Pending(prompt)
        return pending.publish(
            BrowserPrompt.Share(
                id = pending.id,
                tabId = tabIdOf(session),
                title = prompt.title,
                text = prompt.text,
                url = prompt.uri,
                onResult = { shared ->
                    pending.answer { it.confirm(if (shared) SharePrompt.Result.SUCCESS else SharePrompt.Result.ABORT) }
                },
            ),
        )
    }

    private fun tabIdOf(session: GeckoSession): String? = host.tabOf(session)?.id

    private fun toChoiceItem(choice: ChoicePrompt.Choice): BrowserPrompt.ChoiceItem = BrowserPrompt.ChoiceItem(
        id = choice.id,
        label = choice.label,
        selected = choice.selected,
        enabled = !choice.disabled,
        isGroup = choice.items != null,
        isSeparator = choice.separator,
        children = choice.items?.map(::toChoiceItem).orEmpty(),
    )

    /** One published prompt: answered at most once, never after Gecko completed or dismissed it. */
    private inner class Pending<P : BasePrompt>(private val prompt: P) {
        val id: String = UUID.randomUUID().toString()
        private val result = GeckoResult<PromptResponse>()
        private var taken = false

        fun publish(browserPrompt: BrowserPrompt): GeckoResult<PromptResponse> {
            prompt.setDelegate(
                object : PromptInstanceDelegate {
                    override fun onPromptDismiss(dismissed: BasePrompt) {
                        take()
                    }
                },
            )
            registry.add(browserPrompt, owner = ownerId)
            return result
        }

        /** Removes the prompt from the UI; false when it was already answered or dismissed. */
        fun take(): Boolean {
            if (taken) return false
            taken = true
            registry.remove(id)
            return true
        }

        fun complete(respond: (P) -> PromptResponse) {
            if (!prompt.isComplete) result.complete(respond(prompt))
        }

        fun answer(respond: (P) -> PromptResponse) {
            if (take()) complete(respond)
        }
    }
}
