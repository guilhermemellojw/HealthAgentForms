package com.antigravity.healthagent.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.utils.AppConstants
import com.antigravity.healthagent.utils.formatStreetName

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContextDialog(
    currentBlock: String,
    currentBlockSequence: String,
    currentStreet: String,
    currentBairro: String,
    currentQuarteiraoConcluido: Boolean,
    currentLocalidadeConcluida: Boolean,
    invalidFields: Set<String> = emptySet(),
    streetSuggestions: List<String> = emptyList(),
    onDismiss: () -> Unit,
    onConfirm: (String, String, String, String, Boolean, Boolean) -> Unit,
    isEasyMode: Boolean = false
) {
    var block by remember { mutableStateOf(currentBlock) }
    var blockSequence by remember { mutableStateOf(currentBlockSequence) }
    var street by remember { mutableStateOf(currentStreet) }
    var bairro by remember { mutableStateOf(currentBairro) }
    var isQuarteiraoConcluido by remember { mutableStateOf(currentQuarteiraoConcluido) }
    var isLocalidadeConcluida by remember { mutableStateOf(currentLocalidadeConcluida) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Editar Localização", fontWeight = FontWeight.ExtraBold) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(if (isEasyMode) 16.dp else 8.dp)
            ) {
                CompactDropdown(
                    label = "Bairro",
                    currentValue = bairro.formatStreetName(),
                    options = AppConstants.BAIRROS.map { it.formatStreetName() },
                    onOptionSelected = { bairro = it },
                    modifier = Modifier.fillMaxWidth(),
                    isEasyMode = isEasyMode
                )
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(if (isEasyMode) 12.dp else 8.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = block,
                        onValueChange = { block = it },
                        label = { Text("Quarteirão") },
                        isError = invalidFields.contains("blockNumber"),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(if (isEasyMode) 16.dp else 12.dp),
                        textStyle = TextStyle(fontSize = if (isEasyMode) 18.sp else 16.sp)
                    )
                    
                    Text(
                        text = "/", 
                        style = if (isEasyMode) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge, 
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Black,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    CompactDropdown(
                        label = "Sequência",
                        currentValue = blockSequence,
                        options = listOf("1", "2", "3", "4", "5"),
                        onOptionSelected = { blockSequence = it },
                        modifier = Modifier.weight(1f),
                        isEasyMode = isEasyMode
                    )
                }

                // Autocomplete for Street Name
                var streetExpanded by remember { mutableStateOf(false) }
                val formattedSuggestions = remember(streetSuggestions) { 
                    streetSuggestions.map { it.formatStreetName() }.distinct()
                }
                val filteredStreets = remember(street, formattedSuggestions) {
                    if (street.isBlank()) emptyList()
                    else formattedSuggestions.filter { 
                        it.contains(street, ignoreCase = true) && !it.equals(street, ignoreCase = true)
                    }.take(5)
                }

                ExposedDropdownMenuBox(
                    expanded = streetExpanded,
                    onExpandedChange = { streetExpanded = !streetExpanded },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = street,
                        onValueChange = { 
                            street = it
                            streetExpanded = true
                        },
                        label = { Text("Logradouro") },
                        isError = invalidFields.contains("streetName"),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences,
                            imeAction = ImeAction.Done
                        ),
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                        shape = RoundedCornerShape(if (isEasyMode) 16.dp else 12.dp),
                        textStyle = TextStyle(fontSize = if (isEasyMode) 18.sp else 16.sp)
                    )

                    if (filteredStreets.isNotEmpty()) {
                        ExposedDropdownMenu(
                            expanded = streetExpanded,
                            onDismissRequest = { streetExpanded = false }
                        ) {
                            filteredStreets.forEach { suggestion ->
                                DropdownMenuItem(
                                    text = { Text(suggestion, style = MaterialTheme.typography.bodyLarge) },
                                    onClick = {
                                        street = suggestion
                                        streetExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                // Dual Completion Checkboxes
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Quarteirão Option
                    Surface(
                        checked = isQuarteiraoConcluido,
                        onCheckedChange = { isQuarteiraoConcluido = it },
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(
                            width = if (isQuarteiraoConcluido) 2.dp else 1.dp, 
                            color = if (isQuarteiraoConcluido) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                        ),
                        color = if (isQuarteiraoConcluido) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) else MaterialTheme.colorScheme.surface,
                        modifier = Modifier.weight(1f)
                    ) {
                        Row(
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            modifier = Modifier.padding(if (isEasyMode) 8.dp else 6.dp)
                        ) {
                            Checkbox(
                                checked = isQuarteiraoConcluido,
                                onCheckedChange = null,
                                modifier = Modifier.scale(if (isEasyMode) 1.0f else 0.9f)
                            )
                            Spacer(Modifier.width(if (isEasyMode) 4.dp else 2.dp))
                            Text(
                                "Quarteirão Concluído", 
                                style = if (isEasyMode) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.labelSmall,
                                fontWeight = if (isQuarteiraoConcluido) FontWeight.ExtraBold else FontWeight.Bold,
                                lineHeight = if (isEasyMode) 16.sp else 12.sp,
                                softWrap = true,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    // Localidade Option
                    Surface(
                        checked = isLocalidadeConcluida,
                        onCheckedChange = { isLocalidadeConcluida = it },
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(
                            width = if (isLocalidadeConcluida) 2.dp else 1.dp, 
                            color = if (isLocalidadeConcluida) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                        ),
                        color = if (isLocalidadeConcluida) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) else MaterialTheme.colorScheme.surface,
                         modifier = Modifier.weight(1f)
                    ) {
                        Row(
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            modifier = Modifier.padding(if (isEasyMode) 8.dp else 6.dp)
                        ) {
                            Checkbox(
                                checked = isLocalidadeConcluida,
                                onCheckedChange = null,
                                modifier = Modifier.scale(if (isEasyMode) 1.0f else 0.9f)
                            )
                            Spacer(Modifier.width(if (isEasyMode) 4.dp else 2.dp))
                            Text(
                                "Localidade Concluída", 
                                style = if (isEasyMode) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.labelSmall,
                                fontWeight = if (isLocalidadeConcluida) FontWeight.ExtraBold else FontWeight.Bold,
                                lineHeight = if (isEasyMode) 16.sp else 12.sp,
                                softWrap = true,
                                modifier = Modifier.weight(1f)
                            )
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
                    onClick = { onConfirm(block, blockSequence, street.formatStreetName(), bairro, isQuarteiraoConcluido, isLocalidadeConcluida) },
                    modifier = Modifier.weight(1.3f).height(if (isEasyMode) 52.dp else 48.dp),
                    shape = RoundedCornerShape(if (isEasyMode) 16.dp else 12.dp)
                ) {
                    Text("Salvar", fontWeight = FontWeight.Bold)
                }
            }
        }
    )
}
