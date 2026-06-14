package com.antigravity.healthagent.ui.semanal

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.ui.components.CompactDropdown
import com.antigravity.healthagent.ui.components.PremiumCard
import com.antigravity.healthagent.ui.home.DaySummary

@Composable
fun WeeklyDayRow(
    day: DaySummary,
    options: List<String>,
    onStatusChange: (String) -> Unit,
    onToggleLock: () -> Unit,
    onClick: () -> Unit,
    isEasyMode: Boolean = false,
    isSolarMode: Boolean = false,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val dayOfWeek = remember(day.date) {
        val cal = java.util.Calendar.getInstance()
        val parts = day.date.split("-")
        if (parts.size == 3) {
            cal.set(parts[2].toInt(), parts[1].toInt() - 1, parts[0].toInt())
            when (cal.get(java.util.Calendar.DAY_OF_WEEK)) {
                java.util.Calendar.MONDAY -> "Segunda-feira"
                java.util.Calendar.TUESDAY -> "Terça-feira"
                java.util.Calendar.WEDNESDAY -> "Quarta-feira"
                java.util.Calendar.THURSDAY -> "Quinta-feira"
                java.util.Calendar.FRIDAY -> "Sexta-feira"
                else -> ""
            }
        } else ""
    }

    PremiumCard(
        modifier = Modifier.fillMaxWidth().then(modifier),
        onClick = onClick,
        isSolarMode = isSolarMode,
        contentPadding = if (isEasyMode) PaddingValues(8.dp) else PaddingValues(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(modifier = Modifier.weight(1.6f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = dayOfWeek.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Black,
                        fontSize = if (isEasyMode) 12.sp else 10.sp,
                        maxLines = 1,
                        softWrap = false
                    )
                    if (day.editedByAdmin) {
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Homologado",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(10.dp)
                        )
                    }
                }
                Text(
                    text = day.date,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = if (isEasyMode) 18.sp else 16.sp,
                    maxLines = 1,
                    softWrap = false
                )
            }

            Surface(
                color = Color.Transparent,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.width(if (isEasyMode) 60.dp else 48.dp).height(if (isEasyMode) 56.dp else 50.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        "ABERTOS", 
                        style = MaterialTheme.typography.labelSmall, 
                        color = MaterialTheme.colorScheme.primary, 
                        fontSize = (if (isEasyMode) 10.sp else 8.sp), 
                        fontWeight = FontWeight.Black
                    )
                    Text(
                        day.totalWorked.toString(), 
                        style = MaterialTheme.typography.titleMedium, 
                        fontWeight = FontWeight.Black, 
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = if (isEasyMode) 20.sp else 16.sp
                    )
                }
            }

            CompactDropdown(
                label = "Status",
                currentValue = day.status.ifEmpty { "NORMAL" },
                options = options,
                onOptionSelected = onStatusChange,
                modifier = Modifier.weight(2.2f),
                isEasyMode = isEasyMode,
                enabled = enabled
            )

            if (day.isClosed || day.editedByAdmin) {
                val isLocked = (day.isClosed && !day.isManualUnlock) || (day.editedByAdmin && !day.isManualUnlock)
                IconButton(
                    onClick = onToggleLock,
                    modifier = Modifier.size(if (isEasyMode) 44.dp else 36.dp)
                ) {
                    Icon(
                        imageVector = if (isLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                        contentDescription = if (isLocked) "Desbloquear" else "Bloquear",
                        tint = if (isLocked) MaterialTheme.colorScheme.error else Color(0xFF4CAF50),
                        modifier = Modifier.size(if (isEasyMode) 24.dp else 20.dp)
                    )
                }
            } else {
                Spacer(
                    modifier = Modifier.size(if (isEasyMode) 44.dp else 36.dp)
                )
            }
        }
    }
}

@Composable
fun ObservationCard(
    house: House,
    isEasyMode: Boolean = false,
    isSolarMode: Boolean = false,
    onClick: () -> Unit = {}
) {
    PremiumCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        isSolarMode = isSolarMode,
        contentPadding = if (isEasyMode) PaddingValues(8.dp) else PaddingValues(12.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = house.data,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                
                Text(
                    text = "${house.address.bairro.uppercase()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium
                )
            }
            
            Spacer(modifier = Modifier.height(6.dp))
            
            Text(
                text = "${house.address.streetName}, ${house.address.number}${if (house.address.sequence > 0) "-${house.address.sequence}" else ""}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            
            Spacer(modifier = Modifier.height(4.dp))
            
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = house.observation,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun CompactStatItem(
    label: String,
    value: String,
    icon: ImageVector,
    color: Color = MaterialTheme.colorScheme.primary,
    isEasyMode: Boolean = false
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(vertical = 2.dp, horizontal = 4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color.copy(alpha = 0.7f),
            modifier = Modifier.size(if (isEasyMode) 24.dp else 20.dp)
        )
        Text(
            text = value,
            style = if (isEasyMode) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Black,
            color = color
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 9.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}
