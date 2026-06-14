// Admin Dashboard Screen - Unified View
package com.antigravity.healthagent.ui.admin

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
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
import androidx.compose.ui.graphics.Color
import com.antigravity.healthagent.ui.components.PremiumCard
import com.antigravity.healthagent.ui.components.GlassTopAppBar
import com.antigravity.healthagent.ui.components.MeshGradient
import com.antigravity.healthagent.ui.components.CustomSyncPullIndicator
import com.antigravity.healthagent.ui.components.SyncFloatingBalloon
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.*
import com.antigravity.healthagent.ui.admin.components.*

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
    val isSolarMode by viewModel.solarMode.collectAsState()
    val selectedYear by viewModel.selectedYear.collectAsState()
    val selectedMonth by viewModel.selectedMonth.collectAsState()

    var selectedTab by remember { mutableIntStateOf(0) }
    var showAddProfileDialog by remember { mutableStateOf(false) }
    var selectedUidForRestore by remember { mutableStateOf<String?>(null) }
    var isSmartRestore by remember { mutableStateOf(false) }
    
    var showConfirmDialog by remember { mutableStateOf(false) }
    var confirmTitle by remember { mutableStateOf("") }
    var confirmMessage by remember { mutableStateOf("") }
    var onConfirmAction by remember { mutableStateOf<() -> Unit>({}) }
    
    var showApprovalDialog by remember { mutableStateOf(false) }
    var pendingRequest by remember { mutableStateOf<AccessRequest?>(null) }

    var showDeleteUserDialog by remember { mutableStateOf(false) }
    var userToDelete by remember { mutableStateOf<UnifiedProfile?>(null) }
    var deleteCloudDataWithUser by remember { mutableStateOf(false) }

    var showTransferDataDialog by remember { mutableStateOf(false) }
    var transferSourceProfile by remember { mutableStateOf<UnifiedProfile?>(null) }

    var showWipeDialog by remember { mutableStateOf(false) }
    var userForWipe by remember { mutableStateOf<UnifiedProfile?>(null) }

    var showTimelineForUid by remember { mutableStateOf<String?>(null) }
    var showTimelineForName by remember { mutableStateOf("") }

    val snackbarHostState = remember { SnackbarHostState() }

    val context = LocalContext.current
    val tabs = listOf("Gestão", "Config.")

    val filePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            val targetUid = selectedUidForRestore
            if (targetUid != null) {
                confirmTitle = if (isSmartRestore) "Restaurar Dia" else "Confirmar Restauração"
                confirmMessage = if (isSmartRestore) 
                    "Deseja realizar a importação inteligente deste dia? Se houver conflito, ele será movido para o próximo dia disponível." 
                    else "Deseja restaurar todos os dados do backup?"
                
                onConfirmAction = { 
                    viewModel.restoreAgentBackup(context, targetUid, uri, autoShift = isSmartRestore)
                }
                showConfirmDialog = true
                selectedUidForRestore = null
            }
        }
    }

    LaunchedEffect(viewModel) { viewModel.uiEvent.collect { snackbarHostState.showSnackbar(it) } }
    androidx.activity.compose.BackHandler { onNavigateBack() }

    Scaffold(
        topBar = { GlassTopAppBar(
            title = { Text("Painel do Administrador", fontWeight = FontWeight.Black) }, 
            navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } }, 
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
                TabRow(
                    selectedTabIndex = selectedTab, 
                    containerColor = Color.Transparent,
                    contentColor = Color.White,
                    divider = { HorizontalDivider(color = Color.White.copy(alpha = 0.12f)) }
                ) {
                    tabs.forEachIndexed { index, title -> 
                        Tab(
                            selected = selectedTab == index, 
                            onClick = { selectedTab = index }, 
                            text = { 
                                Text(
                                    title,
                                    fontWeight = if (selectedTab == index) FontWeight.Black else FontWeight.Bold,
                                    style = MaterialTheme.typography.titleSmall
                                ) 
                            },
                            selectedContentColor = Color.White,
                            unselectedContentColor = Color.White.copy(alpha = 0.7f)
                        ) 
                    }
                }
                
                val syncState by viewModel.syncState.collectAsState()
                val isSolarMode by viewModel.solarMode.collectAsState()
                
                val pullToRefreshState = rememberPullToRefreshState()
                val isRefreshing = syncState is com.antigravity.healthagent.ui.state.SyncUiState.Syncing

                PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = { viewModel.refreshAll() },
                    state = pullToRefreshState,
                    indicator = {
                        CustomSyncPullIndicator(
                            state = pullToRefreshState,
                            isRefreshing = isRefreshing,
                            isSolarMode = isSolarMode,
                            syncStatus = syncState,
                            pullText = "Puxe para atualizar...",
                            releaseText = "Solte para atualizar!"
                        )
                    },
                    modifier = Modifier.weight(1f).fillMaxWidth()
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                    if (selectedTab == 0) {
                        // Gestão Tab (Combined Profiles, Requests, and Master List)
                        Column(modifier = Modifier.fillMaxSize()) {
                            var showMasterList by remember { mutableStateOf(false) }
                            
                            // 1. Access Requests (if any)
                            val accessRequests by viewModel.accessRequests.collectAsState()
                            if (accessRequests.isNotEmpty()) {
                                Text("Solicitações de Acesso", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp), color = Color.White)
                                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    items(accessRequests) { request ->
                                        AccessRequestCard(
                                            request = request,
                                            isSolarMode = isSolarMode,
                                            onApprove = { showApprovalDialog = true; pendingRequest = request },
                                            onReject = { viewModel.rejectAccess(request.id) }
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                            }

                            // 2. Filter and Search Section
                            PremiumCard(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                                isSolarMode = isSolarMode
                            ) {
                                Column(modifier = Modifier.padding(8.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.FilterList, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("FILTRAR PRODUÇÃO", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
                                    }
                                    
                                    Spacer(modifier = Modifier.height(12.dp))
                                    
                                    // Year Selection
                                    LazyRow(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        items(viewModel.availableYears) { year ->
                                            FilterChip(
                                                selected = selectedYear == year,
                                                onClick = { viewModel.updateYear(year) },
                                                label = { Text(year.toString(), fontWeight = FontWeight.Bold) },
                                                colors = FilterChipDefaults.filterChipColors(
                                                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                                )
                                            )
                                        }
                                    }
                                    
                                    Spacer(modifier = Modifier.height(8.dp))
                                    
                                    // Month Selection
                                    LazyRow(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        val filteredMonths = viewModel.getFilteredMonths()
                                        items(filteredMonths.size) { index ->
                                            val monthName = filteredMonths[index]
                                            val monthValue = index - 1 // -1 for "Ano Todo", 0-11 for months
                                            FilterChip(
                                                selected = selectedMonth == monthValue,
                                                onClick = { viewModel.updateMonth(monthValue) },
                                                label = { Text(monthName, fontWeight = FontWeight.Bold) },
                                                colors = FilterChipDefaults.filterChipColors(
                                                    selectedContainerColor = MaterialTheme.colorScheme.secondary,
                                                    selectedLabelColor = MaterialTheme.colorScheme.onSecondary
                                                )
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(12.dp))
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                                    Spacer(modifier = Modifier.height(12.dp))

                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                        OutlinedTextField(
                                            value = searchQuery,
                                            onValueChange = { viewModel.updateSearchQuery(it) },
                                            modifier = Modifier.weight(1f),
                                            placeholder = { Text("Pesquisar nome ou email...") },
                                            leadingIcon = { Icon(Icons.Default.Search, null) },
                                            shape = RoundedCornerShape(12.dp),
                                            textStyle = MaterialTheme.typography.bodyMedium,
                                            singleLine = true
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        IconButton(onClick = { showMasterList = !showMasterList }) {
                                            Icon(if (showMasterList) Icons.Default.Badge else Icons.AutoMirrored.Filled.ListAlt, null, tint = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                }
                            }
                            
                            // 3. Master List Section
                            AnimatedVisibility(visible = showMasterList) {
                                PremiumCard(
                                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                                    isSolarMode = isSolarMode
                                ) {
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
                            Box(modifier = Modifier.weight(1f)) {
                                Column(modifier = Modifier.fillMaxSize()) {
                                    if (isRefreshing) {
                                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                    }
                                    if (unifiedProfiles.isEmpty()) {
                                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                            Text("Nenhum usuário encontrado.", color = Color.White.copy(alpha = 0.75f))
                                        }
                                    } else {
                                        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
                                            items(unifiedProfiles, key = { it.uid ?: it.email ?: it.agentName ?: it.hashCode() }) { profile ->
                                                UnifiedProfileCard(
                                                    profile = profile,
                                                    agentNamesList = agentNames,
                                                    isSolarMode = isSolarMode,
                                                    onAuthorize = { authorized -> viewModel.authorizeUser(profile.uid ?: "", authorized) },
                                                    onRoleChange = { role -> viewModel.changeUserRole(profile.uid ?: "", role) },
                                                    onUpdateName = { name ->
                                                        profile.uid?.let { viewModel.updateUserProfile(it, mapOf("agentName" to name)) }
                                                    },
                                                    onDelete = {
                                                        userToDelete = profile
                                                        deleteCloudDataWithUser = false
                                                        showDeleteUserDialog = true
                                                    },
                                                    onRestore = { 
                                                        selectedUidForRestore = profile.uid ?: profile.agentData?.uid
                                                        isSmartRestore = false
                                                        filePickerLauncher.launch("application/json") 
                                                    },
                                                    onRestoreDay = { uid -> 
                                                        selectedUidForRestore = uid ?: profile.agentData?.uid
                                                        isSmartRestore = true
                                                        filePickerLauncher.launch("application/json")
                                                    },
                                                    onEditAgent = { viewModel.selectAgentForEdit(profile.agentData); onNavigateBack() },
                                                    onClearSyncError = { profile.uid?.let { viewModel.clearSyncError(it) } },
                                                    onMigrateData = {
                                                        val authUser = users.find { it.uid == profile.uid }
                                                        if (authUser != null) {
                                                            confirmTitle = "Migrar Dados"; confirmMessage = "Deseja migrar os dados de conta não vinculada para este perfil (${authUser.email})?"; onConfirmAction = { viewModel.migrateData(authUser) }; showConfirmDialog = true
                                                        }
                                                    },
                                                    onTransferData = {
                                                        transferSourceProfile = profile
                                                        showTransferDataDialog = true
                                                    },
                                                    onRemoteWipe = {
                                                        userForWipe = profile
                                                        showWipeDialog = true
                                                    },
                                                    onOpenTimeline = { uid, name ->
                                                        showTimelineForUid = uid
                                                        showTimelineForName = name
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

            if (showTimelineForUid != null) {
                AdminTimelineScreen(
                    agentUid = showTimelineForUid!!,
                    agentName = showTimelineForName,
                    viewModel = viewModel,
                    onNavigateBack = { showTimelineForUid = null }
                )
            }
        }
    }

    if (showAddProfileDialog) {
        AddProfileDialog(
            agentNamesList = agentNames,
            onDismiss = { showAddProfileDialog = false },
            onConfirm = { email: String?, name: String?, role: UserRole, authorized: Boolean ->
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

    if (showTransferDataDialog && transferSourceProfile != null) {
        val availableTargets = unifiedProfiles.filter { 
            it.uid != null && it.uid != transferSourceProfile?.uid && !it.isPreRegistered 
        }
        
        TransferDataDialog(
            sourceProfile = transferSourceProfile!!,
            availableTargets = availableTargets,
            onDismiss = { showTransferDataDialog = false; transferSourceProfile = null },
            onConfirm = { targetUid ->
                viewModel.transferData(transferSourceProfile?.uid ?: "", targetUid)
                showTransferDataDialog = false
                transferSourceProfile = null
            }
        )
    }

    if (showWipeDialog && userForWipe != null) {
        AlertDialog(
            onDismissRequest = { showWipeDialog = false },
            title = { Text("CONFIRMAR WIPE REMOTO", fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.error) },
            text = {
                Column {
                    Text("Esta ação irá APAGAR PERMANENTEMENTE toda a produção de:", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(userForWipe?.agentName ?: userForWipe?.email ?: "Usuário", fontWeight = FontWeight.Black)
                    Spacer(Modifier.height(16.dp))
                    Text("1. Os dados serão removidos da Nuvem imediatamente.", style = MaterialTheme.typography.labelSmall)
                    Text("2. O aplicativo no dispositivo do agente será resetado no próximo acesso.", style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.height(16.dp))
                    Text("O perfil de acesso (e-mail e senha) NÃO será excluído.", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        userForWipe?.uid?.let { viewModel.remoteWipeAgentData(it) }
                        showWipeDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("CONFIRMAR WIPE") }
            },
            dismissButton = {
                TextButton(onClick = { showWipeDialog = false }) { Text("Cancelar") }
            }
        )
    }
}
