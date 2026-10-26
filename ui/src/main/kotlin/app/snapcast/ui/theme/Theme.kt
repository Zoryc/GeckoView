package app.snapcast.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import app.snapcast.core.model.ThemeMode
import app.snapcast.ui.util.findActivity

private val LocalExtraColors = staticCompositionLocalOf { DarkExtraColors }

private val SnapcastShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

private val SnapcastTypography: Typography = Typography().let { base ->
    base.copy(
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

/**
 * The app theme. Dark by default (Material 3 dark scheme with Snapcast's blue logo accent), light for
 * [ThemeMode.LIGHT], following the system for [ThemeMode.SYSTEM]. Dynamic color is intentionally off so
 * the brand accent stays consistent. Also keeps the system bar icon colors in sync with the scheme.
 */
@Composable
fun SnapcastTheme(themeMode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = view.context.findActivity()?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    CompositionLocalProvider(LocalExtraColors provides if (dark) DarkExtraColors else LightExtraColors) {
        MaterialTheme(
            colorScheme = if (dark) DarkColorScheme else LightColorScheme,
            typography = SnapcastTypography,
            shapes = SnapcastShapes,
            content = content,
        )
    }
}

/** Access to [SnapcastExtraColors] of the current theme. */
object SnapcastThemeExtras {
    val colors: SnapcastExtraColors
        @Composable
        @ReadOnlyComposable
        get() = LocalExtraColors.current
}
