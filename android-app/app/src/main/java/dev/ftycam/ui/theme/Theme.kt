package dev.ftycam.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// A restrained blue-grey. A camera app is mostly a window onto a video frame, and
// chrome that competes with the picture is chrome that's in the way.
private val Primary = Color(0xFF4A7DBF)
private val PrimaryDark = Color(0xFF8FB4E3)
private val Surface = Color(0xFF14171C)
private val SurfaceLight = Color(0xFFF7F8FA)

private val DarkColors = darkColorScheme(
    primary = PrimaryDark,
    onPrimary = Color(0xFF0A1929),
    primaryContainer = Color(0xFF23405F),
    onPrimaryContainer = Color(0xFFD3E3F8),
    background = Surface,
    onBackground = Color(0xFFE3E5E9),
    surface = Surface,
    onSurface = Color(0xFFE3E5E9),
    surfaceVariant = Color(0xFF1F242B),
    onSurfaceVariant = Color(0xFFB6BCC6),
    error = Color(0xFFE8837C),
)

private val LightColors = lightColorScheme(
    primary = Primary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E3F8),
    onPrimaryContainer = Color(0xFF0A1929),
    background = SurfaceLight,
    onBackground = Color(0xFF14171C),
    surface = Color.White,
    onSurface = Color(0xFF14171C),
    surfaceVariant = Color(0xFFE7EAEF),
    onSurfaceVariant = Color(0xFF454B54),
    error = Color(0xFFB3261E),
)

private val AppTypography = Typography(
    headlineSmall = Typography().headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = Typography().titleMedium.copy(fontWeight = FontWeight.Medium),
    labelSmall = Typography().labelSmall.copy(letterSpacing = 0.6.sp),
)

@Composable
fun FtycamTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content,
    )
}
