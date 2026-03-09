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
    isEasyMode: Boolean = false
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primary,
        tonalElevation = 2.dp
    ) {
        val verticalPadding = if (isEasyMode) 10.dp else 6.dp
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = verticalPadding),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatItem(label = "VISITAS", value = totals.totalRegisteredHouses.toString(), color = MaterialTheme.colorScheme.onPrimary, isEasyMode = isEasyMode)
            
            VerticalDivider(modifier = Modifier.height(28.dp).padding(horizontal = 4.dp), thickness = 1.dp, color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.2f))
            
            StatItem(label = "A1", value = totals.a1.toString(), color = MaterialTheme.colorScheme.onPrimary, isEasyMode = isEasyMode)
            StatItem(label = "A2", value = totals.a2.toString(), color = MaterialTheme.colorScheme.onPrimary, isEasyMode = isEasyMode)
            StatItem(label = "B", value = totals.b.toString(), color = MaterialTheme.colorScheme.onPrimary, isEasyMode = isEasyMode)
            StatItem(label = "C", value = totals.c.toString(), color = MaterialTheme.colorScheme.onPrimary, isEasyMode = isEasyMode)
            StatItem(label = "D1", value = totals.d1.toString(), color = MaterialTheme.colorScheme.onPrimary, isEasyMode = isEasyMode)
            StatItem(label = "D2", value = totals.d2.toString(), color = MaterialTheme.colorScheme.onPrimary, isEasyMode = isEasyMode)
            StatItem(label = "E", value = totals.e.toString(), color = MaterialTheme.colorScheme.onPrimary, isEasyMode = isEasyMode)
            
            VerticalDivider(modifier = Modifier.height(28.dp).padding(horizontal = 4.dp), thickness = 1.dp, color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.2f))
            
            StatItem(label = "ELIMINADOS", value = totals.eliminados.toString(), color = MaterialTheme.colorScheme.onPrimary, isEasyMode = isEasyMode)
            StatItem(label = "LARVICIDA", value = String.format("%.1fg", totals.larvicida), color = MaterialTheme.colorScheme.onPrimary, isEasyMode = isEasyMode)
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
