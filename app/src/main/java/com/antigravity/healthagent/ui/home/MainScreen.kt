package com.antigravity.healthagent.ui.home

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import com.antigravity.healthagent.domain.repository.UserRole
import com.antigravity.healthagent.ui.auth.AuthState
import com.antigravity.healthagent.ui.auth.LoginViewModel
import com.antigravity.healthagent.ui.components.*
import com.antigravity.healthagent.ui.semanal.SemanalScreen
import com.antigravity.healthagent.ui.quarteiroes.QuarteiroesScreen
import com.antigravity.healthagent.ui.supervisor.*
import com.antigravity.healthagent.ui.admin.*
import com.antigravity.healthagent.ui.settings.SettingsScreen
import com.antigravity.healthagent.ui.boletim.BoletimScreen

@Composable
fun MainScreen(loginViewModel: LoginViewModel, homeViewModel: com.antigravity.healthagent.ui.home.HomeViewModel) {
    val authState by loginViewModel.authState.collectAsState()
    val user = (authState as? AuthState.Authenticated)?.user
    val isAdmin = user?.role == com.antigravity.healthagent.domain.repository.UserRole.ADMIN
    val isSupervisor = user?.role == com.antigravity.healthagent.domain.repository.UserRole.SUPERVISOR

    // Shared ViewModels for consistency across tabs/screens
    val adminViewModel: com.antigravity.healthagent.ui.admin.AdminViewModel = androidx.hilt.navigation.compose.hiltViewModel()
    val weeklySummaryViewModel: com.antigravity.healthagent.ui.semanal.WeeklySummaryViewModel = androidx.hilt.navigation.compose.hiltViewModel()

    var selectedTab by remember(isSupervisor) { 
        mutableIntStateOf(if (isSupervisor) 0 else 2) 
    }
    var showSettings by remember { mutableStateOf(false) }
    var showExitDialog by remember { mutableStateOf(false) }
    var showAdmin by remember { mutableStateOf(false) }

    if (showAdmin) {
        com.antigravity.healthagent.ui.admin.AdminDashboardScreen(
            viewModel = adminViewModel,
            onNavigateBack = { showAdmin = false },
            user = user,
            onLogout = { loginViewModel.signOut() },
            onSwitchAccount = { loginViewModel.signOut() },
            onOpenSettings = { 
                showAdmin = false
                showSettings = true
            }
        )
    } else if (showSettings) {
        com.antigravity.healthagent.ui.settings.SettingsScreen(
            onNavigateBack = { showSettings = false },
            onOpenAdmin = {
                showSettings = false
                showAdmin = true
            },
            isAdmin = isAdmin,
            isSupervisor = isSupervisor,
            user = user,
            onLogout = { loginViewModel.signOut() },
            onSwitchAccount = { loginViewModel.signOut() }
        )
    } else {
        val context = LocalContext.current
        val activity = context as? Activity
        // Use the shared homeViewModel passed from MainActivity
        
        val selectedRemoteAgent by adminViewModel.selectedAgentForEdit.collectAsState()

        // Sync local context with remote agent selection
        LaunchedEffect(selectedRemoteAgent) {
            homeViewModel.setRemoteAgent(selectedRemoteAgent)
            if (selectedRemoteAgent != null) {
                // When an agent is selected for edit, pull THEIR data from cloud
                homeViewModel.pullDataFromCloud(selectedRemoteAgent?.uid)
            }
        }
    
        // Auto-restore logic: Pull once per session after login is now handled by LoginViewModel.
        // We keep the effect to potentially handle other side effects of 'user' state changes if needed.
        LaunchedEffect(user) {
            // Logic moved to LoginViewModel to ensure it only happens once and at the right time.
        }

        // Propagate role to HomeViewModel
        LaunchedEffect(isAdmin, isSupervisor) {
            homeViewModel.setAdmin(isAdmin)
            homeViewModel.setSupervisor(isSupervisor)
        }

        val daysWithErrors by homeViewModel.daysWithErrors.collectAsState()
        val navigationTab by homeViewModel.navigationTab.collectAsState()
        val isEasyMode by homeViewModel.easyMode.collectAsState()
        
        LaunchedEffect(navigationTab) {
            navigationTab?.let {
                selectedTab = it
                homeViewModel.clearNavigationTab()
            }
        }

        BackHandler {
            if (isSupervisor) {
                if (selectedTab != 0) selectedTab = 0
                else showExitDialog = true
                return@BackHandler
            }

            if (daysWithErrors.isNotEmpty()) {
                selectedTab = 0
                homeViewModel.showMultiDayErrorDialog()
                return@BackHandler
            }
            
            if (!homeViewModel.validateCurrentDay(showDialog = false)) {
                 selectedTab = 0
                 homeViewModel.validateCurrentDay(showDialog = true)
                 return@BackHandler
            }

            if (selectedTab != 2) {
                selectedTab = 2
            } else {
                showExitDialog = true
            }
        }

        if (showExitDialog) {
            AppExitDialog(
                onConfirm = { activity?.finish() },
                onDismiss = { showExitDialog = false },
                isEasyMode = isEasyMode
            )
        }

        Scaffold(
            topBar = {
                if (!isSupervisor && selectedRemoteAgent != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        tonalElevation = 8.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .padding(horizontal = 16.dp, vertical = 6.dp)
                                .fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    Icons.Default.Edit, 
                                    contentDescription = null, 
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = "MODO DE EDIÇÃO",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Black
                                    )
                                    Text(
                                        text = selectedRemoteAgent?.email ?: "",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            FilledTonalButton(
                                onClick = { 
                                    homeViewModel.finishEditSession {
                                        adminViewModel.selectAgentForEdit(null)
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 12.dp),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Text("FINALIZAR", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            },
            bottomBar = {
                com.antigravity.healthagent.ui.components.GlassBottomNavigationBar(
                    isSupervisor = isSupervisor,
                    selectedTab = selectedTab,
                    onTabSelected = { index ->
                        if (isSupervisor) {
                            selectedTab = index
                        } else {
                            if (index == 0 && selectedTab != 0) {
                                homeViewModel.goToLastWorkDay()
                            }
                            if (index == 3 && selectedTab == 3) {
                                weeklySummaryViewModel.goToCurrentWeek()
                            }
                            
                            // Always allow navigation to reference tools (Semanal and Mapa)
                            if (index == 3 || index == 4) {
                                selectedTab = index
                            } else if (daysWithErrors.isNotEmpty()) {
                                if (index != 0) {
                                    homeViewModel.showMultiDayErrorDialog()
                                    selectedTab = 0
                                } else {
                                    selectedTab = 0
                                }
                            } else {
                                if (index == 0 || homeViewModel.validateCurrentDay(showDialog = false)) {
                                    selectedTab = index
                                } else {
                                    homeViewModel.validateCurrentDay(showDialog = true)
                                    if (selectedTab != 0) selectedTab = 0
                                }
                            }
                        }
                    }
                )
            }
        ) { paddingValues ->
            Box(modifier = Modifier.padding(paddingValues)) {
                com.antigravity.healthagent.ui.navigation.AppNavigation(
                    isSupervisor = isSupervisor,
                    selectedTab = selectedTab,
                    isEasyMode = isEasyMode,
                    user = user,
                    homeViewModel = homeViewModel,
                    weeklySummaryViewModel = weeklySummaryViewModel,
                    onLogout = { loginViewModel.signOut() },
                    onSwitchAccount = { loginViewModel.signOut() },
                    onOpenSettings = { showSettings = true }
                )
                
                // Unified Sync Status Feedback for all screens
                val uiState by homeViewModel.uiState.collectAsState()
                
                SyncFloatingBalloon(
                    syncStatus = uiState.syncStatus,
                    isEasyMode = uiState.isEasyMode,
                    isSolarMode = uiState.isSolarMode,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .zIndex(3000f)
                )

                SyncStatusOverlay(
                    syncStatus = uiState.syncStatus,
                    isEasyMode = uiState.isEasyMode,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .zIndex(4000f)
                )
            }
        }
    }
}
