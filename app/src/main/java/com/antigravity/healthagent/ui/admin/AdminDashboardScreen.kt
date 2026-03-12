package com.antigravity.healthagent.ui.admin

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.antigravity.healthagent.domain.repository.AgentData
import com.antigravity.healthagent.domain.repository.UserRole
import com.antigravity.healthagent.utils.AppConstants
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminDashboardScreen(
    viewModel: AdminViewModel,
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val users by viewModel.users.collectAsState()
    val agentNames by viewModel.agentNames.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }
    var showAddUserDialog by remember { mutableStateOf(false) }
    var showAddAgentDialog by remember { mutableStateOf(false) }
    var showAddNameDialog by remember { mutableStateOf(false) }
    var selectedUidForRestore by remember { mutableStateOf<String?>(null) }
    
    // Confirmation Dialog State
    var showConfirmDialog by remember { mutableStateOf(false) }
    var confirmTitle by remember { mutableStateOf("") }
    var confirmMessage by remember { mutableStateOf("") }
    var onConfirmAction by remember { mutableStateOf<() -> Unit>({}) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val tabs = listOf("Dados", "Usuários", "Nomes")

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            val targetUid = selectedUidForRestore
            if (targetUid != null) {
                confirmTitle = "Confirmar Restauração"
                confirmMessage = "Esta ação enviará os dados do arquivo JSON para a nuvem deste agente, substituindo e mesclando conforme necessário. Deseja continuar?"
                onConfirmAction = {
                    scope.launch {
                        snackbarHostState.showSnackbar("Lendo arquivo e enviando para nuvem...")
                    }
                    viewModel.restoreAgentBackup(context, targetUid, it)
                }
                showConfirmDialog = true
                selectedUidForRestore = null
            }
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.uiEvent.collect { event ->
            snackbarHostState.showSnackbar(event)
        }
    }

    androidx.activity.compose.BackHandler {
        onNavigateBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Painel do Administrador", color = MaterialTheme.colorScheme.onPrimary) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Voltar", tint = MaterialTheme.colorScheme.onPrimary)
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refreshAll() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Atualizar", tint = MaterialTheme.colorScheme.onPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primary)
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    when (selectedTab) {
                        0 -> showAddAgentDialog = true
                        1 -> showAddUserDialog = true
                        2 -> showAddNameDialog = true
                    }
                },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ) {
                Icon(Icons.Default.Add, contentDescription = "Adicionar")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            TabRow(selectedTabIndex = selectedTab) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title) }
                    )
                }
            }

            Box(modifier = Modifier.weight(1f)) {
                if (selectedTab == 0) {
                    when (val state = uiState) {
                        is AdminUiState.Loading -> {
                            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                        }
                        is AdminUiState.Error -> {
                            Text(
                                text = "Erro: ${state.message}",
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .padding(16.dp)
                            )
                        }
                        is AdminUiState.Success -> {
                            if (state.agents.isEmpty()) {
                                Text(
                                    text = "Nenhum dado de agente encontrado na nuvem.",
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.align(Alignment.Center)
                                )
                            } else {
                                LazyColumn(
                                    contentPadding = PaddingValues(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(16.dp),
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    items(state.agents) { agent ->
                                        AgentCard(
                                            agent = agent,
                                            onEditAgent = {
                                                viewModel.selectAgentForEdit(it)
                                                onNavigateBack() // Go back to production view
                                            },
                                            onDeleteAgent = { uid -> 
                                                confirmTitle = "Excluir Agente"
                                                confirmMessage = "Tem certeza que deseja excluir os dados deste agente da nuvem? Esta ação não pode ser desfeita."
                                                onConfirmAction = { viewModel.deleteAgent(uid) }
                                                showConfirmDialog = true
                                            },
                                            onRestoreRequest = {
                                                selectedUidForRestore = it
                                                filePickerLauncher.launch("application/json")
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else if (selectedTab == 1) {
                    // User Management Tab
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(users) { user ->
                            UserCard(
                                user = user,
                                agentNamesList = agentNames,
                                onAuthorize = { viewModel.authorizeUser(user.uid, it) },
                                onRoleChange = { viewModel.changeUserRole(user.uid, it) },
                                onUpdateProfile = { updates -> viewModel.updateUserProfile(user.uid, updates) },
                                onDeleteUser = { uid ->
                                    confirmTitle = "Excluir Conta de Usuário"
                                    confirmMessage = "Tem certeza que deseja excluir esta conta de usuário e todos os seus metadados? O usuário perderá o acesso imediatamente."
                                    onConfirmAction = { viewModel.deleteUser(uid) }
                                    showConfirmDialog = true
                                }
                            )
                        }
                    }
                } else {
                    // Names Management Tab
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(agentNames) { name ->
                            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    IconButton(
                                        onClick = {
                                            confirmTitle = "Excluir Nome"
                                            confirmMessage = "Tem certeza que deseja remover '$name' da lista de agentes?"
                                            onConfirmAction = { viewModel.removeAgentName(name) }
                                            showConfirmDialog = true
                                        },
                                        colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                    ) {
                                        Icon(Icons.Default.Delete, contentDescription = "Excluir")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

    if (showAddUserDialog) {
        AddUserDialog(
            agentNamesList = agentNames,
            onDismiss = { showAddUserDialog = false },
            onConfirm = { email, role, agentName, isAuthorized ->
                viewModel.createUser(email, role, agentName, isAuthorized)
                showAddUserDialog = false
                scope.launch {
                    snackbarHostState.showSnackbar("Usuário $email pré-registrado com sucesso!")
                }
            }
        )
    }

    if (showAddAgentDialog) {
        AddAgentDialog(
            agentNamesList = agentNames,
            onDismiss = { showAddAgentDialog = false },
            onConfirm = { email, agentName ->
                viewModel.createAgent(email, agentName)
                showAddAgentDialog = false
                scope.launch {
                    snackbarHostState.showSnackbar("Perfil de agente para $email criado!")
                }
            }
        )
    }

    if (showAddNameDialog) {
        var newName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddNameDialog = false },
            title = { Text("Adicionar Nome de Agente") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Nome do Agente") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = { 
                    viewModel.addAgentName(newName)
                    showAddNameDialog = false 
                }, enabled = newName.isNotBlank()) {
                    Text("Adicionar")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddNameDialog = false }) { Text("Cancelar") }
            }
        )
    }

    if (showConfirmDialog) {
        GenericConfirmationDialog(
            title = confirmTitle,
            message = confirmMessage,
            onDismiss = { showConfirmDialog = false },
            onConfirm = {
                onConfirmAction()
                showConfirmDialog = false
            }
        )
    }
    }
}

@Composable
fun GenericConfirmationDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text("Confirmar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserCard(
    user: com.antigravity.healthagent.domain.repository.AuthUser,
    agentNamesList: List<String>,
    onAuthorize: (Boolean) -> Unit,
    onRoleChange: (com.antigravity.healthagent.domain.repository.UserRole) -> Unit,
    onUpdateProfile: (Map<String, Any?>) -> Unit,
    onDeleteUser: (String) -> Unit
) {
    var editName by remember(user.displayName) { mutableStateOf(user.displayName ?: "") }
    var editAgentName by remember(user.agentName) { mutableStateOf(user.agentName ?: "") }
    var isEditing by remember { mutableStateOf(false) }

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Person, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(user.email ?: "Sem Email", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    
                    if (isEditing) {
                        OutlinedTextField(
                            value = editName,
                            onValueChange = { editName = it },
                            label = { Text("Nome Completo") },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            singleLine = true
                        )
                        
                        var expanded by remember { mutableStateOf(false) }
                        ExposedDropdownMenuBox(
                            expanded = expanded,
                            onExpandedChange = { expanded = !expanded },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        ) {
                            OutlinedTextField(
                                value = editAgentName,
                                onValueChange = { },
                                readOnly = true,
                                label = { Text("Nome do Agente") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                                modifier = Modifier.menuAnchor().fillMaxWidth()
                            )
                            ExposedDropdownMenu(
                                expanded = expanded,
                                onDismissRequest = { expanded = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Nenhum") },
                                    onClick = {
                                        editAgentName = ""
                                        expanded = false
                                    }
                                )
                                agentNamesList.forEach { name ->
                                    DropdownMenuItem(
                                        text = { Text(name) },
                                        onClick = {
                                            editAgentName = name
                                            expanded = false
                                        }
                                    )
                                }
                            }
                        }
                        Row(
                            horizontalArrangement = Arrangement.End,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            TextButton(onClick = { 
                                isEditing = false
                                editName = user.displayName ?: ""
                                editAgentName = user.agentName ?: ""
                            }) {
                                Text("Cancelar")
                            }
                            Button(onClick = {
                                onUpdateProfile(mapOf(
                                    "displayName" to editName,
                                    "agentName" to editAgentName
                                ))
                                isEditing = false
                            }) {
                                Text("Salvar")
                            }
                        }
                    } else {
                        Text("Nome: ${user.displayName ?: "Não informado"}", style = MaterialTheme.typography.bodyMedium)
                        Text("Agente: ${user.agentName ?: "Não vinculado"}", style = MaterialTheme.typography.bodyMedium)
                        
                        TextButton(
                            onClick = { isEditing = true },
                            contentPadding = PaddingValues(0.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Editar Perfil", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    
                    Text("Papel: ${user.role.name}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                }
                
                Switch(
                    checked = user.isAuthorized,
                    onCheckedChange = onAuthorize
                )
                
                IconButton(
                    onClick = { onDeleteUser(user.uid) },
                    colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = "Excluir Usuário")
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                UserRoleChip(
                    label = "Agente",
                    selected = user.role == com.antigravity.healthagent.domain.repository.UserRole.AGENT,
                    onClick = { onRoleChange(com.antigravity.healthagent.domain.repository.UserRole.AGENT) }
                )
                UserRoleChip(
                    label = "Supervisor",
                    selected = user.role == com.antigravity.healthagent.domain.repository.UserRole.SUPERVISOR,
                    onClick = { onRoleChange(com.antigravity.healthagent.domain.repository.UserRole.SUPERVISOR) }
                )
                UserRoleChip(
                    label = "Admin",
                    selected = user.role == com.antigravity.healthagent.domain.repository.UserRole.ADMIN,
                    onClick = { onRoleChange(com.antigravity.healthagent.domain.repository.UserRole.ADMIN) }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserRoleChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) }
    )
}

@Composable
fun AgentCard(agent: AgentData, onEditAgent: (AgentData) -> Unit, onDeleteAgent: (String) -> Unit, onRestoreRequest: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }

    val formattedDate = remember(agent.lastSyncTime) {
        if (agent.lastSyncTime > 0) {
            SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(agent.lastSyncTime))
        } else {
            "Nunca"
        }
    }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = agent.agentName ?: agent.email.ifBlank { "Sem Email" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (agent.agentName != null && agent.email.isNotBlank()) {
                         Text(
                            text = agent.email,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = "Última Sincronização: $formattedDate",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = "Expandir"
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                ) {
                    HorizontalDivider(modifier = Modifier.padding(bottom = 8.dp))
                    
                    Text(
                        text = "Resumo Geral",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    
                    val totalHouses = agent.houses.size
                    val housesWithFocus = agent.houses.count { it.comFoco }
                    val totalActivities = agent.activities.size
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        DataStat(label = "Imóveis Visitados", value = "$totalHouses")
                        DataStat(label = "Com Foco", value = "$housesWithFocus", color = if (housesWithFocus > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                        DataStat(label = "Dias Trabalhados", value = "$totalActivities")
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = { onEditAgent(agent) },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                                contentColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("EDITAR")
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Button(
                            onClick = { onRestoreRequest(agent.uid) },
                            modifier = Modifier.weight(1.5f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("RESTAURAR JSON", style = MaterialTheme.typography.labelMedium)
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        IconButton(
                            onClick = { onDeleteAgent(agent.uid) },
                            colors = IconButtonDefaults.iconButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "Excluir")
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddUserDialog(
    agentNamesList: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (String, UserRole, String?, Boolean) -> Unit
) {
    var email by remember { mutableStateOf("") }
    var role by remember { mutableStateOf(UserRole.AGENT) }
    var agentName by remember { mutableStateOf("") }
    var isAuthorized by remember { mutableStateOf(true) }
    var expandedRole by remember { mutableStateOf(false) }
    var expandedAgent by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Convidar Usuário") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("E-mail") },
                    modifier = Modifier.fillMaxWidth()
                )
                
                ExposedDropdownMenuBox(
                    expanded = expandedRole,
                    onExpandedChange = { expandedRole = !expandedRole },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = role.name,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Papel") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedRole) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(expanded = expandedRole, onDismissRequest = { expandedRole = false }) {
                        UserRole.values().forEach { r ->
                            DropdownMenuItem(text = { Text(r.name) }, onClick = { role = r; expandedRole = false })
                        }
                    }
                }

                ExposedDropdownMenuBox(
                    expanded = expandedAgent,
                    onExpandedChange = { expandedAgent = !expandedAgent },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = agentName,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Vincular ao Agente") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedAgent) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(expanded = expandedAgent, onDismissRequest = { expandedAgent = false }) {
                        DropdownMenuItem(text = { Text("Nenhum") }, onClick = { agentName = ""; expandedAgent = false })
                        agentNamesList.forEach { name ->
                            DropdownMenuItem(text = { Text(name) }, onClick = { agentName = name; expandedAgent = false })
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = isAuthorized, onCheckedChange = { isAuthorized = it })
                    Text("Autorizado a Logar")
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(email, role, agentName.ifBlank { null }, isAuthorized) }, enabled = email.isNotBlank()) {
                Text("Confirmar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddAgentDialog(
    agentNamesList: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (String, String?) -> Unit
) {
    var email by remember { mutableStateOf("") }
    var agentName by remember { mutableStateOf("") }
    var expandedAgent by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Adicionar Perfil de Agente") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("E-mail do Agente") },
                    modifier = Modifier.fillMaxWidth()
                )
                
                ExposedDropdownMenuBox(
                    expanded = expandedAgent,
                    onExpandedChange = { expandedAgent = !expandedAgent },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = agentName,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Nome do Agente") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedAgent) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(expanded = expandedAgent, onDismissRequest = { expandedAgent = false }) {
                        agentNamesList.forEach { name ->
                            DropdownMenuItem(text = { Text(name) }, onClick = { agentName = name; expandedAgent = false })
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(email, agentName.ifBlank { null }) }, enabled = email.isNotBlank() && agentName.isNotBlank()) {
                Text("Adicionar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        }
    )
}

@Composable
fun DataStat(label: String, value: String, color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = color)
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
