package com.antigravity.healthagent.ui.components.action

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object ActionButton {

    @Composable
    fun Primary(
        icon: ImageVector,
        label: String,
        onClick: () -> Unit,
        enabled: Boolean = true,
        isActive: Boolean = false,
        activeColor: Color = MaterialTheme.colorScheme.tertiary,
        activeContentColor: Color = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier: Modifier = Modifier,
    ) {
        val targetBgColor = when {
            !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
            isActive -> activeColor
            else -> MaterialTheme.colorScheme.primary
        }
        val targetContentColor = when {
            !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            isActive -> activeContentColor
            else -> MaterialTheme.colorScheme.onPrimary
        }
        val bgColor by animateColorAsState(
            targetValue = targetBgColor,
            animationSpec = tween(300),
            label = "buttonBg",
        )
        val contentColor by animateColorAsState(
            targetValue = targetContentColor,
            animationSpec = tween(300),
            label = "buttonContent",
        )

        Surface(
            onClick = onClick,
            color = bgColor,
            shape = RoundedCornerShape(20.dp),
            modifier = modifier
                .fillMaxWidth()
                .height(ActionSize.touchXl),
            enabled = enabled,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Surface(
                    color = contentColor.copy(alpha = 0.2f),
                    shape = CircleShape,
                    modifier = Modifier.size(ActionSize.touchMd),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            modifier = Modifier.size(ActionSize.iconMd),
                            tint = contentColor,
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = contentColor,
                    maxLines = 1,
                )
            }
        }
    }

    @Composable
    fun Secondary(
        icon: ImageVector,
        label: String,
        onClick: () -> Unit,
        enabled: Boolean = true,
        iconTint: Color = MaterialTheme.colorScheme.primary,
        labelColor: Color = MaterialTheme.colorScheme.primary,
        borderColor: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
        modifier: Modifier = Modifier,
    ) {
        Surface(
            onClick = onClick,
            color = Color.Transparent,
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(
                width = 1.5.dp,
                color = if (enabled) borderColor else borderColor.copy(alpha = 0.12f),
            ),
            modifier = modifier
                .fillMaxWidth()
                .height(ActionSize.touchXl),
            enabled = enabled,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(ActionSize.iconMd),
                    tint = if (enabled) iconTint else iconTint.copy(alpha = 0.38f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = if (enabled) labelColor else labelColor.copy(alpha = 0.38f),
                    maxLines = 1,
                )
            }
        }
    }

    @Composable
    fun Destructive(
        icon: ImageVector,
        label: String,
        onClick: () -> Unit,
        enabled: Boolean = true,
        modifier: Modifier = Modifier,
    ) {
        val haptic = LocalHapticFeedback.current

        Surface(
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            },
            color = Color.Transparent,
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(
                width = 1.5.dp,
                color = if (enabled) MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                else MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
            ),
            modifier = modifier
                .fillMaxWidth()
                .height(ActionSize.touchXl),
            enabled = enabled,
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(ActionSize.iconMd),
                    tint = if (enabled) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.error.copy(alpha = 0.38f),
                )
                Text(
                    text = label,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (enabled) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.error.copy(alpha = 0.38f),
                )
            }
        }
    }

    @Composable
    fun IconLabel(
        icon: ImageVector,
        label: String,
        onClick: () -> Unit,
        enabled: Boolean = true,
        contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
        borderColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
        modifier: Modifier = Modifier,
    ) {
        Surface(
            onClick = onClick,
            color = Color.Transparent,
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(
                width = 1.5.dp,
                color = if (enabled) borderColor else borderColor.copy(alpha = 0.12f),
            ),
            modifier = modifier
                .fillMaxWidth()
                .fillMaxHeight(),
            enabled = enabled,
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(ActionSize.iconMd),
                    tint = if (enabled) contentColor else contentColor.copy(alpha = 0.38f),
                )
                Text(
                    text = label,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (enabled) contentColor else contentColor.copy(alpha = 0.38f),
                )
            }
        }
    }
}
