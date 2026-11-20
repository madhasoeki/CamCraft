package org.artcraft.camcraft.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * ArtCraft Studio v2 color tokens (WCAG AA >= 4.5:1 on studio dark surfaces).
 */
object ArtCraftColors {
    val BgCarbon = Color(0xFF0D0F14)
    val BgStage = Color(0xFF080A0E)
    val SurfaceCard = Color(0xFF161A22)
    val BorderSubtle = Color(0xFF252B37)
    val SurfaceCardHighlight = Color(0xFF1F2530)
    val AccentBlue = Color(0xFF3B82F6)
    val StatusEmerald = Color(0xFF10B981)
    val StatusAmber = Color(0xFFF59E0B)
    val StatusRose = Color(0xFFEF4444)
    val TextPrimary = Color(0xFFF8FAFC)
    val TextSecondary = Color(0xFFCBD5E1)
    val TextMuted = Color(0xFF8B95A7)
}

private val ArtCraftDarkColorScheme = darkColorScheme(
    primary = ArtCraftColors.AccentBlue,
    onPrimary = ArtCraftColors.TextPrimary,
    secondary = ArtCraftColors.StatusEmerald,
    onSecondary = ArtCraftColors.BgStage,
    background = ArtCraftColors.BgCarbon,
    onBackground = ArtCraftColors.TextPrimary,
    surface = ArtCraftColors.SurfaceCard,
    onSurface = ArtCraftColors.TextPrimary,
    surfaceVariant = ArtCraftColors.SurfaceCardHighlight,
    onSurfaceVariant = ArtCraftColors.TextSecondary,
    outline = ArtCraftColors.BorderSubtle,
    error = ArtCraftColors.StatusRose,
    onError = ArtCraftColors.TextPrimary,
)

val ArtCraftTypography = Typography(
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.2).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.2.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 18.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.4.sp,
    ),
)

/**
 * Tabular monospace text style for live telemetry counters (`FPS`, `Mbps`, `IP:Port`).
 */
val TabularMonoStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontFeatureSettings = "tnum",
)

@Composable
fun CamCraftTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ArtCraftDarkColorScheme,
        typography = ArtCraftTypography,
        content = content,
    )
}
