package com.antigravity.healthagent.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import android.graphics.Color as AndroidColor

// --- Dynamic Palette Generator ---

fun generateThemePalette(seed: Color, isDark: Boolean): ThemeColorPalette {
    val hsv = FloatArray(3)
    AndroidColor.colorToHSV(seed.toArgb(), hsv)
    
    val hue = hsv[0]
    val sat = hsv[1]
    val value = hsv[2]

    fun getColor(h: Float, s: Float, v: Float): Color {
        return Color(AndroidColor.HSVToColor(floatArrayOf(h, s.coerceIn(0f, 1f), v.coerceIn(0f, 1f))))
    }

    // Success color hue is around 140 (Greenish)
    // We want it to match the "vibe" (saturation) of the theme
    val successHue = 142f 

    return if (isDark) {
        val primary = getColor(hue, sat * 0.5f, 0.9f)
        val secondary = getColor((hue + 30) % 360, sat * 0.4f, 0.8f)
        val success = getColor(successHue, sat.coerceIn(0.3f, 0.6f), 0.8f)
        
        ThemeColorPalette(
            primary = primary,
            onPrimary = getColor(hue, sat, 0.2f),
            primaryContainer = getColor(hue, sat * 0.8f, 0.3f),
            onPrimaryContainer = getColor(hue, sat * 0.3f, 0.95f),
            secondary = secondary,
            onSecondary = getColor((hue + 30) % 360, sat, 0.2f),
            secondaryContainer = getColor((hue + 30) % 360, sat * 0.8f, 0.3f),
            onSecondaryContainer = getColor((hue + 30) % 360, sat * 0.3f, 0.95f),
            tertiary = getColor((hue + 180) % 360, sat * 0.4f, 0.7f),
            onTertiary = Color.White,
            success = success,
            onSuccess = getColor(successHue, sat, 0.1f)
        )
    } else {
        // Respect seed saturation but cap it for extreme cases
        // If user picked a desaturated color, keep it desaturated!
        val targetSat = sat.coerceIn(0.1f, 0.9f)
        val primary = getColor(hue, targetSat, 0.6f)
        val secondary = getColor((hue + 30) % 360, targetSat * 0.8f, 0.5f)
        val success = getColor(successHue, targetSat.coerceIn(0.4f, 0.8f), 0.5f)

        ThemeColorPalette(
            primary = primary,
            onPrimary = Color.White,
            primaryContainer = getColor(hue, targetSat * 0.2f, 0.98f),
            onPrimaryContainer = getColor(hue, targetSat, 0.25f),
            secondary = secondary,
            onSecondary = Color.White,
            secondaryContainer = getColor((hue + 30) % 360, targetSat * 0.2f, 0.98f),
            onSecondaryContainer = getColor((hue + 30) % 360, targetSat, 0.25f),
            tertiary = getColor((hue + 180) % 360, targetSat * 0.5f, 0.5f),
            onTertiary = Color.White,
            success = success,
            onSuccess = Color.White
        )
    }
}

// --- Master Color List (Sorted by Hue) ---
val AppColors = listOf(
    "RUBY" to Color(0xFFDC2626),
    "CRIMSON" to Color(0xFF991B1B),
    "MAROON" to Color(0xFF7F1D1D),
    "BROWN" to Color(0xFFA52A2A),
    "CLAY" to Color(0xFFA75D5D),
    "ROSY_BROWN" to Color(0xFFBC8F8F),
    "SALMON" to Color(0xFFFA8072),
    "TOMATO" to Color(0xFFFF6347),
    "CORAL" to Color(0xFFFF7F50),
    "SIENNA" to Color(0xFFA0522D),
    "SUNSET" to Color(0xFFEA580C),
    "ORANGE" to Color(0xFFEA580C),
    "COFFEE" to Color(0xFF78350F),
    "BRONZE" to Color(0xFF92400E),
    "SADDLE_BROWN" to Color(0xFF8B4513),
    "CHOCOLATE" to Color(0xFFD2691E),
    "SANDY_BROWN" to Color(0xFFF4A460),
    "PERU" to Color(0xFFCD853F),
    "MANGO" to Color(0xFFFF8200),
    "AMBER" to Color(0xFFD97706),
    "PEACH" to Color(0xFFD97706),
    "TAN" to Color(0xFFD2B48C),
    "LEMON" to Color(0xFFA16207),
    "GOLD" to Color(0xFFCA8A04),
    "DARK_GOLDENROD" to Color(0xFFB8860B),
    "GOLDENROD" to Color(0xFFDAA520),
    "SAND" to Color(0xFFC2B280),
    "OLIVE" to Color(0xFF808000),
    "OLIVE_DRAB" to Color(0xFF6B8E23),
    "YELLOW_GREEN" to Color(0xFF9ACD32),
    "DARK_OLIVE_GREEN" to Color(0xFF556B2F),
    "GREEN_YELLOW" to Color(0xFFADFF2F),
    "LIME" to Color(0xFF65A30D),
    "MOSS" to Color(0xFF4D7C0F),
    "CHARTREUSE" to Color(0xFF7FFF00),
    "LAWN_GREEN" to Color(0xFF7CFC00),
    "PISTACHIO" to Color(0xFF93C572),
    "GREEN" to Color(0xFF008000),
    "DARK_GREEN" to Color(0xFF006400),
    "LIME_GREEN" to Color(0xFF32CD32),
    "FOREST_GREEN" to Color(0xFF228B22),
    "LIGHT_GREEN" to Color(0xFF90EE90),
    "PALE_GREEN" to Color(0xFF98FB98),
    "DARK_SEA_GREEN" to Color(0xFF8FBC8F),
    "FOREST" to Color(0xFF166534),
    "SEA_GREEN" to Color(0xFF2E8B57),
    "MEDIUM_SEA_GREEN" to Color(0xFF3CB371),
    "SPRING_GREEN" to Color(0xFF00FF7F),
    "MEDIUM_SPRING_GREEN" to Color(0xFF00FA9A),
    "MEDIUM_AQUAMARINE" to Color(0xFF66CDAA),
    "MINT" to Color(0xFF059669),
    "JADE" to Color(0xFF059669),
    "PINE" to Color(0xFF065F46),
    "EMERALD" to Color(0xFF0D9488),
    "LIGHT_SEA_GREEN" to Color(0xFF20B2AA),
    "DARK_CYAN" to Color(0xFF008B8B),
    "TEAL" to Color(0xFF008080),
    "CADET_BLUE" to Color(0xFF5F9EA0),
    "POWDER_BLUE" to Color(0xFFB0E0E6),
    "AQUA" to Color(0xFF00B4D8),
    "TURQUOISE" to Color(0xFF0891B2),
    "CYAN" to Color(0xFF0891B2),
    "LIGHT_BLUE" to Color(0xFFADD8E6),
    "DEEP_SKY_BLUE" to Color(0xFF00BFFF),
    "SKY_BLUE" to Color(0xFF87CEEB),
    "SKY" to Color(0xFF0284C7),
    "OCEAN" to Color(0xFF0369A1),
    "LIGHT_SKY_BLUE" to Color(0xFF87CEFA),
    "STEEL_BLUE" to Color(0xFF4682B4),
    "DODGER_BLUE" to Color(0xFF1E90FF),
    "GRAPHITE" to Color(0xFF383B3E),
    "AZURE" to Color(0xFF007FFF),
    "LIGHT_STEEL_BLUE" to Color(0xFFB0C4DE),
    "SLATE" to Color(0xFF334155),
    "STEEL" to Color(0xFF475569),
    "CORNFLOWER_BLUE" to Color(0xFF6495ED),
    "SAPPHIRE" to Color(0xFF2563EB),
    "MIDNIGHT" to Color(0xFF0F172A),
    "NAVY" to Color(0xFF1E3A8A),
    "DENIM" to Color(0xFF1E40AF),
    "BLUE" to Color(0xFF0000FF),
    "MEDIUM_BLUE" to Color(0xFF0000CD),
    "DARK_BLUE" to Color(0xFF00008B),
    "MIDNIGHT_BLUE" to Color(0xFF191970),
    "CHARCOAL" to Color(0xFF27272A),
    "INDIGO" to Color(0xFF4F46E5),
    "ROYAL" to Color(0xFF4338CA),
    "SLATE_BLUE" to Color(0xFF6A5ACD),
    "DARK_SLATE_BLUE" to Color(0xFF483D8B),
    "MEDIUM_SLATE_BLUE" to Color(0xFF7B68EE),
    "LAVENDER" to Color(0xFF7C3AED),
    "AMETHYST" to Color(0xFF9333EA),
    "GRAPE" to Color(0xFF7E22CE),
    "VIOLET" to Color(0xFF8F00FF),
    "DARK_VIOLET" to Color(0xFF9400D3),
    "FUCHSIA" to Color(0xFFC026D3),
    "PLUM" to Color(0xFF701A75),
    "PURPLE" to Color(0xFF800080),
    "THISTLE" to Color(0xFFD8BFD8),
    "ORCHID" to Color(0xFFDA70D6),
    "MEDIUM_VIOLET_RED" to Color(0xFFC71585),
    "DEEP_PINK" to Color(0xFFFF1493),
    "HOT_PINK" to Color(0xFFFF69B4),
    "FLAMINGO" to Color(0xFFDB2777),
    "BERRY" to Color(0xFF9D174D),
    "WINE" to Color(0xFF881337),
    "ROSE" to Color(0xFFE11D48),
    "STRAWBERRY" to Color(0xFFDF1642)
)
