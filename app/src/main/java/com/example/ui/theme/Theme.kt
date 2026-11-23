package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

private val KartiDarkColorScheme = darkColorScheme(
    primary = EmeraldPrimary,
    onPrimary = OnEmerald,
    primaryContainer = EmeraldDarkContainer,
    onPrimaryContainer = EmeraldPrimary,
    secondary = CyanSecondary,
    onSecondary = OnCyan,
    secondaryContainer = CyanDarkContainer,
    onSecondaryContainer = CyanSecondary,
    tertiary = AmberWarning,
    onTertiary = MidnightBg,
    tertiaryContainer = AmberDarkContainer,
    onTertiaryContainer = AmberWarning,
    error = CoralError,
    onError = MidnightBg,
    errorContainer = CoralDarkContainer,
    onErrorContainer = CoralError,
    background = MidnightBg,
    onBackground = TextPrimary,
    surface = DeepNavySurface,
    onSurface = TextPrimary,
    surfaceVariant = ElevatedNavyCard,
    onSurfaceVariant = TextSecondary,
    outline = SubtleBorderNavy,
    outlineVariant = ColorTokens.OutlineSubtle
)

private object ColorTokens {
    val OutlineSubtle = SubtleBorderNavy.copy(alpha = 0.55f)
}

val KartiShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun KartiTheme(
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        MaterialTheme(
            colorScheme = KartiDarkColorScheme,
            typography = KartiTypography,
            shapes = KartiShapes,
            content = content
        )
    }
}
