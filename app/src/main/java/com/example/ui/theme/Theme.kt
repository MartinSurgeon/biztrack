package com.example.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

private val LightColorScheme = lightColorScheme(
    primary = Palette.Indigo600,                 // 6.3:1 against white, white text 6.3:1
    onPrimary = Color.White,
    primaryContainer = Palette.Indigo50,
    onPrimaryContainer = Palette.Indigo900,
    inversePrimary = Palette.Indigo400,
    secondary = Palette.Zinc900,
    onSecondary = Color.White,
    secondaryContainer = Palette.Zinc100,
    onSecondaryContainer = Palette.Zinc900,
    tertiary = Color(0xFF0F766E),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFCCFBF1),
    onTertiaryContainer = Color(0xFF134E4A),
    background = Color(0xFFEEF0F6),              // cool tint so clay cards stand out; zinc-600 text 7:1
    onBackground = Palette.Zinc950,
    surface = Color.White,
    onSurface = Palette.Zinc950,                 // 19.8:1
    surfaceVariant = Palette.Zinc100,
    onSurfaceVariant = Palette.Zinc600,          // 7.7:1 on white, 7.0:1 on zinc-100
    surfaceTint = Color.Transparent,
    inverseSurface = Palette.Zinc900,
    inverseOnSurface = Palette.Zinc50,
    outline = Palette.Zinc300,
    outlineVariant = Palette.Zinc200,
    scrim = Color.Black,
    surfaceBright = Color.White,
    surfaceDim = Palette.Zinc200,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color.White,
    surfaceContainerHigh = Palette.Zinc50,
    surfaceContainerHighest = Palette.Zinc100,
    error = LightStatusColors.danger,
    onError = Color.White,
    errorContainer = LightStatusColors.dangerContainer,
    onErrorContainer = LightStatusColors.onDangerContainer
)

private val DarkColorScheme = darkColorScheme(
    primary = Palette.Indigo400,                 // 6.4:1 on zinc-900
    onPrimary = Palette.Zinc950,                 // 7.2:1 on indigo-400
    primaryContainer = Palette.Indigo950,
    onPrimaryContainer = Palette.Indigo100,
    inversePrimary = Palette.Indigo600,
    secondary = Palette.Zinc100,
    onSecondary = Palette.Zinc950,
    secondaryContainer = Palette.Zinc800,
    onSecondaryContainer = Palette.Zinc50,
    tertiary = Color(0xFF5EEAD4),
    onTertiary = Color(0xFF042F2E),
    tertiaryContainer = Color(0xFF134E4A),
    onTertiaryContainer = Color(0xFFCCFBF1),
    background = Palette.Zinc950,
    onBackground = Palette.Zinc50,
    surface = Palette.Zinc900,
    onSurface = Palette.Zinc50,                  // 17:1
    surfaceVariant = Palette.Zinc800,
    onSurfaceVariant = Palette.Zinc400,          // 7.0:1 on zinc-900, 5.6:1 on zinc-800
    surfaceTint = Color.Transparent,
    inverseSurface = Palette.Zinc100,
    inverseOnSurface = Palette.Zinc900,
    outline = Palette.Zinc700,
    outlineVariant = Palette.Zinc800,
    scrim = Color.Black,
    surfaceBright = Palette.Zinc800,
    surfaceDim = Palette.Zinc950,
    surfaceContainerLowest = Palette.Zinc950,
    surfaceContainerLow = Palette.Zinc900,
    surfaceContainer = Palette.Zinc900,
    surfaceContainerHigh = Palette.Zinc850,
    surfaceContainerHighest = Palette.Zinc800,
    error = DarkStatusColors.danger,
    onError = DarkStatusColors.onDanger,
    errorContainer = DarkStatusColors.dangerContainer,
    onErrorContainer = DarkStatusColors.onDangerContainer
)

val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val statusColors = if (darkTheme) DarkStatusColors else LightStatusColors

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(LocalStatusColors provides statusColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = AppShapes,
            content = content
        )
    }
}
