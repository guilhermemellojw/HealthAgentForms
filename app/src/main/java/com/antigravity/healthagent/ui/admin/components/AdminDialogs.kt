// Admin Dialogs - Sub-components of Admin Dashboard
package com.antigravity.healthagent.ui.admin.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import com.antigravity.healthagent.domain.repository.AccessRequest
import com.antigravity.healthagent.domain.repository.UserRole
import com.antigravity.healthagent.ui.admin.UnifiedProfile

@Composable
fun ApprovalDialog(
    request: AccessRequest,
    agentNamesList: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (String?) -> Unit
) {
    var selectedName by remember { mutableStateOf<String?>(request.requestedName?.takeIf { it.isNotBlank() }) }
    var expanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Aprovar Acesso") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Vincular conta ${request.email} a um nome da lista mestra:")
                Column {
                    OutlinedTextField(
                        value = selectedName ?: "",
                        onValueChange = { selectedName = it; expanded = true },
                        label = { Text("Nome do Agente (da Lista Mestra)") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        trailingIcon = { 
                            IconButton(onClick = { expanded = !expanded }) {
                                Icon(Icons.Default.ArrowDropDown, null)
                            }
                        }
                    )
                    
                    DropdownMenu(
                        expanded = expanded && agentNamesList.isNotEmpty(),
                        onDismissRequest = { expanded = false },
                        properties = PopupProperties(focusable = false),
                        modifier = Modifier.fillMaxWidth(0.8f)
                    ) {
                        val filteredNames = agentNamesList.filter { 
                            it.contains(selectedName ?: "", ignoreCase = true) 
                        }.take(5)

                        filteredNames.forEach { name ->
                            DropdownMenuItem(
                                text = { Text(name) },
                                onClick = { 
                                    selectedName = name
                                    expanded = false 
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { onConfirm(selectedName) }) { Text("Aprovar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

@Composable
fun AddProfileDialog(
    agentNamesList: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (String?, String?, UserRole, Boolean) -> Unit
) {
    var email by remember { mutableStateOf("") }
    var nameInput by remember { mutableStateOf("") }
    var role by remember { mutableStateOf(UserRole.AGENT) }
    var authorized by remember { mutableStateOf(true) }
    var expandedName by remember { mutableStateOf(false) }
    var expandedRole by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Novo Perfil / Agente", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("E-mail (Para conta Google)") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                Column {
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it; expandedName = true },
                        label = { Text("Nome Completo do Agente (Lista Mestra)") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        trailingIcon = { 
                            IconButton(onClick = { expandedName = !expandedName }) {
                                Icon(Icons.Default.ArrowDropDown, null)
                            }
                        }
                    )
                    
                    DropdownMenu(
                        expanded = expandedName && agentNamesList.isNotEmpty(),
                        onDismissRequest = { expandedName = false },
                        properties = PopupProperties(focusable = false),
                        modifier = Modifier.fillMaxWidth(0.8f)
                    ) {
                        val filteredNames = agentNamesList.filter { 
                            it.contains(nameInput, ignoreCase = true) 
                        }.take(5)

                        filteredNames.forEach { name ->
                            DropdownMenuItem(
                                text = { Text(name) },
                                onClick = { 
                                    nameInput = name
                                    expandedName = false 
                                }
                            )
                        }
                        
                        if (nameInput.isNotBlank() && !agentNamesList.contains(nameInput)) {
                            DropdownMenuItem(
                                text = { Text("Adicionar como novo: \"$nameInput\"") },
                                onClick = { expandedName = false }
                            )
                        }
                    }
                    
                    Text(
                        "O nome deve preferencialmente corresponder ao da lista oficial.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                    )
                }

                Box {
                    OutlinedButton(onClick = { expandedRole = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                        Text("Função: ${role.name}")
                        Spacer(modifier = Modifier.weight(1f))
                        Icon(Icons.Default.ArrowDropDown, null)
                    }
                    DropdownMenu(expanded = expandedRole, onDismissRequest = { expandedRole = false }) {
                        UserRole.entries.forEach { r ->
                            DropdownMenuItem(text = { Text(r.name) }, onClick = { role = r; expandedRole = false })
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = authorized, onCheckedChange = { authorized = it })
                    Text("Autorizado a operar", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { 
                    onConfirm(
                        email.takeIf { it.isNotBlank() }, 
                        nameInput.takeIf { it.isNotBlank() }, 
                        role, 
                        authorized
                    ) 
                },
                enabled = email.isNotBlank() || nameInput.isNotBlank()
            ) { Text("Criar") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TransferDataDialog(
    sourceProfile: UnifiedProfile,
    availableTargets: List<UnifiedProfile>,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedTargetUid by remember { mutableStateOf<String?>(null) }
    var showStep2 by remember { mutableStateOf(false) }

    val filteredTargets = availableTargets.filter {
        it.agentName?.contains(searchQuery, ignoreCase = true) == true ||
        it.email?.contains(searchQuery, ignoreCase = true) == true
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (!showStep2) "Transferir Dados de Produção" else "CONFIRMAR TRANSFERÊNCIA") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (!showStep2) {
                    Text("Selecione o destino para os dados de ${sourceProfile.agentName ?: sourceProfile.email}:", style = MaterialTheme.typography.bodyMedium)
                    
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Pesquisar destino...") },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        shape = RoundedCornerShape(12.dp)
                    )
                    
                    Box(modifier = Modifier.heightIn(max = 300.dp)) {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(filteredTargets) { target ->
                                Surface(
                                    onClick = { selectedTargetUid = target.uid },
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (selectedTargetUid == target.uid) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                    border = BorderStroke(1.dp, if (selectedTargetUid == target.uid) MaterialTheme.colorScheme.primary else Color.Transparent)
                                ) {
                                    Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        RadioButton(selected = selectedTargetUid == target.uid, onClick = { selectedTargetUid = target.uid })
                                        Spacer(Modifier.width(8.dp))
                                        Column {
                                            Text(target.agentName ?: "Sem nome", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                                            Text(target.email ?: "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                            if (filteredTargets.isEmpty()) {
                                item { 
                                    Text("Nenhum alvo de transferência disponível.", 
                                         style = MaterialTheme.typography.bodySmall, 
                                         modifier = Modifier.padding(16.dp),
                                         textAlign = TextAlign.Center
                                    ) 
                                }
                            }
                        }
                    }
                } else {
                    val target = availableTargets.find { it.uid == selectedTargetUid }
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error)
                                Spacer(Modifier.width(8.dp))
                                Text("AÇÃO IRREVERSÍVEL", fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.error)
                            }
                            Text(
                                "Você está movendo TODA a produção de:",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(sourceProfile.agentName ?: sourceProfile.email ?: "", fontWeight = FontWeight.Bold)
                            Icon(Icons.Default.ArrowDownward, null, modifier = Modifier.align(Alignment.CenterHorizontally))
                            Text(
                                "Para a conta de:",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(target?.agentName ?: target?.email ?: "", fontWeight = FontWeight.Bold)
                            
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Os dados serão REMOVIDOS da conta de origem e mesclados ao destino. Este processo não pode ser desfeito.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (!showStep2) {
                Button(
                    onClick = { showStep2 = true },
                    enabled = selectedTargetUid != null
                ) { Text("Próximo") }
            } else {
                Button(
                    onClick = { selectedTargetUid?.let { onConfirm(it) } },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("CONFIRMAR TRANSFERÊNCIA") }
            }
        },
        dismissButton = {
            TextButton(onClick = if (showStep2) { { showStep2 = false } } else onDismiss) { 
                Text(if (showStep2) "Voltar" else "Cancelar") 
            }
        }
    )
}
