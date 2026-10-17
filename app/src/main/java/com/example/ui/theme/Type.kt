package com.example.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// System sans (Roboto on Android) — the same default React Native renders with,
// and it ships every weight natively so bold text is never synthesized.
val AppSansFamily = FontFamily.Default
val AppDisplayFamily = FontFamily.Default

private fun style(size: Int, line: Int, weight: FontWeight, tracking: Double = 0.0) = TextStyle(
    fontFamily = AppSansFamily,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.sp
)

val Typography = Typography(
    displaySmall = style(34, 40, FontWeight.Bold, -0.8),     // large screen titles
    headlineLarge = style(30, 36, FontWeight.Bold, -0.6),    // hero amounts
    headlineMedium = style(26, 32, FontWeight.Bold, -0.4),
    headlineSmall = style(22, 28, FontWeight.Bold, -0.3),
    titleLarge = style(19, 25, FontWeight.SemiBold, -0.2),
    titleMedium = style(16, 22, FontWeight.SemiBold, -0.1),
    titleSmall = style(14, 20, FontWeight.SemiBold),
    bodyLarge = style(16, 24, FontWeight.Normal),
    bodyMedium = style(14, 20, FontWeight.Normal),
    bodySmall = style(13, 18, FontWeight.Normal),
    labelLarge = style(14, 20, FontWeight.SemiBold),
    labelMedium = style(12, 16, FontWeight.SemiBold, 0.1),
    labelSmall = style(11, 14, FontWeight.Medium, 0.2)
)
