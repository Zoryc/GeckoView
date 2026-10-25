package app.snapcast.ui.browser

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.snapcast.core.browser.UrlInputResolver
import app.snapcast.ui.R

/** Dropdown of history / bookmark matches under the address bar while editing. */
@Composable
internal fun SuggestionsPanel(
    visible: Boolean,
    suggestions: List<AddressSuggestion>,
    onPick: (AddressSuggestion) -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible && suggestions.isNotEmpty(),
        modifier = modifier,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 3.dp,
            shadowElevation = 8.dp,
        ) {
            LazyColumn(modifier = Modifier.heightIn(max = 380.dp)) {
                items(suggestions, key = { it.url }) { suggestion ->
                    ListItem(
                        headlineContent = {
                            Text(suggestion.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        supportingContent = {
                            Text(
                                text = UrlInputResolver.displayText(suggestion.url),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        },
                        leadingContent = {
                            Icon(
                                imageVector = if (suggestion.isBookmark) Icons.Filled.Bookmark else Icons.Filled.History,
                                contentDescription = stringResource(
                                    if (suggestion.isBookmark) {
                                        R.string.ui_browser_suggestion_bookmark
                                    } else {
                                        R.string.ui_browser_suggestion_history
                                    },
                                ),
                            )
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable { onPick(suggestion) },
                    )
                }
            }
        }
    }
}
