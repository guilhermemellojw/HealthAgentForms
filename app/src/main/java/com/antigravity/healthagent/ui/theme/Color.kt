package com.antigravity.healthagent.ui.theme

import androidx.compose.ui.graphics.Color

// --- Theme Data Structure ---
data class ThemeColorPalette(
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val secondary: Color,
    val onSecondary: Color,
    val secondaryContainer: Color,
    val onSecondaryContainer: Color,
    val tertiary: Color = Color(0xFF64748B), // Slate 500 default
    val onTertiary: Color = Color.White,
    val success: Color = Color(0xFF10B981), // Emerald 500 default
    val onSuccess: Color = Color.White
)

val SolarPalette = ThemeColorPalette(
    primary = Color(0xFFA3E635), // Neon Lime (Lime 400)
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF1E293B), // Slate 800-like or Black
    onPrimaryContainer = Color(0xFFA3E635),
    secondary = Color(0xFFFDE047), // Yellow 300
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF334155),
    onSecondaryContainer = Color(0xFFFDE047)
)

// --- 1. EMERALD (Original / Default) ---
val EmeraldLight = ThemeColorPalette(
    primary = Color(0xFF6FDBA1), // Sampled New Green
    onPrimary = Color(0xFF1B3D31),
    primaryContainer = Color(0xFF214F66), // Sampled New Slate Blue
    onPrimaryContainer = Color(0xFF6FDBA1),
    secondary = Color(0xFF2DD4BF),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD1FAE5),
    onSecondaryContainer = Color(0xFF065F46)
)

val EmeraldDark = ThemeColorPalette(
    primary = Color(0xFF6FDBA1), // Sampled New Green
    onPrimary = Color(0xFF003833),
    primaryContainer = Color(0xFF1B3D31),
    onPrimaryContainer = Color(0xFF6FDBA1),
    secondary = Color(0xFF2DD4BF),
    onSecondary = Color(0xFF003833),
    secondaryContainer = Color(0xFF214F66), // Sampled Slate Blue
    onSecondaryContainer = Color(0xFFD1FAE5)
)

// --- 2. SAPPHIRE (Blue - Trust) ---
val SapphireLight = ThemeColorPalette(
    primary = Color(0xFF2563EB), // Blue 600
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDBEAFE), // Blue 100
    onPrimaryContainer = Color(0xFF1E40AF), // Blue 800
    secondary = Color(0xFF0891B2), // Cyan 600
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCFFAFE), // Cyan 100
    onSecondaryContainer = Color(0xFF155E75) // Cyan 800
)

val SapphireDark = ThemeColorPalette(
    primary = Color(0xFF60A5FA), // Blue 400
    onPrimary = Color(0xFF172554),
    primaryContainer = Color(0xFF1E40AF), // Blue 800
    onPrimaryContainer = Color(0xFFDBEAFE), // Blue 100
    secondary = Color(0xFF22D3EE), // Cyan 400
    onSecondary = Color(0xFF164E63),
    secondaryContainer = Color(0xFF155E75), // Cyan 800
    onSecondaryContainer = Color(0xFFCFFAFE) // Cyan 100
)

// --- 3. AMETHYST (Purple - Creative) ---
val AmethystLight = ThemeColorPalette(
    primary = Color(0xFF9333EA), // Purple 600
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF3E8FF), // Purple 100
    onPrimaryContainer = Color(0xFF6B21A8), // Purple 800
    secondary = Color(0xFFD946EF), // Fuchsia 500
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFAE8FF), // Fuchsia 100
    onSecondaryContainer = Color(0xFF86198F) // Fuchsia 800
)

val AmethystDark = ThemeColorPalette(
    primary = Color(0xFFA855F7), // Purple 400 (Adjusted for contrast)
    onPrimary = Color(0xFF3B0764),
    primaryContainer = Color(0xFF6B21A8), // Purple 800
    onPrimaryContainer = Color(0xFFF3E8FF), // Purple 100
    secondary = Color(0xFFE879F9), // Fuchsia 400
    onSecondary = Color(0xFF4A044E),
    secondaryContainer = Color(0xFF86198F), // Fuchsia 800
    onSecondaryContainer = Color(0xFFFAE8FF) // Fuchsia 100
)

// --- 4. RUBY (Red - Energetic) ---
val RubyLight = ThemeColorPalette(
    primary = Color(0xFFDC2626), // Red 600
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFEE2E2), // Red 100
    onPrimaryContainer = Color(0xFF991B1B), // Red 800
    secondary = Color(0xFFEA580C), // Orange 600
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFEDD5), // Orange 100
    onSecondaryContainer = Color(0xFF9A3412) // Orange 800
)

val RubyDark = ThemeColorPalette(
    primary = Color(0xFFF87171), // Red 400
    onPrimary = Color(0xFF450A0A),
    primaryContainer = Color(0xFF991B1B), // Red 800
    onPrimaryContainer = Color(0xFFFEE2E2), // Red 100
    secondary = Color(0xFFFB923C), // Orange 400
    onSecondary = Color(0xFF431407),
    secondaryContainer = Color(0xFF9A3412), // Orange 800
    onSecondaryContainer = Color(0xFFFFEDD5) // Orange 100
)

// --- 5. AMBER (Orange/Warm) ---
val AmberLight = ThemeColorPalette(
    primary = Color(0xFFD97706), // Amber 600
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFEF3C7), // Amber 100
    onPrimaryContainer = Color(0xFF78350F), // Amber 900
    secondary = Color(0xFFB45309), // Amber 700
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFFBEB),
    onSecondaryContainer = Color(0xFF78350F)
)

val AmberDark = ThemeColorPalette(
    primary = Color(0xFFFBBF24), // Amber 400
    onPrimary = Color(0xFF451A03),
    primaryContainer = Color(0xFFB45309), // Amber 700
    onPrimaryContainer = Color(0xFFFEF3C7), // Amber 100
    secondary = Color(0xFFF59E0B), // Amber 500
    onSecondary = Color(0xFF451A03),
    secondaryContainer = Color(0xFF78350F), // Amber 900
    onSecondaryContainer = Color(0xFFFFFBEB)
)

// --- 6. SLATE (Minimalist/Mono) ---
val SlateLight = ThemeColorPalette(
    primary = Color(0xFF334155), // Slate 700
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF1F5F9), // Slate 100
    onPrimaryContainer = Color(0xFF0F172A), // Slate 900
    secondary = Color(0xFF64748B), // Slate 500
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE2E8F0), // Slate 200
    onSecondaryContainer = Color(0xFF1E293B) // Slate 800
)

val SlateDark = ThemeColorPalette(
    primary = Color(0xFF94A3B8), // Slate 400
    onPrimary = Color(0xFF0F172A), // Slate 900
    primaryContainer = Color(0xFF334155), // Slate 700
    onPrimaryContainer = Color(0xFFF1F5F9), // Slate 100
    secondary = Color(0xFFCBD5E1), // Slate 300
    onSecondary = Color(0xFF0F172A),
    secondaryContainer = Color(0xFF1E293B), // Slate 800
    onSecondaryContainer = Color(0xFFE2E8F0) // Slate 200
)

// --- 7. ROSE (Romantic/Soft Red) ---
val RoseLight = ThemeColorPalette(
    primary = Color(0xFFE11D48), // Rose 600
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE4E6), // Rose 100
    onPrimaryContainer = Color(0xFF881337), // Rose 800
    secondary = Color(0xFFBE123C), // Rose 700
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFF1F2), // Rose 50
    onSecondaryContainer = Color(0xFF881337)
)

val RoseDark = ThemeColorPalette(
    primary = Color(0xFFFB7185), // Rose 400
    onPrimary = Color(0xFF4C0519),
    primaryContainer = Color(0xFF9F1239), // Rose 700
    onPrimaryContainer = Color(0xFFFFE4E6), // Rose 100
    secondary = Color(0xFFFDA4AF), // Rose 300
    onSecondary = Color(0xFF4C0519),
    secondaryContainer = Color(0xFF881337), // Rose 800
    onSecondaryContainer = Color(0xFFFFF1F2)
)

// --- 8. SKY (Light Blue/Airy) ---
val SkyLight = ThemeColorPalette(
    primary = Color(0xFF0284C7), // Sky 600
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0F2FE), // Sky 100
    onPrimaryContainer = Color(0xFF075985), // Sky 800
    secondary = Color(0xFF0EA5E9), // Sky 500
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF0F9FF), // Sky 50
    onSecondaryContainer = Color(0xFF0C4A6E)
)

val SkyDark = ThemeColorPalette(
    primary = Color(0xFF38BDF8), // Sky 400
    onPrimary = Color(0xFF082F49),
    primaryContainer = Color(0xFF0369A1), // Sky 700
    onPrimaryContainer = Color(0xFFE0F2FE), // Sky 100
    secondary = Color(0xFF7DD3FC), // Sky 300
    onSecondary = Color(0xFF082F49),
    secondaryContainer = Color(0xFF0C4A6E), // Sky 800
    onSecondaryContainer = Color(0xFFF0F9FF)
)

// --- 9. LIME (Energetic Green) ---
val LimeLight = ThemeColorPalette(
    primary = Color(0xFF65A30D), // Lime 600
    onPrimary = Color.White,
    primaryContainer = Color(0xFFECFCCB), // Lime 100
    onPrimaryContainer = Color(0xFF365314), // Lime 800
    secondary = Color(0xFF84CC16), // Lime 500
    onSecondary = Color(0xFF1A2E05),
    secondaryContainer = Color(0xFFF7FEE7), // Lime 50
    onSecondaryContainer = Color(0xFF3F6212)
)

val LimeDark = ThemeColorPalette(
    primary = Color(0xFFA3E635), // Lime 400
    onPrimary = Color(0xFF1A2E05),
    primaryContainer = Color(0xFF4D7C0F), // Lime 700
    onPrimaryContainer = Color(0xFFECFCCB), // Lime 100
    secondary = Color(0xFFBEF264), // Lime 300
    onSecondary = Color(0xFF1A2E05),
    secondaryContainer = Color(0xFF365314), // Lime 800
    onSecondaryContainer = Color(0xFFF7FEE7)
)

// --- 10. INDIGO (Deep Blue) ---
val IndigoLight = ThemeColorPalette(
    primary = Color(0xFF4F46E5), // Indigo 600
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0E7FF), // Indigo 100
    onPrimaryContainer = Color(0xFF3730A3), // Indigo 800
    secondary = Color(0xFF6366F1), // Indigo 500
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEEF2FF), // Indigo 50
    onSecondaryContainer = Color(0xFF312E81)
)

val IndigoDark = ThemeColorPalette(
    primary = Color(0xFF818CF8), // Indigo 400
    onPrimary = Color(0xFF312E81),
    primaryContainer = Color(0xFF4338CA), // Indigo 700
    onPrimaryContainer = Color(0xFFE0E7FF), // Indigo 100
    secondary = Color(0xFFA5B4FC), // Indigo 300
    onSecondary = Color(0xFF312E81),
    secondaryContainer = Color(0xFF3730A3), // Indigo 800
    onSecondaryContainer = Color(0xFFEEF2FF)
)

// --- 11. FUCHSIA (Vibrant Pink) ---
val FuchsiaLight = ThemeColorPalette(
    primary = Color(0xFFC026D3), // Fuchsia 600
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFAE8FF), // Fuchsia 100
    onPrimaryContainer = Color(0xFF86198F), // Fuchsia 800
    secondary = Color(0xFFD946EF), // Fuchsia 500
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFDF4FF), // Fuchsia 50
    onSecondaryContainer = Color(0xFF701A75)
)

val FuchsiaDark = ThemeColorPalette(
    primary = Color(0xFFE879F9), // Fuchsia 400
    onPrimary = Color(0xFF4A044E),
    primaryContainer = Color(0xFFA21CAF), // Fuchsia 700
    onPrimaryContainer = Color(0xFFFAE8FF), // Fuchsia 100
    secondary = Color(0xFFF0ABFC), // Fuchsia 300
    onSecondary = Color(0xFF4A044E),
    secondaryContainer = Color(0xFF86198F), // Fuchsia 800
    onSecondaryContainer = Color(0xFFFDF4FF)
)

// --- 12. JADE (Soft Green) ---
val JadeLight = ThemeColorPalette(
    primary = Color(0xFF059669), // Emerald 600
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD1FAE5), // Emerald 100
    onPrimaryContainer = Color(0xFF065F46), // Emerald 800
    secondary = Color(0xFF10B981), // Emerald 500
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFECFDF5), // Emerald 50
    onSecondaryContainer = Color(0xFF064E3B)
)

val JadeDark = ThemeColorPalette(
    primary = Color(0xFF34D399), // Emerald 400
    onPrimary = Color(0xFF064E3B),
    primaryContainer = Color(0xFF059669), // Emerald 600
    onPrimaryContainer = Color(0xFFD1FAE5), // Emerald 100
    secondary = Color(0xFF6EE7B7), // Emerald 300
    onSecondary = Color(0xFF064E3B),
    secondaryContainer = Color(0xFF065F46), // Emerald 800
    onSecondaryContainer = Color(0xFFECFDF5)
)

// --- 13. TURQUOISE (Aqua) ---
val TurquoiseLight = ThemeColorPalette(
    primary = Color(0xFF0891B2), // Cyan 600
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCFFAFE), // Cyan 100
    onPrimaryContainer = Color(0xFF155E75), // Cyan 800
    secondary = Color(0xFF06B6D4), // Cyan 500
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFECFEFF), // Cyan 50
    onSecondaryContainer = Color(0xFF0E7490)
)

val TurquoiseDark = ThemeColorPalette(
    primary = Color(0xFF22D3EE), // Cyan 400
    onPrimary = Color(0xFF083344),
    primaryContainer = Color(0xFF0E7490), // Cyan 700
    onPrimaryContainer = Color(0xFFCFFAFE), // Cyan 100
    secondary = Color(0xFF67E8F9), // Cyan 300
    onSecondary = Color(0xFF083344),
    secondaryContainer = Color(0xFF155E75), // Cyan 800
    onSecondaryContainer = Color(0xFFECFEFF)
)

// --- 14. SUNSET (Warm Red-Orange) ---
val SunsetLight = ThemeColorPalette(
    primary = Color(0xFFEA580C), // Orange 600
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFEDD5), // Orange 100
    onPrimaryContainer = Color(0xFF9A3412), // Orange 800
    secondary = Color(0xFFF97316), // Orange 500
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFF7ED), // Orange 50
    onSecondaryContainer = Color(0xFF7C2D12)
)

val SunsetDark = ThemeColorPalette(
    primary = Color(0xFFFB923C), // Orange 400
    onPrimary = Color(0xFF431407),
    primaryContainer = Color(0xFFC2410C), // Orange 700
    onPrimaryContainer = Color(0xFFFFEDD5), // Orange 100
    secondary = Color(0xFFFDBA74), // Orange 300
    onSecondary = Color(0xFF431407),
    secondaryContainer = Color(0xFF9A3412), // Orange 800
    onSecondaryContainer = Color(0xFFFFF7ED)
)

// --- 15. GOLD (Rich Yellow) ---
val GoldLight = ThemeColorPalette(
    primary = Color(0xFFCA8A04), // Yellow 600
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFEF9C3), // Yellow 100
    onPrimaryContainer = Color(0xFF713F12), // Yellow 800
    secondary = Color(0xFFEAB308), // Yellow 500
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFEFCE8), // Yellow 50
    onSecondaryContainer = Color(0xFF854D0E)
)

val GoldDark = ThemeColorPalette(
    primary = Color(0xFFFACC15), // Yellow 400
    onPrimary = Color(0xFF422006),
    primaryContainer = Color(0xFFA16207), // Yellow 700
    onPrimaryContainer = Color(0xFFFEF9C3), // Yellow 100
    secondary = Color(0xFFFDE047), // Yellow 300
    onSecondary = Color(0xFF422006),
    secondaryContainer = Color(0xFF713F12), // Yellow 800
    onSecondaryContainer = Color(0xFFFEFCE8)
)

// --- 16. ROYAL (Deep Purple/Indigo mix) ---
val RoyalLight = ThemeColorPalette(
    primary = Color(0xFF4338CA), // Indigo 700
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0E7FF), // Indigo 100
    onPrimaryContainer = Color(0xFF312E81), // Indigo 900
    secondary = Color(0xFF5B21B6), // Violet 800
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEDE9FE), // Violet 100
    onSecondaryContainer = Color(0xFF4C1D95)
)

val RoyalDark = ThemeColorPalette(
    primary = Color(0xFF818CF8), // Indigo 400
    onPrimary = Color(0xFF312E81),
    primaryContainer = Color(0xFF4338CA), // Indigo 700
    onPrimaryContainer = Color(0xFFE0E7FF), // Indigo 100
    secondary = Color(0xFFA78BFA), // Violet 400
    onSecondary = Color(0xFF2E1065),
    secondaryContainer = Color(0xFF5B21B6), // Violet 800
    onSecondaryContainer = Color(0xFFEDE9FE)
)

// --- 17. COFFEE (Brown) ---
val CoffeeLight = ThemeColorPalette(
    primary = Color(0xFF78350F), // Amber 900
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFFBEB), // Amber 50
    onPrimaryContainer = Color(0xFF451A03), // Amber 950
    secondary = Color(0xFF92400E), // Amber 800
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFEF3C7), // Amber 100
    onSecondaryContainer = Color(0xFF78350F)
)

val CoffeeDark = ThemeColorPalette(
    primary = Color(0xFFD97706), // Amber 600
    onPrimary = Color(0xFF451A03),
    primaryContainer = Color(0xFF92400E), // Amber 800
    onPrimaryContainer = Color(0xFFFFFBEB), // Amber 50
    secondary = Color(0xFFB45309), // Amber 700
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF78350F), // Amber 900
    onSecondaryContainer = Color(0xFFFEF3C7)
)

// --- 18. STEEL (Blue Grey) ---
val SteelLight = ThemeColorPalette(
    primary = Color(0xFF475569), // Slate 600
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF1F5F9), // Slate 100
    onPrimaryContainer = Color(0xFF0F172A), // Slate 900
    secondary = Color(0xFF64748B), // Slate 500
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF8FAFC), // Slate 50
    onSecondaryContainer = Color(0xFF1E293B)
)

val SteelDark = ThemeColorPalette(
    primary = Color(0xFF94A3B8), // Slate 400
    onPrimary = Color(0xFF0F172A),
    primaryContainer = Color(0xFF475569), // Slate 600
    onPrimaryContainer = Color(0xFFF1F5F9), // Slate 100
    secondary = Color(0xFFCBD5E1), // Slate 300
    onSecondary = Color(0xFF0F172A),
    secondaryContainer = Color(0xFF334155), // Slate 700
    onSecondaryContainer = Color(0xFFF8FAFC)
)

// --- 19. FLAMINGO (Soft Pink) ---
val FlamingoLight = ThemeColorPalette(
    primary = Color(0xFFDB2777), // Pink 600
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFCE7F3), // Pink 100
    onPrimaryContainer = Color(0xFF831843), // Pink 800
    secondary = Color(0xFFEC4899), // Pink 500
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFDF2F8), // Pink 50
    onSecondaryContainer = Color(0xFF9D174D)
)

val FlamingoDark = ThemeColorPalette(
    primary = Color(0xFFF472B6), // Pink 400
    onPrimary = Color(0xFF500724),
    primaryContainer = Color(0xFFBE185D), // Pink 700
    onPrimaryContainer = Color(0xFFFCE7F3), // Pink 100
    secondary = Color(0xFFF9A8D4), // Pink 300
    onSecondary = Color(0xFF500724),
    secondaryContainer = Color(0xFF831843), // Pink 800
    onSecondaryContainer = Color(0xFFFDF2F8)
)

// --- 20. MOSS (Olive Green) ---
val MossLight = ThemeColorPalette(
    primary = Color(0xFF4D7C0F), // Lime 700
    onPrimary = Color.White,
    primaryContainer = Color(0xFFECFCCB), // Lime 100
    onPrimaryContainer = Color(0xFF1A2E05), // Lime 950
    secondary = Color(0xFF65A30D), // Lime 600
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF7FEE7), // Lime 50
    onSecondaryContainer = Color(0xFF365314)
)

val MossDark = ThemeColorPalette(
    primary = Color(0xFFA3E635), // Lime 400
    onPrimary = Color(0xFF1A2E05),
    primaryContainer = Color(0xFF4D7C0F), // Lime 700
    onPrimaryContainer = Color(0xFFECFCCB), // Lime 100
    secondary = Color(0xFFBEF264), // Lime 300
    onSecondary = Color(0xFF1A2E05),
    secondaryContainer = Color(0xFF3F6212), // Lime 800
    onSecondaryContainer = Color(0xFFF7FEE7)
)

// --- 21. CHARCOAL (Dark Grey) ---
val CharcoalLight = ThemeColorPalette(
    primary = Color(0xFF27272A), // Zinc 800
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF4F4F5), // Zinc 100
    onPrimaryContainer = Color(0xFF18181B), // Zinc 900
    secondary = Color(0xFF52525B), // Zinc 600
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFAFAFA), // Zinc 50
    onSecondaryContainer = Color(0xFF27272A)
)

val CharcoalDark = ThemeColorPalette(
    primary = Color(0xFFA1A1AA), // Zinc 400
    onPrimary = Color(0xFF18181B),
    primaryContainer = Color(0xFF3F3F46), // Zinc 700
    onPrimaryContainer = Color(0xFFF4F4F5), // Zinc 100
    secondary = Color(0xFFD4D4D8), // Zinc 300
    onSecondary = Color(0xFF18181B),
    secondaryContainer = Color(0xFF27272A), // Zinc 800
    onSecondaryContainer = Color(0xFFFAFAFA)
)

// --- 22. AQUA ---
val AquaLight = ThemeColorPalette(
    primary = Color(0xFF00B4D8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCAF0F8),
    onPrimaryContainer = Color(0xFF0077B6),
    secondary = Color(0xFF48CAE4),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFADE8F4),
    onSecondaryContainer = Color(0xFF023E8A)
)

val AquaDark = ThemeColorPalette(
    primary = Color(0xFF90E0EF),
    onPrimary = Color(0xFF03045E),
    primaryContainer = Color(0xFF0077B6),
    onPrimaryContainer = Color(0xFFCAF0F8),
    secondary = Color(0xFFADE8F4),
    onSecondary = Color(0xFF03045E),
    secondaryContainer = Color(0xFF0096C7),
    onSecondaryContainer = Color(0xFFCAF0F8)
)

// --- 23. BERRY ---
val BerryLight = ThemeColorPalette(
    primary = Color(0xFF9D174D),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFCE7F3),
    onPrimaryContainer = Color(0xFF500724),
    secondary = Color(0xFFBE185D),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFDF2F8),
    onSecondaryContainer = Color(0xFF831843)
)

val BerryDark = ThemeColorPalette(
    primary = Color(0xFFF472B6),
    onPrimary = Color(0xFF500724),
    primaryContainer = Color(0xFF831843),
    onPrimaryContainer = Color(0xFFFCE7F3),
    secondary = Color(0xFFF9A8D4),
    onSecondary = Color(0xFF500724),
    secondaryContainer = Color(0xFF9D174D),
    onSecondaryContainer = Color(0xFFFDF2F8)
)

// --- 24. BRONZE ---
val BronzeLight = ThemeColorPalette(
    primary = Color(0xFF92400E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFEF3C7),
    onPrimaryContainer = Color(0xFF451A03),
    secondary = Color(0xFFB45309),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFFBEB),
    onSecondaryContainer = Color(0xFF78350F)
)

val BronzeDark = ThemeColorPalette(
    primary = Color(0xFFF59E0B),
    onPrimary = Color(0xFF451A03),
    primaryContainer = Color(0xFF78350F),
    onPrimaryContainer = Color(0xFFFEF3C7),
    secondary = Color(0xFFD97706),
    onSecondary = Color(0xFF451A03),
    secondaryContainer = Color(0xFFB45309),
    onSecondaryContainer = Color(0xFFFFFBEB)
)

// --- 25. CRIMSON ---
val CrimsonLight = ThemeColorPalette(
    primary = Color(0xFF991B1B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFEE2E2),
    onPrimaryContainer = Color(0xFF450A0A),
    secondary = Color(0xFFB91C1C),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFEF2F2),
    onSecondaryContainer = Color(0xFF7F1D1D)
)

val CrimsonDark = ThemeColorPalette(
    primary = Color(0xFFEF4444),
    onPrimary = Color(0xFF450A0A),
    primaryContainer = Color(0xFF7F1D1D),
    onPrimaryContainer = Color(0xFFFEE2E2),
    secondary = Color(0xFFF87171),
    onSecondary = Color(0xFF450A0A),
    secondaryContainer = Color(0xFF991B1B),
    onSecondaryContainer = Color(0xFFFEF2F2)
)

// --- 26. CYAN ---
val CyanLight = ThemeColorPalette(
    primary = Color(0xFF0891B2),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCFFAFE),
    onPrimaryContainer = Color(0xFF155E75),
    secondary = Color(0xFF06B6D4),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFECFEFF),
    onSecondaryContainer = Color(0xFF164E63)
)

val CyanDark = ThemeColorPalette(
    primary = Color(0xFF22D3EE),
    onPrimary = Color(0xFF083344),
    primaryContainer = Color(0xFF164E63),
    onPrimaryContainer = Color(0xFFCFFAFE),
    secondary = Color(0xFF67E8F9),
    onSecondary = Color(0xFF083344),
    secondaryContainer = Color(0xFF155E75),
    onSecondaryContainer = Color(0xFFECFEFF)
)

// --- 27. DENIM ---
val DenimLight = ThemeColorPalette(
    primary = Color(0xFF1E40AF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDBEAFE),
    onPrimaryContainer = Color(0xFF1E3A8A),
    secondary = Color(0xFF2563EB),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEFF6FF),
    onSecondaryContainer = Color(0xFF1D4ED8)
)

val DenimDark = ThemeColorPalette(
    primary = Color(0xFF60A5FA),
    onPrimary = Color(0xFF172554),
    primaryContainer = Color(0xFF1D4ED8),
    onPrimaryContainer = Color(0xFFDBEAFE),
    secondary = Color(0xFF93C5FD),
    onSecondary = Color(0xFF172554),
    secondaryContainer = Color(0xFF1E3A8A),
    onSecondaryContainer = Color(0xFFEFF6FF)
)

// --- 28. FOREST ---
val ForestLight = ThemeColorPalette(
    primary = Color(0xFF166534),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCFCE7),
    onPrimaryContainer = Color(0xFF14532D),
    secondary = Color(0xFF15803D),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF0FDF4),
    onSecondaryContainer = Color(0xFF166534)
)

val ForestDark = ThemeColorPalette(
    primary = Color(0xFF4ADE80),
    onPrimary = Color(0xFF052E16),
    primaryContainer = Color(0xFF166534),
    onPrimaryContainer = Color(0xFFDCFCE7),
    secondary = Color(0xFF86EFAC),
    onSecondary = Color(0xFF052E16),
    secondaryContainer = Color(0xFF14532D),
    onSecondaryContainer = Color(0xFFF0FDF4)
)

// --- 29. GRAPE ---
val GrapeLight = ThemeColorPalette(
    primary = Color(0xFF7E22CE),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF3E8FF),
    onPrimaryContainer = Color(0xFF581C87),
    secondary = Color(0xFF9333EA),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFAF5FF),
    onSecondaryContainer = Color(0xFF6B21A8)
)

val GrapeDark = ThemeColorPalette(
    primary = Color(0xFFC084FC),
    onPrimary = Color(0xFF2E1065),
    primaryContainer = Color(0xFF6B21A8),
    onPrimaryContainer = Color(0xFFF3E8FF),
    secondary = Color(0xFFD8B4FE),
    onSecondary = Color(0xFF2E1065),
    secondaryContainer = Color(0xFF581C87),
    onSecondaryContainer = Color(0xFFFAF5FF)
)

// --- 30. LAVENDER ---
val LavenderLight = ThemeColorPalette(
    primary = Color(0xFF7C3AED),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEDE9FE),
    onPrimaryContainer = Color(0xFF4C1D95),
    secondary = Color(0xFF8B5CF6),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF5F3FF),
    onSecondaryContainer = Color(0xFF5B21B6)
)

val LavenderDark = ThemeColorPalette(
    primary = Color(0xFFA78BFA),
    onPrimary = Color(0xFF2E1065),
    primaryContainer = Color(0xFF5B21B6),
    onPrimaryContainer = Color(0xFFEDE9FE),
    secondary = Color(0xFFC4B5FD),
    onSecondary = Color(0xFF2E1065),
    secondaryContainer = Color(0xFF4C1D95),
    onSecondaryContainer = Color(0xFFF5F3FF)
)

// --- 31. LEMON ---
val LemonLight = ThemeColorPalette(
    primary = Color(0xFFA16207),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFEF9C3),
    onPrimaryContainer = Color(0xFF422006),
    secondary = Color(0xFFCA8A04),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFEFCE8),
    onSecondaryContainer = Color(0xFF713F12)
)

val LemonDark = ThemeColorPalette(
    primary = Color(0xFFFDE047),
    onPrimary = Color(0xFF422006),
    primaryContainer = Color(0xFF713F12),
    onPrimaryContainer = Color(0xFFFEF9C3),
    secondary = Color(0xFFFACC15),
    onSecondary = Color(0xFF422006),
    secondaryContainer = Color(0xFFA16207),
    onSecondaryContainer = Color(0xFFFEFCE8)
)

// --- 32. MAROON ---
val MaroonLight = ThemeColorPalette(
    primary = Color(0xFF7F1D1D),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFEE2E2),
    onPrimaryContainer = Color(0xFF450A0A),
    secondary = Color(0xFF991B1B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFEF2F2),
    onSecondaryContainer = Color(0xFF7F1D1D)
)

val MaroonDark = ThemeColorPalette(
    primary = Color(0xFFF87171),
    onPrimary = Color(0xFF450A0A),
    primaryContainer = Color(0xFF7F1D1D),
    onPrimaryContainer = Color(0xFFFEE2E2),
    secondary = Color(0xFFEF4444),
    onSecondary = Color(0xFF450A0A),
    secondaryContainer = Color(0xFF991B1B),
    onSecondaryContainer = Color(0xFFFEF2F2)
)

// --- 33. MIDNIGHT ---
val MidnightLight = ThemeColorPalette(
    primary = Color(0xFF0F172A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF1F5F9),
    onPrimaryContainer = Color(0xFF1E293B),
    secondary = Color(0xFF334155),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE2E8F0),
    onSecondaryContainer = Color(0xFF0F172A)
)

val MidnightDark = ThemeColorPalette(
    primary = Color(0xFF94A3B8),
    onPrimary = Color(0xFF0F172A),
    primaryContainer = Color(0xFF1E293B),
    onPrimaryContainer = Color(0xFFF1F5F9),
    secondary = Color(0xFF64748B),
    onSecondary = Color(0xFF0F172A),
    secondaryContainer = Color(0xFF0F172A),
    onSecondaryContainer = Color(0xFFE2E8F0)
)

// --- 34. MINT ---
val MintLight = ThemeColorPalette(
    primary = Color(0xFF059669),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD1FAE5),
    onPrimaryContainer = Color(0xFF064E3B),
    secondary = Color(0xFF10B981),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFECFDF5),
    onSecondaryContainer = Color(0xFF065F46)
)

val MintDark = ThemeColorPalette(
    primary = Color(0xFF34D399),
    onPrimary = Color(0xFF064E3B),
    primaryContainer = Color(0xFF065F46),
    onPrimaryContainer = Color(0xFFD1FAE5),
    secondary = Color(0xFF6EE7B7),
    onSecondary = Color(0xFF064E3B),
    secondaryContainer = Color(0xFF059669),
    onSecondaryContainer = Color(0xFFECFDF5)
)

// --- 35. NAVY ---
val NavyLight = ThemeColorPalette(
    primary = Color(0xFF1E3A8A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDBEAFE),
    onPrimaryContainer = Color(0xFF172554),
    secondary = Color(0xFF1E40AF),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEFF6FF),
    onSecondaryContainer = Color(0xFF1E3A8A)
)

val NavyDark = ThemeColorPalette(
    primary = Color(0xFF60A5FA),
    onPrimary = Color(0xFF172554),
    primaryContainer = Color(0xFF1E3A8A),
    onPrimaryContainer = Color(0xFFDBEAFE),
    secondary = Color(0xFF3B82F6),
    onSecondary = Color(0xFF172554),
    secondaryContainer = Color(0xFF1D4ED8),
    onSecondaryContainer = Color(0xFFEFF6FF)
)

// --- 36. OCEAN ---
val OceanLight = ThemeColorPalette(
    primary = Color(0xFF0369A1),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0F2FE),
    onPrimaryContainer = Color(0xFF075985),
    secondary = Color(0xFF0284C7),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF0F9FF),
    onSecondaryContainer = Color(0xFF0C4A6E)
)

val OceanDark = ThemeColorPalette(
    primary = Color(0xFF38BDF8),
    onPrimary = Color(0xFF082F49),
    primaryContainer = Color(0xFF075985),
    onPrimaryContainer = Color(0xFFE0F2FE),
    secondary = Color(0xFF7DD3FC),
    onSecondary = Color(0xFF082F49),
    secondaryContainer = Color(0xFF0C4A6E),
    onSecondaryContainer = Color(0xFFF0F9FF)
)

// --- 37. ORANGE ---
val OrangeLight = ThemeColorPalette(
    primary = Color(0xFFEA580C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFEDD5),
    onPrimaryContainer = Color(0xFF7C2D12),
    secondary = Color(0xFFF97316),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFF7ED),
    onSecondaryContainer = Color(0xFF9A3412)
)

val OrangeDark = ThemeColorPalette(
    primary = Color(0xFFFB923C),
    onPrimary = Color(0xFF431407),
    primaryContainer = Color(0xFF9A3412),
    onPrimaryContainer = Color(0xFFFFEDD5),
    secondary = Color(0xFFFDBA74),
    onSecondary = Color(0xFF431407),
    secondaryContainer = Color(0xFFC2410C),
    onSecondaryContainer = Color(0xFFFFF7ED)
)

// --- 38. PEACH ---
val PeachLight = ThemeColorPalette(
    primary = Color(0xFFD97706),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE4E6),
    onPrimaryContainer = Color(0xFF7C2D12),
    secondary = Color(0xFFF59E0B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFF1F2),
    onSecondaryContainer = Color(0xFF9A3412)
)

val PeachDark = ThemeColorPalette(
    primary = Color(0xFFFBBF24),
    onPrimary = Color(0xFF422006),
    primaryContainer = Color(0xFF9A3412),
    onPrimaryContainer = Color(0xFFFFE4E6),
    secondary = Color(0xFFFCD34D),
    onSecondary = Color(0xFF422006),
    secondaryContainer = Color(0xFFD97706),
    onSecondaryContainer = Color(0xFFFFF1F2)
)

// --- 39. PINE ---
val PineLight = ThemeColorPalette(
    primary = Color(0xFF065F46),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD1FAE5),
    onPrimaryContainer = Color(0xFF064E3B),
    secondary = Color(0xFF059669),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFECFDF5),
    onSecondaryContainer = Color(0xFF065F46)
)

val PineDark = ThemeColorPalette(
    primary = Color(0xFF34D399),
    onPrimary = Color(0xFF064E3B),
    primaryContainer = Color(0xFF065F46),
    onPrimaryContainer = Color(0xFFD1FAE5),
    secondary = Color(0xFF6EE7B7),
    onSecondary = Color(0xFF064E3B),
    secondaryContainer = Color(0xFF059669),
    onSecondaryContainer = Color(0xFFECFDF5)
)

// --- 40. PLUM ---
val PlumLight = ThemeColorPalette(
    primary = Color(0xFF701A75),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFDF4FF),
    onPrimaryContainer = Color(0xFF4A044E),
    secondary = Color(0xFF86198F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFAE8FF),
    onSecondaryContainer = Color(0xFF701A75)
)

val PlumDark = ThemeColorPalette(
    primary = Color(0xFFD946EF),
    onPrimary = Color(0xFF4A044E),
    primaryContainer = Color(0xFF701A75),
    onPrimaryContainer = Color(0xFFFDF4FF),
    secondary = Color(0xFFE879F9),
    onSecondary = Color(0xFF4A044E),
    secondaryContainer = Color(0xFFA21CAF),
    onSecondaryContainer = Color(0xFFFAE8FF)
)

// --- 41. WINE ---
val WineLight = ThemeColorPalette(
    primary = Color(0xFF881337),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFF1F2),
    onPrimaryContainer = Color(0xFF4C0519),
    secondary = Color(0xFF9F1239),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE4E6),
    onSecondaryContainer = Color(0xFF881337)
)

val WineDark = ThemeColorPalette(
    primary = Color(0xFFE11D48),
    onPrimary = Color(0xFF4C0519),
    primaryContainer = Color(0xFF881337),
    onPrimaryContainer = Color(0xFFFFF1F2),
    secondary = Color(0xFFFB7185),
    onSecondary = Color(0xFF4C0519),
    secondaryContainer = Color(0xFF9F1239),
    onSecondaryContainer = Color(0xFFFFE4E6)
)

// --- 42. STRAWBERRY ---
val StrawberryLight = ThemeColorPalette(
    primary = Color(0xFFDF1642),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE4E6),
    onPrimaryContainer = Color(0xFF9F1239),
    secondary = Color(0xFFBE123C),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFDF2F8),
    onSecondaryContainer = Color(0xFF831843)
)

val StrawberryDark = ThemeColorPalette(
    primary = Color(0xFFFB7185),
    onPrimary = Color(0xFF4C0519),
    primaryContainer = Color(0xFF9F1239),
    onPrimaryContainer = Color(0xFFFFE4E6),
    secondary = Color(0xFFF43F5E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF881337),
    onSecondaryContainer = Color(0xFFFDF2F8)
)

// --- 43. MANGO ---
val MangoLight = ThemeColorPalette(
    primary = Color(0xFFFF8200),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFF7ED),
    onPrimaryContainer = Color(0xFF9A3412),
    secondary = Color(0xFFF97316),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFEDD5),
    onSecondaryContainer = Color(0xFF7C2D12)
)

val MangoDark = ThemeColorPalette(
    primary = Color(0xFFFB923C),
    onPrimary = Color(0xFF431407),
    primaryContainer = Color(0xFF9A3412),
    onPrimaryContainer = Color(0xFFFFEDD5),
    secondary = Color(0xFFFDBA74),
    onSecondary = Color(0xFF431407),
    secondaryContainer = Color(0xFFC2410C),
    onSecondaryContainer = Color(0xFFFFF7ED)
)

// --- 44. PISTACHIO ---
val PistachioLight = ThemeColorPalette(
    primary = Color(0xFF93C572),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF1F8E9),
    onPrimaryContainer = Color(0xFF33691E),
    secondary = Color(0xFF8BC34A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCEDC8),
    onSecondaryContainer = Color(0xFF1B5E20)
)

val PistachioDark = ThemeColorPalette(
    primary = Color(0xFFAED581),
    onPrimary = Color(0xFF1B5E20),
    primaryContainer = Color(0xFF33691E),
    onPrimaryContainer = Color(0xFFF1F8E9),
    secondary = Color(0xFFC5E1A5),
    onSecondary = Color(0xFF1B5E20),
    secondaryContainer = Color(0xFF2E7D32),
    onSecondaryContainer = Color(0xFFF1F8E9)
)

// --- 45. OLIVE ---
val OliveLight = ThemeColorPalette(
    primary = Color(0xFF808000),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF9FBE7),
    onPrimaryContainer = Color(0xFF33691E),
    secondary = Color(0xFF9E9D24),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF0F4C3),
    onSecondaryContainer = Color(0xFF33691E)
)

val OliveDark = ThemeColorPalette(
    primary = Color(0xFFC0CA33),
    onPrimary = Color(0xFF1A1A00),
    primaryContainer = Color(0xFF33691E),
    onPrimaryContainer = Color(0xFFF9FBE7),
    secondary = Color(0xFFD4E157),
    onSecondary = Color(0xFF1A1A00),
    secondaryContainer = Color(0xFF33691E),
    onSecondaryContainer = Color(0xFFF0F4C3)
)

// --- 46. AZURE ---
val AzureLight = ThemeColorPalette(
    primary = Color(0xFF007FFF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE3F2FD),
    onPrimaryContainer = Color(0xFF0D47A1),
    secondary = Color(0xFF2196F3),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFBBDEFB),
    onSecondaryContainer = Color(0xFF0D47A1)
)

val AzureDark = ThemeColorPalette(
    primary = Color(0xFF64B5F6),
    onPrimary = Color(0xFF0D47A1),
    primaryContainer = Color(0xFF0D47A1),
    onPrimaryContainer = Color(0xFFE3F2FD),
    secondary = Color(0xFF42A5F5),
    onSecondary = Color(0xFF0D47A1),
    secondaryContainer = Color(0xFF1976D2),
    onSecondaryContainer = Color(0xFFE3F2FD)
)

// --- 47. VIOLET ---
val VioletLight = ThemeColorPalette(
    primary = Color(0xFF8F00FF),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF3E5F5),
    onPrimaryContainer = Color(0xFF4A148C),
    secondary = Color(0xFF9C27B0),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE1BEE7),
    onSecondaryContainer = Color(0xFF4A148C)
)

val VioletDark = ThemeColorPalette(
    primary = Color(0xFFBA68C8),
    onPrimary = Color(0xFF4A148C),
    primaryContainer = Color(0xFF4A148C),
    onPrimaryContainer = Color(0xFFF3E5F5),
    secondary = Color(0xFFCE93D8),
    onSecondary = Color(0xFF4A148C),
    secondaryContainer = Color(0xFF7B1FA2),
    onSecondaryContainer = Color(0xFFF3E5F5)
)

// --- 48. GRAPHITE ---
val GraphiteLight = ThemeColorPalette(
    primary = Color(0xFF383B3E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF5F5F5),
    onPrimaryContainer = Color(0xFF212121),
    secondary = Color(0xFF616161),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEEEEEE),
    onSecondaryContainer = Color(0xFF212121)
)

val GraphiteDark = ThemeColorPalette(
    primary = Color(0xFFBDBDBD),
    onPrimary = Color(0xFF212121),
    primaryContainer = Color(0xFF424242),
    onPrimaryContainer = Color(0xFFF5F5F5),
    secondary = Color(0xFF9E9E9E),
    onSecondary = Color(0xFF212121),
    secondaryContainer = Color(0xFF212121),
    onSecondaryContainer = Color(0xFFEEEEEE)
)

// --- 49. SAND ---
val SandLight = ThemeColorPalette(
    primary = Color(0xFFC2B280),
    onPrimary = Color(0xFF3E2723),
    primaryContainer = Color(0xFFEFEBE9),
    onPrimaryContainer = Color(0xFF4E342E),
    secondary = Color(0xFFA1887F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD7CCC8),
    onSecondaryContainer = Color(0xFF3E2723)
)

val SandDark = ThemeColorPalette(
    primary = Color(0xFFD7CCC8),
    onPrimary = Color(0xFF3E2723),
    primaryContainer = Color(0xFF5D4037),
    onPrimaryContainer = Color(0xFFEFEBE9),
    secondary = Color(0xFFBCAAA4),
    onSecondary = Color(0xFF3E2723),
    secondaryContainer = Color(0xFF3E2723),
    onSecondaryContainer = Color(0xFFD7CCC8)
)

// --- 50. CLAY ---
val ClayLight = ThemeColorPalette(
    primary = Color(0xFFA75D5D),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFBE9E7),
    onPrimaryContainer = Color(0xFFBF360C),
    secondary = Color(0xFFD84315),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFCCBC),
    onSecondaryContainer = Color(0xFFBF360C)
)

val ClayDark = ThemeColorPalette(
    primary = Color(0xFFFFAB91),
    onPrimary = Color(0xFF3E2723),
    primaryContainer = Color(0xFFBF360C),
    onPrimaryContainer = Color(0xFFFBE9E7),
    secondary = Color(0xFFFF8A65),
    onSecondary = Color(0xFF3E2723),
    secondaryContainer = Color(0xFFD84315),
    onSecondaryContainer = Color(0xFFFFE0B2)
)

val NeutralSlate = Color(0xFF334155)
val NeutralLightSlate = Color(0xFFF1F5F9)
val NeutralGray = Color(0xFF94A3B8)
val NeutralBorder = Color(0xFFE2E8F0)

val AppBackground = Color(0xFFF8FAFC)
val AppSurface = Color(0xFFFFFFFF)
val AppTextMain = Color(0xFF0F172A)
val AppTextSecondary = Color(0xFF475569)

val DarkBackground = Color(0xFF0F172A) // Slate 900
val DarkSurface = Color(0xFF1E293B) // Slate 800
val DarkSurfaceVariant = Color(0xFF334155) // Slate 700
val DarkText = Color(0xFFF8FAFC) // Slate 50
val DarkSubText = Color(0xFFCBD5E1) // Slate 300
val DarkBorder = Color(0xFF475569) // Slate 600

// Functional Colors
val SuccessGreen = Color(0xFF34D399)
val ErrorRed = Color(0xFFF87171)
