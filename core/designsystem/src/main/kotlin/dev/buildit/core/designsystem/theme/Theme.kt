package dev.buildit.core.designsystem.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import dev.buildit.core.model.ThemeMode

// Warm scheme: brick orange actions, clay-brown secondary, mint for success, paper neutrals.
internal val LightScheme: ColorScheme = lightColorScheme(
    primary = Palette.Brick40, onPrimary = Palette.White,
    primaryContainer = Palette.Brick90, onPrimaryContainer = Palette.Brick10,
    secondary = Color(0xFF6F4C35), onSecondary = Palette.White,
    secondaryContainer = Color(0xFFF3E1D3), onSecondaryContainer = Palette.Clay10,
    tertiary = Palette.Mint40, onTertiary = Palette.White,
    tertiaryContainer = Palette.Mint90, onTertiaryContainer = Palette.Mint20,
    error = Palette.Red40, onError = Palette.White,
    errorContainer = Palette.Red90, onErrorContainer = Palette.Red20,
    background = Palette.Sand98, onBackground = Palette.Sand10,
    surface = Palette.Sand98, onSurface = Palette.Sand10,
    surfaceVariant = Palette.Sand90, onSurfaceVariant = Palette.Sand30,
    surfaceContainerLowest = Palette.White,
    surfaceContainerLow = Palette.Sand96,
    surfaceContainer = Palette.Sand94,
    surfaceContainerHigh = Palette.Sand90,
    surfaceContainerHighest = Palette.Sand80,
    outline = Palette.Sand50, outlineVariant = Palette.Sand80,
)

internal val DarkScheme: ColorScheme = darkColorScheme(
    primary = Palette.Brick80, onPrimary = Palette.Brick20,
    primaryContainer = Palette.Brick30, onPrimaryContainer = Palette.Brick90,
    secondary = Color(0xFFE2C0A8), onSecondary = Color(0xFF412A1B),
    secondaryContainer = Color(0xFF5A3F2D), onSecondaryContainer = Color(0xFFF3E1D3),
    tertiary = Palette.Mint80, onTertiary = Palette.Mint20,
    tertiaryContainer = Palette.Mint30, onTertiaryContainer = Palette.Mint90,
    error = Palette.Red80, onError = Palette.Red20,
    errorContainer = Palette.Red30, onErrorContainer = Palette.Red90,
    background = Palette.Sand6, onBackground = Palette.Sand90,
    surface = Palette.Sand6, onSurface = Palette.Sand90,
    surfaceVariant = Palette.Sand30, onSurfaceVariant = Palette.Sand80,
    surfaceContainerLowest = Palette.Sand4,
    surfaceContainerLow = Palette.Sand10,
    surfaceContainer = Palette.Sand12,
    surfaceContainerHigh = Palette.Sand17,
    surfaceContainerHighest = Palette.Sand22,
    outline = Palette.Sand60, outlineVariant = Palette.Sand30,
)

val LocalExtendedColors = staticCompositionLocalOf { LightExtended }

/** Root theme for every Build It screen. */
@Composable
fun BuildItTheme(
    themeMode: ThemeMode = ThemeMode.System,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val context = LocalContext.current
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkScheme
        else -> LightScheme
    }
    CompositionLocalProvider(LocalExtendedColors provides if (dark) DarkExtended else LightExtended) {
        MaterialTheme(colorScheme = scheme, typography = BuildItTypography, shapes = BuildItShapes, content = content)
    }
}

object BuildItThemeExt {
    val colors: ExtendedColors
        @Composable get() = LocalExtendedColors.current
}

/** True when dynamic color can be offered on this device. */
val supportsDynamicColor: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
