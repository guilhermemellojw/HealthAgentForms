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
import com.antigravity.healthagent.ui.home.SyncStatus
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun SyncFloatingBalloon(
    syncStatus: SyncStatus,
    modifier: Modifier = Modifier
) {
    if (syncStatus.lastSyncTimestamp <= 0) return

    val timeStr = SimpleDateFormat("HH:mm", Locale("pt", "BR"))
        .format(Date(syncStatus.lastSyncTimestamp))
    val dateStr = SimpleDateFormat("dd/MM", Locale("pt", "BR"))
        .format(Date(syncStatus.lastSyncTimestamp))

    AnimatedVisibility(
        visible = syncStatus.lastSyncTimestamp > 0,
        enter = fadeIn() + slideInVertically(),
        exit = fadeOut() + slideOutVertically()
    ) {
        Box(
            modifier = modifier
                .padding(top = 8.dp)
                .fillMaxWidth(),
            contentAlignment = Alignment.TopCenter
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.95f),
                shape = RoundedCornerShape(100.dp),
                shadowElevation = 8.dp,
                tonalElevation = 4.dp,
                border = androidx.compose.foundation.BorderStroke(
                    width = 1.dp,
                    brush = Brush.linearGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.1f)
                        )
                    )
                )
            ) {
                Row(
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.CloudDone,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Sincronizado: $dateStr às $timeStr",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        letterSpacing = 0.5.sp
                    )
                }
            }
        }
    }
}
