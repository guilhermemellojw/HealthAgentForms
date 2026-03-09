package com.antigravity.healthagent.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.antigravity.healthagent.ui.theme.generateThemePalette
import com.antigravity.healthagent.ui.theme.AppColors

@Composable
fun HealthAgentFormsTheme(
    themeMode: String = "SYSTEM", // SYSTEM, LIGHT, DARK
    themeColor: String = "EMERALD", // EMERALD, SAPPHIRE, etc.
    solarMode: Boolean = false,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        "LIGHT" -> false
        "DARK" -> true
        else -> isSystemInDarkTheme()
    }

    val palette = when (themeColor) {
        "SAPPHIRE" -> if (darkTheme) SapphireDark else SapphireLight
        "AMETHYST" -> if (darkTheme) AmethystDark else AmethystLight
        "RUBY" -> if (darkTheme) RubyDark else RubyLight
        "AMBER" -> if (darkTheme) AmberDark else AmberLight
        "SLATE" -> if (darkTheme) SlateDark else SlateLight
        "ROSE" -> if (darkTheme) RoseDark else RoseLight
        "SKY" -> if (darkTheme) SkyDark else SkyLight
        "LIME" -> if (darkTheme) LimeDark else LimeLight
        "INDIGO" -> if (darkTheme) IndigoDark else IndigoLight
        "FUCHSIA" -> if (darkTheme) FuchsiaDark else FuchsiaLight
        "JADE" -> if (darkTheme) JadeDark else JadeLight
        "TURQUOISE" -> if (darkTheme) TurquoiseDark else TurquoiseLight
        "SUNSET" -> if (darkTheme) SunsetDark else SunsetLight
        "GOLD" -> if (darkTheme) GoldDark else GoldLight
        "ROYAL" -> if (darkTheme) RoyalDark else RoyalLight
        "COFFEE" -> if (darkTheme) CoffeeDark else CoffeeLight
        "STEEL" -> if (darkTheme) SteelDark else SteelLight
        "FLAMINGO" -> if (darkTheme) FlamingoDark else FlamingoLight
        "MOSS" -> if (darkTheme) MossDark else MossLight
        "CHARCOAL" -> if (darkTheme) CharcoalDark else CharcoalLight
        "AQUA" -> if (darkTheme) AquaDark else AquaLight
        "BERRY" -> if (darkTheme) BerryDark else BerryLight
        "BRONZE" -> if (darkTheme) BronzeDark else BronzeLight
        "CRIMSON" -> if (darkTheme) CrimsonDark else CrimsonLight
        "CYAN" -> if (darkTheme) CyanDark else CyanLight
        "DENIM" -> if (darkTheme) DenimDark else DenimLight
        "FOREST" -> if (darkTheme) ForestDark else ForestLight
        "GRAPE" -> if (darkTheme) GrapeDark else GrapeLight
        "LAVENDER" -> if (darkTheme) LavenderDark else LavenderLight
        "LEMON" -> if (darkTheme) LemonDark else LemonLight
        "MAROON" -> if (darkTheme) MaroonDark else MaroonLight
        "MIDNIGHT" -> if (darkTheme) MidnightDark else MidnightLight
        "MINT" -> if (darkTheme) MintDark else MintLight
        "NAVY" -> if (darkTheme) NavyDark else NavyLight
        "OCEAN" -> if (darkTheme) OceanDark else OceanLight
        "ORANGE" -> if (darkTheme) OrangeDark else OrangeLight
        "PEACH" -> if (darkTheme) PeachDark else PeachLight
        "PINE" -> if (darkTheme) PineDark else PineLight
        "PLUM" -> if (darkTheme) PlumDark else PlumLight
        "WINE" -> if (darkTheme) WineDark else WineLight
        "STRAWBERRY" -> if (darkTheme) StrawberryDark else StrawberryLight
        "MANGO" -> if (darkTheme) MangoDark else MangoLight
        "PISTACHIO" -> if (darkTheme) PistachioDark else PistachioLight
        "OLIVE" -> if (darkTheme) OliveDark else OliveLight
        "AZURE" -> if (darkTheme) AzureDark else AzureLight
        "VIOLET" -> if (darkTheme) VioletDark else VioletLight
        "GRAPHITE" -> if (darkTheme) GraphiteDark else GraphiteLight
        "SAND" -> if (darkTheme) SandDark else SandLight
        "CLAY" -> if (darkTheme) ClayDark else ClayLight
        else -> {
            // Dynamic Generation for extended palette
            val seed = AppColors.find { it.first == themeColor }?.second
            if (seed != null) {
                generateThemePalette(seed, darkTheme)
            } else {
                if (darkTheme) EmeraldDark else EmeraldLight // Default Fallback
            }
        }
    }

    val finalPalette = if (solarMode) SolarPalette else palette

    // Base Schemes
    val baseDarkScheme = darkColorScheme(
        background = DarkBackground,
        surface = DarkSurface,
        onBackground = DarkText,
        onSurface = DarkText,
        surfaceVariant = DarkSurfaceVariant,
        onSurfaceVariant = DarkSubText,
        outline = DarkBorder,
        outlineVariant = DarkBorder.copy(alpha = 0.5f),
        error = ErrorRed,
        errorContainer = ErrorRed.copy(alpha = 0.2f),
        onError = Color.White,
        onErrorContainer = ErrorRed
    )

    val baseLightScheme = lightColorScheme(
        background = AppBackground,
        surface = AppSurface,
        onBackground = AppTextMain,
        onSurface = AppTextMain,
        surfaceVariant = NeutralLightSlate,
        onSurfaceVariant = AppTextSecondary,
        outline = NeutralBorder,
        outlineVariant = NeutralLightSlate,
        error = Color(0xFFDC2626), // Stronger red for light mode (Red 600)
        errorContainer = Color(0xFFFEE2E2), // Red 100 for light mode
        onError = Color.White,
        onErrorContainer = Color(0xFF991B1B)
    )

    val colorScheme = if (solarMode) {
        // High contrast scheme for Solar Mode
        darkColorScheme(
            primary = SolarPalette.primary,
            onPrimary = SolarPalette.onPrimary,
            primaryContainer = SolarPalette.primaryContainer,
            onPrimaryContainer = SolarPalette.onPrimaryContainer,
            secondary = SolarPalette.secondary,
            onSecondary = SolarPalette.onSecondary,
            secondaryContainer = SolarPalette.secondaryContainer,
            onSecondaryContainer = SolarPalette.onSecondaryContainer,
            background = Color.Black,
            surface = Color.Black,
            onBackground = Color.White,
            onSurface = Color.White,
            surfaceVariant = Color(0xFF1E293B),
            onSurfaceVariant = Color.White,
            outline = Color.White,
            error = Color(0xFFF87171)
        )
    } else if (darkTheme) {
        baseDarkScheme.copy(
            primary = palette.primary,
            onPrimary = palette.onPrimary,
            primaryContainer = palette.primaryContainer,
            onPrimaryContainer = palette.onPrimaryContainer,
            secondary = palette.secondary,
            onSecondary = palette.onSecondary,
            secondaryContainer = palette.secondaryContainer,
            onSecondaryContainer = palette.onSecondaryContainer,
            tertiary = palette.tertiary,
            onTertiary = palette.onTertiary,
            tertiaryContainer = palette.success,
            onTertiaryContainer = palette.onSuccess
        )
    } else {
        baseLightScheme.copy(
            primary = palette.primary,
            onPrimary = palette.onPrimary,
            primaryContainer = palette.primaryContainer,
            onPrimaryContainer = palette.onPrimaryContainer,
            secondary = palette.secondary,
            onSecondary = palette.onSecondary,
            secondaryContainer = palette.secondaryContainer,
            onSecondaryContainer = palette.onSecondaryContainer,
            tertiary = palette.tertiary,
            onTertiary = palette.onTertiary,
            tertiaryContainer = palette.success,
            onTertiaryContainer = palette.onSuccess
        )
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val activity = findActivity(view.context)
            val window = activity?.window
            if (window != null) {
                window.statusBarColor = if (solarMode) Color.Black.toArgb() else if (darkTheme) colorScheme.surface.toArgb() else colorScheme.primary.toArgb()
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme && !solarMode
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

private fun findActivity(context: android.content.Context): Activity? {
    var currentContext = context
    while (currentContext is android.content.ContextWrapper) {
        if (currentContext is Activity) return currentContext
        currentContext = currentContext.baseContext
    }
    return null
}
