package com.antigravity.healthagent.ui.home.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.antigravity.healthagent.ui.components.CompactDropdown
import com.antigravity.healthagent.ui.components.CompactInputBox
import com.antigravity.healthagent.ui.components.PremiumCard
import com.antigravity.healthagent.utils.AppConstants
import com.antigravity.healthagent.utils.formatStreetName

@Composable
fun HomeHeader(
    municipio: String,
    data: String,
    bairro: String,
    zona: String,
    ciclo: String,
    tipo: Int,
    atividade: Int,
    agentName: String,
    isDayClosed: Boolean,
    onUpdateHeader: (String, String, String, String, Int, String, String, Int) -> Unit,
    onUpdateBairro: (String) -> Unit,
    onUpdateAgentName: (String) -> Unit,
    onUpdateMunicipio: (String) -> Unit,
    onUpdateZona: (String) -> Unit,
    onUpdateCategoria: (String) -> Unit,
    onSelectDate: () -> Unit,
    onMoveDateBackward: () -> Unit = {},
    onMoveDateForward: () -> Unit = {},
    isEasyMode: Boolean = false
) {
    var isHeaderExpanded by remember { mutableStateOf(false) }
    
    // Auto-collapse header when Easy Mode is toggled on
    LaunchedEffect(isEasyMode) {
        if (isEasyMode) {
            isHeaderExpanded = false
        }
    }

    PremiumCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        isSolarMode = false // Standard mode for header
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = 12.dp, 
                vertical = if (isHeaderExpanded) 8.dp else 4.dp
            ),
            verticalArrangement = Arrangement.spacedBy(if (isHeaderExpanded) 8.dp else 0.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Cabeçalho da Produção Diária", 
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.primary
                )
                
                IconButton(
                    onClick = { isHeaderExpanded = !isHeaderExpanded },
                    modifier = Modifier.size(32.dp),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = Color.Transparent)
                ) {
                    Icon(
                        if (isHeaderExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = "Alternar Cabeçalho",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            
            AnimatedVisibility(
                visible = isHeaderExpanded,
                enter = expandVertically(animationSpec = spring(stiffness = Spring.StiffnessMedium)) + fadeIn(animationSpec = tween(300)),
                exit = shrinkVertically(animationSpec = spring(stiffness = Spring.StiffnessMedium)) + fadeOut(animationSpec = tween(300))
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CompactInputBox(
                            value = municipio.uppercase(),
                            onValueChange = { 
                                onUpdateMunicipio(it.uppercase())
                            },
                            label = "Município",
                            modifier = Modifier.weight(1.2f),
                            enabled = false
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1.2f)
                        ) {
                            IconButton(
                                onClick = onMoveDateBackward,
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = androidx.compose.material.icons.Icons.Default.KeyboardArrowLeft,
                                    contentDescription = "Dia Anterior",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                            CompactInputBox(
                                value = if (data.isEmpty()) "SELECIONAR" else data,
                                onValueChange = {}, 
                                label = "Data Trabalho",
                                readOnly = true,
                                onClick = onSelectDate,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = onMoveDateForward,
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = androidx.compose.material.icons.Icons.Default.KeyboardArrowRight,
                                    contentDescription = "Próximo Dia",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {


                        CompactDropdown(
                            currentValue = bairro,
                            options = AppConstants.BAIRROS.map { it },
                            onOptionSelected = { onUpdateBairro(it) },
                            label = "Bairro",
                            modifier = Modifier.weight(1.5f),
                            enabled = !isDayClosed
                        )
                        CompactDropdown(
                            currentValue = zona.uppercase(),
                            options = listOf("URB", "RUR"),
                            onOptionSelected = { 
                                onUpdateZona(it.uppercase())
                            },
                            label = "Zona",
                            modifier = Modifier.weight(0.7f),
                            enabled = !isDayClosed
                        )
                    }

                     Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CompactInputBox(
                            value = "BRR",
                            onValueChange = {},
                            label = "Cat.",
                            modifier = Modifier.weight(1f),
                            readOnly = true,
                            enabled = false
                        )
                        CompactDropdown(
                            currentValue = ciclo.uppercase(),
                            options = (1..6).map { "${it}º" },
                            onOptionSelected = { 
                                onUpdateHeader(municipio, bairro, "BRR", zona, tipo, data, it.uppercase(), atividade)
                            },
                            label = "Ciclo",
                            modifier = Modifier.weight(1f),
                            enabled = false
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CompactDropdown(
                            currentValue = (AppConstants.TIPO_OPTIONS.find { it.startsWith("$tipo -") } ?: tipo.toString()).uppercase(),
                            options = AppConstants.TIPO_OPTIONS.map { it.uppercase() },
                            onOptionSelected = { 
                                val intVal = it.split(" - ").firstOrNull()?.toIntOrNull() ?: 0
                                onUpdateHeader(municipio, bairro, "BRR", zona, intVal, data, ciclo, atividade)
                            },
                            label = "Tipo Visit.",
                            modifier = Modifier.weight(1f),
                            enabled = false
                        )
                        CompactDropdown(
                            currentValue = (AppConstants.ATIVIDADE_OPTIONS.find { it.startsWith("$atividade -") } ?: atividade.toString()).uppercase(),
                            options = AppConstants.ATIVIDADE_OPTIONS.map { it.uppercase() },
                            onOptionSelected = { 
                                val intVal = it.split(" - ").firstOrNull()?.toIntOrNull() ?: 0
                                onUpdateHeader(municipio, bairro, "BRR", zona, tipo, data, ciclo, intVal)
                            },
                            label = "Atividade",
                            modifier = Modifier.weight(1f),
                            enabled = !isDayClosed
                        )
                    }

                    CompactDropdown(
                        currentValue = agentName.uppercase(),
                        options = AppConstants.AGENT_NAMES.map { it.uppercase() },
                        onOptionSelected = { onUpdateAgentName(it.uppercase()) },
                        label = "Nome do Agente Responsável",
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isDayClosed
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f), thickness = 1.dp)
                }
            }
        }
    }
}
