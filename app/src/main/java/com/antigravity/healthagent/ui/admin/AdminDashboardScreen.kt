package com.antigravity.healthagent.ui.admin

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.antigravity.healthagent.domain.repository.AgentData
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
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Dados", "Usuários")

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
                                        AgentCard(agent = agent)
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // User Management Tab
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(users) { user ->
                            UserCard(
                                user = user,
                                onAuthorize = { viewModel.authorizeUser(user.uid, it) },
                                onRoleChange = { viewModel.changeUserRole(user.uid, it) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun UserCard(
    user: com.antigravity.healthagent.domain.repository.AuthUser,
    onAuthorize: (Boolean) -> Unit,
    onRoleChange: (com.antigravity.healthagent.domain.repository.UserRole) -> Unit
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Person, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(user.email ?: "Sem Email", fontWeight = FontWeight.Bold)
                    Text("Papel: ${user.role.name}", style = MaterialTheme.typography.bodySmall)
                }
                
                Switch(
                    checked = user.isAuthorized,
                    onCheckedChange = onAuthorize
                )
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
fun AgentCard(agent: AgentData) {
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
                        text = agent.email.ifBlank { "Sem Email" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
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
                }
            }
        }
    }
}

@Composable
fun DataStat(label: String, value: String, color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = color)
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
