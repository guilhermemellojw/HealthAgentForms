package com.antigravity.healthagent.ui.components

import androidx.compose.animation.core.*
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.antigravity.healthagent.ui.home.SyncStatus
import androidx.compose.ui.graphics.Color
import com.antigravity.healthagent.ui.home.SyncStage

@Composable
fun SyncStatusOverlay(
    syncStatus: SyncStatus,
    isEasyMode: Boolean = false
) {
    AnimatedVisibility(
        visible = syncStatus.stage != SyncStage.IDLE,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 84.dp) // Below the GlassTopAppBar
                .padding(horizontal = 32.dp)
                .zIndex(1000f),
            contentAlignment = Alignment.TopCenter
        ) {
            val bgColor = when (syncStatus.stage) {
                SyncStage.SUCCESS -> Color(0xFF4CAF50)
                SyncStage.ERROR -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.primary
            }

            val icon: ImageVector = when (syncStatus.stage) {
                SyncStage.STARTING -> Icons.Default.Sync
                SyncStage.UPLOADING -> Icons.Default.CloudUpload
                SyncStage.DOWNLOADING -> Icons.Default.CloudDownload
                SyncStage.SUCCESS -> Icons.Default.CloudDone
                SyncStage.ERROR -> Icons.Default.ErrorOutline
                SyncStage.IDLE -> Icons.Default.Sync
            }

            Surface(
                color = if (syncStatus.stage == SyncStage.ERROR) bgColor.copy(alpha = 0.95f) else Color.Black.copy(alpha = 0.8f),
                contentColor = Color.White,
                shape = RoundedCornerShape(24.dp),
                tonalElevation = 16.dp,
                shadowElevation = 12.dp,
                modifier = Modifier.wrapContentSize(),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.2f))
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        val isRotating = syncStatus.stage == SyncStage.STARTING || 
                                       syncStatus.stage == SyncStage.UPLOADING || 
                                       syncStatus.stage == SyncStage.DOWNLOADING

                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.size(if (isEasyMode) 36.dp else 30.dp)
                        ) {
                            if (isRotating) {
                                CircularProgressIndicator(
                                    modifier = Modifier.fillMaxSize(),
                                    strokeWidth = 3.dp,
                                    color = Color.White,
                                    trackColor = Color.White.copy(alpha = 0.2f)
                                )
                            }
                            
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                modifier = Modifier.size(if (isEasyMode) 24.dp else 20.dp),
                                tint = if (syncStatus.stage == SyncStage.SUCCESS) Color(0xFF4CAF50) else Color.White
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = syncStatus.message ?: "Sincronizando...",
                            style = if (isEasyMode) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Black,
                            color = Color.White
                        )
                    }
                    
                    if (syncStatus.stage != SyncStage.SUCCESS && syncStatus.stage != SyncStage.ERROR) {
                        Spacer(modifier = Modifier.height(12.dp))
                        LinearProgressIndicator(
                            progress = { syncStatus.progress },
                            modifier = Modifier
                                .width(180.dp)
                                .height(if (isEasyMode) 8.dp else 6.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = Color.White.copy(alpha = 0.2f),
                        )
                    }
                }
            }
        }
    }
}
