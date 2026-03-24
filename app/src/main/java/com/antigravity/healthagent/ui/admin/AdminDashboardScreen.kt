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
import androidx.compose.foundation.*

// --- Helper Components ---

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AdminSettingsTab(viewModel: AdminViewModel) {
    val bairros by viewModel.bairros.collectAsState()
    val globalActivities by viewModel.globalCustomActivities.collectAsState()
    val maxHouses by viewModel.maxOpenHouses.collectAsState()
    val systemSettings by viewModel.systemSettings.collectAsState()
    
    var showAddBairroDialog by remember { mutableStateOf(false) }
    var showAddActivityDialog by remember { mutableStateOf(false) }
    
    val scrollState = androidx.compose.foundation.rememberScrollState()
    
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
            color = MaterialTheme.colorScheme.primary
        )

        // --- Section 1: Jornada e Metas ---
        SettingsSectionHeader(title = "Jornada e Metas", icon = Icons.Default.Timer)
        PremiumCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // Meta Diária
                var tempMaxHouses by remember(maxHouses) { mutableFloatStateOf(maxHouses.toFloat()) }
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
        SettingsSectionHeader(title = "Atividades Padrão", icon = Icons.Default.PlaylistAddCheck)
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
fun SettingsSectionHeader(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
    }
}

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
            user = user,
            onLogout = onLogout,
            onSwitchAccount = onSwitchAccount,
            onOpenSettings = onOpenSettings
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
                                                    onClearSyncError = { profile.uid?.let { viewModel.clearSyncError(it) } },
                                                    onMigrateData = {
                                                        // We need an AuthUser object for migration. 
                                                        // profiles list is derived from users and agents. 
                                                        // Let's find the actual AuthUser from the users list.
                                                        val authUser = users.find { it.uid == profile.uid }
                                                        if (authUser != null) {
                                                            confirmTitle = "Migrar Dados"; confirmMessage = "Deseja migrar os dados de conta não vinculada para este perfil (${authUser.email})?"; onConfirmAction = { viewModel.migrateData(authUser) }; showConfirmDialog = true
                                                        }
                                                    }
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
                    OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
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
fun AddProfileDialog(
    agentNamesList: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (String?, String?, UserRole, Boolean) -> Unit
) {
    var email by remember { mutableStateOf("") }
    var agentName by remember { mutableStateOf<String?>(null) }
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

                Box {
                    OutlinedButton(onClick = { expandedName = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                        Text(agentName ?: "Vincular a Nome da Lista Mestra (Opcional)")
                        Spacer(modifier = Modifier.weight(1f))
                        Icon(Icons.Default.ArrowDropDown, null)
                    }
                    DropdownMenu(expanded = expandedName, onDismissRequest = { expandedName = false }) {
                        agentNamesList.forEach { name ->
                            DropdownMenuItem(text = { Text(name) }, onClick = { agentName = name; expandedName = false })
                        }
                        DropdownMenuItem(text = { Text("Nenhum / Novo") }, onClick = { agentName = null; expandedName = false })
                    }
                }

                Box {
                    OutlinedButton(onClick = { expandedRole = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                        Text("Função: ${role.name}")
                        Spacer(modifier = Modifier.weight(1f))
                        Icon(Icons.Default.ArrowDropDown, null)
                    }
                    DropdownMenu(expanded = expandedRole, onDismissRequest = { expandedRole = false }) {
                        UserRole.values().forEach { r ->
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
            Button(onClick = { 
                onConfirm(email.takeIf { it.isNotBlank() }, agentName, role, authorized) 
            }) { Text("Criar") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        }
    )
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
    onClearSyncError: () -> Unit,
    onMigrateData: () -> Unit
) {
    var expandedRole by remember { mutableStateOf(false) }
    var expandedName by remember { mutableStateOf(false) }
    var showGranular by remember { mutableStateOf(false) }

    PremiumCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = if (profile.isPreRegistered) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surface
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header: User Info
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = (if (profile.isPreRegistered) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary).copy(alpha = 0.1f),
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        if (profile.isPreRegistered) Icons.Default.Portrait else Icons.Default.Person,
                        null,
                        modifier = Modifier.padding(8.dp),
                        tint = if (profile.isPreRegistered) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        profile.email ?: "Conta não vinculada",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        profile.agentName ?: "Sem nome de agente",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (profile.uid != null) {
                    Switch(checked = profile.isAuthorized, onCheckedChange = onAuthorize)
                } else {
                    TextButton(onClick = onDelete, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                        Icon(Icons.Default.Delete, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Excluir", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Badges & Status
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (profile.uid != null) {
                    Box {
                        AssistChip(
                            onClick = { expandedRole = true },
                            label = { Text(profile.role.name) },
                            leadingIcon = { Icon(Icons.Default.AdminPanelSettings, null, modifier = Modifier.size(16.dp)) }
                        )
                        DropdownMenu(expanded = expandedRole, onDismissRequest = { expandedRole = false }) {
                            UserRole.values().forEach { role ->
                                DropdownMenuItem(text = { Text(role.name) }, onClick = { onRoleChange(role); expandedRole = false })
                            }
                        }
                    }
                }
                
                if (profile.isPreRegistered) {
                    SuggestionChip(
                        onClick = { },
                        label = { Text("Pré-registrado") },
                        colors = SuggestionChipDefaults.suggestionChipColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                    )
                }

                if (profile.agentData?.lastSyncError != null) {
                    Badge(containerColor = MaterialTheme.colorScheme.error) {
                        Text("ERRO SYNC", color = Color.White, modifier = Modifier.padding(4.dp))
                    }
                    IconButton(onClick = onClearSyncError) {
                        Icon(Icons.Default.Refresh, "Limpar Erro", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Actions
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (profile.uid != null) {
                    Box {
                        OutlinedButton(
                            onClick = { expandedName = true },
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Icon(Icons.Default.DriveFileRenameOutline, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Vincular", style = MaterialTheme.typography.labelSmall)
                        }
                        DropdownMenu(expanded = expandedName, onDismissRequest = { expandedName = false }) {
                            agentNamesList.forEach { name ->
                                DropdownMenuItem(text = { Text(name) }, onClick = { onUpdateName(name); expandedName = false })
                            }
                            DropdownMenuItem(text = { Text("Limpar Vínculo") }, onClick = { onUpdateName(null); expandedName = false })
                        }
                    }
                }

                OutlinedButton(
                    onClick = onRestore,
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Icon(Icons.Default.SettingsBackupRestore, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Restaurar", style = MaterialTheme.typography.labelSmall)
                }

                if (profile.agentData != null) {
                    OutlinedButton(
                        onClick = onEditAgent,
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Icon(Icons.Default.BarChart, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Ver Dados", style = MaterialTheme.typography.labelSmall)
                    }

                    OutlinedButton(
                        onClick = { showGranular = !showGranular },
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Icon(if (showGranular) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (showGranular) "Ocultar" else "Detalhes", style = MaterialTheme.typography.labelSmall)
                    }
                }

                if (profile.uid != null && profile.agentData == null) {
                    Button(
                        onClick = onMigrateData,
                        modifier = Modifier.height(32.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.MoveToInbox, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Migrar", style = MaterialTheme.typography.labelSmall)
                    }
                }

                if (profile.uid != null) {
                    IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.DeleteForever, "Excluir", tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f), modifier = Modifier.size(20.dp))
                    }
                }
            }

            // Expanded Granular Data Section
            AnimatedVisibility(visible = showGranular && profile.agentData != null) {
                Spacer(modifier = Modifier.height(16.dp))
                profile.agentData?.let { data ->
                    GranularDataSection(
                        agentUid = profile.uid ?: data.uid,
                        houses = data.houses,
                        activities = data.activities,
                        onDeleteHouse = onDeleteHouse,
                        onDeleteActivity = onDeleteActivity,
                        onClearSyncError = onClearSyncError
                    )
                }
            }
        }
    }
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
                                TextButton(onClick = { onDeleteHouse(agentUid, house.cloudId ?: house.generateNaturalKey()) }) {
                                    Icon(Icons.Default.Delete, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                                    Spacer(Modifier.width(4.dp))
                                    Text("Excluir", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
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
                                TextButton(onClick = { onDeleteActivity(agentUid, activity.date) }) {
                                    Icon(Icons.Default.Delete, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                                    Spacer(Modifier.width(4.dp))
                                    Text("Excluir", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
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
