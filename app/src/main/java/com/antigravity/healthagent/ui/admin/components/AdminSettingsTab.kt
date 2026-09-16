// Admin Settings Tab - Sub-component of Admin Dashboard
package com.antigravity.healthagent.ui.admin.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SettingsSuggest
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.automirrored.filled.PlaylistAddCheck
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.antigravity.healthagent.ui.admin.AdminViewModel
import com.antigravity.healthagent.ui.components.PremiumCard

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AdminSettingsTab(viewModel: AdminViewModel) {
    val bairros by viewModel.bairros.collectAsState()
    val globalActivities by viewModel.globalCustomActivities.collectAsState()
    val maxHouses by viewModel.maxOpenHouses.collectAsState()
    val systemSettings by viewModel.systemSettings.collectAsState()
    
    var showAddBairroDialog by remember { mutableStateOf(false) }
    var showAddActivityDialog by remember { mutableStateOf(false) }
    
    val scrollState = rememberScrollState()
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Text(
            "Central de Configuração", 
            style = MaterialTheme.typography.headlineSmall, 
            fontWeight = FontWeight.Black,
            color = Color.White
        )

        // --- Section 1: Jornada e Metas ---
        SettingsSectionHeader(title = "Jornada e Metas", icon = Icons.Default.Timer)
        PremiumCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // Meta Diária
                var tempMaxHouses by remember(maxHouses) { mutableFloatStateOf(maxHouses.toFloat().coerceIn(25f, 35f)) }
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Meta Diária (Imóveis)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                tempMaxHouses.toInt().toString(),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Slider(
                        value = tempMaxHouses,
                        onValueChange = { tempMaxHouses = it },
                        onValueChangeFinished = { viewModel.updateSystemSetting("max_open_houses", tempMaxHouses.toLong()) },
                        valueRange = 25f..35f,
                        steps = 9
                    )
                    Text(
                        "Define a meta padrão para todos os agentes.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                // Gerenciar Bairros
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Bairros Atendidos", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        FilledTonalButton(
                            onClick = { showAddBairroDialog = true },
                            contentPadding = PaddingValues(horizontal = 12.dp),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Novo", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        bairros.forEach { bairro ->
                            InputChip(
                                selected = false,
                                onClick = { },
                                label = { Text(bairro) },
                                trailingIcon = { 
                                    IconButton(onClick = { viewModel.deleteBairro(bairro) }, modifier = Modifier.size(16.dp)) {
                                        Icon(Icons.Default.Close, null)
                                    }
                                },
                                shape = RoundedCornerShape(8.dp)
                            )
                        }
                        if (bairros.isEmpty()) {
                            Text("Nenhum bairro cadastrado", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        // --- Section 2: Atividades Padrão ---
        SettingsSectionHeader(title = "Atividades Padrão", icon = Icons.AutoMirrored.Filled.PlaylistAddCheck)
        PremiumCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Opções de Atividades", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    FilledTonalButton(
                        onClick = { showAddActivityDialog = true },
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Adicionar", style = MaterialTheme.typography.labelMedium)
                    }
                }
                Text(
                    "Define o que aparece na seção 'Resumo do Dia' dos agentes.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    globalActivities.forEach { activity ->
                        InputChip(
                            selected = false,
                            onClick = { },
                            label = { Text(activity) },
                            trailingIcon = { 
                                IconButton(onClick = { viewModel.removeGlobalActivity(activity) }, modifier = Modifier.size(16.dp)) {
                                    Icon(Icons.Default.Close, null)
                                }
                            },
                            shape = RoundedCornerShape(8.dp),
                            colors = InputChipDefaults.inputChipColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f))
                        )
                    }
                    if (globalActivities.isEmpty()) {
                        Text("Nenhuma atividade padrão configurada.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        // --- Section 3: Sistema ---
        SettingsSectionHeader(title = "Preferências de Sistema", icon = Icons.Default.SettingsSuggest)
        PremiumCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // Easy Mode Default
                val isEasyModeDefault = (systemSettings["default_easy_mode"] as? Boolean) ?: false
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Modo Simplificado por Padrão", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                        Text("Novos usuários entrarão no modo fácil automaticamente.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = isEasyModeDefault,
                        onCheckedChange = { viewModel.updateSystemSetting("default_easy_mode", it) }
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                // Recommended Theme Color
                val recommendedColor = (systemSettings["recommended_theme_color"] as? String) ?: "EMERALD"
                Column {
                    Text("Cor de Tema Recomendada", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    Text("Define a cor de destaque principal do aplicativo.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
                    
                    val colors = listOf("EMERALD", "OCEAN", "VIOLET", "AMBER", "ROSE")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        colors.forEach { name ->
                            val colorValue = when(name) {
                                "EMERALD" -> Color(0xFF10B981)
                                "OCEAN" -> Color(0xFF0EA5E9)
                                "VIOLET" -> Color(0xFF8B5CF6)
                                "AMBER" -> Color(0xFFF59E0B)
                                "ROSE" -> Color(0xFFF43F5E)
                                else -> Color.Gray
                            }
                            
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(colorValue, CircleShape)
                                    .border(
                                        width = if (recommendedColor == name) 3.dp else 1.dp,
                                        color = if (recommendedColor == name) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                                        shape = CircleShape
                                    )
                                    .clickable { viewModel.updateSystemSetting("recommended_theme_color", name) },
                                contentAlignment = Alignment.Center
                            ) {
                                if (recommendedColor == name) {
                                    Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
        
        Spacer(Modifier.height(32.dp))
    }

    // Dialogs
    if (showAddBairroDialog) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddBairroDialog = false },
            title = { Text("Novo Bairro") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Nome do Bairro") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) },
            confirmButton = { Button(onClick = { if (name.isNotBlank()) viewModel.addBairro(name); showAddBairroDialog = false }) { Text("Adicionar") } },
            dismissButton = { TextButton(onClick = { showAddBairroDialog = false }) { Text("Cancelar") } }
        )
    }
    
    if (showAddActivityDialog) {
        var activity by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddActivityDialog = false },
            title = { Text("Nova Atividade Padrão") },
            text = { 
                Column {
                    Text("Esta atividade aparecerá como opção para todos os agentes.", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(bottom = 8.dp))
                    OutlinedTextField(value = activity, onValueChange = { activity = it }, label = { Text("Nome da Atividade") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
                }
            },
            confirmButton = { Button(onClick = { if (activity.isNotBlank()) viewModel.addGlobalActivity(activity); showAddActivityDialog = false }) { Text("Adicionar") } },
            dismissButton = { TextButton(onClick = { showAddActivityDialog = false }) { Text("Cancelar") } }
        )
    }
}

@Composable
fun SettingsSectionHeader(title: String, icon: ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black, color = Color.White)
    }
}
