package app.snapcast.ui.browser

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TheaterComedy
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.snapcast.addons.ExtensionAction
import app.snapcast.browser.TabState
import app.snapcast.ui.R
import app.snapcast.ui.components.ExtensionIcon

/** Callbacks of the overflow menu; each one also closes the menu. */
@Immutable
internal class BrowserMenuActions(
    val back: () -> Unit,
    val forward: () -> Unit,
    val reloadOrStop: () -> Unit,
    val toggleBookmark: () -> Unit,
    val newTab: () -> Unit,
    val newPrivateTab: () -> Unit,
    val history: () -> Unit,
    val bookmarks: () -> Unit,
    val addons: () -> Unit,
    val extensionAction: (ExtensionAction) -> Unit,
    val toggleDesktopMode: () -> Unit,
    val share: () -> Unit,
    val castLocal: () -> Unit,
    val settings: () -> Unit,
)

/** The browser's overflow menu: navigation row, tabs, library, extension toolbar actions, page tools. */
@Composable
internal fun BrowserMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    tab: TabState?,
    isBookmarked: Boolean,
    extensionActions: List<ExtensionAction>,
    actions: BrowserMenuActions,
) {
    val isWebPage = tab != null && !tab.isHome
    fun closing(action: () -> Unit): () -> Unit = {
        onDismiss()
        action()
    }
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            IconButton(onClick = closing(actions.back), enabled = tab?.canGoBack == true) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.ui_browser_back))
            }
            IconButton(onClick = closing(actions.forward), enabled = tab?.canGoForward == true) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = stringResource(R.string.ui_browser_forward))
            }
            val loading = tab?.isLoading == true
            IconButton(onClick = closing(actions.reloadOrStop), enabled = isWebPage) {
                Icon(
                    imageVector = if (loading) Icons.Filled.Close else Icons.Filled.Refresh,
                    contentDescription = stringResource(if (loading) R.string.ui_browser_stop else R.string.ui_browser_reload),
                )
            }
            IconButton(onClick = closing(actions.toggleBookmark), enabled = isWebPage) {
                Icon(
                    imageVector = if (isBookmarked) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                    contentDescription = stringResource(
                        if (isBookmarked) R.string.ui_browser_remove_bookmark else R.string.ui_browser_add_bookmark,
                    ),
                    tint = if (isBookmarked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        HorizontalDivider()
        MenuItem(R.string.ui_browser_new_tab, Icons.Filled.Add, closing(actions.newTab))
        MenuItem(R.string.ui_browser_new_private_tab, Icons.Filled.TheaterComedy, closing(actions.newPrivateTab))
        HorizontalDivider()
        MenuItem(R.string.ui_browser_history, Icons.Filled.History, closing(actions.history))
        MenuItem(R.string.ui_browser_bookmarks, Icons.Filled.Bookmarks, closing(actions.bookmarks))
        MenuItem(R.string.ui_browser_addons, Icons.Filled.Extension, closing(actions.addons))
        if (extensionActions.isNotEmpty()) {
            HorizontalDivider()
            extensionActions.forEach { action ->
                ExtensionActionItem(action = action, onClick = closing { actions.extensionAction(action) })
            }
        }
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text(stringResource(R.string.ui_browser_desktop_site)) },
            leadingIcon = { Icon(Icons.Filled.DesktopWindows, contentDescription = null) },
            trailingIcon = { Checkbox(checked = tab?.isDesktopMode == true, onCheckedChange = null) },
            enabled = isWebPage,
            onClick = closing(actions.toggleDesktopMode),
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.ui_browser_share)) },
            leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
            enabled = isWebPage,
            onClick = closing(actions.share),
        )
        MenuItem(R.string.ui_browser_cast_local, Icons.Filled.CastConnected, closing(actions.castLocal))
        HorizontalDivider()
        MenuItem(R.string.ui_browser_settings, Icons.Filled.Settings, closing(actions.settings))
    }
}

@Composable
private fun MenuItem(label: Int, icon: ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(label)) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        onClick = onClick,
    )
}

@Composable
private fun ExtensionActionItem(action: ExtensionAction, onClick: () -> Unit) {
    val badge = action.badgeText?.takeIf { it.isNotBlank() }
    DropdownMenuItem(
        text = {
            Text(
                text = action.title?.takeIf { it.isNotBlank() } ?: action.extensionName,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingIcon = {
            BadgedBox(
                badge = {
                    if (badge != null) {
                        Badge(
                            containerColor = action.badgeBackgroundColor?.let { Color(it) } ?: MaterialTheme.colorScheme.error,
                            contentColor = action.badgeTextColor?.let { Color(it) } ?: MaterialTheme.colorScheme.onError,
                        ) {
                            Text(badge, maxLines = 1)
                        }
                    }
                },
            ) {
                ExtensionIcon(icon = action.icon)
            }
        },
        enabled = action.enabled,
        onClick = onClick,
    )
}
