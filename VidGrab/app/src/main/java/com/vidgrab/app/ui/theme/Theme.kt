package com.vidgrab.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = Color(0xFF1F4FD8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE5FF),
    onPrimaryContainer = Color(0xFF00164F),
    secondary = Color(0xFF4E5B7A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD6E0FA),
    onSecondaryContainer = Color(0xFF0A1633),
    tertiary = Color(0xFF00796B),
    background = Color(0xFFF5F7FC),
    onBackground = Color(0xFF14181F),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF14181F),
    surfaceVariant = Color(0xFFE3E8F4),
    onSurfaceVariant = Color(0xFF444B5C),
    outline = Color(0xFF747B8C),
    outlineVariant = Color(0xFFC7CDDC),
    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF0F3FA),
    surfaceContainer = Color(0xFFEAEEF8),
    surfaceContainerHigh = Color(0xFFE4E9F5),
    surfaceContainerHighest = Color(0xFFDEE4F2),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB4C5FF),
    onPrimary = Color(0xFF002A78),
    primaryContainer = Color(0xFF173DA8),
    onPrimaryContainer = Color(0xFFDCE5FF),
    secondary = Color(0xFFB9C4E3),
    onSecondary = Color(0xFF232F49),
    secondaryContainer = Color(0xFF39445F),
    onSecondaryContainer = Color(0xFFD6E0FA),
    tertiary = Color(0xFF4FDBC8),
    background = Color(0xFF0E1117),
    onBackground = Color(0xFFE2E5EE),
    surface = Color(0xFF151A23),
    onSurface = Color(0xFFE2E5EE),
    surfaceVariant = Color(0xFF2A3040),
    onSurfaceVariant = Color(0xFFC3C9D9),
    outline = Color(0xFF8D94A6),
    outlineVariant = Color(0xFF404759),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    surfaceContainerLowest = Color(0xFF0A0D13),
    surfaceContainerLow = Color(0xFF131822),
    surfaceContainer = Color(0xFF1A202B),
    surfaceContainerHigh = Color(0xFF242B38),
    surfaceContainerHighest = Color(0xFF2E3644),
)

/** Dark blue-indigo gradient used by the header of the home screen. */
val HeroBrush = Brush.linearGradient(listOf(Color(0xFF0A1B45), Color(0xFF1B49B8)))

@Composable
fun VidGrabTheme(
    dynamicColor: Boolean,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, typography = Typography(), content = content)
}
