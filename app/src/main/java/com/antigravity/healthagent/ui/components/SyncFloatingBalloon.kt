package com.antigravity.healthagent.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.antigravity.healthagent.ui.home.SyncStage
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.clip
import com.antigravity.healthagent.ui.home.SyncStatus
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun SyncFloatingBalloon(
    syncStatus: SyncStatus,
    isEasyMode: Boolean = false,
    isSolarMode: Boolean = false,
    modifier: Modifier = Modifier
) {
    if (syncStatus.lastSyncTimestamp <= 0) return

    val timeStr = SimpleDateFormat("HH:mm", Locale("pt", "BR"))
        .format(Date(syncStatus.lastSyncTimestamp))
    val dateStr = SimpleDateFormat("dd/MM", Locale("pt", "BR"))
        .format(Date(syncStatus.lastSyncTimestamp))

    Box(
        modifier = modifier
            .padding(top = 8.dp)
            .fillMaxWidth(),
        contentAlignment = Alignment.TopCenter
    ) {
        Surface(
            color = if (isSolarMode) MaterialTheme.colorScheme.surface.copy(alpha = 0.9f) else Color.Black.copy(alpha = 0.4f),
            shape = RoundedCornerShape(100.dp),
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            border = androidx.compose.foundation.BorderStroke(
                width = 1.dp,
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.3f),
                        Color.White.copy(alpha = 0.05f)
                    )
                )
            ),
            modifier = Modifier.graphicsLayer {
                this.shadowElevation = 12.dp.toPx()
                this.shape = RoundedCornerShape(24.dp)
                this.clip = true
            }
        ) {
            Row(
                modifier = Modifier
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.CloudDone,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = if (isSolarMode) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.8f)
                )
                
                Spacer(Modifier.width(8.dp))
                
                Text(
                    text = "Nuvem atualizada: $dateStr às $timeStr",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (isSolarMode) MaterialTheme.colorScheme.onSurface else Color.White,
                    letterSpacing = 0.2.sp,
                    fontSize = if (isEasyMode) 11.sp else 9.sp
                )
            }
        }
    }
}
