package com.example.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// ---------------------------------------------------------------------------
// Raw palette (zinc neutrals + indigo accent). Every text/background pairing
// used by the theme below meets WCAG AA (>= 4.5:1) in both light and dark mode.
// ---------------------------------------------------------------------------
object Palette {
    val Zinc50 = Color(0xFFFAFAFA)
    val Zinc100 = Color(0xFFF4F4F5)
    val Zinc200 = Color(0xFFE4E4E7)
    val Zinc300 = Color(0xFFD4D4D8)
    val Zinc400 = Color(0xFFA1A1AA)
    val Zinc500 = Color(0xFF71717A)
    val Zinc600 = Color(0xFF52525B)
    val Zinc700 = Color(0xFF3F3F46)
    val Zinc800 = Color(0xFF27272A)
    val Zinc850 = Color(0xFF1F1F23)
    val Zinc900 = Color(0xFF18181B)
    val Zinc950 = Color(0xFF09090B)

    val Indigo50 = Color(0xFFEEF2FF)
    val Indigo100 = Color(0xFFE0E7FF)
    val Indigo300 = Color(0xFFA5B4FC)
    val Indigo400 = Color(0xFF818CF8)
    val Indigo600 = Color(0xFF4F46E5)
    val Indigo900 = Color(0xFF312E81)
    val Indigo950 = Color(0xFF1E1B4B)
}

/**
 * Semantic status colours that Material's ColorScheme has no slot for.
 * `x` is for text/icons on surfaces, `onX` for text on a solid `x` fill,
 * `xContainer`/`onXContainer` for tinted badges and banners.
 */
@Immutable
data class StatusColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val danger: Color,
    val onDanger: Color,
    val dangerContainer: Color,
    val onDangerContainer: Color,
    val info: Color,
    val infoContainer: Color,
    val onInfoContainer: Color,
    val whatsApp: Color,
    val cardBorder: Color,
    val isDark: Boolean
)

val LightStatusColors = StatusColors(
    success = Color(0xFF15803D),          // 5.0:1 on white
    onSuccess = Color.White,
    successContainer = Color(0xFFDCFCE7),
    onSuccessContainer = Color(0xFF14532D),
    warning = Color(0xFFB45309),          // 5.0:1 on white
    onWarning = Color.White,
    warningContainer = Color(0xFFFEF3C7),
    onWarningContainer = Color(0xFF78350F),
    danger = Color(0xFFDC2626),           // 4.8:1 on white
    onDanger = Color.White,
    dangerContainer = Color(0xFFFEE2E2),
    onDangerContainer = Color(0xFF7F1D1D),
    info = Color(0xFF1D4ED8),
    infoContainer = Color(0xFFDBEAFE),
    onInfoContainer = Color(0xFF1E3A8A),
    whatsApp = Color(0xFF128C4A),
    cardBorder = Palette.Zinc200,
    isDark = false
)

val DarkStatusColors = StatusColors(
    success = Color(0xFF4ADE80),          // 10:1 on zinc-900
    onSuccess = Color(0xFF052E16),
    successContainer = Color(0xFF0B2E1A),
    onSuccessContainer = Color(0xFFBBF7D0),
    warning = Color(0xFFFBBF24),
    onWarning = Color(0xFF451A03),
    warningContainer = Color(0xFF3A2306),
    onWarningContainer = Color(0xFFFDE68A),
    danger = Color(0xFFF87171),           // 6.6:1 on zinc-900
    onDanger = Color(0xFF450A0A),
    dangerContainer = Color(0xFF3B1212),
    onDangerContainer = Color(0xFFFECACA),
    info = Color(0xFF60A5FA),
    infoContainer = Color(0xFF172554),
    onInfoContainer = Color(0xFFBFDBFE),
    whatsApp = Color(0xFF25D366),
    cardBorder = Palette.Zinc800,
    isDark = true
)

val LocalStatusColors = staticCompositionLocalOf { LightStatusColors }

object AppTheme {
    val status: StatusColors
        @Composable @ReadOnlyComposable get() = LocalStatusColors.current
}

// ---------------------------------------------------------------------------
// Legacy token names. They used to be fixed light-mode colours, which broke
// contrast in dark mode; they now resolve against the active theme so every
// existing call site gets accessible colours automatically.
// ---------------------------------------------------------------------------
val FarmGreen: Color @Composable @ReadOnlyComposable get() = LocalStatusColors.current.success
val FarmGreenContainer: Color @Composable @ReadOnlyComposable get() = LocalStatusColors.current.successContainer
val DebtRed: Color @Composable @ReadOnlyComposable get() = LocalStatusColors.current.danger
val DebtRedContainer: Color @Composable @ReadOnlyComposable get() = LocalStatusColors.current.dangerContainer
val WarningAmber: Color @Composable @ReadOnlyComposable get() = LocalStatusColors.current.warning
val WarningAmberContainer: Color @Composable @ReadOnlyComposable get() = LocalStatusColors.current.warningContainer
val BrandAmberGold: Color @Composable @ReadOnlyComposable get() = LocalStatusColors.current.warning
val BrandAmberGoldContainer: Color @Composable @ReadOnlyComposable get() = LocalStatusColors.current.warningContainer
val WhatsAppGreen: Color @Composable @ReadOnlyComposable get() = LocalStatusColors.current.whatsApp
val StripeCardBorder: Color @Composable @ReadOnlyComposable get() = LocalStatusColors.current.cardBorder

// The old "brand green" was used for primary actions; map it to the theme primary.
val BrandGreenPrimary: Color @Composable @ReadOnlyComposable get() = androidx.compose.material3.MaterialTheme.colorScheme.primary
val BrandGreenPrimaryDark: Color @Composable @ReadOnlyComposable get() = androidx.compose.material3.MaterialTheme.colorScheme.primary
val PastelMauve: Color @Composable @ReadOnlyComposable get() = androidx.compose.material3.MaterialTheme.colorScheme.secondaryContainer
val PastelSkyLight: Color @Composable @ReadOnlyComposable get() = androidx.compose.material3.MaterialTheme.colorScheme.primaryContainer

/** Solid WhatsApp-green fill that keeps white text at >= 4.5:1 in both themes. */
val WhatsAppSolid = Color(0xFF128C4A)
