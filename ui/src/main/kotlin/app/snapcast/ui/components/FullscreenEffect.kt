package app.snapcast.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.snapcast.ui.util.findActivity

/** Hides the status and navigation bars while [fullscreen] is true (swipe shows them transiently). */
@Composable
fun FullscreenEffect(fullscreen: Boolean) {
    val view = LocalView.current
    DisposableEffect(fullscreen, view) {
        val window = view.context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        if (controller != null && fullscreen) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            if (controller != null && fullscreen) controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}
