package com.antigravity.healthagent.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.ui.zIndex
import com.antigravity.healthagent.ui.state.SyncUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoxScope.CustomSyncPullIndicator(
    state: PullToRefreshState,
    isRefreshing: Boolean,
    isSolarMode: Boolean = false,
    syncStatus: SyncUiState? = null,
    pullText: String = "Puxe para sincronizar...",
    releaseText: String = "Solte para enviar!",
    refreshingText: String = "Sincronizando...",
    modifier: Modifier = Modifier
) {
    // 1. Calculate dynamic pull values
    val dragFraction = state.distanceFraction
    val rotation = dragFraction * 360f
    
    val displayRefreshingText = remember(isRefreshing, syncStatus, refreshingText) {
        if (isRefreshing && syncStatus is SyncUiState.Syncing) {
            syncStatus.message ?: refreshingText
        } else {
            refreshingText
        }
    }
    
    // Scale goes elastically from 0.8f up to 1.0f when dragging
    val scale = (0.8f + (dragFraction * 0.2f)).coerceIn(0.8f, 1.0f)
    
    // Pulsing scale for "Release to sync" state
    val releasePulseTransition = rememberInfiniteTransition(label = "releasePulse")
    val releasePulseScale by releasePulseTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "releasePulseScale"
    )
    
    val finalScale = if (dragFraction >= 1.0f && !isRefreshing) {
        scale * releasePulseScale
    } else {
        scale
    }
    
    val isLightTheme = MaterialTheme.colorScheme.background.red > 0.5f

    // Smooth opacity fade-in
    val opacity = dragFraction.coerceIn(0f, 1f)

    // 2. Haptic feedback trigger on threshold crossing
    val haptic = LocalHapticFeedback.current
    var hasTriggeredHaptic by remember { mutableStateOf(false) }

    LaunchedEffect(dragFraction) {
        if (dragFraction >= 1.0f && !hasTriggeredHaptic) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            hasTriggeredHaptic = true
        } else if (dragFraction < 1.0f) {
            hasTriggeredHaptic = false
        }
    }

    // 3. Infinite rotation for the gradient circle when syncing
    val infiniteTransition = rememberInfiniteTransition(label = "syncRotation")
    val spinningRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "spinningRotation"
    )

    // 4. Smooth scale and slide entrance/exit animations for the entire card
    AnimatedVisibility(
        visible = dragFraction > 0.01f || isRefreshing,
        enter = fadeIn(animationSpec = tween(150)) + slideInVertically(
            initialOffsetY = { -it },
            animationSpec = tween(300, easing = EaseOutBack)
        ),
        exit = fadeOut(animationSpec = tween(150)) + slideOutVertically(
            targetOffsetY = { -it },
            animationSpec = tween(200)
        ),
        modifier = modifier
            .align(Alignment.TopCenter)
            .padding(top = 12.dp)
            .zIndex(5000f)
    ) {
        Card(
            shape = RoundedCornerShape(100.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isSolarMode || isLightTheme) {
                    MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
                } else {
                    Color(0xCC1A1A1A) // Premium semi-transparent dark grey
                }
            ),
            border = BorderStroke(
                width = if (dragFraction >= 1.0f || isRefreshing) 1.5.dp else 1.dp,
                brush = Brush.linearGradient(
                    colors = if (dragFraction >= 1.0f || isRefreshing) {
                        listOf(
                            MaterialTheme.colorScheme.primary, // App primary color neon glow
                            MaterialTheme.colorScheme.secondary  // App secondary color neon glow
                        )
                    } else {
                        if (isLightTheme) {
                            listOf(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                                MaterialTheme.colorScheme.secondary.copy(alpha = 0.1f)
                            )
                        } else {
                            listOf(
                                Color.White.copy(alpha = 0.4f),
                                Color.White.copy(alpha = 0.1f)
                            )
                        }
                    }
                )
            ),
            modifier = Modifier
                .graphicsLayer {
                    scaleX = finalScale
                    scaleY = finalScale
                    alpha = opacity
                }
                .shadow(
                    elevation = if (dragFraction >= 1.0f || isRefreshing) 16.dp else 8.dp,
                    shape = RoundedCornerShape(100.dp),
                    ambientColor = if (dragFraction >= 1.0f || isRefreshing) {
                        if (isLightTheme) Color.Black.copy(alpha = 0.2f) else MaterialTheme.colorScheme.primary
                    } else {
                        Color.Black
                    },
                    spotColor = if (dragFraction >= 1.0f || isRefreshing) {
                        if (isLightTheme) Color.Black.copy(alpha = 0.2f) else MaterialTheme.colorScheme.primary
                    } else {
                        Color.Black
                    }
                )
                .wrapContentSize()
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                // Spinning gradient accent or standard rotating sync icon
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(24.dp)
                ) {
                    if (isRefreshing) {
                        // Neon spinning gradient circle
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .rotate(spinningRotation)
                                .background(
                                    brush = Brush.sweepGradient(
                                        colors = listOf(
                                            MaterialTheme.colorScheme.primary,
                                            MaterialTheme.colorScheme.secondary,
                                            Color.Transparent
                                        )
                                    ),
                                    shape = RoundedCornerShape(100.dp)
                                )
                                .padding(2.dp)
                                .background(
                                    color = if (isSolarMode || isLightTheme) MaterialTheme.colorScheme.surface else Color(0xFF1A1A1A),
                                    shape = RoundedCornerShape(100.dp)
                                )
                        )
                    }
                    
                    Icon(
                        imageVector = Icons.Default.Sync,
                        contentDescription = null,
                        modifier = Modifier
                            .size(16.dp)
                            .rotate(if (isRefreshing) spinningRotation else rotation),
                        tint = if (dragFraction >= 1.0f || isRefreshing) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            if (isSolarMode || isLightTheme) MaterialTheme.colorScheme.primary else Color.White
                        }
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Text(
                    text = when {
                        isRefreshing -> displayRefreshingText
                        dragFraction >= 1.0f -> releaseText
                        else -> pullText
                    },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 11.sp,
                    letterSpacing = 0.5.sp,
                    color = if (dragFraction >= 1.0f || isRefreshing) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        if (isSolarMode || isLightTheme) MaterialTheme.colorScheme.onSurface else Color.White
                    }
                )
            }
        }
    }
}
