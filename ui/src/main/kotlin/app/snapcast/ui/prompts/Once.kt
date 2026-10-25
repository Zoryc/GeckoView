package app.snapcast.ui.prompts

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * Runs a block at most once. Prompt callbacks must be invoked at most once, but a fast double tap can
 * deliver two clicks before the resolved prompt leaves the composition.
 */
internal class Once {
    private var used = false

    operator fun invoke(block: () -> Unit) {
        if (used) return
        used = true
        block()
    }
}

@Composable
internal fun rememberOnce(key: Any): Once = remember(key) { Once() }
