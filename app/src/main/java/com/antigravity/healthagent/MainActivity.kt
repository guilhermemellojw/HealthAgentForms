package com.antigravity.healthagent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.tooling.preview.Preview
import com.antigravity.healthagent.ui.theme.HealthAgentFormsTheme
import com.antigravity.healthagent.ui.home.DayErrorSummary
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.Scaffold
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Group
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import dagger.hilt.android.AndroidEntryPoint
import com.antigravity.healthagent.ui.semanal.SemanalScreen
import com.antigravity.healthagent.ui.quarteiroes.QuarteiroesScreen
import androidx.compose.material.icons.filled.LocationOn
import androidx.activity.compose.BackHandler
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.mutableLongStateOf
import android.widget.Toast
import android.app.Activity
import androidx.compose.runtime.LaunchedEffect
import com.antigravity.healthagent.ui.components.AppExitDialog
import com.antigravity.healthagent.utils.SoundManager
import com.antigravity.healthagent.ui.auth.LoginScreen
import com.antigravity.healthagent.ui.auth.LoginViewModel
import com.antigravity.healthagent.ui.auth.AuthState
import androidx.hilt.navigation.compose.hiltViewModel
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    
    @Inject
    lateinit var soundManager: SoundManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val viewModel: com.antigravity.healthagent.ui.home.HomeViewModel = hiltViewModel()
            val loginViewModel: LoginViewModel = hiltViewModel()
            
            val themeMode by viewModel.themeMode.collectAsState()
            val themeColor by viewModel.themeColor.collectAsState()
            val solarMode by viewModel.solarMode.collectAsState()
            
            val authState by loginViewModel.authState.collectAsState()

            val tMode = themeMode
            val tColor = themeColor

            if (tMode != null && tColor != null && authState !is AuthState.Loading) {
                HealthAgentFormsTheme(
                    themeMode = tMode,
                    themeColor = tColor,
                    solarMode = solarMode
                ) {
                    when (authState) {
                        is AuthState.Authenticated -> {
                            MainScreen(loginViewModel)
                        }
                        else -> {
                            LoginScreen(
                                viewModel = loginViewModel,
                                onLoginSuccess = { /* Automatically handled by state */ }
                            )
                        }
                    }
                }
            } else {
                Surface(modifier = Modifier.fillMaxSize()) {
                    // Loading State (Blank Screen to prevent flash)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        soundManager.release()
    }
}

@Composable
fun MainScreen(loginViewModel: LoginViewModel) {
    var selectedTab by remember { mutableIntStateOf(2) }
    var showSettings by remember { mutableStateOf(false) }
    var showExitDialog by remember { mutableStateOf(false) }
    var showAdmin by remember { mutableStateOf(false) }

    val authState by loginViewModel.authState.collectAsState()
    val user = (authState as? AuthState.Authenticated)?.user
    val isAdmin = user?.role == com.antigravity.healthagent.domain.repository.UserRole.ADMIN
    val isSupervisor = user?.role == com.antigravity.healthagent.domain.repository.UserRole.SUPERVISOR

    if (showAdmin) {
        val adminViewModel: com.antigravity.healthagent.ui.admin.AdminViewModel = androidx.hilt.navigation.compose.hiltViewModel()
        com.antigravity.healthagent.ui.admin.AdminDashboardScreen(
            viewModel = adminViewModel,
            onNavigateBack = { showAdmin = false }
        )
    } else if (showSettings) {
        com.antigravity.healthagent.ui.settings.SettingsScreen(
            onNavigateBack = { showSettings = false },
            onOpenAdmin = {
                showSettings = false
                showAdmin = true
            },
            isAdmin = isAdmin,
            isSupervisor = isSupervisor
        )
    } else {
        val context = LocalContext.current
        val activity = context as? Activity
        var backPressedTime by remember { mutableLongStateOf(0L) }

        val homeViewModel: com.antigravity.healthagent.ui.home.HomeViewModel = androidx.hilt.navigation.compose.hiltViewModel()
        val adminViewModel: com.antigravity.healthagent.ui.admin.AdminViewModel = androidx.hilt.navigation.compose.hiltViewModel()
        
        val selectedRemoteAgent by adminViewModel.selectedAgentForEdit.collectAsState()

        // Sync local context with remote agent selection
        LaunchedEffect(selectedRemoteAgent) {
            homeViewModel.setRemoteAgent(selectedRemoteAgent?.email)
            if (selectedRemoteAgent != null) {
                // When an agent is selected for edit, pull THEIR data from cloud
                homeViewModel.syncDataToCloud() // This now pulls and then pushes
            }
        }

        // Auto-restore logic: Pull data once per session after authentication
        LaunchedEffect(user) {
            user?.let {
                if (selectedRemoteAgent == null) {
                    homeViewModel.syncDataToCloud()
                }
            }
        }

        // Propagate role to HomeViewModel
        LaunchedEffect(isSupervisor) {
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

        val isAppModeSelected by homeViewModel.isAppModeSelected.collectAsState()
        if (isAppModeSelected == false && !isSupervisor) {
            com.antigravity.healthagent.ui.components.AppModeSelectionDialog(
                onModeSelected = { isEasy ->
                    homeViewModel.selectAppMode(isEasy)
                }
            )
        }

        Scaffold(
            topBar = {
                if (!isSupervisor && selectedRemoteAgent != null) {
                    androidx.compose.material3.Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = androidx.compose.ui.Modifier.fillMaxWidth()
                    ) {
                        androidx.compose.foundation.layout.Row(
                            modifier = androidx.compose.ui.Modifier
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                .fillMaxWidth(),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween
                        ) {
                            androidx.compose.foundation.layout.Row(
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                modifier = androidx.compose.ui.Modifier.weight(1f)
                            ) {
                                Icon(
                                    Icons.Default.Info, 
                                    contentDescription = null, 
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = androidx.compose.ui.Modifier.size(20.dp)
                                )
                                androidx.compose.foundation.layout.Spacer(modifier = androidx.compose.ui.Modifier.width(8.dp))
                                Text(
                                    text = "Editando: ${selectedRemoteAgent?.email}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                            }
                            TextButton(
                                onClick = { 
                                    adminViewModel.selectAgentForEdit(null)
                                    // Reset local name to admin's email
                                    user?.email?.let { homeViewModel.setRemoteAgent(null) }
                                },
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                            ) {
                                Text("SAIR", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            },
            bottomBar = {
                com.antigravity.healthagent.ui.components.GlassNavigationBar {
                    val navItemColors = androidx.compose.material3.NavigationBarItemDefaults.colors(
                        indicatorColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.2f),
                        selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                        selectedTextColor = MaterialTheme.colorScheme.onPrimary,
                        unselectedIconColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f),
                        unselectedTextColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                    )

                    if (isSupervisor) {
                        NavigationBarItem(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            icon = { Icon(Icons.Default.Dashboard, contentDescription = "Resumo") },
                            label = { Text("Resumo") },
                            colors = navItemColors
                        )
                        NavigationBarItem(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            icon = { Icon(Icons.Default.Group, contentDescription = "Agentes") },
                            label = { Text("Agentes") },
                            colors = navItemColors
                        )
                    } else {
                        val onTabSelected: (Int) -> Unit = { index ->
                            if (daysWithErrors.isNotEmpty()) {
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

                        NavigationBarItem(
                            selected = selectedTab == 0,
                            onClick = { 
                                homeViewModel.goToLastWorkDay()
                                onTabSelected(0) 
                            },
                            icon = { Icon(Icons.Default.EditNote, contentDescription = "Produção") },
                            label = { Text("Produção") },
                            colors = navItemColors
                        )
                        NavigationBarItem(
                            selected = selectedTab == 1,
                            onClick = { onTabSelected(1) },
                            icon = { Icon(Icons.Default.LibraryBooks, contentDescription = "RG") },
                            label = { Text("RG") },
                            colors = navItemColors
                        )
                        NavigationBarItem(
                            selected = selectedTab == 2,
                            onClick = { onTabSelected(2) },
                            icon = { Icon(Icons.Default.Assignment, contentDescription = "Boletim") },
                            label = { Text("Boletim") },
                            colors = navItemColors
                        )
                        NavigationBarItem(
                            selected = selectedTab == 3,
                            onClick = { 
                                if (selectedTab == 3) {
                                    homeViewModel.goToCurrentWeek()
                                }
                                onTabSelected(3)
                            },
                            icon = { Icon(Icons.Default.DateRange, contentDescription = "Semanal") },
                            label = { Text("Semanal") },
                            colors = navItemColors
                        )
                        NavigationBarItem(
                            selected = selectedTab == 4,
                            onClick = { onTabSelected(4) },
                            icon = { Icon(Icons.Default.LocationOn, contentDescription = "Quarteirões") },
                            label = { Text("Mapa") },
                            colors = navItemColors
                        )
                    }
                }
            }
        ) { paddingValues ->
            Box(modifier = Modifier.padding(paddingValues)) {
                if (isSupervisor) {
                    when (selectedTab) {
                        0 -> com.antigravity.healthagent.ui.supervisor.SupervisorSummaryScreen()
                        1 -> com.antigravity.healthagent.ui.supervisor.SupervisorAgentsScreen()
                    }
                } else {
                    when (selectedTab) {
                        0 -> com.antigravity.healthagent.ui.home.HomeScreen()
                        1 -> com.antigravity.healthagent.ui.rg.RGScreen()
                        2 -> com.antigravity.healthagent.ui.boletim.BoletimScreen(
                            onOpenSettings = { showSettings = true }
                        )
                        3 -> SemanalScreen()
                        4 -> QuarteiroesScreen(isEasyMode = isEasyMode)
                    }
                }
            }
        }
    }
}

@Preview
@Composable
fun HomePreview(){
    HealthAgentFormsTheme {
        // MainScreen() // Cannot easily preview without ViewModels
    }
}