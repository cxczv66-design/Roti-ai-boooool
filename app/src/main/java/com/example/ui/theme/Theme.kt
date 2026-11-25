package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

data class GradientPalette(
    val primaryStart: Color,
    val primaryEnd: Color,
    val dangerStart: Color,
    val dangerEnd: Color
)

val LocalGradientPalette = staticCompositionLocalOf {
    GradientPalette(
        primaryStart = GradientCyan,
        primaryEnd = GradientIndigo,
        dangerStart = DisconnectRose,
        dangerEnd = DisconnectCrimson
    )
}

private val DarkColorScheme = darkColorScheme(
    primary = CyanPulse,
    onPrimary = OnCyan,
    primaryContainer = CyanContainer,
    onPrimaryContainer = CyanPulse,
    secondary = IndigoAccent,
    onSecondary = Color.White,
    secondaryContainer = IndigoContainer,
    onSecondaryContainer = Color(0xFFC7D2FE),
    tertiary = EmeraldActive,
    onTertiary = OnCyan,
    tertiaryContainer = EmeraldContainer,
    onTertiaryContainer = EmeraldActive,
    background = ObsidianBackground,
    onBackground = TextPrimary,
    surface = SlateSurface,
    onSurface = TextPrimary,
    surfaceVariant = SlateSurfaceVariant,
    onSurfaceVariant = TextSecondary,
    surfaceContainerHigh = SlateElevated,
    outline = SlateOutline,
    outlineVariant = SlateOutline,
    error = CoralError,
    onError = Color.White,
    errorContainer = CoralContainer,
    onErrorContainer = CoralError
)

private val LightColorScheme = lightColorScheme(
    primary = LightGradientCyan,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD0F4FC),
    onPrimaryContainer = Color(0xFF00222B),
    secondary = LightGradientIndigo,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0E7FF),
    onSecondaryContainer = Color(0xFF1E1B4B),
    tertiary = Color(0xFF059669),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD1FAE5),
    onTertiaryContainer = Color(0xFF064E3B),
    background = LightBackground,
    onBackground = LightTextPrimary,
    surface = LightSurface,
    onSurface = LightTextPrimary,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightTextSecondary,
    surfaceContainerHigh = LightSurfaceVariant,
    outline = LightOutline,
    outlineVariant = LightOutline,
    error = CoralError,
    onError = Color.White,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D)
)

private val EntityBodyShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp)
)

@Composable
fun EntityBodyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val gradients = if (darkTheme) {
        GradientPalette(
            primaryStart = GradientCyan,
            primaryEnd = GradientIndigo,
            dangerStart = DisconnectRose,
            dangerEnd = DisconnectCrimson
        )
    } else {
        GradientPalette(
            primaryStart = LightGradientCyan,
            primaryEnd = LightGradientIndigo,
            dangerStart = DisconnectRose,
            dangerEnd = DisconnectCrimson
        )
    }

    CompositionLocalProvider(LocalGradientPalette provides gradients) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = EntityBodyTypography,
            shapes = EntityBodyShapes,
            content = content
        )
    }
}
