package app.snapcast.ui.shared

import android.content.Context
import androidx.annotation.StringRes

/**
 * A one-shot message a ViewModel asks its screen to show (usually in a snackbar). Resources are resolved by
 * the screen so ViewModels stay free of localized text; [Text] carries messages that are already localized
 * (for example cast errors produced by the cast module).
 */
sealed interface UiMessage {
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiMessage

    data class Text(val text: String) : UiMessage

    fun resolve(context: Context): String = when (this) {
        is Res -> if (args.isEmpty()) context.getString(id) else context.getString(id, *args.toTypedArray())
        is Text -> text
    }

    companion object {
        fun res(@StringRes id: Int, vararg args: Any): UiMessage = Res(id, args.toList())
    }
}
