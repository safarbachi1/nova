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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.core.i18n.EnglishStrings
import com.example.core.i18n.LocalAppStrings
import com.example.core.i18n.PersianStrings
import com.example.domain.model.ThemeMode

data class NovaExtendedColors(
    val glassSurface: Color,
    val glassBorder: Color,
    val canvasTop: Color,
    val canvasBottom: Color,
    val accentBlue: Color,
    val accentViolet: Color,
    val accentCyan: Color,
    val statusConnected: Color,
    val statusWarning: Color,
    val statusError: Color,
    val isDark: Boolean
)

val LocalNovaColors = staticCompositionLocalOf {
    NovaExtendedColors(
        glassSurface = NovaNavyCard,
        glassBorder = NovaBorderGlow,
        canvasTop = Color(0xFF0B1530),
        canvasBottom = NovaNavyDeep,
        accentBlue = NovaElectricBlue,
        accentViolet = NovaViolet,
        accentCyan = NovaCyan,
        statusConnected = NovaEmerald,
        statusWarning = NovaAmber,
        statusError = NovaCoral,
        isDark = true
    )
}

private val DarkNavyColorScheme = darkColorScheme(
    primary = NovaElectricBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF153470),
    onPrimaryContainer = Color(0xFFD6E5FF),
    secondary = NovaViolet,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF2C1E5C),
    onSecondaryContainer = Color(0xFFE7DDFF),
    tertiary = NovaCyan,
    onTertiary = NovaNavyDeep,
    background = NovaNavyDeep,
    onBackground = NovaTextPrimary,
    surface = NovaNavySurface,
    onSurface = NovaTextPrimary,
    surfaceVariant = NovaNavyCard,
    onSurfaceVariant = NovaTextSecondary,
    outline = NovaBorderGlow,
    error = NovaCoral,
    onError = Color.White
)

private val MidnightVioletColorScheme = DarkNavyColorScheme.copy(
    primary = NovaViolet,
    secondary = NovaElectricBlue,
    background = NovaMidnightDeep,
    surface = NovaMidnightSurface,
    surfaceVariant = NovaMidnightCard
)

private val LightNovaColorScheme = lightColorScheme(
    primary = NovaElectricBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE8FF),
    onPrimaryContainer = Color(0xFF0A2457),
    secondary = NovaViolet,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEBE4FF),
    onSecondaryContainer = Color(0xFF2A146B),
    tertiary = Color(0xFF0091B3),
    onTertiary = Color.White,
    background = NovaLightCanvas,
    onBackground = NovaLightTextPrimary,
    surface = NovaLightSurface,
    onSurface = NovaLightTextPrimary,
    surfaceVariant = NovaLightCard,
    onSurfaceVariant = NovaLightTextSecondary,
    outline = NovaLightBorder,
    error = Color(0xFFD32F2F),
    onError = Color.White
)

private val NovaShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun NovaVpnTheme(
    themeMode: ThemeMode = ThemeMode.DARK_NAVY,
    languageCode: String = "fa",
    content: @Composable () -> Unit
) {
    val isPersian = languageCode.lowercase() == "fa"
    val systemDark = isSystemInDarkTheme()
    val useDark = when (themeMode) {
        ThemeMode.DARK_NAVY, ThemeMode.MIDNIGHT_VIOLET -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> systemDark
    }

    val colorScheme = when {
        !useDark -> LightNovaColorScheme
        themeMode == ThemeMode.MIDNIGHT_VIOLET -> MidnightVioletColorScheme
        else -> DarkNavyColorScheme
    }

    val extended = when {
        !useDark -> NovaExtendedColors(
            glassSurface = Color(0xF5FFFFFF),
            glassBorder = NovaLightBorder,
            canvasTop = Color(0xFFE9F0FF),
            canvasBottom = NovaLightCanvas,
            accentBlue = NovaElectricBlue,
            accentViolet = NovaViolet,
            accentCyan = Color(0xFF0091B3),
            statusConnected = Color(0xFF00A854),
            statusWarning = Color(0xFFE68A00),
            statusError = Color(0xFFD32F2F),
            isDark = false
        )
        themeMode == ThemeMode.MIDNIGHT_VIOLET -> NovaExtendedColors(
            glassSurface = Color(0xD91A1542),
            glassBorder = Color(0xFF392B75),
            canvasTop = Color(0xFF160E3B),
            canvasBottom = NovaMidnightDeep,
            accentBlue = NovaElectricBlue,
            accentViolet = NovaViolet,
            accentCyan = NovaCyan,
            statusConnected = NovaEmerald,
            statusWarning = NovaAmber,
            statusError = NovaCoral,
            isDark = true
        )
        else -> NovaExtendedColors(
            glassSurface = Color(0xD9111C36),
            glassBorder = NovaBorderGlow,
            canvasTop = Color(0xFF0C1838),
            canvasBottom = NovaNavyDeep,
            accentBlue = NovaElectricBlue,
            accentViolet = NovaViolet,
            accentCyan = NovaCyan,
            statusConnected = NovaEmerald,
            statusWarning = NovaAmber,
            statusError = NovaCoral,
            isDark = true
        )
    }

    val layoutDirection = if (isPersian) LayoutDirection.Rtl else LayoutDirection.Ltr
    val strings = if (isPersian) PersianStrings else EnglishStrings

    CompositionLocalProvider(
        LocalNovaColors provides extended,
        LocalAppStrings provides strings,
        LocalLayoutDirection provides layoutDirection
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = novaTypography(isPersian),
            shapes = NovaShapes,
            content = content
        )
    }
}
