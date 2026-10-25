package app.snapcast.ui.tabs

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Tab
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.snapcast.ui.R
import app.snapcast.ui.shared.ConfirmDialog
import app.snapcast.ui.shared.EmptyState
import app.snapcast.ui.shared.siteInitial

/**
 * Tab switcher: normal and private tabs in a grid. Tapping a card selects that tab and closes the switcher;
 * the floating button opens a new tab of the mode shown.
 */
@Composable
fun TabsScreen(onClose: () -> Unit) {
    val viewModel: TabsViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    TabsContent(
        state = state,
        onClose = onClose,
        onShowPrivate = viewModel::showPrivateTabs,
        onSelect = { id ->
            viewModel.selectTab(id)
            onClose()
        },
        onCloseTab = viewModel::closeTab,
        onNewTab = {
            viewModel.newTab()
            onClose()
        },
        onCloseAll = viewModel::closeAllTabs,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TabsContent(
    state: TabsUiState,
    onClose: () -> Unit,
    onShowPrivate: (Boolean) -> Unit,
    onSelect: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onNewTab: () -> Unit,
    onCloseAll: () -> Unit,
) {
    var confirmCloseAll by rememberSaveable { mutableStateOf(false) }
    val tabs = state.visibleTabs

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(stringResource(R.string.ui_screens_tabs_title)) },
                    navigationIcon = {
                        IconButton(onClick = onClose) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.ui_screens_tabs_close_switcher))
                        }
                    },
                    actions = {
                        TabsMenu(enabled = tabs.isNotEmpty(), private = state.showPrivate) { confirmCloseAll = true }
                    },
                )
                PrimaryTabRow(selectedTabIndex = if (state.showPrivate) 1 else 0) {
                    Tab(
                        selected = !state.showPrivate,
                        onClick = { onShowPrivate(false) },
                        text = { Text(stringResource(R.string.ui_screens_tabs_normal, state.normalTabs.size)) },
                        icon = { Icon(Icons.Default.Tab, contentDescription = null) },
                    )
                    Tab(
                        selected = state.showPrivate,
                        onClick = { onShowPrivate(true) },
                        text = { Text(stringResource(R.string.ui_screens_tabs_private, state.privateTabs.size)) },
                        icon = { Icon(Icons.Default.VisibilityOff, contentDescription = null) },
                    )
                }
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNewTab,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = {
                    Text(
                        stringResource(
                            if (state.showPrivate) R.string.ui_screens_tabs_new_private else R.string.ui_screens_tabs_new,
                        ),
                    )
                },
            )
        },
    ) { padding ->
        if (tabs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                if (state.showPrivate) {
                    EmptyState(
                        icon = Icons.Default.VisibilityOff,
                        title = stringResource(R.string.ui_screens_tabs_private_empty_title),
                        body = stringResource(R.string.ui_screens_tabs_private_empty_body),
                    )
                } else {
                    EmptyState(
                        icon = Icons.Default.Tab,
                        title = stringResource(R.string.ui_screens_tabs_empty_title),
                        body = stringResource(R.string.ui_screens_tabs_empty_body),
                    )
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 160.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 96.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(tabs, key = { it.id }) { tab ->
                    TabCard(tab = tab, onSelect = { onSelect(tab.id) }, onClose = { onCloseTab(tab.id) })
                }
            }
        }
    }

    if (confirmCloseAll) {
        ConfirmDialog(
            title = stringResource(
                if (state.showPrivate) R.string.ui_screens_tabs_close_all_private_title else R.string.ui_screens_tabs_close_all_title,
            ),
            text = stringResource(R.string.ui_screens_tabs_close_all_body, tabs.size),
            confirmLabel = stringResource(R.string.ui_screens_tabs_close_all_confirm),
            onConfirm = onCloseAll,
            onDismiss = { confirmCloseAll = false },
        )
    }
}

@Composable
private fun TabsMenu(enabled: Boolean, private: Boolean, onCloseAll: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.ui_screens_more_options))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(
                            if (private) R.string.ui_screens_tabs_close_all_private else R.string.ui_screens_tabs_close_all,
                        ),
                    )
                },
                leadingIcon = { Icon(Icons.Default.Close, contentDescription = null) },
                enabled = enabled,
                onClick = {
                    expanded = false
                    onCloseAll()
                },
            )
        }
    }
}

@Composable
private fun TabCard(tab: TabCardModel, onSelect: () -> Unit, onClose: () -> Unit) {
    val title = if (tab.isHome) stringResource(R.string.ui_screens_tabs_home_title) else tab.title
    val selectedDescription = stringResource(R.string.ui_screens_tabs_selected)
    val colors = if (tab.isSelected) {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    } else {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    }
    Card(
        onClick = onSelect,
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                selected = tab.isSelected
                if (tab.isSelected) contentDescription = "$title, $selectedDescription"
            },
        colors = colors,
        border = if (tab.isSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (tab.isSelected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (tab.isMediaPlaying) {
                Icon(
                    Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = stringResource(R.string.ui_screens_tabs_media_playing),
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            IconButton(onClick = onClose) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.ui_screens_tabs_close_tab, title),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1.25f)
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) {
            if (tab.isHome) {
                Icon(
                    Icons.Default.Home,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    siteInitial(tab.url, tab.title),
                    style = MaterialTheme.typography.displayMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (tab.isLoading) {
                LinearProgressIndicator(
                    progress = { tab.progress / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter),
                )
            }
        }
        Text(
            if (tab.isHome) stringResource(R.string.ui_screens_tabs_home_subtitle) else tab.host,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
