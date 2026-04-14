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
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antigravity.healthagent.domain.repository.UserRole
import com.antigravity.healthagent.domain.repository.AuthUser
import com.antigravity.healthagent.domain.repository.AccessRequest
import java.text.SimpleDateFormat
import java.util.*
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.text.style.TextAlign

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
fun SettingsSectionHeader(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black, color = Color.White)
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
                
                val pullToRefreshState = rememberPullToRefreshState()
                val isRefreshing = uiState is AdminUiState.Loading

                PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = { viewModel.refreshAll() },
                    state = pullToRefreshState,
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
                                            items(unifiedProfiles) { profile ->
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
}

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
            Button(onClick = { 
                onConfirm(
                    email.takeIf { it.isNotBlank() }, 
                    nameInput.takeIf { it.isNotBlank() }, 
                    role, 
                    authorized
                ) 
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
    onTransferData: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var expandedRole by remember { mutableStateOf(false) }
    var showEditNameDialog by remember { mutableStateOf(false) }
    
    val agent = profile.agentData
    val lastSync = remember(agent?.lastSyncTime) {
        if (agent != null && agent.lastSyncTime > 0) {
            SimpleDateFormat("dd/MM/yy HH:mm", Locale.getDefault()).format(Date(agent.lastSyncTime))
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
                com.antigravity.healthagent.ui.components.UserAvatar(
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
                            val focusCount = agent.summary?.focusCount?.toString() ?: agent.houses.count { it.comFoco }.toString()
                            val treatedCount = agent.summary?.treatedCount?.toString() ?: agent.houses.count { it.a1 > 0 || it.a2 > 0 || it.b > 0 || it.c > 0 || it.d1 > 0 || it.d2 > 0 || it.e > 0 }.toString()
                            
                            AgentStatItem(label = "IMÓVEIS", value = housesCount, modifier = Modifier.weight(1f))
                            AgentStatItem(label = "TRATADOS", value = treatedCount, modifier = Modifier.weight(1f))
                            AgentStatItem(label = "DIAS", value = daysCount, modifier = Modifier.weight(1f))
                            AgentStatItem(
                                label = "FOCOS", 
                                value = focusCount,
                                color = if ((agent.summary?.focusCount ?: 0) > 0 || agent.houses.any { it.comFoco }) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
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
                            val sdf = SimpleDateFormat("dd-MM-yyyy", Locale.US)
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
                        ActionButton(Icons.Default.SettingsBackupRestore, "Restauração", onClick = onRestore)
                        ActionButton(Icons.Default.PostAdd, "Importar Dia", onClick = { onRestoreDay(profile.uid) })
                        
                        if (agent != null) {
                            ActionButton(Icons.Default.QueryStats, "Analisar", onClick = onEditAgent)
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
                                    val filteredNames = agentNamesList.filter { 
                                        it.contains(nameInput, ignoreCase = true) 
                                    }.take(5)

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
private fun QuickStat(icon: androidx.compose.ui.graphics.vector.ImageVector, count: Int, label: String) {
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
private fun ActionButton(
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
