package app.snapcast.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Snapcast palette: a warm coral accent ("cast beam") on cool graphite surfaces, with a cyan secondary.

internal val DarkColorScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFFFF8A65),
    onPrimary = Color(0xFF3E0D00),
    primaryContainer = Color(0xFF7C2D12),
    onPrimaryContainer = Color(0xFFFFDBCF),
    inversePrimary = Color(0xFFB4441F),
    secondary = Color(0xFF7DD3E8),
    onSecondary = Color(0xFF00363F),
    secondaryContainer = Color(0xFF004E5B),
    onSecondaryContainer = Color(0xFFA9EDFF),
    tertiary = Color(0xFFC9B8FF),
    onTertiary = Color(0xFF2F1A63),
    tertiaryContainer = Color(0xFF46327B),
    onTertiaryContainer = Color(0xFFE7DEFF),
    background = Color(0xFF0E1014),
    onBackground = Color(0xFFE3E2E6),
    surface = Color(0xFF0E1014),
    onSurface = Color(0xFFE3E2E6),
    surfaceVariant = Color(0xFF2A2D34),
    onSurfaceVariant = Color(0xFFC3C6CF),
    surfaceTint = Color(0xFFFF8A65),
    inverseSurface = Color(0xFFE3E2E6),
    inverseOnSurface = Color(0xFF2F3035),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF8D9099),
    outlineVariant = Color(0xFF43474E),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF34363C),
    surfaceDim = Color(0xFF0E1014),
    surfaceContainer = Color(0xFF1A1C21),
    surfaceContainerHigh = Color(0xFF24272C),
    surfaceContainerHighest = Color(0xFF2F3237),
    surfaceContainerLow = Color(0xFF16181C),
    surfaceContainerLowest = Color(0xFF090B0E),
)

internal val LightColorScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFFB4441F),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDBCF),
    onPrimaryContainer = Color(0xFF3A0B00),
    inversePrimary = Color(0xFFFF8A65),
    secondary = Color(0xFF006878),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFA9EDFF),
    onSecondaryContainer = Color(0xFF001F25),
    tertiary = Color(0xFF5E4A94),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE7DEFF),
    onTertiaryContainer = Color(0xFF1A0549),
    background = Color(0xFFFAF9FC),
    onBackground = Color(0xFF1A1C1F),
    surface = Color(0xFFFAF9FC),
    onSurface = Color(0xFF1A1C1F),
    surfaceVariant = Color(0xFFDFE2EB),
    onSurfaceVariant = Color(0xFF43474E),
    surfaceTint = Color(0xFFB4441F),
    inverseSurface = Color(0xFF2F3035),
    inverseOnSurface = Color(0xFFF1F0F4),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    outline = Color(0xFF73777F),
    outlineVariant = Color(0xFFC3C6CF),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFAF9FC),
    surfaceDim = Color(0xFFDAD9DD),
    surfaceContainer = Color(0xFFEEEDF1),
    surfaceContainerHigh = Color(0xFFE8E7EB),
    surfaceContainerHighest = Color(0xFFE3E2E6),
    surfaceContainerLow = Color(0xFFF4F3F7),
    surfaceContainerLowest = Color(0xFFFFFFFF),
)

/** Colors that are not part of the Material scheme (private browsing, status tags). */
data class SnapcastExtraColors(
    /** Top bar background of private tabs. */
    val privateBar: Color,
    val onPrivateBar: Color,
    /** Address field background inside the private top bar. */
    val privateField: Color,
    /** "LIVE" tag. */
    val live: Color,
    val onLive: Color,
    /** "DRM" tag and warnings about protected content. */
    val drm: Color,
    val onDrm: Color,
    /** HTTPS lock. */
    val secure: Color,
)

internal val DarkExtraColors = SnapcastExtraColors(
    privateBar = Color(0xFF2B1B4D),
    onPrivateBar = Color(0xFFEADDFF),
    privateField = Color(0xFF3D2A66),
    live = Color(0xFFE53935),
    onLive = Color(0xFFFFFFFF),
    drm = Color(0xFFFFB74D),
    onDrm = Color(0xFF3E2500),
    secure = Color(0xFF81C995),
)

internal val LightExtraColors = SnapcastExtraColors(
    privateBar = Color(0xFFE9DDFF),
    onPrivateBar = Color(0xFF24104F),
    privateField = Color(0xFFF6EEFF),
    live = Color(0xFFD32F2F),
    onLive = Color(0xFFFFFFFF),
    drm = Color(0xFFB26A00),
    onDrm = Color(0xFFFFFFFF),
    secure = Color(0xFF2E7D32),
)
