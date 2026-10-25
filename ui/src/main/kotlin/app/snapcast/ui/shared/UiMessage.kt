package app.snapcast.ui.shared

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * A one-shot message a ViewModel asks its screen to show (usually in a snackbar). Resources are resolved by
 * the screen so ViewModels stay free of localized text; [Text] carries messages that are already localized
 * (for example cast errors produced by the cast module).
 */
sealed interface UiMessage {
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiMessage

    data class Plural(@PluralsRes val id: Int, val count: Int, val args: List<Any> = emptyList()) : UiMessage

    data class Text(val text: String) : UiMessage

    fun resolve(context: Context): String = when (this) {
        is Res -> if (args.isEmpty()) context.getString(id) else context.getString(id, *args.toTypedArray())
        is Plural -> context.resources.getQuantityString(id, count, *args.toTypedArray())
        is Text -> text
    }

    companion object {
        fun res(@StringRes id: Int, vararg args: Any): UiMessage = Res(id, args.toList())

        /** A plural resource; [args] are the format arguments (usually starting with [count]). */
        fun plural(@PluralsRes id: Int, count: Int, vararg args: Any): UiMessage = Plural(id, count, args.toList())
    }
}

/** Formatted quantity string, like `stringResource` for plurals. */
@Composable
internal fun quantityString(@PluralsRes id: Int, count: Int, vararg args: Any): String =
    LocalContext.current.resources.getQuantityString(id, count, *args)
