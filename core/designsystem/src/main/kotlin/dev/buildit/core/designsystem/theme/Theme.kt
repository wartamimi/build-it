package dev.buildit.core.designsystem.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
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

internal val LightScheme: ColorScheme = lightColorScheme(
    primary = Palette.Blueprint40, onPrimary = Palette.White,
    primaryContainer = Palette.Blueprint90, onPrimaryContainer = Palette.Blueprint10,
    secondary = Palette.Kiln40, onSecondary = Palette.White,
    secondaryContainer = Palette.Kiln90, onSecondaryContainer = Palette.Kiln10,
    tertiary = Palette.Mint40, onTertiary = Palette.White,
    tertiaryContainer = Palette.Mint90, onTertiaryContainer = Palette.Mint20,
    error = Palette.Red40, onError = Palette.White,
    errorContainer = Palette.Red90, onErrorContainer = Palette.Red20,
    background = Palette.Graphite98, onBackground = Palette.Graphite10,
    surface = Palette.Graphite98, onSurface = Palette.Graphite10,
    surfaceVariant = Palette.Graphite90, onSurfaceVariant = Palette.Graphite30,
    surfaceContainerLowest = Palette.White,
    surfaceContainerLow = Palette.Graphite96,
    surfaceContainer = Palette.Graphite94,
    surfaceContainerHigh = Palette.Graphite90,
    surfaceContainerHighest = Palette.Graphite80,
    outline = Palette.Graphite50, outlineVariant = Palette.Graphite80,
)

internal val DarkScheme: ColorScheme = darkColorScheme(
    primary = Palette.Blueprint80, onPrimary = Palette.Blueprint20,
    primaryContainer = Palette.Blueprint30, onPrimaryContainer = Palette.Blueprint90,
    secondary = Palette.Kiln80, onSecondary = Palette.Kiln20,
    secondaryContainer = Palette.Kiln30, onSecondaryContainer = Palette.Kiln90,
    tertiary = Palette.Mint80, onTertiary = Palette.Mint20,
    tertiaryContainer = Palette.Mint30, onTertiaryContainer = Palette.Mint90,
    error = Palette.Red80, onError = Palette.Red20,
    errorContainer = Palette.Red30, onErrorContainer = Palette.Red90,
    background = Palette.Graphite6, onBackground = Palette.Graphite90,
    surface = Palette.Graphite6, onSurface = Palette.Graphite90,
    surfaceVariant = Palette.Graphite30, onSurfaceVariant = Palette.Graphite80,
    surfaceContainerLowest = Palette.Graphite4,
    surfaceContainerLow = Palette.Graphite10,
    surfaceContainer = Palette.Graphite12,
    surfaceContainerHigh = Palette.Graphite17,
    surfaceContainerHighest = Palette.Graphite22,
    outline = Palette.Graphite60, outlineVariant = Palette.Graphite30,
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
