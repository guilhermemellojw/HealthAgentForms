package com.antigravity.healthagent.ui.home

import com.antigravity.healthagent.data.local.model.House



enum class SyncStage { IDLE, STARTING, UPLOADING, DOWNLOADING, SUCCESS, ERROR }

data class SyncStatus(
    val stage: SyncStage = SyncStage.IDLE,
    val progress: Float = 0f,
    val message: String? = null
)

data class HomeUiState(
    val houses: List<HouseUiState> = emptyList(),
    val dashboardTotals: DashboardTotals = DashboardTotals(),
    val searchQuery: String = "",
    val data: String = "",
    val isDayClosed: Boolean = false,
    val isSupervisor: Boolean = false,
    val agentName: String = "",
    val municipality: String = "BOM JARDIM",
    val neighborhood: String = "",
    val zone: String = "URB",
    val category: String = "BRR",
    val cycle: String = "1º",
    val type: Int = 2,
    val activity: Int = 4,
    val isLoading: Boolean = false,
    val error: String? = null,
    val pendingCount: Int = 0,
    val strictPendingCount: Int = 0,
    val validationErrorHouseIds: Set<Int> = emptySet(),
    val isAppModeSelected: Boolean? = null,
    val rgBlocks: List<BlockSegment> = emptyList(),
    val weeklySummary: List<DaySummary> = emptyList(),
    val weeklySummaryTotals: WeeklySummaryTotals = WeeklySummaryTotals(),
    val boletimList: List<BoletimSummary> = emptyList(),
    val bairrosList: List<String> = emptyList(),
    val currentBlock: String = "",
    val currentBlockSequence: String = "",
    val currentStreet: String = "",
    val selectedRgBairro: String = "",
    val selectedRgBlock: String = "",
    val rgFilteredList: List<House> = emptyList(),
    val rgYear: String = "",
    val availableYears: List<String> = emptyList(),
    val activityOptions: List<String> = listOf("NORMAL"),
    val weekRangeText: String = "",
    val customActivities: Set<String> = emptySet(),
    val rgBairros: List<String> = emptyList(),
    val isEasyMode: Boolean = false,
    val isSolarMode: Boolean = false,
    val maxOpenHouses: Int = 25,
    val syncStatus: SyncStatus = SyncStatus()
)
