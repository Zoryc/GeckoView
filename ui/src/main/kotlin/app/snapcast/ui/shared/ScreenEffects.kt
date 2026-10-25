package app.snapcast.ui.shared

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.flow.Flow

/** Shows every message of [messages] in [snackbarHostState], one after another, while the screen is shown. */
@Composable
internal fun SnackbarMessages(messages: Flow<UiMessage>, snackbarHostState: SnackbarHostState) {
    val context = LocalContext.current
    LaunchedEffect(messages, snackbarHostState) {
        messages.collect { message -> snackbarHostState.showSnackbar(message.resolve(context)) }
    }
}

/** The [Activity] behind this context, unwrapping [ContextWrapper]s (Compose hands out wrapped contexts). */
internal tailrec fun Context.hostActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.hostActivity()
    else -> null
}

/** The hosting [FragmentActivity] (the Cast device chooser needs one), or null. */
internal fun Context.hostFragmentActivity(): FragmentActivity? = hostActivity() as? FragmentActivity

/** Gives a control without visible text (sliders and the like) an accessibility label. */
internal fun Modifier.semanticsLabel(label: String): Modifier = semantics { contentDescription = label }
