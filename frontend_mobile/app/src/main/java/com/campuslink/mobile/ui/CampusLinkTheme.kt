package com.campuslink.mobile.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val CampusLinkLightColors = lightColorScheme(
    primary = Color(0xFF006B4F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC8F5E2),
    onPrimaryContainer = Color(0xFF073D2F),
    secondary = Color(0xFF47655A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDDEDE5),
    onSecondaryContainer = Color(0xFF243E35),
    background = Color(0xFFF6F8F6),
    onBackground = Color(0xFF191C1A),
    surface = Color(0xFFFCFDFC),
    onSurface = Color(0xFF191C1A),
    surfaceVariant = Color(0xFFE8EEEA),
    onSurfaceVariant = Color(0xFF48524D),
    outline = Color(0xFF78827D),
    outlineVariant = Color(0xFFC8D0CB),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val CampusLinkDarkColors = darkColorScheme(
    primary = Color(0xFF7DDBB7),
    onPrimary = Color(0xFF003829),
    primaryContainer = Color(0xFF145540),
    onPrimaryContainer = Color(0xFFB9EED8),
    secondary = Color(0xFFB4CCC0),
    onSecondary = Color(0xFF20352D),
    secondaryContainer = Color(0xFF354B42),
    onSecondaryContainer = Color(0xFFD0E8DB),
    background = Color(0xFF101412),
    onBackground = Color(0xFFE1E5E2),
    surface = Color(0xFF181D1A),
    onSurface = Color(0xFFE1E5E2),
    surfaceVariant = Color(0xFF303834),
    onSurfaceVariant = Color(0xFFC2CAC5),
    outline = Color(0xFF8C9690),
    outlineVariant = Color(0xFF404944),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val CampusLinkTypography = Typography(
    headlineLarge = TextStyle(
        fontSize = 32.sp,
        lineHeight = 38.sp,
        fontWeight = FontWeight.Bold,
    ),
    headlineSmall = TextStyle(
        fontSize = 24.sp,
        lineHeight = 30.sp,
        fontWeight = FontWeight.Bold,
    ),
    titleLarge = TextStyle(
        fontSize = 20.sp,
        lineHeight = 26.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    titleMedium = TextStyle(
        fontSize = 16.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.SemiBold,
    ),
)

private val CampusLinkShapes = Shapes(
    small = RoundedCornerShape(CampusCorners.Small),
    medium = RoundedCornerShape(CampusCorners.Medium),
    large = RoundedCornerShape(CampusCorners.Large),
    extraLarge = RoundedCornerShape(CampusCorners.ExtraLarge),
)

@Composable
internal fun CampusLinkTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    val view = LocalView.current
    SideEffect {
        val window = view.context.findActivity()?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !darkTheme
            isAppearanceLightNavigationBars = !darkTheme
        }
    }
    MaterialTheme(
        colorScheme = if (darkTheme) CampusLinkDarkColors else CampusLinkLightColors,
        typography = CampusLinkTypography,
        shapes = CampusLinkShapes,
        content = content,
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
