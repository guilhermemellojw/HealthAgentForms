package com.antigravity.healthagent.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.filled.BugReport
import com.antigravity.healthagent.data.local.model.House

@Composable
fun TreatmentDialog(
    house: House,
    onDismiss: () -> Unit,
    onConfirm: (House) -> Unit,
    isEasyMode: Boolean = false
) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    // Local state for the form
    var a1 by remember { androidx.compose.runtime.mutableIntStateOf(house.a1) }
    var a2 by remember { androidx.compose.runtime.mutableIntStateOf(house.a2) }
    var b by remember { androidx.compose.runtime.mutableIntStateOf(house.b) }
    var c by remember { androidx.compose.runtime.mutableIntStateOf(house.c) }
    var d1 by remember { androidx.compose.runtime.mutableIntStateOf(house.d1) }
    var d2 by remember { androidx.compose.runtime.mutableIntStateOf(house.d2) }
    var e by remember { androidx.compose.runtime.mutableIntStateOf(house.e) }
    var eliminados by remember { androidx.compose.runtime.mutableIntStateOf(house.eliminados) }
    var larvicida by remember { androidx.compose.runtime.mutableDoubleStateOf(house.larvicida) }
    var comFoco by remember { mutableStateOf(house.comFoco) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { 
            Text(
                "Tratamento", 
                fontWeight = FontWeight.ExtraBold,
                style = MaterialTheme.typography.headlineSmall
            ) 
        },
        text = {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(if (isEasyMode) 12.dp else 8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                item {
                    Text(
                        "Depósitos Inspecionados", 
                        fontWeight = FontWeight.Bold,
                        style = if (isEasyMode) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium
                    )
                    HorizontalDivider(modifier = Modifier.padding(top = 4.dp), thickness = 0.5.dp)
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(if (isEasyMode) 12.dp else 8.dp)) {
                        CounterInput("A1", a1, { a1 = it }, Modifier.weight(1f), isEasyMode)
                        CounterInput("A2", a2, { a2 = it }, Modifier.weight(1f), isEasyMode)
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(if (isEasyMode) 12.dp else 8.dp)) {
                        CounterInput("B", b, { b = it }, Modifier.weight(1f), isEasyMode)
                        CounterInput("C", c, { c = it }, Modifier.weight(1f), isEasyMode)
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(if (isEasyMode) 12.dp else 8.dp)) {
                        CounterInput("D1", d1, { d1 = it }, Modifier.weight(1f), isEasyMode)
                        CounterInput("D2", d2, { d2 = it }, Modifier.weight(1f), isEasyMode)
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(if (isEasyMode) 12.dp else 8.dp)) {
                        CounterInput("E", e, { e = it }, Modifier.weight(1f), isEasyMode)
                        CounterInput("Eliminados", eliminados, { eliminados = it }, Modifier.weight(1f), isEasyMode)
                    }
                }
                item {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), thickness = 0.5.dp)
                    
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Left: Larvicida Logic
                        Column(
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.Start
                        ) {
                            Text(
                                "Larvicida (g)", 
                                fontWeight = FontWeight.Bold,
                                style = if (isEasyMode) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(if (isEasyMode) 8.dp else 4.dp),
                                modifier = Modifier.padding(top = 4.dp)
                            ) {
                                IconButton(
                                    onClick = { if (larvicida >= 0.5) larvicida -= 0.5 },
                                    modifier = Modifier.size(if (isEasyMode) 48.dp else 40.dp)
                                ) {
                                    Text("-", fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary, fontSize = if (isEasyMode) 24.sp else 22.sp)
                                }
                                Text(
                                    String.format(java.util.Locale("pt", "BR"), "%.1f", larvicida), 
                                    color = MaterialTheme.colorScheme.onSurface,
                                    style = TextStyle(
                                        fontSize = if (isEasyMode) 20.sp else 18.sp,
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                )
                                IconButton(
                                    onClick = { larvicida += 0.5 },
                                    modifier = Modifier.size(if (isEasyMode) 48.dp else 40.dp)
                                ) {
                                    Text("+", fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary, fontSize = if (isEasyMode) 24.sp else 22.sp)
                                }
                            }
                        }

                        // Right: Com Foco Logic - Styled
                        Surface(
                            checked = comFoco,
                            onCheckedChange = { comFoco = it },
                            shape = RoundedCornerShape(16.dp),
                            border = androidx.compose.foundation.BorderStroke(
                                width = if (comFoco) 2.dp else 1.dp,
                                color = if (comFoco) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                            ),
                            color = if (comFoco) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f) else MaterialTheme.colorScheme.surface,
                            modifier = Modifier.padding(start = 12.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Checkbox(
                                    checked = comFoco,
                                    onCheckedChange = null, // Handled by Surface
                                    colors = CheckboxDefaults.colors(
                                        checkedColor = MaterialTheme.colorScheme.error,
                                        uncheckedColor = MaterialTheme.colorScheme.onSurfaceVariant
                                    ),
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    "Com Foco",
                                    style = if (isEasyMode) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.labelLarge,
                                    fontWeight = if (comFoco) FontWeight.ExtraBold else FontWeight.Bold,
                                    color = if (comFoco) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f).height(if (isEasyMode) 52.dp else 48.dp),
                    shape = RoundedCornerShape(if (isEasyMode) 16.dp else 12.dp)
                ) {
                    Text("Cancelar", fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = {
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                        onConfirm(house.copy(
                            a1 = a1, a2 = a2, b = b, c = c, d1 = d1, d2 = d2, e = e, eliminados = eliminados, larvicida = larvicida, comFoco = comFoco
                        ))
                    },
                    modifier = Modifier.weight(1.3f).height(if (isEasyMode) 52.dp else 48.dp),
                    shape = RoundedCornerShape(if (isEasyMode) 16.dp else 12.dp)
                ) {
                    Text("Salvar", fontWeight = FontWeight.Bold)
                }
            }
        }
    )
}
