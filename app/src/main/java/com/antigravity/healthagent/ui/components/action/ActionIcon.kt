package com.antigravity.healthagent.ui.components.action

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

object ActionIcon {

    @Composable
    fun Standard(
        icon: ImageVector,
        onClick: () -> Unit,
        contentDescription: String?,
        tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
        enabled: Boolean = true,
        touchSize: Dp = ActionSize.touchMd,
        iconSize: Dp = ActionSize.iconMd,
        modifier: Modifier = Modifier,
    ) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.size(touchSize),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(iconSize),
                tint = tint.copy(alpha = if (enabled) 1f else 0.38f),
            )
        }
    }

    @Composable
    fun Tonal(
        icon: ImageVector,
        onClick: () -> Unit,
        contentDescription: String?,
        containerColor: Color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
        contentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
        enabled: Boolean = true,
        touchSize: Dp = ActionSize.touchMd,
        iconSize: Dp = ActionSize.iconMd,
        modifier: Modifier = Modifier,
    ) {
        Surface(
            onClick = onClick,
            color = if (enabled) containerColor else containerColor.copy(alpha = 0.12f),
            shape = RoundedCornerShape(12.dp),
            modifier = modifier.size(touchSize),
            enabled = enabled,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = contentDescription,
                    modifier = Modifier.size(iconSize),
                    tint = if (enabled) contentColor else contentColor.copy(alpha = 0.38f),
                )
            }
        }
    }

    @Composable
    fun Outlined(
        icon: ImageVector,
        onClick: () -> Unit,
        contentDescription: String?,
        borderColor: Color = MaterialTheme.colorScheme.outline,
        contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
        enabled: Boolean = true,
        touchSize: Dp = ActionSize.touchMd,
        iconSize: Dp = ActionSize.iconMd,
        modifier: Modifier = Modifier,
    ) {
        Surface(
            onClick = onClick,
            color = Color.Transparent,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(
                width = 1.5.dp,
                color = if (enabled) borderColor else borderColor.copy(alpha = 0.12f),
            ),
            modifier = modifier.size(touchSize),
            enabled = enabled,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = contentDescription,
                    modifier = Modifier.size(iconSize),
                    tint = if (enabled) contentColor else contentColor.copy(alpha = 0.38f),
                )
            }
        }
    }

    @Composable
    fun Badge(
        icon: ImageVector,
        onClick: () -> Unit,
        contentDescription: String?,
        hasBadge: Boolean = false,
        badgeColor: Color = MaterialTheme.colorScheme.tertiary,
        tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
        enabled: Boolean = true,
        touchSize: Dp = ActionSize.touchMd,
        iconSize: Dp = ActionSize.iconMd,
        modifier: Modifier = Modifier,
    ) {
        Box(modifier = modifier.size(touchSize)) {
            IconButton(
                onClick = onClick,
                enabled = enabled,
                modifier = Modifier.fillMaxSize(),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = contentDescription,
                    modifier = Modifier.size(iconSize),
                    tint = tint.copy(alpha = if (enabled) 1f else 0.38f),
                )
            }
            if (hasBadge) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 2.dp, y = 2.dp)
                        .size(ActionSize.badgeSize)
                        .clip(CircleShape)
                        .background(badgeColor),
                )
            }
        }
    }
}
