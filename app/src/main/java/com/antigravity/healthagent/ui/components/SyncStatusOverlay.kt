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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.antigravity.healthagent.ui.home.SyncStatus
import androidx.compose.ui.graphics.Color
import com.antigravity.healthagent.ui.home.SyncStage
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.sp

@Composable
fun SyncStatusOverlay(
    syncStatus: SyncStatus,
    isEasyMode: Boolean = false,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = syncStatus.stage != SyncStage.IDLE,
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
                targetValue = syncStatus.progress,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessLow
                ),
                label = "progress"
            )

            val bgColor = when (syncStatus.stage) {
                SyncStage.SUCCESS -> Color(0xFF4CAF50)
                SyncStage.ERROR -> MaterialTheme.colorScheme.error
                else -> Color.Black.copy(alpha = 0.85f)
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
                color = bgColor,
                contentColor = Color.White,
                shape = RoundedCornerShape(32.dp),
                tonalElevation = 20.dp,
                shadowElevation = 16.dp,
                modifier = Modifier
                    .wrapContentSize()
                    .graphicsLayer {
                        // Subtle scale effect based on success
                        val scale = if (syncStatus.stage == SyncStage.SUCCESS) 1.05f else 1f
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
                        val isRotating = syncStatus.stage == SyncStage.STARTING || 
                                       syncStatus.stage == SyncStage.UPLOADING || 
                                       syncStatus.stage == SyncStage.DOWNLOADING

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
                            text = syncStatus.message ?: "Sincronizando...",
                            style = if (isEasyMode) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White,
                            letterSpacing = 0.5.sp
                        )
                    }
                    
                    if (syncStatus.stage != SyncStage.SUCCESS && syncStatus.stage != SyncStage.ERROR) {
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
