package com.antigravity.healthagent.ui.home.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.antigravity.healthagent.ui.home.DashboardTotals

@Composable
fun UnlockDayDialog(
    show: Boolean,
    isEasyMode: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    if (!show) return

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "Reabrir Dia",
                fontWeight = FontWeight.ExtraBold,
                style = if (isEasyMode) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Text(
                "Deseja reabrir este dia para edição? Todas as edições serão permitidas novamente.",
                style = if (isEasyMode) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .weight(1f)
                        .height(if (isEasyMode) 52.dp else 48.dp),
                    shape = RoundedCornerShape(if (isEasyMode) 16.dp else 12.dp)
                ) {
                    Text("Cancelar", fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = onConfirm,
                    modifier = Modifier
                        .weight(1.3f)
                        .height(if (isEasyMode) 52.dp else 48.dp),
                    shape = RoundedCornerShape(if (isEasyMode) 16.dp else 12.dp)
                ) {
                    Text("Reabrir", fontWeight = FontWeight.Bold)
                }
            }
        },
        shape = RoundedCornerShape(if (isEasyMode) 28.dp else 24.dp)
    )
}

@Composable
fun HistoryUnlockDialog(
    show: Boolean,
    isEasyMode: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    if (!show) return

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "Reabrir Dia Antigo",
                fontWeight = FontWeight.ExtraBold,
                style = if (isEasyMode) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Text(
                "Atenção: Você está tentando reabrir um dia histórico.\n\nFazer alterações pode afetar relatórios consolidados e estatísticas de produtividade.\n\nDeseja continuar?",
                style = if (isEasyMode) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .weight(1f)
                        .height(if (isEasyMode) 52.dp else 48.dp),
                    shape = RoundedCornerShape(if (isEasyMode) 16.dp else 12.dp)
                ) {
                    Text("Cancelar", fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = onConfirm,
                    modifier = Modifier
                        .weight(1.3f)
                        .height(if (isEasyMode) 52.dp else 48.dp),
                    shape = RoundedCornerShape(if (isEasyMode) 16.dp else 12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Sim, Reabrir", fontWeight = FontWeight.Bold)
                }
            }
        },
        shape = RoundedCornerShape(if (isEasyMode) 28.dp else 24.dp)
    )
}

@Composable
fun HouseOptionsDialog(
    show: Boolean,
    onMoveToDate: () -> Unit,
    onReorderList: () -> Unit,
    onDismiss: () -> Unit
) {
    if (!show) return

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Opções do Imóvel") },
        text = { Text("O que deseja fazer com este imóvel?") },
        confirmButton = {
            TextButton(onClick = onMoveToDate) {
                Text("Mover para outra Data")
            }
        },
        dismissButton = {
            TextButton(onClick = onReorderList) {
                Text("Reordenar Lista")
            }
        }
    )
}

@Composable
fun DashboardSummaryDialog(
    show: Boolean,
    dashboardTotals: DashboardTotals,
    isDayClosed: Boolean,
    isSupervisor: Boolean,
    isAdmin: Boolean,
    showDeduplicate: Boolean,
    onDeduplicate: () -> Unit,
    onCloseProduction: () -> Unit,
    onDismiss: () -> Unit
) {
    if (!show) return

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Resumo do Dia") },
        text = {
            Column {
                Text("Total Visitas: ${dashboardTotals.totalHouses}", fontWeight = FontWeight.Bold)
                Text("Abertos: ${dashboardTotals.worked}", color = MaterialTheme.colorScheme.primary)
                Text("Vazios: ${dashboardTotals.vacant}")
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text("A1: ${dashboardTotals.a1} | A2: ${dashboardTotals.a2}")
                Text("B: ${dashboardTotals.b} | C: ${dashboardTotals.c}")
                Text("D1: ${dashboardTotals.d1} | D2: ${dashboardTotals.d2}")
                Text("E: ${dashboardTotals.e}")
                Text("Eliminados: ${dashboardTotals.eliminados}")
                Text("Larvicida: ${dashboardTotals.larvicida}g")
                Text("Com Foco: ${dashboardTotals.totalFocos}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                if (!isDayClosed && !isSupervisor) {
                    TextButton(
                        onClick = onCloseProduction,
                        colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF00C853)) // Green
                    ) {
                        Text("FECHAR PRODUÇÃO", fontWeight = FontWeight.Bold)
                    }
                }

                if (isAdmin && showDeduplicate) {
                    TextButton(
                        onClick = onDeduplicate,
                        colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFFF9800)) // Orange
                    ) {
                        Text("DEDUPLICAR AGORA", fontWeight = FontWeight.Bold)
                    }
                }

                TextButton(onClick = onDismiss) { Text("FECHAR") }
            }
        }
    )
}

@Composable
fun MoveHouseGoalReachedDialog(
    show: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    if (!show) return

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Confirmar Movimentação") },
        text = { Text("O dia selecionado já atingiu a Meta Diária. Deseja mover o imóvel mesmo assim?") },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary)
            ) {
                Text("Sim, Mover")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        }
    )
}
