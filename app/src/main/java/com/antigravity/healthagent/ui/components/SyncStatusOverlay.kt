package com.antigravity.healthagent.ui.components

import androidx.compose.animation.core.*
import androidx.compose.animation.*
import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.antigravity.healthagent.ui.state.SyncUiState
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.sp

@Composable
fun SyncStatusOverlay(
    syncStatus: SyncUiState,
    isEasyMode: Boolean = false,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = syncStatus !is SyncUiState.Idle,
        enter = slideInVertically { -it * 2 } + fadeIn(animationSpec = tween(400)),
        exit = slideOutVertically { -it * 2 } + fadeOut(animationSpec = tween(400)),
        modifier = modifier // Apply the modifier here
    ) {
        Box(
            modifier = Modifier
                .wrapContentSize()
                .padding(top = 110.dp) // Float below SyncFloatingBalloon
                .zIndex(2000f),
            contentAlignment = Alignment.TopCenter
        ) {
            // Smoothly animate progress changes with a Spring spec
            val animatedProgress by animateFloatAsState(
                targetValue = (syncStatus as? SyncUiState.Syncing)?.progress ?: 1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessLow
                ),
                label = "progress"
            )

            val bgColor = when (syncStatus) {
                is SyncUiState.Success -> Color(0xFF4CAF50)
                is SyncUiState.Error -> MaterialTheme.colorScheme.error
                else -> Color.Black.copy(alpha = 0.85f)
            }

            val icon: ImageVector = when (syncStatus) {
                is SyncUiState.Syncing -> if (syncStatus.isDownloading) Icons.Default.CloudDownload else Icons.Default.CloudUpload
                is SyncUiState.Success -> Icons.Default.CloudDone
                is SyncUiState.Error -> Icons.Default.ErrorOutline
                is SyncUiState.Idle -> Icons.Default.Sync
            }

            Surface(
                color = bgColor,
                contentColor = Color.White,
                shape = RoundedCornerShape(32.dp),
                tonalElevation = 20.dp,
                shadowElevation = 16.dp,
                modifier = Modifier
                    .wrapContentSize()
                    .graphicsLayer {
                        // Subtle scale effect based on success
                        val scale = if (syncStatus is SyncUiState.Success) 1.05f else 1f
                        scaleX = scale
                        scaleY = scale
                    }
                    .widthIn(max = 280.dp), // Prevent it from becoming too wide
                border = androidx.compose.foundation.BorderStroke(
                    width = 1.dp,
                    brush = Brush.linearGradient(
                        colors = listOf(Color.White.copy(alpha = 0.3f), Color.Transparent)
                    )
                )
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        val isRotating = syncStatus is SyncUiState.Syncing

                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.size(if (isEasyMode) 40.dp else 32.dp)
                        ) {
                            if (isRotating) {
                                CircularProgressIndicator(
                                    modifier = Modifier.fillMaxSize(),
                                    strokeWidth = 3.dp,
                                    color = Color.White.copy(alpha = 0.6f),
                                    trackColor = Color.White.copy(alpha = 0.15f)
                                )
                            }
                            
                            // Animate icon swap
                            Crossfade(targetState = icon, label = "icon") { targetIcon ->
                                Icon(
                                    imageVector = targetIcon,
                                    contentDescription = null,
                                    modifier = Modifier.size(if (isEasyMode) 24.dp else 22.dp),
                                    tint = Color.White
                                )
                            }
                        }
                        
                        Spacer(modifier = Modifier.width(18.dp))
                        
                        Text(
                            text = when(syncStatus) {
                                is SyncUiState.Syncing -> syncStatus.message ?: "Sincronizando..."
                                is SyncUiState.Success -> "Concluído!"
                                is SyncUiState.Error -> syncStatus.message
                                is SyncUiState.Idle -> ""
                            },
                            style = if (isEasyMode) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White,
                            letterSpacing = 0.5.sp
                        )
                    }
                    
                    if (syncStatus is SyncUiState.Syncing) {
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        // Premium Glow Progress Bar
                        Box(
                            modifier = Modifier
                                .width(160.dp)
                                .height(if (isEasyMode) 8.dp else 6.dp)
                                .clip(RoundedCornerShape(100.dp))
                                .background(Color.White.copy(alpha = 0.1f))
                        ) {
                            // Shimmer Effect Background
                            val infiniteTransition = rememberInfiniteTransition(label = "shimmer")
                            val shimmerOffset by infiniteTransition.animateFloat(
                                initialValue = -200f,
                                targetValue = 400f,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(1200, easing = LinearEasing),
                                    repeatMode = RepeatMode.Restart
                                ),
                                label = "shimmer"
                            )

                            // Actual Progress
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(animatedProgress)
                                    .background(
                                        Brush.horizontalGradient(
                                            colors = listOf(
                                                MaterialTheme.colorScheme.primary,
                                                MaterialTheme.colorScheme.tertiary,
                                                MaterialTheme.colorScheme.primary
                                            ),
                                            startX = shimmerOffset,
                                            endX = shimmerOffset + 200f
                                        )
                                    )
                                    .shadow(
                                        elevation = 10.dp,
                                        shape = RoundedCornerShape(100.dp),
                                        ambientColor = MaterialTheme.colorScheme.primary,
                                        spotColor = MaterialTheme.colorScheme.primary
                                    )
                            )
                        }
                    }
                }
            }
        }
    }
}
