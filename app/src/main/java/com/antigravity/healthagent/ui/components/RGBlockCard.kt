package com.antigravity.healthagent.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.antigravity.healthagent.ui.home.HomeViewModel

@Composable
fun RGBlockCard(
    segment: HomeViewModel.BlockSegment,
    onClick: () -> Unit,
    isEasyMode: Boolean = false,
    isSolarMode: Boolean = false,
    modifier: Modifier = Modifier
) {
    val statusColor = if (segment.isConcluded) Color(0xFF4CAF50) else Color(0xFFFF9800) // Green for closed, Orange for open
    val statusIcon = if (segment.isConcluded) Icons.Default.CheckCircle else Icons.Default.Warning
    
    // Size adjustments for Easy Mode
    val cardPadding = if (isEasyMode) 24.dp else 16.dp
    val titleStyle = if (isEasyMode) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium
    val bodyStyle = if (isEasyMode) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium
    val iconSize = if (isEasyMode) 20.dp else 14.dp
    val indicatorHeight = if (isEasyMode) 64.dp else 48.dp
    
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (isSolarMode) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(cardPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Status Indicator Strip
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(indicatorHeight)
                    .background(statusColor, RoundedCornerShape(2.dp))
            )
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Quarteirão ${segment.blockNumber}",
                    style = titleStyle,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                
                if (segment.blockSequence.isNotBlank()) {
                    Text(
                        text = "Sequência ${segment.blockSequence}",
                        style = bodyStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                Spacer(modifier = Modifier.height(4.dp))
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = statusIcon,
                        contentDescription = null,
                        tint = statusColor,
                        modifier = Modifier.size(iconSize)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (segment.isConcluded) "Concluído em ${segment.conclusionDate}" else "Em Aberto",
                        style = if (isEasyMode) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.labelMedium,
                        color = statusColor,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                
                Text(
                    text = "${segment.startDate} a ${segment.endDate}",
                    style = if (isEasyMode) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            // Count Badge
            Surface(
                color = Color.Transparent,
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
            ) {
                Text(
                    text = "${segment.houses.size} imóveis",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = if (isEasyMode) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
