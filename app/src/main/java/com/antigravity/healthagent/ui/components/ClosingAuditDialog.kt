package com.antigravity.healthagent.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.antigravity.healthagent.ui.home.HomeViewModel
import com.antigravity.healthagent.ui.home.AuditSummary

@Composable
fun ClosingAuditDialog(
    audit: AuditSummary,
    onConfirm: (AuditSummary) -> Unit,
    onDismiss: () -> Unit,
    isEasyMode: Boolean = false
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(if (isEasyMode) 28.dp else 24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 16.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Default.Assignment, 
                    contentDescription = null, 
                    modifier = Modifier.size(if (isEasyMode) 52.dp else 48.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                
                Text(
                    "Auditoria de Fechamento",
                    style = if (isEasyMode) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold
                )
                
                Text(
                    "Confira o resumo do dia ${audit.date}:",
                    style = if (isEasyMode) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), RoundedCornerShape(if (isEasyMode) 16.dp else 12.dp))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AuditItem("Trabalhados", "${audit.totalWorked}", isEasyMode)
                    if (audit.totalTreated > 0) AuditItem("Tratados", "${audit.totalTreated}", isEasyMode)
                    if (audit.totalClosed > 0) AuditItem("Fechados (F)", "${audit.totalClosed}", isEasyMode)
                    if (audit.totalRefused > 0) AuditItem("Recusados (REC)", "${audit.totalRefused}", isEasyMode)
                    if (audit.totalAbsent > 0) AuditItem("Ausentes (A)", "${audit.totalAbsent}", isEasyMode)
                    if (audit.totalVacant > 0) AuditItem("Vagos (V)", "${audit.totalVacant}", isEasyMode)
                    
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    
                    if (audit.a1 > 0) AuditItem("Depósito A1", "${audit.a1}", isEasyMode)
                    if (audit.a2 > 0) AuditItem("Depósito A2", "${audit.a2}", isEasyMode)
                    if (audit.b > 0) AuditItem("Depósito B", "${audit.b}", isEasyMode)
                    if (audit.c > 0) AuditItem("Depósito C", "${audit.c}", isEasyMode)
                    if (audit.d1 > 0) AuditItem("Depósito D1", "${audit.d1}", isEasyMode)
                    if (audit.d2 > 0) AuditItem("Depósito D2", "${audit.d2}", isEasyMode)
                    if (audit.e > 0) AuditItem("Depósito E", "${audit.e}", isEasyMode)
                    if (audit.eliminados > 0) AuditItem("Eliminados", "${audit.eliminados}", isEasyMode)

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    AuditItem("Total Larvicida", String.format(java.util.Locale("pt", "BR"), "%.2fg", audit.totalLarvicide))
                }


                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f).height(if (isEasyMode) 52.dp else 48.dp),
                        shape = RoundedCornerShape(if (isEasyMode) 16.dp else 12.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Text("Revisar", fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = { onConfirm(audit) },
                        modifier = Modifier.weight(1.3f).height(if (isEasyMode) 52.dp else 48.dp),
                        shape = RoundedCornerShape(if (isEasyMode) 16.dp else 12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Text("Confirmar e Fechar", fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}

@Composable
fun AuditItem(label: String, value: String, isEasyMode: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = if (isEasyMode) 2.dp else 0.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label, 
            style = if (isEasyMode) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium
        )
        Text(
            value, 
            style = if (isEasyMode) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge, 
            fontWeight = FontWeight.ExtraBold, 
            color = MaterialTheme.colorScheme.primary
        )
    }
}
