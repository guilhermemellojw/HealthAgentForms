package com.antigravity.healthagent.ui.components.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun SettingsItemCard(
    onClick: () -> Unit,
    headline: String,
    supportingText: String,
    leadingIcon: ImageVector,
    isSolarMode: Boolean = false,
    color: Color = MaterialTheme.colorScheme.primary,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
    badgeCount: Int? = null
) {
    val containerColor = if (isSolarMode) {
        MaterialTheme.colorScheme.surface
    } else {
        color.copy(alpha = 0.08f)
    }

    val borderColor = if (isSolarMode) {
        color.copy(alpha = 0.5f)
    } else {
        color.copy(alpha = 0.15f)
    }

    Surface(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onClick() },
        color = containerColor,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, borderColor)
    ) {
        ListItem(
            headlineContent = { Text(headline, fontWeight = FontWeight.Bold, color = textColor) },
            supportingContent = { Text(supportingText, style = MaterialTheme.typography.bodySmall) },
            leadingContent = { Icon(leadingIcon, null, tint = color) },
            trailingContent = {
                if (badgeCount != null) {
                    Badge(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ) {
                        Text(badgeCount.toString())
                    }
                }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )
    }
}
