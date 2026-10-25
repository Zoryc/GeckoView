package app.snapcast.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

/**
 * Displays [session] in a [GeckoView]. Switching tabs swaps the session on the same view; the session
 * is released (not closed: the tab still owns it) when the view leaves the composition.
 */
@Composable
fun GeckoViewHost(session: GeckoSession?, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { context -> GeckoView(context) },
        modifier = modifier,
        update = { view ->
            if (view.session !== session) {
                view.releaseSession()
                session?.let(view::setSession)
            }
        },
        onRelease = { view -> view.releaseSession() },
    )
}
