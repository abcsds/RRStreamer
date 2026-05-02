package io.github.abcsds.rrstreamer.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.abcsds.rrstreamer.R

// ---------------------------------------------------------------
// Palette — "Real-Time Operations" (Dark/OLED) per ui-ux-pro-max
// ---------------------------------------------------------------
object Tokens {
    val Background        = Color(0xFF050810)
    val Surface           = Color(0xFF0D1320)
    val SurfaceHover      = Color(0xFF131B2C)
    val SurfaceElev       = Color(0xFF131A2A)
    val Rule              = Color(0xFF1B2336)
    val RuleSoft          = Color(0xFF11172A)

    val Text              = Color(0xFFE8EDF5)
    val TextSoft          = Color(0xFF8A93A8)
    val TextFaint         = Color(0xFF4F566C)

    val Hr                = Color(0xFFF43F5E)
    val HrSoft            = Color(0xFFFB7185)
    val HrGlow            = Color(0x59F43F5E)

    val Rr                = Color(0xFF2DD4BF)
    val RrSoft            = Color(0xFF5EEAD4)
    val RrGlow            = Color(0x4D2DD4BF)

    val Signal            = Color(0xFF34D399)
}

// ---------------------------------------------------------------
// Type — Fira Sans (UI / labels) + Fira Code (numerics / mono)
// "Dashboard Data" pairing per ui-ux-pro-max typography.csv.
// ---------------------------------------------------------------
val FiraSans = FontFamily(
    Font(R.font.fira_sans_regular,  FontWeight.Normal),
    Font(R.font.fira_sans_medium,   FontWeight.Medium),
    Font(R.font.fira_sans_semibold, FontWeight.SemiBold),
    Font(R.font.fira_sans_bold,     FontWeight.Bold),
)
val FiraCode = FontFamily(Font(R.font.fira_code))

private val AppTypography: Typography = Typography().run {
    copy(
        // Display / hero numerics → Fira Code, tabular figures
        displayLarge  = displayLarge.copy(fontFamily = FiraCode, fontWeight = FontWeight.Medium),
        displayMedium = displayMedium.copy(fontFamily = FiraCode, fontWeight = FontWeight.Medium),
        displaySmall  = displaySmall.copy(fontFamily = FiraCode, fontWeight = FontWeight.Medium),

        headlineLarge  = headlineLarge.copy(fontFamily = FiraSans, fontWeight = FontWeight.SemiBold),
        headlineMedium = headlineMedium.copy(fontFamily = FiraSans, fontWeight = FontWeight.SemiBold),
        headlineSmall  = headlineSmall.copy(fontFamily = FiraSans, fontWeight = FontWeight.Medium),

        titleLarge  = titleLarge.copy(fontFamily = FiraSans, fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontFamily = FiraSans, fontWeight = FontWeight.Medium),
        titleSmall  = titleSmall.copy(fontFamily = FiraSans, fontWeight = FontWeight.Medium),

        bodyLarge  = bodyLarge.copy(fontFamily = FiraSans),
        bodyMedium = bodyMedium.copy(fontFamily = FiraSans),
        bodySmall  = bodySmall.copy(fontFamily = FiraSans),

        // Labels are mono small caps in this design
        labelLarge  = labelLarge.copy(fontFamily = FiraCode, letterSpacing = 0.18.sp),
        labelMedium = labelMedium.copy(fontFamily = FiraCode, letterSpacing = 0.18.sp),
        labelSmall  = labelSmall.copy(fontFamily = FiraCode, letterSpacing = 0.18.sp),
    )
}

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small      = RoundedCornerShape(12.dp),
    medium     = RoundedCornerShape(16.dp),
    large      = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

private val AppColors = darkColorScheme(
    primary       = Tokens.Hr,
    onPrimary     = Tokens.Text,
    secondary     = Tokens.Rr,
    onSecondary   = Tokens.Background,
    background    = Tokens.Background,
    onBackground  = Tokens.Text,
    surface       = Tokens.Surface,
    onSurface     = Tokens.Text,
    surfaceVariant = Tokens.SurfaceElev,
    onSurfaceVariant = Tokens.TextSoft,
    outline       = Tokens.Rule,
    outlineVariant = Tokens.RuleSoft,
)

// Helpers used widely
val MonoTabularLabel = TextStyle(
    fontFamily = FiraCode,
    fontSize = 11.sp,
    color = Tokens.TextSoft,
    letterSpacing = 2.2.sp,
)

@Composable
fun RRStreamerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AppColors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
