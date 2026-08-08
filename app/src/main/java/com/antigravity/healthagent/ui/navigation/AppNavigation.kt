package com.antigravity.healthagent.ui.navigation

import androidx.compose.runtime.Composable
import com.antigravity.healthagent.domain.repository.AuthUser
import com.antigravity.healthagent.ui.home.HomeViewModel
import com.antigravity.healthagent.ui.semanal.WeeklySummaryViewModel

@Composable
fun AppNavigation(
    isSupervisor: Boolean,
    selectedTab: Int,
    isEasyMode: Boolean,
    user: AuthUser?,
    homeViewModel: HomeViewModel,
    weeklySummaryViewModel: WeeklySummaryViewModel,
    onLogout: () -> Unit,
    onSwitchAccount: () -> Unit,
    onOpenSettings: () -> Unit
) {
    if (isSupervisor) {
        when (selectedTab) {
            0 -> com.antigravity.healthagent.ui.supervisor.SupervisorSummaryScreen(
                user = user,
                onLogout = onLogout,
                onSwitchAccount = onSwitchAccount,
                onOpenSettings = onOpenSettings
            )
            1 -> com.antigravity.healthagent.ui.supervisor.SupervisorAgentsScreen(
                user = user,
                onLogout = onLogout,
                onSwitchAccount = onSwitchAccount,
                onOpenSettings = onOpenSettings
            )
        }
    } else {
        when (selectedTab) {
            0 -> com.antigravity.healthagent.ui.home.HomeScreen(
                viewModel = homeViewModel,
                user = user,
                onLogout = onLogout,
                onSwitchAccount = onSwitchAccount,
                onOpenSettings = onOpenSettings
            )
            1 -> com.antigravity.healthagent.ui.rg.RGScreen(
                user = user,
                onLogout = onLogout,
                onSwitchAccount = onSwitchAccount,
                onOpenSettings = onOpenSettings
            )
            2 -> com.antigravity.healthagent.ui.boletim.BoletimScreen(
                viewModel = homeViewModel,
                onOpenSettings = onOpenSettings,
                user = user,
                onLogout = onLogout,
                onSwitchAccount = onSwitchAccount
            )
            3 -> com.antigravity.healthagent.ui.semanal.SemanalScreen(
                viewModel = weeklySummaryViewModel,
                onNavigateToDate = { date ->
                    homeViewModel.navigateToDate(date)
                },
                user = user,
                onLogout = onLogout,
                onSwitchAccount = onSwitchAccount,
                onOpenSettings = onOpenSettings
            )
            4 -> com.antigravity.healthagent.ui.quarteiroes.QuarteiroesScreen(
                isEasyMode = isEasyMode,
                user = user,
                onLogout = onLogout,
                onSwitchAccount = onSwitchAccount,
                onOpenSettings = onOpenSettings
            )
        }
    }
}
