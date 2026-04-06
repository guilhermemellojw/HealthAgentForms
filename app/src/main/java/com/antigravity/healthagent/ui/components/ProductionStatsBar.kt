package com.antigravity.healthagent.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antigravity.healthagent.ui.home.DashboardTotals

@Composable
fun ProductionStatsBar(
    totals: DashboardTotals,
    modifier: Modifier = Modifier,
    isEasyMode: Boolean = false,
    isSolarMode: Boolean = false
) {
    Box(
        modifier = modifier.fillMaxWidth()
    ) {
        val verticalPadding = if (isEasyMode) 10.dp else 6.dp
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = verticalPadding)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(if (isEasyMode) 12.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val primaryColor = MaterialTheme.colorScheme.primary
            val dividerColor = if (isSolarMode) MaterialTheme.colorScheme.outline.copy(alpha = 0.2f) else MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.2f)

            // New "Visitas" (Total Houses) stat as requested
            StatItem(label = "VISITAS", value = totals.totalRegisteredHouses.toString(), color = primaryColor, isEasyMode = isEasyMode)
            
            VerticalDivider(modifier = Modifier.height(28.dp).padding(horizontal = 4.dp), thickness = 1.dp, color = dividerColor)

            StatItem(label = "A1", value = totals.a1.toString(), color = primaryColor, isEasyMode = isEasyMode)
            StatItem(label = "A2", value = totals.a2.toString(), color = primaryColor, isEasyMode = isEasyMode)
            StatItem(label = "B", value = totals.b.toString(), color = primaryColor, isEasyMode = isEasyMode)
            StatItem(label = "C", value = totals.c.toString(), color = primaryColor, isEasyMode = isEasyMode)
            StatItem(label = "D1", value = totals.d1.toString(), color = primaryColor, isEasyMode = isEasyMode)
            StatItem(label = "D2", value = totals.d2.toString(), color = primaryColor, isEasyMode = isEasyMode)
            StatItem(label = "E", value = totals.e.toString(), color = primaryColor, isEasyMode = isEasyMode)
            
            VerticalDivider(modifier = Modifier.height(28.dp).padding(horizontal = 4.dp), thickness = 1.dp, color = dividerColor)
            
            StatItem(label = "ELIMINADOS", value = totals.eliminados.toString(), color = primaryColor, isEasyMode = isEasyMode)
            StatItem(label = "LARVICIDA", value = String.format(java.util.Locale("pt", "BR"), "%.1fg", totals.larvicida), color = primaryColor, isEasyMode = isEasyMode)
        }
    }
}

@Composable
private fun StatItem(
    label: String,
    value: String,
    color: Color = MaterialTheme.colorScheme.onSurface,
    isEasyMode: Boolean = false
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (color == MaterialTheme.colorScheme.onSurface) MaterialTheme.colorScheme.onSurfaceVariant else color,
            fontWeight = FontWeight.Black,
            fontSize = if (isEasyMode) 9.sp else 8.sp,
            letterSpacing = 0.5.sp
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = color,
            fontWeight = FontWeight.ExtraBold,
            fontSize = if (isEasyMode) 18.sp else 14.sp
        )
    }
}
