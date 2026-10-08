package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme =
  darkColorScheme(
    primary = BrandSkyPrimary,
    onPrimary = Color(0xFF0F172A),
    primaryContainer = Color(0xFF1E3A5F),
    onPrimaryContainer = PastelSkyLight,
    secondary = PastelMauve,
    onSecondary = Color(0xFF2A153A),
    secondaryContainer = Color(0xFF3F2B52),
    onSecondaryContainer = PastelMauve,
    tertiary = PastelPinkCarnation,
    onTertiary = Color(0xFF4C0A24),
    tertiaryContainer = Color(0xFF651B38),
    onTertiaryContainer = PastelPinkLight,
    background = PolishBackgroundDark,
    onBackground = BrandTextPrimaryDark,
    surface = PolishSurfaceDark,
    onSurface = BrandTextPrimaryDark,
    surfaceVariant = PolishSurfaceVariantDark,
    onSurfaceVariant = BrandTextSecondaryDark,
    outline = PolishOutlineDark,
    outlineVariant = Color(0xFF24334A),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF93000A)
  )

private val LightColorScheme =
  lightColorScheme(
    primary = Color(0xFF2563EB),        // Accessible royal blue anchor harmonious with PastelSky
    onPrimary = Color.White,
    primaryContainer = PastelSkyLight,   // #BDE0FE soft sky container
    onPrimaryContainer = Color(0xFF1E3A8A),
    secondary = Color(0xFF7C3AED),      // Accessible violet anchor harmonious with PastelMauve
    onSecondary = Color.White,
    secondaryContainer = PastelMauve,   // #CDB4DB soft mauve container
    onSecondaryContainer = Color(0xFF3B1E6D),
    tertiary = Color(0xFFDB2777),       // Accessible rose anchor harmonious with PastelPink
    onTertiary = Color.White,
    tertiaryContainer = PastelPinkLight, // #FFC8DD soft pink container
    onTertiaryContainer = Color(0xFF701A45),
    background = PolishBackgroundLight,
    onBackground = BrandTextPrimaryLight,
    surface = PolishSurfaceLight,
    onSurface = BrandTextPrimaryLight,
    surfaceVariant = PolishSurfaceVariantLight,
    onSurfaceVariant = BrandTextSecondaryLight,
    outline = PolishOutlineLight,
    outlineVariant = PolishOutlineVariantLight,
    error = DebtRed,
    errorContainer = DebtRedContainer,
    onError = Color.White,
    onErrorContainer = Color(0xFF410002)
  )

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  // Use our curated Professional Polish theme consistently
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit,
) {
  val colorScheme =
    when {
      dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
      }

      darkTheme -> DarkColorScheme
      else -> LightColorScheme
    }

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
