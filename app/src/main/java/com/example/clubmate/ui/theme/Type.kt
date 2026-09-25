package com.example.clubmate.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private fun style(size: Int, line: Int, weight: FontWeight, tracking: Double = 0.0) = TextStyle(
    fontFamily = AppFontFamily,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.sp
)

/** A compact, calm type scale: few sizes, weight does the work. */
val AppTypography = Typography(
    displaySmall = style(32, 40, FontWeight.Bold, -0.5),
    headlineLarge = style(30, 36, FontWeight.Bold, -0.4),
    headlineMedium = style(26, 32, FontWeight.Bold, -0.3),
    headlineSmall = style(22, 28, FontWeight.SemiBold, -0.2),
    titleLarge = style(19, 26, FontWeight.SemiBold, -0.1),
    titleMedium = style(16, 22, FontWeight.SemiBold),
    titleSmall = style(14, 20, FontWeight.SemiBold),
    bodyLarge = style(16, 23, FontWeight.Normal),
    bodyMedium = style(14, 20, FontWeight.Normal),
    bodySmall = style(12, 16, FontWeight.Normal, 0.1),
    labelLarge = style(15, 20, FontWeight.SemiBold),
    labelMedium = style(12, 16, FontWeight.Medium, 0.2),
    labelSmall = style(11, 14, FontWeight.Medium, 0.3)
)
