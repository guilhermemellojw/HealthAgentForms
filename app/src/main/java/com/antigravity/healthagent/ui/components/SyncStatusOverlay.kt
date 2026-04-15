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
import com.antigravity.healthagent.ui.home.SyncStage

@Composable
fun SyncStatusOverlay(
    syncStatus: SyncStatus,
    isEasyMode: Boolean = false
) {
    // SyncStatusOverlay no longer uses manual rotation state

    AnimatedVisibility(
        visible = syncStatus.stage != SyncStage.IDLE,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 80.dp) // Below the GlassTopAppBar
                .padding(horizontal = 24.dp)
                .zIndex(1000f), // Very high z-index
            contentAlignment = Alignment.TopCenter
        ) {
            val bgColor = when (syncStatus.stage) {
                SyncStage.SUCCESS -> MaterialTheme.colorScheme.tertiary
                SyncStage.ERROR -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.primary
            }

            val contentColor = when (syncStatus.stage) {
                SyncStage.SUCCESS -> MaterialTheme.colorScheme.onTertiary
                SyncStage.ERROR -> MaterialTheme.colorScheme.onError
                else -> MaterialTheme.colorScheme.onPrimary
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
                color = bgColor.copy(alpha = 0.9f),
                contentColor = contentColor,
                shape = RoundedCornerShape(32.dp), // Pill shape
                tonalElevation = 12.dp,
                shadowElevation = 8.dp,
                modifier = Modifier.wrapContentSize(),
                border = androidx.compose.foundation.BorderStroke(1.dp, contentColor.copy(alpha = 0.2f))
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
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
                            modifier = Modifier.size(if (isEasyMode) 32.dp else 28.dp)
                        ) {
                            if (isRotating) {
                                CircularProgressIndicator(
                                    modifier = Modifier.fillMaxSize(),
                                    strokeWidth = 2.dp,
                                    color = contentColor,
                                    trackColor = contentColor.copy(alpha = 0.1f)
                                )
                            }
                            
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                modifier = Modifier.size(if (isEasyMode) 22.dp else 18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = syncStatus.message ?: "Sincronizando...",
                            style = if (isEasyMode) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Black
                        )
                    }
                    
                    if (syncStatus.stage != SyncStage.SUCCESS && syncStatus.stage != SyncStage.ERROR) {
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { syncStatus.progress },
                            modifier = Modifier
                                .width(120.dp)
                                .height(if (isEasyMode) 6.dp else 4.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = contentColor,
                            trackColor = contentColor.copy(alpha = 0.3f),
                        )
                    }
                }
            }
        }
    }
}
