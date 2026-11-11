package com.knte.preview.ui.product

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** HTML_UI_BASELINE: system serif fallback only; no downloaded or bundled font claim. */
internal object ProductInk {
    val Paper = Color(0xFFFBF7EC)
    val Paper2 = Color(0xFFF5EFDF)
    val Ink = Color(0xFF2B2620)
    val Secondary = Color(0xFF6E6558)
    val Line = Color(0xFFE4DAC4)
    val Accent = Color(0xFF3A5A78)
    val Accent2 = Color(0xFF8C4A3C)
    val Dark = Color(0xFF0A0A0C)
    val DarkInk = Color(0xFFE6E6F0)
}

private val paperTypography = Typography(
    headlineLarge = TextStyle(fontFamily = FontFamily.Serif, fontSize = 25.sp,
        fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.Serif, fontSize = 19.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.Serif, fontSize = 16.sp, lineHeight = 24.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.Serif, fontSize = 14.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.Serif, fontSize = 13.5.sp, lineHeight = 23.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 11.sp, lineHeight = 18.7.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.Serif, fontSize = 13.5.sp, letterSpacing = 1.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 11.sp),
)

@Composable
internal fun ProductTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = ProductInk.Accent, onPrimary = ProductInk.Paper,
            secondary = ProductInk.Accent2, onSecondary = ProductInk.Paper,
            background = ProductInk.Paper, onBackground = ProductInk.Ink,
            surface = ProductInk.Paper, onSurface = ProductInk.Ink,
            surfaceVariant = ProductInk.Paper2, onSurfaceVariant = ProductInk.Secondary,
            outline = ProductInk.Secondary, outlineVariant = ProductInk.Line,
        ),
        typography = paperTypography,
        shapes = Shapes(extraSmall = RoundedCornerShape(3.dp), small = RoundedCornerShape(3.dp),
            medium = RoundedCornerShape(3.dp), large = RoundedCornerShape(6.dp),
            extraLarge = RoundedCornerShape(6.dp)),
        content = content,
    )
}
