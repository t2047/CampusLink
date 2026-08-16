package com.campuslink.mobile.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val CampusLinkLightColors = lightColorScheme(
    primary = Color(0xFF006C4F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF9BF6D1),
    onPrimaryContainer = Color(0xFF002117),
    secondary = Color(0xFF4C635A),
    secondaryContainer = Color(0xFFCFE9DC),
    onSecondaryContainer = Color(0xFF092018),
    background = Color(0xFFF5FAF7),
    onBackground = Color(0xFF171D1A),
    surface = Color(0xFFFBFDFB),
    onSurface = Color(0xFF171D1A),
    surfaceVariant = Color(0xFFDAE5DF),
    onSurfaceVariant = Color(0xFF3F4944),
    outline = Color(0xFF6F7974),
)

private val CampusLinkDarkColors = darkColorScheme(
    primary = Color(0xFF7CDBB6),
    onPrimary = Color(0xFF003828),
    primaryContainer = Color(0xFF00513B),
    onPrimaryContainer = Color(0xFF9BF6D1),
    secondary = Color(0xFFB3CCC0),
    secondaryContainer = Color(0xFF354B42),
    onSecondaryContainer = Color(0xFFCFE9DC),
    background = Color(0xFF0F1512),
    onBackground = Color(0xFFDEE5E0),
    surface = Color(0xFF0F1512),
    onSurface = Color(0xFFDEE5E0),
    surfaceVariant = Color(0xFF3F4944),
    onSurfaceVariant = Color(0xFFBEC9C3),
    outline = Color(0xFF89938E),
)

@Composable
internal fun CampusLinkTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) CampusLinkDarkColors else CampusLinkLightColors,
        content = content,
    )
}
