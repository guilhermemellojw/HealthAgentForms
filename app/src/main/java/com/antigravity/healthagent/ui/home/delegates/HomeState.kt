package com.antigravity.healthagent.ui.home.delegates

import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.ui.home.AuditSummary
import com.antigravity.healthagent.domain.usecase.HouseValidationUseCase
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Interface defining the mutable and readable states of the Home UI layer,
 * allowing decoupled functional delegates to read and write state properties safely.
 */
interface HomeState {
    val data: MutableStateFlow<String>
    val agentName: MutableStateFlow<String>
    val remoteAgent: MutableStateFlow<String?>
    val remoteAgentUid: MutableStateFlow<String?>
    val currentUserUid: MutableStateFlow<String?>
    val uiEvent: MutableStateFlow<String?>
    val pendingUpdateDrafts: MutableStateFlow<Map<Int, House>>
    val housesInFlight: MutableStateFlow<List<House>>
    val recentlyEditedHouseIds: MutableStateFlow<Map<Int, Long>>
    val highlightedHouseId: MutableStateFlow<Int?>
    val showClosingAudit: MutableStateFlow<AuditSummary?>
    val showGoalReached: MutableStateFlow<Boolean>
    val showHistoryUnlockConfirmation: MutableStateFlow<Boolean>
    val validationErrorHouseIds: MutableStateFlow<Set<Int>>
    val isDuplicateIds: MutableStateFlow<Set<Int>>
    val integrityDialogMessage: MutableStateFlow<String?>
    val showMultiDayErrorDialog: MutableStateFlow<Boolean>
    val validationErrorDetails: MutableStateFlow<List<HouseValidationUseCase.ErrorDetail>>
    val scrollToHouseId: MutableStateFlow<Int?>
    val situationLimitConfirmation: MutableStateFlow<House?>
    val moveConfirmationData: MutableStateFlow<Pair<House, String>?>
    val duplicateHouseConfirmation: MutableStateFlow<House?>
    val isSupervisor: MutableStateFlow<Boolean>
    val isAdmin: MutableStateFlow<Boolean>
    val isSyncing: MutableStateFlow<Boolean>

    val currentBlock: MutableStateFlow<String>
    val currentBlockSequence: MutableStateFlow<String>
    val currentStreet: MutableStateFlow<String>
    val uiState: MutableStateFlow<com.antigravity.healthagent.ui.home.HomeUiState>

    val municipio: MutableStateFlow<String>
    val bairro: MutableStateFlow<String>
    val categoria: MutableStateFlow<String>
    val zona: MutableStateFlow<String>
    val ciclo: MutableStateFlow<String>
    val tipo: MutableStateFlow<Int>
    val atividade: MutableStateFlow<Int>
}
