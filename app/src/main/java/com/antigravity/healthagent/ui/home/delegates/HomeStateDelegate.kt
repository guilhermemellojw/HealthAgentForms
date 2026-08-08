package com.antigravity.healthagent.ui.home.delegates

import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.ui.home.AuditSummary
import com.antigravity.healthagent.ui.home.HomeUiState
import com.antigravity.healthagent.domain.usecase.HouseValidationUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import com.antigravity.healthagent.utils.DateUtils
import java.util.Date
import javax.inject.Inject

class HomeStateDelegate @Inject constructor() : HomeState {
    private val dateFormatter get() = DateUtils.DASH_DATE.get()

    override val uiState = MutableStateFlow(HomeUiState())
    override val data = MutableStateFlow(dateFormatter.format(Date()))
    override val agentName = MutableStateFlow("")
    override val remoteAgent = MutableStateFlow<String?>(null)
    override val remoteAgentUid = MutableStateFlow<String?>(null)
    override val currentUserUid = MutableStateFlow<String?>(null)
    override val uiEvent = MutableStateFlow<String?>(null)
    override val pendingUpdateDrafts = MutableStateFlow<Map<Int, House>>(emptyMap())
    override val housesInFlight = MutableStateFlow<List<House>>(emptyList())
    override val recentlyEditedHouseIds = MutableStateFlow<Map<Int, Long>>(emptyMap())
    override val highlightedHouseId = MutableStateFlow<Int?>(null)
    override val showClosingAudit = MutableStateFlow<AuditSummary?>(null)
    override val showGoalReached = MutableStateFlow(false)
    override val showHistoryUnlockConfirmation = MutableStateFlow(false)
    override val validationErrorHouseIds = MutableStateFlow<Set<Int>>(emptySet())
    override val isDuplicateIds = MutableStateFlow<Set<Int>>(emptySet())
    override val integrityDialogMessage = MutableStateFlow<String?>(null)
    override val showMultiDayErrorDialog = MutableStateFlow(false)
    override val validationErrorDetails = MutableStateFlow<List<HouseValidationUseCase.ErrorDetail>>(emptyList())
    override val scrollToHouseId = MutableStateFlow<Int?>(null)
    override val situationLimitConfirmation = MutableStateFlow<House?>(null)
    override val moveConfirmationData = MutableStateFlow<Pair<House, String>?>(null)
    override val duplicateHouseConfirmation = MutableStateFlow<House?>(null)
    override val isSupervisor = MutableStateFlow(false)
    override val isAdmin = MutableStateFlow(false)
    override val isSyncing = MutableStateFlow(false)

    override val currentBlock = MutableStateFlow("")
    override val currentBlockSequence = MutableStateFlow("")
    override val currentStreet = MutableStateFlow("")

    override val municipio = MutableStateFlow("Bom Jardim")
    override val bairro = MutableStateFlow("")
    override val categoria = MutableStateFlow("BRR")
    override val zona = MutableStateFlow("URB")
    override val ciclo = MutableStateFlow("1º")
    override val tipo = MutableStateFlow(2)
    override val atividade = MutableStateFlow(4)
}
