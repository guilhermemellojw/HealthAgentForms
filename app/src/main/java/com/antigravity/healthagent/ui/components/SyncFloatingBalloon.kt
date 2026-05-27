package com.antigravity.healthagent.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.antigravity.healthagent.ui.state.SyncUiState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.clip
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun SyncFloatingBalloon(
    syncStatus: SyncUiState,
    isEasyMode: Boolean = false,
    isSolarMode: Boolean = false,
    isPullActive: Boolean = false,
    topPadding: androidx.compose.ui.unit.Dp = 72.dp,
    modifier: Modifier = Modifier
) {
    // Entrance/Exit Animation
    AnimatedVisibility(
        visible = (syncStatus.lastSyncTime ?: 0L) > 0 && !isPullActive,
        enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
        exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top),
        modifier = modifier.fillMaxWidth()
    ) {
        val timeStr = remember(syncStatus.lastSyncTime) {
            SimpleDateFormat("HH:mm", Locale("pt", "BR")).format(Date(syncStatus.lastSyncTime ?: 0L))
        }
        val dateStr = remember(syncStatus.lastSyncTime) {
            SimpleDateFormat("dd/MM", Locale("pt", "BR")).format(Date(syncStatus.lastSyncTime ?: 0L))
        }

        // Pulse Animation for the icon
        val infiniteTransition = rememberInfiniteTransition(label = "pulse")
        val iconAlpha by infiniteTransition.animateFloat(
            initialValue = 0.6f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(1500, easing = LinearOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "alpha"
        )

        val isLightTheme = MaterialTheme.colorScheme.background.red > 0.5f

        Box(
            modifier = Modifier
                .padding(top = topPadding)
                .fillMaxWidth(),
            contentAlignment = Alignment.TopCenter
        ) {
            Surface(
                color = if (isSolarMode || isLightTheme) {
                    MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
                } else {
                    Color(0xCC1A1A1A) // Premium semi-transparent dark grey matching the pull indicator
                },
                shape = RoundedCornerShape(100.dp),
                border = androidx.compose.foundation.BorderStroke(
                    width = 1.5.dp,
                    brush = Brush.linearGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primary, // App primary color neon glow
                            MaterialTheme.colorScheme.secondary  // App secondary color neon glow
                        )
                    )
                ),
                modifier = Modifier
                    .shadow(
                        elevation = 12.dp,
                        shape = RoundedCornerShape(100.dp),
                        ambientColor = if (isLightTheme) Color.Black.copy(alpha = 0.2f) else MaterialTheme.colorScheme.primary,
                        spotColor = if (isLightTheme) Color.Black.copy(alpha = 0.2f) else MaterialTheme.colorScheme.primary
                    )
                    .widthIn(min = 180.dp)
            ) {
                Row(
                    modifier = Modifier
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.CloudDone,
                        contentDescription = null,
                        modifier = Modifier
                            .size(18.dp)
                            .graphicsLayer { alpha = iconAlpha },
                        tint = MaterialTheme.colorScheme.primary
                    )
                    
                    Spacer(Modifier.width(10.dp))
                    
                    Text(
                        text = "Sincronizado: $dateStr às $timeStr",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.primary,
                        letterSpacing = 0.5.sp,
                        fontSize = if (isEasyMode) 13.sp else 11.sp
                    )
                }
            }
        }
    }
}
