package app.snapcast.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.snapcast.core.util.UrlUtils
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade

private val AvatarPalette = listOf(
    Color(0xFFEF6C57),
    Color(0xFF26A69A),
    Color(0xFF5C6BC0),
    Color(0xFFFFA726),
    Color(0xFFAB47BC),
    Color(0xFF29B6F6),
    Color(0xFF66BB6A),
    Color(0xFFEC407A),
)

/** First letter/digit of the site's host ("youtube.com" -> "Y"), or of [fallback]. */
internal fun avatarLetter(url: String, fallback: String): String {
    val host = UrlUtils.hostOf(url)?.removePrefix("www.").orEmpty()
    val source = host.ifBlank { fallback }
    return source.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "•"
}

/**
 * Round site avatar: a colored letter tile with the site's favicon drawn over it once it loads
 * (the letter stays visible when there is no favicon).
 */
@Composable
fun SiteAvatar(url: String, title: String, modifier: Modifier = Modifier, size: Dp = 48.dp, loadFavicon: Boolean = true) {
    val host = remember(url) { UrlUtils.hostOf(url) }
    val letter = remember(url, title) { avatarLetter(url, title) }
    val color = remember(host, title) { AvatarPalette[((host ?: title).hashCode() and 0x7FFFFFFF) % AvatarPalette.size] }
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(color),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = letter,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = (size.value * 0.42f).sp,
        )
        if (loadFavicon && host != null && UrlUtils.isHttp(url)) {
            val scheme = if (url.startsWith("http://", ignoreCase = true)) "http" else "https"
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data("$scheme://$host/favicon.ico")
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .size(size * 0.6f)
                    .clip(CircleShape),
            )
        }
    }
}

/** An extension's toolbar icon, or a generic puzzle piece when it has none. */
@Composable
fun ExtensionIcon(icon: Bitmap?, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    if (icon != null) {
        val image = remember(icon) { icon.asImageBitmap() }
        Image(bitmap = image, contentDescription = null, modifier = modifier.size(size))
    } else {
        Icon(
            imageVector = Icons.Filled.Extension,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.size(size),
        )
    }
}
