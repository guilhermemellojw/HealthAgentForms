package com.antigravity.healthagent.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ProductionProgressBar(
    current: Int,
    total: Int,
    isEasyMode: Boolean = false,
    focusCount: Int = 0,
    modifier: Modifier = Modifier
) {
    val progress = if (total > 0) (current.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f
    val animatedProgress by animateFloatAsState(
        targetValue = progress, 
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 200, easing = androidx.compose.animation.core.FastOutSlowInEasing),
        label = "ProgressAnimation"
    )
    
    val isGoalReached = total > 0 && current >= total
    
    // Choose colors based on theme
    val primaryColor = MaterialTheme.colorScheme.primary
    val successColor = MaterialTheme.colorScheme.tertiaryContainer
    val dynamicColor = androidx.compose.ui.graphics.lerp(primaryColor, successColor, progress)
    
    // We keep the logic for exact completion if needed, or just use dynamic everywhere.
    // The user wants it to "vary while adding", so dynamicColor is best.
    val displayColor = dynamicColor
    
    // Adjust sizes for Easy Mode
    val barHeight = if (isEasyMode) 10.dp else 6.dp
    val labelSize = if (isEasyMode) 12.sp else 10.sp
    val countSize = if (isEasyMode) 16.sp else MaterialTheme.typography.labelMedium.fontSize
    val verticalPadding = if (isEasyMode) 8.dp else 4.dp

    Box(
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = verticalPadding)
                .fillMaxWidth()
        ) {
            // Text Labels
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "IMOVÉIS ABERTOS",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f),
                    fontWeight = FontWeight.Bold,
                    fontSize = labelSize,
                    modifier = Modifier.weight(1f)
                )
                
                if (focusCount > 0) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Text(
                            text = "FOCOS: $focusCount",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                Text(
                    text = "$current / $total",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = countSize
                )
            }
            
            Spacer(modifier = Modifier.height(2.dp))
            
            // Progress Bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(barHeight)
                    .clip(RoundedCornerShape(barHeight / 2))
                    .background(MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.2f))
            ) {
                // Progress Fill
                Box(
                    modifier = Modifier
                        .fillMaxWidth(animatedProgress)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(barHeight / 2))
                        .background(
                            brush = Brush.horizontalGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.tertiaryContainer,
                                    MaterialTheme.colorScheme.tertiary
                                )
                            )
                        )
                )
            }
        }
    }
}
