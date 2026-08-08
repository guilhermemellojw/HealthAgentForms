package com.antigravity.healthagent.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.antigravity.healthagent.data.sync.SyncFeedbackManager
import com.antigravity.healthagent.ui.state.SyncUiState
import com.antigravity.healthagent.utils.TimeManager
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import kotlinx.coroutines.delay

@Composable
fun SyncCompactBalloon(
    feedbackManager: SyncFeedbackManager,
    isEasyMode: Boolean = false,
    isSolarMode: Boolean = false,
    isPullActive: Boolean = false,
    showWhenIdle: Boolean = false,
    modifier: Modifier = Modifier
) {
    val syncStatus by feedbackManager.feedback.collectAsState()
    val height = if (isEasyMode) 56.dp else 48.dp
    val iconSize = if (isEasyMode) 24.dp else 20.dp
    val progressHeight = if (isEasyMode) 6.dp else 4.dp
    val textStyle = if (isEasyMode) MaterialTheme.typography.titleSmall else MaterialTheme.typography.labelMedium

    val isVisible = ((syncStatus !is SyncUiState.Idle) || showWhenIdle) && !isPullActive

    val infiniteTransition = rememberInfiniteTransition(label = "sync_spin")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "spin"
    )

    // Extract status-specific values using separate variables
    val status = syncStatus

    // Tick every minute so relative timestamps ("há X min") stay fresh
    var nowMs by remember { mutableLongStateOf(TimeManager.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            nowMs = TimeManager.currentTimeMillis()
        }
    }

    val isSyncing: Boolean
    val isDownloading: Boolean
    val progress: Float
    val message: String
    val isSuccess: Boolean
    val isError: Boolean
    
    when (status) {
        is SyncUiState.Syncing -> {
            isSyncing = true
            isDownloading = status.isDownloading
            progress = status.progress
            message = status.message ?: (if (status.isDownloading) "Baixando..." else "Enviando...")
            isSuccess = false
            isError = false
        }
        is SyncUiState.Success -> {
            isSyncing = false
            isDownloading = false
            progress = 1f
            val timeStr = status.lastSyncTime?.let { feedbackManager.formatRelative(it, now = nowMs) } ?: "agora mesmo"
            message = "Sincronizado: $timeStr"
            isSuccess = true
            isError = false
        }
        is SyncUiState.Idle -> {
            val lastSync = status.lastSyncTime
            if (showWhenIdle && lastSync != null) {
                isSyncing = false
                isDownloading = false
                progress = 1f
                message = "Sincronizado: ${feedbackManager.formatRelative(lastSync, now = nowMs)}"
                isSuccess = true
                isError = false
            } else {
                isSyncing = false
                isDownloading = false
                progress = 1f
                message = ""
                isSuccess = false
                isError = false
            }
        }
        is SyncUiState.Error -> {
            isSyncing = false
            isDownloading = false
            progress = 1f
            message = "Erro: ${status.message}"
            isSuccess = false
            isError = true
        }
        else -> {
            isSyncing = false
            isDownloading = false
            progress = 1f
            message = ""
            isSuccess = false
            isError = false
        }
    }
    
    val isLightTheme = MaterialTheme.colorScheme.background.red > 0.5f
    val bgColor = when {
        isError -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.9f)
        isSolarMode || isLightTheme -> MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
        else -> Color(0xCC1A1A1A)
    }
    val contentColor = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.primary
    
    val icon = when {
        isSyncing && isDownloading -> Icons.Default.CloudDownload
        isSyncing && !isDownloading -> Icons.Default.CloudUpload
        isSuccess -> Icons.Default.CheckCircle
        isError -> Icons.Default.ErrorOutline
        else -> Icons.Default.Sync
    }
    
    val showProgress = isSyncing
    val showTimestamp = isSuccess
    val showDismiss = isError
    val showSkewWarning = isSuccess && syncStatus.clockSkewMs != 0L && kotlin.math.abs(syncStatus.clockSkewMs) > 120_000

    AnimatedVisibility(
        visible = isVisible,
        enter = slideInVertically { -it } + fadeIn(animationSpec = tween(300, easing = EaseOut)),
        exit = slideOutVertically { -it } + fadeOut(animationSpec = tween(200, easing = EaseIn)),
        modifier = modifier
            .padding(top = 12.dp)
            .zIndex(3000f)
    ) {
        val animatedProgress by animateFloatAsState(
            targetValue = if (isSyncing) progress else 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = Spring.StiffnessLow
            ),
            label = "progress"
        )

        Surface(
            color = bgColor,
            contentColor = contentColor,
            shape = RoundedCornerShape(100.dp),
            tonalElevation = 8.dp,
            shadowElevation = 4.dp,
            modifier = Modifier
                .wrapContentWidth()
                .widthIn(min = 180.dp, max = 340.dp)
                .height(height)
                .clip(RoundedCornerShape(100.dp))
                .border(
                    width = 1.5.dp,
                    brush = Brush.linearGradient(
                        colors = listOf(
                            if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            if (isError) MaterialTheme.colorScheme.error.copy(alpha = 0.5f) else MaterialTheme.colorScheme.secondary
                        )
                    ),
                    shape = RoundedCornerShape(100.dp)
                )
        ) {
            Row(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .height(height)
                ,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Icon with rotation
                SyncIconBox(
                    icon = icon,
                    isSyncing = isSyncing,
                    rotation = rotation,
                    iconSize = iconSize,
                    contentColor = contentColor
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column(
                    modifier = Modifier
                        .wrapContentWidth()
                        .padding(vertical = 4.dp),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = message,
                        style = textStyle,
                        fontWeight = FontWeight.ExtraBold,
                        color = contentColor,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )

                    if (showProgress) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(progressHeight)
                                .clip(RoundedCornerShape(100.dp))
                                .background(contentColor.copy(alpha = 0.15f))
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(animatedProgress)
                                    .background(
                                        Brush.horizontalGradient(
                                            colors = listOf(
                                                MaterialTheme.colorScheme.primary,
                                                MaterialTheme.colorScheme.secondary,
                                                MaterialTheme.colorScheme.primary
                                            )
                                        )
                                    )
                                    .clip(RoundedCornerShape(100.dp))
                            )
                        }
                    }
                }

                if (showTimestamp) {
                    if (showSkewWarning) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Desvio de relógio detectado",
                            modifier = Modifier
                                .size(16.dp)
                                .padding(start = 8.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }

                if (showDismiss) {
                    IconButton(
                        onClick = {
                            // Trigger error dismissal by updating sync status
                            // This will be handled by the ViewModel's syncStatus flow
                        },
                        modifier = Modifier.padding(start = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Dispensar",
                            modifier = Modifier.size(18.dp),
                            tint = contentColor.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SyncIconBox(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isSyncing: Boolean,
    rotation: Float,
    iconSize: androidx.compose.ui.unit.Dp,
    contentColor: Color
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(iconSize)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(iconSize),
            tint = contentColor
        )
        
        if (isSyncing) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .rotate(rotation)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.fillMaxSize(),
                    strokeWidth = 2.dp,
                    color = contentColor.copy(alpha = 0.4f),
                    trackColor = contentColor.copy(alpha = 0.1f)
                )
            }
        }
    }
}