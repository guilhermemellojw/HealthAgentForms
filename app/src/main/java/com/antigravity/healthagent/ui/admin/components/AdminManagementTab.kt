// Admin Management Tab - Sub-components of Admin Dashboard
package com.antigravity.healthagent.ui.admin.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import com.antigravity.healthagent.domain.repository.AccessRequest
import com.antigravity.healthagent.domain.repository.UserRole
import com.antigravity.healthagent.ui.admin.UnifiedProfile
import com.antigravity.healthagent.ui.components.PremiumCard
import com.antigravity.healthagent.ui.components.UserAvatar
import java.util.*

@Composable
fun AccessRequestCard(
    request: AccessRequest,
    isSolarMode: Boolean = false,
    onApprove: () -> Unit,
    onReject: () -> Unit
) {
    PremiumCard(
        modifier = Modifier.width(280.dp),
        isSolarMode = isSolarMode
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.PersonSearch, null, modifier = Modifier.padding(6.dp), tint = MaterialTheme.colorScheme.primary)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(request.email, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(request.requestedName ?: request.displayName ?: "Sem nome", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onApprove, modifier = Modifier.weight(1f), contentPadding = PaddingValues(0.dp)) { Text("Aprovar", style = MaterialTheme.typography.labelSmall) }
                OutlinedButton(onClick = onReject, modifier = Modifier.weight(1f), contentPadding = PaddingValues(0.dp)) { Text("Rejeitar", style = MaterialTheme.typography.labelSmall) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UnifiedProfileCard(
    profile: UnifiedProfile,
    agentNamesList: List<String>,
    isSolarMode: Boolean = false,
    onAuthorize: (Boolean) -> Unit,
    onRoleChange: (UserRole) -> Unit,
    onUpdateName: (String?) -> Unit,
    onDelete: () -> Unit,
    onRestore: () -> Unit,
    onRestoreDay: (String?) -> Unit,
    onEditAgent: () -> Unit,
    onClearSyncError: () -> Unit,
    onMigrateData: () -> Unit,
    onTransferData: () -> Unit,
    onRemoteWipe: () -> Unit,
    onOpenTimeline: (String, String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var expandedRole by remember { mutableStateOf(false) }
    var showEditNameDialog by remember { mutableStateOf(false) }
    
    val agent = profile.agentData
    val lastSync = remember(agent?.lastSyncTime) {
        if (agent != null && agent.lastSyncTime > 0) {
            com.antigravity.healthagent.utils.DateUtils.DATE_TIME_FULL.get().format(Date(agent.lastSyncTime))
        } else "Nunca"
    }

    PremiumCard(
        modifier = Modifier.fillMaxWidth(),
        isSolarMode = isSolarMode,
        onClick = { expanded = !expanded }
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                UserAvatar(
                    uid = profile.uid ?: "",
                    displayName = profile.agentName,
                    email = profile.email ?: "Sem Email",
                    photoUrl = agent?.photoUrl,
                    size = 48.dp
                )
                
                Spacer(modifier = Modifier.width(16.dp))
                
                Column(modifier = Modifier.weight(1f)) {
                    val displayName = profile.agentName?.takeIf { it.isNotBlank() } ?: profile.email?.ifBlank { "Sem Email" } ?: "Sem Nome"
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Role Badge
                        Box {
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                                shape = RoundedCornerShape(6.dp),
                                onClick = { expandedRole = true }
                            ) {
                                Row(modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.AdminPanelSettings, null, modifier = Modifier.size(10.dp), tint = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.width(4.dp))
                                    Text(profile.role.name, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, fontSize = 9.sp)
                                }
                            }
                            DropdownMenu(expanded = expandedRole, onDismissRequest = { expandedRole = false }) {
                                UserRole.entries.forEach { role ->
                                    DropdownMenuItem(
                                        text = { Text(role.name) },
                                        onClick = { onRoleChange(role); expandedRole = false }
                                    )
                                }
                            }
                        }

                        if (profile.isPreRegistered) {
                            Surface(
                                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text("PRÉ-REGISTRO", modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, fontSize = 9.sp)
                            }
                        }

                        if (agent != null) {
                            Text(
                                text = "Sinc: $lastSync",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 9.sp
                            )
                        }
                    }
                }
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (profile.uid != null) {
                        Checkbox(
                            checked = profile.isAuthorized,
                            onCheckedChange = { onAuthorize(it) },
                            modifier = Modifier.scale(0.8f)
                        )
                    }
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                    HorizontalDivider(modifier = Modifier.padding(bottom = 12.dp))
                    
                    // Production Stats (Supervisor Style)
                    if (agent != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            val housesCount = agent.summary?.totalHouses?.toString() ?: agent.houses.size.toString()
                            val daysCount = agent.summary?.daysWorked?.toString() ?: agent.activities.size.toString()
                            val focusCount = agent.summary?.focusCount?.toString() ?: agent.houses.count { it.treatment.comFoco }.toString()
                            val treatedCount = agent.summary?.treatedCount?.toString() ?: agent.houses.count { it.treatment.a1 > 0 || it.treatment.a2 > 0 || it.treatment.b > 0 || it.treatment.c > 0 || it.treatment.d1 > 0 || it.treatment.d2 > 0 || it.treatment.e > 0 }.toString()
                            
                            AgentStatItem(label = "IMÓVEIS", value = housesCount, modifier = Modifier.weight(1f))
                            AgentStatItem(label = "TRATADOS", value = treatedCount, modifier = Modifier.weight(1f))
                            AgentStatItem(label = "DIAS", value = daysCount, modifier = Modifier.weight(1f))
                            AgentStatItem(
                                label = "FOCOS", 
                                value = focusCount,
                                color = if ((agent.summary?.focusCount ?: 0) > 0 || agent.houses.any { it.treatment.comFoco }) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Text(
                            text = "SITUAÇÃO DAS VISITAS",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Black
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                val openCount = agent.summary?.let { s -> 
                                    (s.situationCounts["NONE"] ?: 0) + (s.situationCounts["EMPTY"] ?: 0)
                                }?.toString() ?: agent.houses.count { it.situation == com.antigravity.healthagent.data.local.model.Situation.NONE || it.situation == com.antigravity.healthagent.data.local.model.Situation.EMPTY }.toString()
                                val vCount = agent.summary?.situationCounts?.get("V")?.toString() ?: agent.houses.count { it.situation == com.antigravity.healthagent.data.local.model.Situation.V }.toString()
                                val fCount = agent.summary?.situationCounts?.get("F")?.toString() ?: agent.houses.count { it.situation == com.antigravity.healthagent.data.local.model.Situation.F }.toString()
                                
                                CompactStatChip(label = "ABERTOS", value = openCount, modifier = Modifier.weight(1f))
                                CompactStatChip(label = "V", value = vCount, modifier = Modifier.weight(1f))
                                CompactStatChip(label = "F", value = fCount, modifier = Modifier.weight(1f))
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                val recCount = agent.summary?.situationCounts?.get("REC")?.toString() ?: agent.houses.count { it.situation == com.antigravity.healthagent.data.local.model.Situation.REC }.toString()
                                val aCount = agent.summary?.situationCounts?.get("A")?.toString() ?: agent.houses.count { it.situation == com.antigravity.healthagent.data.local.model.Situation.A }.toString()
                                
                                CompactStatChip(label = "REC", value = recCount, modifier = Modifier.weight(1f))
                                CompactStatChip(label = "A", value = aCount, modifier = Modifier.weight(1f))
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "TIPOS DE IMÓVEIS",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Black
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                val rCount = agent.summary?.propertyTypeCounts?.get("R")?.toString() ?: agent.houses.count { it.propertyType == com.antigravity.healthagent.data.local.model.PropertyType.R }.toString()
                                val cCount = agent.summary?.propertyTypeCounts?.get("C")?.toString() ?: agent.houses.count { it.propertyType == com.antigravity.healthagent.data.local.model.PropertyType.C }.toString()
                                val tbCount = agent.summary?.propertyTypeCounts?.get("TB")?.toString() ?: agent.houses.count { it.propertyType == com.antigravity.healthagent.data.local.model.PropertyType.TB }.toString()
                                
                                CompactStatChip(label = "RES", value = rCount, modifier = Modifier.weight(1f))
                                CompactStatChip(label = "COM", value = cCount, modifier = Modifier.weight(1f))
                                CompactStatChip(label = "TB", value = tbCount, modifier = Modifier.weight(1f))
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                val peCount = agent.summary?.propertyTypeCounts?.get("PE")?.toString() ?: agent.houses.count { it.propertyType == com.antigravity.healthagent.data.local.model.PropertyType.PE }.toString()
                                val oCount = agent.summary?.propertyTypeCounts?.get("O")?.toString() ?: agent.houses.count { it.propertyType == com.antigravity.healthagent.data.local.model.PropertyType.O }.toString()
                                
                                CompactStatChip(label = "PE", value = peCount, modifier = Modifier.weight(1f))
                                CompactStatChip(label = "OUT", value = oCount, modifier = Modifier.weight(1f))
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        Text(
                            text = "ÚLTIMAS PRODUÇÕES",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Black
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        val sortedActivities = remember(agent.activities) {
                            val sdf = com.antigravity.healthagent.utils.DateUtils.DASH_DATE.get()
                            agent.activities.sortedByDescending { activity ->
                                try {
                                    val normalized = activity.date.replace("/", "-")
                                    sdf.parse(normalized)?.time ?: 0L
                                } catch (_: Exception) {
                                    0L
                                }
                            }.take(5)
                        }

                        if (sortedActivities.isEmpty()) {
                            Text("Nenhuma produção registrada", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            sortedActivities.forEach { activity ->
                                val normalizedDate = activity.date.replace("/", "-")
                                val activityHouses = agent.houses.filter { it.data.replace("/", "-") == normalizedDate }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            if (activity.isClosed) Icons.Default.Lock else Icons.Default.LockOpen,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                            tint = if (activity.isClosed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            activity.date,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Text(
                                        "${activityHouses.size} imóveis",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                            }
                        }
                        
                        // Sync Error if any
                        if (agent.lastSyncError != null) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Erro: ${agent.lastSyncError}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                                    IconButton(onClick = onClearSyncError, modifier = Modifier.size(24.dp)) {
                                        Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                    } else {
                        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                            Text("Sem dados de produção para o período.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    // --- Admin Actions ---
                    Text("GERENCIAMENTO", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (profile.uid != null) {
                            ActionButton(Icons.Default.DriveFileRenameOutline, "Vincular", onClick = { showEditNameDialog = true })
                        }
                        if (profile.uid != null && profile.agentData == null) {
                            ActionButton(Icons.Default.MergeType, "Migrar Dados", onClick = onMigrateData)
                        }
                        if (profile.uid != null) {
                            ActionButton(Icons.Default.DeleteForever, "Excluir Conta", tint = MaterialTheme.colorScheme.error, onClick = onDelete)
                            ActionButton(Icons.Default.MoveUp, "Transferir", onClick = onTransferData)
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("PRODUÇÃO E BACKUP", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Only show data restoration/wipe for profiles that actually exist (have a UID)
                        if (profile.uid != null) {
                            ActionButton(Icons.Default.SettingsBackupRestore, "Restauração", onClick = onRestore)
                            ActionButton(Icons.Default.PostAdd, "Importar Dia", onClick = { onRestoreDay(profile.uid) })
                            ActionButton(Icons.Default.DeleteSweep, "Wipe Remoto", tint = MaterialTheme.colorScheme.error, onClick = onRemoteWipe)
                        }
                        
                        if (agent != null) {
                            ActionButton(Icons.Default.QueryStats, "Analisar", onClick = onEditAgent)
                        }

                        if (profile.uid != null) {
                            ActionButton(Icons.Default.History, "Timeline", onClick = { onOpenTimeline(profile.uid, profile.agentName ?: profile.email ?: "") })
                        }
                    }
                }
            }

            if (showEditNameDialog) {
                var nameInput by remember { mutableStateOf(profile.agentName ?: "") }
                var expandedNameMenu by remember { mutableStateOf(false) }

                AlertDialog(
                    onDismissRequest = { showEditNameDialog = false },
                    title = { Text("Vincular Nome ao Perfil") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Defina o nome da lista mestra para este usuário (${profile.email}):", style = MaterialTheme.typography.bodySmall)
                            
                            Column {
                                OutlinedTextField(
                                    value = nameInput,
                                    onValueChange = { nameInput = it; expandedNameMenu = true },
                                    label = { Text("Nome do Agente") },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp),
                                    trailingIcon = { 
                                        IconButton(onClick = { expandedNameMenu = !expandedNameMenu }) {
                                            Icon(Icons.Default.ArrowDropDown, null)
                                        }
                                    }
                                )
                                
                                DropdownMenu(
                                    expanded = expandedNameMenu && agentNamesList.isNotEmpty(),
                                    onDismissRequest = { expandedNameMenu = false },
                                    properties = PopupProperties(focusable = false),
                                    modifier = Modifier.fillMaxWidth(0.8f)
                                ) {
                                    val filteredNames = remember(nameInput, agentNamesList) {
                                        agentNamesList.filter { 
                                            it.contains(nameInput, ignoreCase = true) 
                                        }.take(5)
                                    }

                                    filteredNames.forEach { name ->
                                        DropdownMenuItem(
                                            text = { Text(name) },
                                            onClick = { 
                                                nameInput = name
                                                expandedNameMenu = false 
                                            }
                                        )
                                    }
                                }
                            }
                            
                            TextButton(onClick = { nameInput = ""; onUpdateName(null); showEditNameDialog = false }) {
                                Text("Limpar Vínculo Existente", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    },
                    confirmButton = {
                        Button(onClick = { onUpdateName(nameInput.takeIf { it.isNotBlank() }); showEditNameDialog = false }) {
                            Text("Confirmar")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showEditNameDialog = false }) {
                            Text("Cancelar")
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun QuickStat(icon: androidx.compose.ui.graphics.vector.ImageVector, count: Int, label: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        shape = RoundedCornerShape(6.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Icon(icon, null, modifier = Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(4.dp))
            Text("$count $label", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Black)
        }
    }
}

@Composable
fun ActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector, 
    label: String, 
    tint: Color = MaterialTheme.colorScheme.primary,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.height(32.dp),
        contentPadding = PaddingValues(horizontal = 12.dp),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, tint.copy(alpha = 0.3f))
    ) {
        Icon(icon, null, modifier = Modifier.size(16.dp), tint = tint)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = tint)
    }
}

@Composable
fun StatItem(label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color = Color.White) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = color.copy(alpha = 0.7f), modifier = Modifier.size(16.dp))
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black, color = color)
        Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = color.copy(alpha = 0.6f), fontSize = 9.sp)
    }
}

@Composable
fun AgentStatItem(label: String, value: String, color: Color = MaterialTheme.colorScheme.primary, modifier: Modifier = Modifier) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Black,
            color = color
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Black,
            color = MaterialTheme.colorScheme.primary,
            fontSize = 9.sp
        )
    }
}

@Composable
fun CompactStatChip(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(8.dp),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
