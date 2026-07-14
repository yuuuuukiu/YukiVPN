package dev.yukivpn.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = Color(0xFF006B5E),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF76F8E2),
    onPrimaryContainer = Color(0xFF00201B),
    secondary = Color(0xFF4A635E),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCDE8E1),
    onSecondaryContainer = Color(0xFF06201B),
    tertiary = Color(0xFF7B5800),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDEA1),
    onTertiaryContainer = Color(0xFF271900),
    background = Color(0xFFF4FBF8),
    onBackground = Color(0xFF161D1B),
    surface = Color(0xFFF4FBF8),
    onSurface = Color(0xFF161D1B),
    surfaceVariant = Color(0xFFDAE5E1),
    onSurfaceVariant = Color(0xFF3F4946),
    outline = Color(0xFF6F7976),
    outlineVariant = Color(0xFFBEC9C5),
    error = Color(0xFFBA1A1A),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF55DBC6),
    onPrimary = Color(0xFF00372F),
    primaryContainer = Color(0xFF005046),
    onPrimaryContainer = Color(0xFF76F8E2),
    secondary = Color(0xFFB1CCC5),
    onSecondary = Color(0xFF1C3530),
    secondaryContainer = Color(0xFF334B47),
    onSecondaryContainer = Color(0xFFCDE8E1),
    tertiary = Color(0xFFF3BF48),
    onTertiary = Color(0xFF412D00),
    tertiaryContainer = Color(0xFF5D4200),
    onTertiaryContainer = Color(0xFFFFDEA1),
    background = Color(0xFF0E1513),
    onBackground = Color(0xFFDEE4E1),
    surface = Color(0xFF0E1513),
    onSurface = Color(0xFFDEE4E1),
    surfaceVariant = Color(0xFF3F4946),
    onSurfaceVariant = Color(0xFFBEC9C5),
    outline = Color(0xFF89938F),
    outlineVariant = Color(0xFF3F4946),
    error = Color(0xFFFFB4AB),
)

@Composable
fun YukiVpnTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}

