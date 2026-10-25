package app.snapcast.ui.browser

import android.content.Context
import android.view.View
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TheaterComedy
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.snapcast.browser.SecurityLevel
import app.snapcast.browser.TabState
import app.snapcast.core.browser.UrlInputResolver
import app.snapcast.ui.R
import app.snapcast.ui.theme.SnapcastThemeExtras

/** Hoisted state of the address field so the screen can react to editing (suggestions, back). */
@Stable
internal class AddressBarState {
    var editing by mutableStateOf(false)
    var value by mutableStateOf(TextFieldValue(""))
}

/**
 * Top bar: private-mode mask, address field (security indicator, URL / title, clear), Cast button,
 * tab counter and overflow menu. Private tabs get the purple tint.
 */
@Composable
internal fun BrowserTopBar(
    tab: TabState?,
    tabCount: Int,
    state: AddressBarState,
    showCastButton: Boolean,
    createCastButton: (Context) -> View,
    onQueryChange: (String) -> Unit,
    onSubmit: (String) -> Unit,
    onOpenTabs: () -> Unit,
    menu: @Composable (expanded: Boolean, onDismiss: () -> Unit) -> Unit,
    modifier: Modifier = Modifier,
) {
    val extras = SnapcastThemeExtras.colors
    val isPrivate = tab?.isPrivate == true
    val barColor by animateColorAsState(
        targetValue = if (isPrivate) extras.privateBar else MaterialTheme.colorScheme.surfaceContainer,
        label = "barColor",
    )
    val contentColor by animateColorAsState(
        targetValue = if (isPrivate) extras.onPrivateBar else MaterialTheme.colorScheme.onSurface,
        label = "barContent",
    )
    val fieldColor by animateColorAsState(
        targetValue = if (isPrivate) extras.privateField else MaterialTheme.colorScheme.surfaceContainerHighest,
        label = "fieldColor",
    )
    var menuExpanded by remember { mutableStateOf(false) }

    Surface(color = barColor, contentColor = contentColor, modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(start = 8.dp, end = 2.dp, top = 6.dp, bottom = 6.dp)
                .animateContentSize(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AnimatedVisibility(visible = isPrivate && !state.editing) {
                Icon(
                    imageVector = Icons.Filled.TheaterComedy,
                    contentDescription = stringResource(R.string.ui_browser_private_tab),
                    modifier = Modifier.padding(start = 4.dp, end = 8.dp),
                )
            }
            AddressField(
                tab = tab,
                state = state,
                fieldColor = fieldColor,
                onQueryChange = onQueryChange,
                onSubmit = onSubmit,
                modifier = Modifier.weight(1f),
            )
            AnimatedVisibility(
                visible = !state.editing,
                enter = fadeIn() + expandHorizontally(),
                exit = fadeOut() + shrinkHorizontally(),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (showCastButton) {
                        AndroidView(
                            factory = createCastButton,
                            modifier = Modifier.size(48.dp),
                        )
                    }
                    TabCounterButton(count = tabCount, isPrivate = isPrivate, onClick = onOpenTabs)
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.ui_browser_menu))
                        }
                        menu(menuExpanded) { menuExpanded = false }
                    }
                }
            }
        }
    }
}

@Composable
private fun AddressField(
    tab: TabState?,
    state: AddressBarState,
    fieldColor: Color,
    onQueryChange: (String) -> Unit,
    onSubmit: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val url = tab?.takeUnless { it.isHome }?.url.orEmpty()
    val display = remember(url) { if (url.isEmpty()) "" else UrlInputResolver.displayText(url) }
    val title = tab?.takeUnless { it.isHome }?.title?.takeIf { it.isNotBlank() && it != url }

    // Mirror the tab while not editing.
    LaunchedEffect(display, state.editing) {
        if (!state.editing) state.value = TextFieldValue(display)
    }
    // Entering edit mode shows the full URL selected. Re-applied after a frame because the tap that
    // focused the field places the cursor after the focus change.
    LaunchedEffect(state.editing) {
        if (state.editing) {
            state.value = TextFieldValue(url, TextRange(0, url.length))
            onQueryChange("")
            withFrameNanos { }
            if (state.value.text == url) state.value = TextFieldValue(url, TextRange(0, url.length))
        }
    }

    val textColor = LocalContentColor.current
    val textStyle = MaterialTheme.typography.bodyLarge.copy(color = textColor)
    Row(
        modifier = modifier
            .heightIn(min = 48.dp)
            .background(fieldColor, RoundedCornerShape(24.dp))
            .padding(start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state.editing || tab == null || tab.isHome) {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = null,
                tint = textColor.copy(alpha = 0.7f),
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .size(20.dp),
            )
        } else {
            SecurityIndicator(security = tab.security)
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 4.dp),
        ) {
            BasicTextField(
                value = state.value,
                onValueChange = { value ->
                    if (value.text != state.value.text) onQueryChange(value.text)
                    state.value = value
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { focus -> if (focus.isFocused != state.editing) state.editing = focus.isFocused },
                singleLine = true,
                textStyle = textStyle,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Go,
                ),
                keyboardActions = KeyboardActions(onGo = { onSubmit(state.value.text) }),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (state.value.text.isEmpty()) {
                            Text(
                                text = stringResource(R.string.ui_browser_address_hint),
                                style = textStyle,
                                color = textColor.copy(alpha = 0.6f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        inner()
                    }
                },
            )
            AnimatedVisibility(
                visible = !state.editing && title != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Text(
                    text = title.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = textColor.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (state.editing && state.value.text.isNotEmpty()) {
            IconButton(
                onClick = {
                    state.value = TextFieldValue("")
                    onQueryChange("")
                },
            ) {
                Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.ui_browser_clear))
            }
        }
    }
}

/** HTTPS lock (or warning) with a small popup explaining the connection security. */
@Composable
private fun SecurityIndicator(security: SecurityLevel) {
    var expanded by remember { mutableStateOf(false) }
    val extras = SnapcastThemeExtras.colors
    val (icon, tint) = when (security) {
        SecurityLevel.SECURE -> Icons.Filled.Lock to extras.secure
        SecurityLevel.INSECURE, SecurityLevel.BROKEN -> Icons.Filled.WarningAmber to MaterialTheme.colorScheme.error
        SecurityLevel.NONE -> null to LocalContentColor.current
    }
    if (icon == null) {
        Spacer(Modifier.width(12.dp))
        return
    }
    val (titleRes, bodyRes) = when (security) {
        SecurityLevel.SECURE -> R.string.ui_browser_security_secure_title to R.string.ui_browser_security_secure_body
        SecurityLevel.BROKEN -> R.string.ui_browser_security_broken_title to R.string.ui_browser_security_broken_body
        else -> R.string.ui_browser_security_insecure_title to R.string.ui_browser_security_insecure_body
    }
    Box {
        IconButton(onClick = { expanded = true }, modifier = Modifier.size(40.dp)) {
            Icon(
                imageVector = icon,
                contentDescription = stringResource(titleRes),
                tint = tint,
                modifier = Modifier.size(18.dp),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Row(
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(titleRes), style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(bodyRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Square tab counter in the style of mobile browsers. */
@Composable
private fun TabCounterButton(count: Int, isPrivate: Boolean, onClick: () -> Unit) {
    val description = stringResource(
        if (isPrivate) R.string.ui_browser_tabs_private_description else R.string.ui_browser_tabs_description,
        count,
    )
    IconButton(onClick = onClick, modifier = Modifier.semantics { contentDescription = description }) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .border(2.dp, LocalContentColor.current, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (count > 99) ":D" else count.toString(),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
            )
        }
    }
}
