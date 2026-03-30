package com.antigravity.healthagent.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.antigravity.healthagent.ui.home.HomeViewModel
import com.antigravity.healthagent.ui.home.DayErrorSummary

@Composable
fun MultiDayErrorDialog(
    daysWithErrors: List<DayErrorSummary>,
    onNavigateToDay: (String) -> Unit,
    onDismiss: () -> Unit,
    isEasyMode: Boolean = false
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { 
            Text(
                "Ação Necessária: Pendências de Dados", 
                fontWeight = FontWeight.ExtraBold, 
                color = MaterialTheme.colorScheme.error,
                style = if (isEasyMode) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium
            ) 
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(if (isEasyMode) 16.dp else 8.dp)) {
                Text(
                    "Você deve corrigir os dados incompletos nos dias abaixo antes de continuar:",
                    style = if (isEasyMode) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium
                )
                if (daysWithErrors.isEmpty()) {
                    Text(
                        "(Nenhuma pendência encontrada. Você pode fechar este aviso.)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                LazyColumn(
                    modifier = Modifier.heightIn(max = if (isEasyMode) 320.dp else 240.dp),
                    verticalArrangement = Arrangement.spacedBy(if (isEasyMode) 12.dp else 4.dp)
                ) {
                    items(daysWithErrors) { summary ->
                        Button(
                            onClick = { onNavigateToDay(summary.date) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(if (isEasyMode) 64.dp else 48.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer
                            ),
                            shape = RoundedCornerShape(if (isEasyMode) 16.dp else 12.dp)
                        ) {
                            Text(
                                "${summary.date} (${summary.errorCount} pendências)",
                                style = if (isEasyMode) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("FECHAR", fontWeight = FontWeight.Bold)
            }
        },
        icon = { 
            Icon(
                Icons.Default.Info, 
                contentDescription = null, 
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(if (isEasyMode) 40.dp else 32.dp)
            ) 
        },
        shape = RoundedCornerShape(if (isEasyMode) 28.dp else 24.dp)
    )
}
