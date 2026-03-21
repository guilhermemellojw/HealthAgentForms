// Admin Dashboard Screen - Unified View
package com.antigravity.healthagent.ui.admin

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.antigravity.healthagent.domain.repository.UserRole
import com.antigravity.healthagent.domain.repository.AuthUser
import com.antigravity.healthagent.domain.repository.AccessRequest
import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.local.model.DayActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.graphics.Color
import com.antigravity.healthagent.ui.components.PremiumCard
import com.antigravity.healthagent.ui.components.GlassTopAppBar
import com.antigravity.healthagent.ui.components.MeshGradient
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi

// --- Helper Components ---

@Composable
fun AdminSettingsTab(viewModel: AdminViewModel) {
    val bairros by viewModel.bairros.collectAsState()
    var showAddBairroDialog by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Configurações do Sistema", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(16.dp))
        PremiumCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Gerenciar Bairros", style = MaterialTheme.typography.titleSmall)
                    IconButton(onClick = { showAddBairroDialog = true }) {
                        Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
                LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                    items(bairros) { bairro ->
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(bairro); IconButton(onClick = { viewModel.deleteBairro(bairro) }) { Icon(Icons.Default.Close, null, modifier = Modifier.size(16.dp)) }
                        }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
        Text("Configurações Globais", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))
        val maxHouses by viewModel.maxOpenHouses.collectAsState()
        var tempMaxHouses by remember(maxHouses) { mutableFloatStateOf(maxHouses.toFloat()) }
        PremiumCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Meta Diária (Global): ${tempMaxHouses.toInt()}", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = tempMaxHouses,
                    onValueChange = { tempMaxHouses = it },
                    onValueChangeFinished = { viewModel.updateSystemSetting("max_open_houses", tempMaxHouses.toLong()) },
                    valueRange = 25f..35f,
                    steps = 9
                )
                Text("Este valor afeta todos os agentes após a próxima sincronização.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (showAddBairroDialog) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddBairroDialog = false },
            title = { Text("Novo Bairro") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Nome do Bairro") }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { Button(onClick = { viewModel.addBairro(name); showAddBairroDialog = false }) { Text("Adicionar") } },
            dismissButton = { TextButton(onClick = { showAddBairroDialog = false }) { Text("Cancelar") } }
        )
    }
}

// Redundant AgentCard removed in favor of UnifiedProfileCard

@Composable
fun AgentStatSubItem(label: String, value: String, color: Color = MaterialTheme.colorScheme.onSurface) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, color = color)
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UnifiedProfileCard(
    profile: UnifiedProfile,
    agentNamesList: List<String>,
    onAuthorize: (Boolean) -> Unit,
    onRoleChange: (UserRole) -> Unit,
    onUpdateName: (String?) -> Unit,
    onDelete: () -> Unit,
    onRestore: () -> Unit,
    onEditAgent: () -> Unit,
    onDeleteHouse: (String, String) -> Unit,
    onDeleteActivity: (String, String) -> Unit,
    onClearSyncError: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    
    PremiumCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = { expanded = !expanded }
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Surface(
                        shape = CircleShape,
                        color = if (profile.isPreRegistered) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = if (profile.isPreRegistered) Icons.Default.PersonAddDisabled else Icons.Default.Person,
                            contentDescription = null,
                            modifier = Modifier.padding(10.dp),
                            tint = if (profile.isPreRegistered) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = profile.email ?: "Pre-register: ${profile.agentName}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (profile.isPreRegistered) {
                            Text(
                                "Conta não vinculada",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        } else {
                            Text(
                                text = "Função: ${profile.role.name}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Quick Stats / Attribution Bar
            if (!expanded) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Agent Name Attribution
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                        ) {
                            Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Badge, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(profile.agentName ?: "Sem Nome", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            }
                        }
                        
                        // PRODUCTION SUMMARY IN COLLAPSED STATE
                        profile.agentData?.let { data ->
                            Spacer(modifier = Modifier.width(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Home, null, modifier = Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(" ${data.houses.size}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(modifier = Modifier.width(6.dp))
                                if (data.houses.any { it.comFoco }) {
                                    Icon(Icons.Default.Warning, null, modifier = Modifier.size(12.dp), tint = MaterialTheme.colorScheme.error)
                                    Text(" ${data.houses.count { it.comFoco }}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }

                    // Cloud Status
                    if (profile.agentData != null) {
                        val lastSync = if (profile.agentData.lastSyncTime > 0) {
                            SimpleDateFormat("dd/MM HH:mm", Locale.US).format(Date(profile.agentData.lastSyncTime))
                        } else "Nunca"
                        
                        Column(horizontalAlignment = Alignment.End) {
                            Text("Sinc: $lastSync", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (profile.agentData.lastSyncError != null) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.SyncProblem, 
                                        null, 
                                        modifier = Modifier.size(12.dp), 
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        "Erro no Sync", 
                                        style = MaterialTheme.typography.labelSmall, 
                                        color = MaterialTheme.colorScheme.error,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    } else if (profile.isPreRegistered && profile.uid == null) {
                         Text("Pendente", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column {
                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                    Spacer(modifier = Modifier.height(16.dp))

                    // Account Settings
                    Text("Gerenciar Conta", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(
                            selected = profile.isAuthorized,
                            onClick = { onAuthorize(!profile.isAuthorized) },
                            label = { Text(if (profile.isAuthorized) "Autorizado" else "Bloqueado") },
                            leadingIcon = { Icon(if (profile.isAuthorized) Icons.Default.CheckCircle else Icons.Default.Block, null, modifier = Modifier.size(18.dp)) }
                        )
                        
                        var showRoleMenu by remember { mutableStateOf(false) }
                        Box {
                            AssistChip(
                                onClick = { showRoleMenu = true },
                                label = { Text("Mudar Função") },
                                leadingIcon = { Icon(Icons.Default.AdminPanelSettings, null, modifier = Modifier.size(18.dp)) }
                            )
                            DropdownMenu(expanded = showRoleMenu, onDismissRequest = { showRoleMenu = false }) {
                                UserRole.entries.forEach { role ->
                                    DropdownMenuItem(
                                        text = { Text(role.name) },
                                        onClick = { onRoleChange(role); showRoleMenu = false }
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Attribution Settings
                    Text("Atribuição de Nome (Master List)", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    var showNameMenu by remember { mutableStateOf(false) }
                    Box {
                        OutlinedButton(
                            onClick = { showNameMenu = true },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(profile.agentName ?: "Vincular Nome do Agente")
                            Spacer(modifier = Modifier.weight(1f))
                            Icon(Icons.Default.ArrowDropDown, null)
                        }
                        DropdownMenu(
                            expanded = showNameMenu,
                            onDismissRequest = { showNameMenu = false },
                            modifier = Modifier.fillMaxWidth(0.8f)
                        ) {
                            DropdownMenuItem(text = { Text("Nenhum") }, onClick = { onUpdateName(null); showNameMenu = false })
                            agentNamesList.forEach { name ->
                                DropdownMenuItem(text = { Text(name) }, onClick = { onUpdateName(name); showNameMenu = false })
                            }
                        }
                    }

                    // Agent Data Stats
                    profile.agentData?.let { data ->
                        Spacer(modifier = Modifier.height(24.dp))
                        Text("Estatísticas de Nuvem", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                            AgentStatSubItem(label = "Imóveis", value = data.houses.size.toString())
                            AgentStatSubItem(label = "Focos", value = data.houses.count { it.comFoco }.toString(), color = if (data.houses.any { it.comFoco }) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                            AgentStatSubItem(label = "Dias", value = data.activities.size.toString())
                        }
                        
                        // Sync Error Action
                        if (data.lastSyncError != null) {
                            Spacer(modifier = Modifier.height(16.dp))
                            PremiumCard(
                                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Error, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Erro de Sincronização", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(data.lastSyncError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                                    Spacer(modifier = Modifier.height(8.dp))
                                    TextButton(
                                        onClick = onClearSyncError,
                                        modifier = Modifier.align(Alignment.End),
                                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                    ) {
                                        Icon(Icons.Default.DeleteSweep, null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Limpar Erro")
                                    }
                                }
                            }
                        }
                        
                        // Granular Control
                        Spacer(modifier = Modifier.height(16.dp))
                        GranularDataSection(
                            agentUid = data.uid,
                            houses = data.houses,
                            activities = data.activities,
                            onDeleteHouse = onDeleteHouse,
                            onDeleteActivity = onDeleteActivity,
                            onClearSyncError = onClearSyncError
                        )
                    }

                    // RESTORE / EDIT BUTTONS (Available for all authorized users)
                    if (profile.isAuthorized || profile.isPreRegistered) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = onEditAgent,
                                modifier = Modifier.weight(1f).height(40.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            ) {
                                Icon(Icons.Default.Edit, null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("EDITAR", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            }
                            Button(
                                onClick = onRestore,
                                modifier = Modifier.weight(1.2f).height(40.dp),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.SettingsBackupRestore, null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("RESTAURAR", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                    
                    // Danger Zone
                    TextButton(
                        onClick = onDelete,
                        modifier = Modifier.fillMaxWidth().height(40.dp),
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Default.DeleteForever, null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("EXCLUIR PERFIL", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AddProfileDialog(
    agentNamesList: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (String?, String?, UserRole, Boolean) -> Unit
) {
    var mode by remember { mutableIntStateOf(0) } // 0: Novo Usuário, 1: Apenas Nome, 2: Vincular
    var email by remember { mutableStateOf("") }
    var agentName by remember { mutableStateOf<String?>(null) }
    var customName by remember { mutableStateOf("") }
    var role by remember { mutableStateOf(UserRole.AGENT) }
    var isAuthorized by remember { mutableStateOf(true) }
    
    var expandedName by remember { mutableStateOf(false) }
    var expandedRole by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(when(mode) { 0 -> "Novo Usuário"; 1 -> "Adicionar Nome"; else -> "Vincular Conta" }) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(selected = mode == 0, onClick = { mode = 0 }, shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3)) { Text("Usuário", style = MaterialTheme.typography.labelSmall) }
                    SegmentedButton(selected = mode == 1, onClick = { mode = 1 }, shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3)) { Text("Nome", style = MaterialTheme.typography.labelSmall) }
                    SegmentedButton(selected = mode == 2, onClick = { mode = 2 }, shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3)) { Text("Vincular", style = MaterialTheme.typography.labelSmall) }
                }

                if (mode == 0 || mode == 2) {
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text("Email do Usuário") },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("usuario@email.com") }
                    )
                }

                if (mode == 1) {
                    OutlinedTextField(
                        value = customName,
                        onValueChange = { customName = it },
                        label = { Text("Nome do Agente") },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    Box {
                        OutlinedButton(onClick = { expandedName = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(agentName ?: "Vincular Nome (Opcional)")
                        }
                        DropdownMenu(expanded = expandedName, onDismissRequest = { expandedName = false }) {
                            DropdownMenuItem(text = { Text("Nenhum") }, onClick = { agentName = null; expandedName = false })
                            agentNamesList.forEach { name ->
                                DropdownMenuItem(text = { Text(name) }, onClick = { agentName = name; expandedName = false })
                            }
                        }
                    }
                }

                if (mode == 0) {
                    Box {
                        OutlinedButton(onClick = { expandedRole = true }, modifier = Modifier.fillMaxWidth()) {
                            Text("Papel: ${role.name}")
                        }
                        DropdownMenu(expanded = expandedRole, onDismissRequest = { expandedRole = false }) {
                            UserRole.entries.forEach { r ->
                                DropdownMenuItem(text = { Text(r.name) }, onClick = { role = r; expandedRole = false })
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = isAuthorized, onCheckedChange = { isAuthorized = it })
                        Text("Autorizado a Sincronizar")
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { 
                    when(mode) {
                        0 -> onConfirm(email, agentName, role, isAuthorized)
                        1 -> onConfirm(null, customName, UserRole.AGENT, false)
                        2 -> onConfirm(email, agentName, UserRole.AGENT, true)
                    }
                },
                enabled = when(mode) {
                    0 -> email.isNotBlank()
                    1 -> customName.isNotBlank()
                    2 -> email.isNotBlank() && agentName != null
                    else -> false
                }
            ) {
                Text("Confirmar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        }
    )
}

// --- Main Screen ---

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AdminDashboardScreen(
    viewModel: AdminViewModel,
    onNavigateBack: () -> Unit,
    user: AuthUser? = null,
    onLogout: () -> Unit = {},
    onSwitchAccount: () -> Unit = {},
    onOpenSettings: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val users by viewModel.users.collectAsState()
    val agentNames by viewModel.agentNames.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val unifiedProfiles by viewModel.unifiedProfiles.collectAsState()

    var selectedTab by remember { mutableIntStateOf(0) }
    var showAddProfileDialog by remember { mutableStateOf(false) }
    var showAddNameMetadataDialog by remember { mutableStateOf(false) }
    var selectedUidForRestore by remember { mutableStateOf<String?>(null) }
    
    var showConfirmDialog by remember { mutableStateOf(false) }
    var confirmTitle by remember { mutableStateOf("") }
    var confirmMessage by remember { mutableStateOf("") }
    var onConfirmAction by remember { mutableStateOf<() -> Unit>({}) }
    
    var showApprovalDialog by remember { mutableStateOf(false) }
    var pendingRequest by remember { mutableStateOf<AccessRequest?>(null) }

    var showDeleteUserDialog by remember { mutableStateOf(false) }
    var userToDelete by remember { mutableStateOf<UnifiedProfile?>(null) }
    var deleteCloudDataWithUser by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val tabs = listOf("Gestão", "Config.")

    val filePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            val targetUid = selectedUidForRestore
            if (targetUid != null) {
                confirmTitle = "Confirmar Restauração"; confirmMessage = "Deseja restaurar dados?"; onConfirmAction = { viewModel.restoreAgentBackup(context, targetUid, it) }; showConfirmDialog = true; selectedUidForRestore = null
            }
        }
    }

    LaunchedEffect(viewModel) { viewModel.uiEvent.collect { snackbarHostState.showSnackbar(it) } }
    androidx.activity.compose.BackHandler { onNavigateBack() }

    Scaffold(
        topBar = { GlassTopAppBar(
            title = { Text("Painel do Administrador", fontWeight = FontWeight.Black) }, 
            navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.Default.ArrowBack, null) } }, 
            actions = { 
                IconButton(onClick = { 
                    selectedUidForRestore = viewModel.getCurrentUserUid()
                    filePickerLauncher.launch("application/json")
                }) { Icon(Icons.Default.Restore, "Restaurar meus dados") }
                // Redundant Refresh Icon removed in favor of Pull-to-Refresh
            }
        ) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = { 
            if (selectedTab == 0) { 
                FloatingActionButton(onClick = { 
                    showAddProfileDialog = true
                }) { Icon(Icons.Default.Add, null) } 
            } 
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            MeshGradient(modifier = Modifier.fillMaxSize())
            Column(modifier = Modifier.fillMaxSize()) {
                TabRow(selectedTabIndex = selectedTab, containerColor = Color.Transparent) {
                    tabs.forEachIndexed { index, title -> Tab(selected = selectedTab == index, onClick = { selectedTab = index }, text = { Text(title) }) }
                }
                Box(modifier = Modifier.weight(1f)) {
                    if (selectedTab == 0) {
                        // Gestão Tab (Combined Profiles, Requests, and Master List)
                        Column(modifier = Modifier.fillMaxSize()) {
                            var showMasterList by remember { mutableStateOf(false) }
                            
                            // 1. Access Requests (if any)
                            val accessRequests by viewModel.accessRequests.collectAsState()
                            if (accessRequests.isNotEmpty()) {
                                Text("Solicitações de Acesso", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp))
                                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    items(accessRequests) { request ->
                                        AccessRequestCard(
                                            request = request,
                                            onApprove = { showApprovalDialog = true; pendingRequest = request },
                                            onReject = { viewModel.rejectAccess(request.id) }
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                            }

                            // 2. Search and Master List Toggle
                            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = searchQuery,
                                    onValueChange = { viewModel.updateSearchQuery(it) },
                                    modifier = Modifier.weight(1f),
                                    placeholder = { Text("Pesquisar agente ou email...") },
                                    leadingIcon = { Icon(Icons.Default.Search, null) },
                                    shape = RoundedCornerShape(12.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                IconButton(onClick = { showMasterList = !showMasterList }) {
                                    Icon(if (showMasterList) Icons.Default.Badge else Icons.Default.ListAlt, null, tint = if (showMasterList) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                                }
                            }
                            
                            // 3. Master List Section
                            AnimatedVisibility(visible = showMasterList) {
                                PremiumCard(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text("Lista Mestra de Nomes", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                                        Spacer(modifier = Modifier.height(8.dp))
                                        FlowRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            agentNames.forEach { name ->
                                                InputChip(
                                                    selected = false,
                                                    onClick = { },
                                                    label = { Text(name) },
                                                    trailingIcon = { 
                                                        IconButton(onClick = { 
                                                            confirmTitle = "Excluir Nome"; confirmMessage = "Excluir '$name' da lista mestra?"; onConfirmAction = { viewModel.removeAgentName(name) }; showConfirmDialog = true 
                                                        }, modifier = Modifier.size(14.dp)) {
                                                            Icon(Icons.Default.Close, null)
                                                        }
                                                    }
                                                )
                                            }
                                            AssistChip(
                                                onClick = { showAddProfileDialog = true },
                                                label = { Text("Adicionar Nome") },
                                                leadingIcon = { Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp)) }
                                            )
                                        }
                                    }
                                }
                            }

                            // 4. Unified Profiles List
                            // Fallback to Box/CircularProgressIndicator if PullToRefreshBox is unresolved
                            val isRefreshing = uiState is AdminUiState.Loading
                            
                            Box(modifier = Modifier.weight(1f)) {
                                Column(modifier = Modifier.fillMaxSize()) {
                                    if (isRefreshing) {
                                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                    }
                                    if (unifiedProfiles.isEmpty()) {
                                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                            Text("Nenhum usuário encontrado.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    } else {
                                        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
                                            items(unifiedProfiles) { profile ->
                                                UnifiedProfileCard(
                                                    profile = profile,
                                                    agentNamesList = agentNames,
                                                    onAuthorize = { viewModel.authorizeUser(profile.uid ?: "", it) },
                                                    onRoleChange = { viewModel.changeUserRole(profile.uid ?: "", it) },
                                                    onUpdateName = { name ->
                                                        profile.uid?.let { viewModel.updateUserProfile(it, mapOf("agentName" to name)) }
                                                    },
                                                    onDelete = {
                                                        userToDelete = profile
                                                        deleteCloudDataWithUser = false
                                                        showDeleteUserDialog = true
                                                    },
                                                    onRestore = { selectedUidForRestore = profile.uid ?: profile.agentData?.uid; filePickerLauncher.launch("application/json") },
                                                    onEditAgent = { viewModel.selectAgentForEdit(profile.agentData); onNavigateBack() },
                                                    onDeleteHouse = { uid, id -> confirmTitle = "Excluir Imóvel"; confirmMessage = "Deseja excluir este registro de imóvel da nuvem?"; onConfirmAction = { viewModel.deleteAgentHouse(uid, id) }; showConfirmDialog = true },
                                                    onDeleteActivity = { uid, date -> confirmTitle = "Excluir Atividade"; confirmMessage = "Deseja excluir este registro de atividade ($date) da nuvem?"; onConfirmAction = { viewModel.deleteAgentActivity(uid, date) }; showConfirmDialog = true },
                                                    onClearSyncError = { profile.uid?.let { viewModel.clearSyncError(it) } }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } else if (selectedTab == 1) {
                        AdminSettingsTab(viewModel)
                    }
                }
            }
        }
    }

    if (showAddProfileDialog) {
        AddProfileDialog(
            agentNamesList = agentNames,
            onDismiss = { showAddProfileDialog = false },
            onConfirm = { email, name, role, authorized ->
                if (email != null) {
                    viewModel.createUser(email, role, name, authorized)
                } else if (name != null) {
                    viewModel.addAgentName(name)
                }
                showAddProfileDialog = false
            }
        )
    }
    
    if (showConfirmDialog) {
        AlertDialog(onDismissRequest = { showConfirmDialog = false }, title = { Text(confirmTitle) }, text = { Text(confirmMessage) }, confirmButton = { Button(onClick = { onConfirmAction(); showConfirmDialog = false }) { Text("Confirmar") } }, dismissButton = { TextButton(onClick = { showConfirmDialog = false }) { Text("Cancelar") } })
    }

    if (showApprovalDialog && pendingRequest != null) {
        ApprovalDialog(
            request = pendingRequest!!,
            agentNamesList = agentNames,
            onDismiss = { showApprovalDialog = false; pendingRequest = null },
            onConfirm = { agentName ->
                viewModel.approveAccess(pendingRequest!!.id, agentName)
                showApprovalDialog = false
                pendingRequest = null
            }
        )
    }

    if (showDeleteUserDialog && userToDelete != null) {
        AlertDialog(
            onDismissRequest = { showDeleteUserDialog = false },
            title = { Text("Excluir Perfil") },
            text = {
                Column {
                    Text("Confirmar a exclusão de ${userToDelete?.email ?: userToDelete?.agentName}?")
                    if (userToDelete?.agentData != null) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = deleteCloudDataWithUser, onCheckedChange = { deleteCloudDataWithUser = it })
                            Text("Excluir também dados da nuvem (imóveis e atividades)", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val uid = userToDelete?.uid
                        if (uid != null) {
                            viewModel.deleteUser(uid, deleteCloudDataWithUser)
                        } else {
                            userToDelete?.agentData?.uid?.let { viewModel.deleteAgent(it) }
                        }
                        showDeleteUserDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Excluir") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteUserDialog = false }) { Text("Cancelar") }
            }
        )
    }
}

@Composable
fun AccessRequestCard(
    request: AccessRequest,
    onApprove: () -> Unit,
    onReject: () -> Unit
) {
    PremiumCard(modifier = Modifier.width(280.dp)) {
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
                Box {
                    OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(selectedName ?: "Selecionar Nome (Opcional)")
                        Spacer(modifier = Modifier.weight(1f))
                        Icon(Icons.Default.ArrowDropDown, null)
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        DropdownMenuItem(text = { Text("Nenhum") }, onClick = { selectedName = null; expanded = false })
                        agentNamesList.forEach { name ->
                            DropdownMenuItem(text = { Text(name) }, onClick = { selectedName = name; expanded = false })
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
fun GranularDataSection(
    agentUid: String,
    houses: List<House>,
    activities: List<DayActivity>,
    onDeleteHouse: (String, String) -> Unit,
    onDeleteActivity: (String, String) -> Unit,
    onClearSyncError: () -> Unit
) {
    var showHouses by remember { mutableStateOf(false) }
    var showActivities by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Controle de Jornada", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        
        OutlinedButton(onClick = { showHouses = !showHouses }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.HomeWork, null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Imóveis (${houses.size})", style = MaterialTheme.typography.labelSmall)
                Spacer(modifier = Modifier.weight(1f))
                Icon(if (showHouses) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
            }
        }

        AnimatedVisibility(visible = showHouses) {
            Surface(
                modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (houses.isEmpty()) {
                    Box(modifier = Modifier.padding(16.dp), contentAlignment = Alignment.Center) {
                        Text("Nenhum imóvel", style = MaterialTheme.typography.labelSmall)
                    }
                } else {
                    LazyColumn(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(houses) { house ->
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(4.dp)) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("${house.streetName}, ${house.number}", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                    Text("${house.data} • ${house.bairro}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                IconButton(onClick = { onDeleteHouse(agentUid, house.cloudId ?: house.generateNaturalKey()) }) {
                                    Icon(Icons.Default.Delete, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                        }
                    }
                }
            }
        }

        OutlinedButton(onClick = { showActivities = !showActivities }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Event, null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Atividades (${activities.size})", style = MaterialTheme.typography.labelSmall)
                Spacer(modifier = Modifier.weight(1f))
                Icon(if (showActivities) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
            }
        }

        AnimatedVisibility(visible = showActivities) {
            Surface(
                modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (activities.isEmpty()) {
                    Box(modifier = Modifier.padding(16.dp), contentAlignment = Alignment.Center) {
                        Text("Nenhuma atividade", style = MaterialTheme.typography.labelSmall)
                    }
                } else {
                    LazyColumn(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(activities) { activity ->
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(4.dp)) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Data: ${activity.date}", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                    // Placeholder for pendentes info as it's not in the model anymore
                                    Text("Status: ${activity.status}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                IconButton(onClick = { onDeleteActivity(agentUid, activity.date) }) {
                                    Icon(Icons.Default.Delete, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                        }
                    }
                }
            }
        }
    }
}
