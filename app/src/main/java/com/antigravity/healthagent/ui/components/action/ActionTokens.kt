package com.antigravity.healthagent.ui.components.action

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

object ActionSize {
    val touchSm = 32.dp
    val touchMd = 40.dp
    val touchLg = 48.dp
    val touchXl = 56.dp
    val iconSm = 16.dp
    val iconMd = 20.dp
    val iconLg = 24.dp
    val badgeSize = 8.dp
}

data class ActionColors(
    val primary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val secondary: Color,
    val secondaryContainer: Color,
    val success: Color,
    val successContainer: Color,
    val destructive: Color,
    val destructiveContainer: Color,
    val noteFilled: Color,
    val noteEmpty: Color,
    val date: Color,
    val disabled: Color,
    val onDisabled: Color,
)

@Composable
fun rememberActionColors(): ActionColors {
    val scheme = MaterialTheme.colorScheme
    return remember(scheme) {
        ActionColors(
            primary = scheme.primary,
            primaryContainer = scheme.primaryContainer,
            onPrimaryContainer = scheme.onPrimaryContainer,
            secondary = scheme.secondary,
            secondaryContainer = scheme.secondaryContainer,
            success = scheme.tertiary,
            successContainer = scheme.tertiaryContainer,
            destructive = scheme.error,
            destructiveContainer = scheme.errorContainer,
            noteFilled = scheme.tertiary,
            noteEmpty = scheme.onSurfaceVariant,
            date = scheme.onSurfaceVariant,
            disabled = scheme.onSurface.copy(alpha = 0.12f),
            onDisabled = scheme.onSurface.copy(alpha = 0.38f),
        )
    }
}
