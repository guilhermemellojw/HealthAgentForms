package com.antigravity.healthagent.ui.home.delegates

import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.domain.repository.HouseRepository
import com.antigravity.healthagent.domain.usecase.AddNewHouseUseCase
import com.antigravity.healthagent.domain.usecase.UpdateHouseUseCase
import com.antigravity.healthagent.domain.usecase.SaveHouseUseCase
import com.antigravity.healthagent.domain.usecase.RecalculateVisitSegmentsUseCase
import com.antigravity.healthagent.domain.usecase.ClashDetector
import com.antigravity.healthagent.domain.usecase.DayLockEnforcer
import com.antigravity.healthagent.domain.usecase.RoleEnforcer
import com.antigravity.healthagent.domain.usecase.CheckWorkedHouseLimitUseCase
import com.antigravity.healthagent.utils.SoundManager
import com.antigravity.healthagent.domain.logger.AppLogger
import com.antigravity.healthagent.utils.formatStreetName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HouseEditDelegate @Inject constructor(
    private val repository: HouseRepository,
    private val saveHouseUseCase: SaveHouseUseCase,
    private val addNewHouseUseCase: AddNewHouseUseCase,
    private val updateHouseUseCase: UpdateHouseUseCase,
    private val recalculateVisitSegmentsUseCase: RecalculateVisitSegmentsUseCase,
    private val clashDetector: ClashDetector,
    private val dayLockEnforcer: DayLockEnforcer,
    private val roleEnforcer: RoleEnforcer,
    private val checkWorkedHouseLimitUseCase: CheckWorkedHouseLimitUseCase,
    private val soundManager: SoundManager
) {
    private var isAddingHouse = false
    private var lastAddClickTime = 0L

    private val clashDialogJobs = ConcurrentHashMap<Int, Job>()
    private val houseUpdateJobs = ConcurrentHashMap<Int, Job>()
    private var recentlyDeletedHouse: House? = null

    // ──────────────────────────────────────────────────────────────
    // ADD HOUSE AT SPECIFIC POSITION (Insert between existing rows)
    // ──────────────────────────────────────────────────────────────
    fun addNewHouseAt(
        scope: CoroutineScope,
        state: HomeState,
        afterId: Int,
        latestHousesList: List<House>,
        isDayClosed: Boolean
    ) {
        val currentTime = System.currentTimeMillis()
        if (isAddingHouse || currentTime - lastAddClickTime < 100) return
        isAddingHouse = true
        lastAddClickTime = currentTime

        if (state.agentName.value.isBlank()) {
            state.uiEvent.value = "Aguardando carregamento do perfil..."
            isAddingHouse = false
            return
        }

        scope.launch {
            try {
                val params = buildAddParams(state, latestHousesList, isDayClosed, afterId = afterId)
                val result = addNewHouseUseCase.execute(params)
                handleAddResult(result, scope, state)
            } catch (e: Exception) {
                AppLogger.e("HomeViewModel", "Error adding house at position", e)
                state.uiEvent.value = "Erro ao inserir: ${e.message}"
                soundManager.playWarning()
            } finally {
                isAddingHouse = false
            }
        }
    }

    // ──────────────────────────────────────────────────────────────
    // ADD HOUSE (Append to end of the day's list)
    // ──────────────────────────────────────────────────────────────
    fun addNewHouse(
        scope: CoroutineScope,
        state: HomeState,
        latestHousesList: List<House>,
        maxOpenHouses: Int,
        isDayClosed: Boolean,
        validateCurrentDay: (Boolean) -> Boolean,
        triggerDelayedValidation: () -> Unit,
        onHouseClick: (Int) -> Unit
    ) {
        val currentTime = System.currentTimeMillis()
        if (isAddingHouse || currentTime - lastAddClickTime < 100) return
        isAddingHouse = true
        lastAddClickTime = currentTime

        if (state.agentName.value.isBlank()) {
            state.uiEvent.value = "Aguardando carregamento do perfil..."
            isAddingHouse = false
            return
        }

        val myUid = state.currentUserUid.value
        val activeRemoteUid = state.remoteAgentUid.value

        if (myUid == null && activeRemoteUid == null) {
            AppLogger.e("HomeViewModel", "ADD HOUSE FAILED: No UID available.")
            state.uiEvent.value = "Erro: Identidade não carregada. Aguarde ou faça re-login."
            isAddingHouse = false
            return
        }

        // Pre-flight clash check (UI-level feedback before hitting the UseCase)
        val clashingDraftIds = state.isDuplicateIds.value
        if (clashingDraftIds.isNotEmpty()) {
            state.uiEvent.value = "Resolva os conflitos (em vermelho) antes de adicionar um novo imóvel."
            soundManager.playWarning()
            onHouseClick(clashingDraftIds.first())
            isAddingHouse = false
            return
        }

        val currentAgentUid = activeRemoteUid ?: myUid!!

        // Build in-flight houses for fully up-to-date state
        val currentInFlights = state.housesInFlight.value
        val fullyUpToDateHouses = (latestHousesList.filter { it.id != 0 } + currentInFlights).distinctBy {
            if (it.id != 0) it.id.toString() else "in_flight_${it.listOrder}"
        }

        // Propagate context from last global house if day is empty
        val isDayEmpty = fullyUpToDateHouses.none { it.data == state.data.value }
        if (isDayEmpty) {
            val lastGlobalHouse = fullyUpToDateHouses.maxByOrNull { it.listOrder }
            if (lastGlobalHouse != null) {
                state.currentBlock.value = lastGlobalHouse.address.blockNumber
                state.currentStreet.value = lastGlobalHouse.address.streetName
                state.currentBlockSequence.value = lastGlobalHouse.address.blockSequence
                state.bairro.value = lastGlobalHouse.address.bairro
                state.municipio.value = lastGlobalHouse.context.municipio
                state.agentName.value = lastGlobalHouse.agentName
            }
        }

        val params = buildAddParams(state, fullyUpToDateHouses, isDayClosed, afterId = -2, maxOpenHouses = maxOpenHouses)

        // Generate the house optimistically (for in-flight display)
        val houseToInsert = addNewHouseUseCase.generateHouseToInsert(params)

        markAsRecentlyEdited(scope, state, 0)
        state.housesInFlight.update { it + houseToInsert }

        // RELEASE THE UI SEMAPHORE LOCK IMMEDIATELY
        isAddingHouse = false

        scope.launch {
            try {
                val result = addNewHouseUseCase.execute(params, preparedHouse = houseToInsert)

                when (result) {
                    is AddNewHouseUseCase.Result.Success -> {
                        val newId = result.newId

                        // Reconcile in-flight state with actual DB state
                        val dbHousesAfter = repository.getHousesByDateAndAgent(state.data.value, currentAgentUid)
                        val currentDrafts = state.pendingUpdateDrafts.value
                        val currentInFlightsNow = state.housesInFlight.value
                        val refreshedLatestHouses = (dbHousesAfter.map { currentDrafts[it.id] ?: it } + currentInFlightsNow)

                        val finalInFlightState = state.housesInFlight.value.find {
                            it.listOrder == houseToInsert.listOrder && it.data == houseToInsert.data
                        }

                        if (finalInFlightState != null &&
                            (finalInFlightState.address.number != houseToInsert.address.number ||
                             finalInFlightState.address.sequence != houseToInsert.address.sequence ||
                             finalInFlightState.address.complement != houseToInsert.address.complement)) {
                            saveHouseUseCase.updateHouse(
                                finalInFlightState.copy(id = newId.toInt()),
                                refreshedLatestHouses,
                                params.isAdmin
                            )
                        }

                        soundManager.playPop()
                        markAsRecentlyEdited(scope, state, newId.toInt())
                        triggerDelayedValidation()
                    }
                    is AddNewHouseUseCase.Result.Blocked -> {
                        state.uiEvent.value = result.message
                        soundManager.playWarning()
                        removeInFlight(state, houseToInsert)
                    }
                    is AddNewHouseUseCase.Result.LimitReached -> {
                        soundManager.playWarning()
                        state.situationLimitConfirmation.value = result.houseTemplate
                        removeInFlight(state, houseToInsert)
                    }
                    is AddNewHouseUseCase.Result.Error -> {
                        AppLogger.e("HomeViewModel", "Error adding new house", result.exception)
                        state.uiEvent.value = "Erro ao adicionar imóvel: ${result.exception.message}"
                        soundManager.playWarning()
                        removeInFlight(state, houseToInsert)
                    }
                }
            } catch (e: Exception) {
                AppLogger.e("HomeViewModel", "Error adding new house", e)
                state.uiEvent.value = "Erro ao adicionar imóvel: ${e.message}"
                soundManager.playWarning()
                removeInFlight(state, houseToInsert)
            }
        }
    }

    // ──────────────────────────────────────────────────────────────
    // UPDATE HOUSE
    // ──────────────────────────────────────────────────────────────
    fun updateHouse(
        scope: CoroutineScope,
        state: HomeState,
        house: House,
        latestHousesList: List<House>,
        maxOpenHouses: Int,
        isDayClosed: Boolean,
        triggerDelayedValidation: () -> Unit
    ) {
        if (roleEnforcer.enforce(state.isSupervisor.value, state.isAdmin.value) is RoleEnforcer.RoleResult.Blocked) {
            return
        }

        val lockResult = dayLockEnforcer.enforce(
            isDayClosed = isDayClosed,
            isManualUnlock = state.uiState.value.isManualUnlock,
            isAdmin = state.isAdmin.value
        )
        if (lockResult is DayLockEnforcer.LockResult.Blocked && house.id != 0) {
            return
        }

        val original = latestHousesList.find { it.id == house.id }
        if (original?.editedByAdmin == true && !state.isAdmin.value && !state.uiState.value.isManualUnlock) {
            state.uiEvent.value = "Este imóvel foi homologado por um administrador. Desbloqueie o dia para editá-lo."
            soundManager.playWarning()
            return
        }

        val limitResult = checkWorkedHouseLimitUseCase(
            house = house,
            original = original,
            dayHouses = latestHousesList,
            currentDate = state.data.value,
            maxOpenHouses = maxOpenHouses,
            isAdmin = state.isAdmin.value,
            isManualUnlock = state.uiState.value.isManualUnlock
        )
        if (limitResult is CheckWorkedHouseLimitUseCase.Result.LimitExceeded) {
            soundManager.playWarning()
            state.situationLimitConfirmation.value = house
            return
        }

        if (house.id == 0) {
            state.housesInFlight.update { list ->
                list.map {
                    if (it.listOrder == house.listOrder) house else it
                }
            }
            return
        }

        val clashingHouse = clashDetector.findClash(house, latestHousesList)

        val updatedHouse = house.copy(lastUpdated = System.currentTimeMillis())
        state.pendingUpdateDrafts.update { it + (updatedHouse.id to updatedHouse) }

        if (clashingHouse != null) {
            AppLogger.w("HomeViewModel", "Clash detected for house ${updatedHouse.id} with ${clashingHouse.id}. Skipping DB update.")
            clashDialogJobs[house.id]?.cancel()
            clashDialogJobs.remove(house.id)
        } else {
            clashDialogJobs[house.id]?.cancel()
            clashDialogJobs.remove(house.id)

            performUpdateHouseWithDebounce(scope, state, updatedHouse, original)
        }

        markAsRecentlyEdited(scope, state, updatedHouse.id)
        triggerDelayedValidation()
    }

    private fun performUpdateHouseWithDebounce(
        scope: CoroutineScope,
        state: HomeState,
        house: House,
        baselineHouse: House? = null
    ) {
        houseUpdateJobs[house.id]?.cancel()

        houseUpdateJobs[house.id] = scope.launch {
            try {
                val isHeavy = baselineHouse != null && (
                    baselineHouse.address.streetName != house.address.streetName ||
                    baselineHouse.address.blockNumber != house.address.blockNumber ||
                    baselineHouse.address.bairro != house.address.bairro
                )

                delay(if (isHeavy) 800L else 300L)

                performUpdateHouse(scope, state, house, baselineHouse)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Normal cancellation, do nothing
            } catch (e: Exception) {
                AppLogger.e("HomeViewModel", "Error in debounced update", e)
            }
        }
    }

    fun confirmDuplicateMerge(scope: CoroutineScope, state: HomeState) {
        state.duplicateHouseConfirmation.value?.let { house ->
            clashDialogJobs[house.id]?.cancel()
            clashDialogJobs.remove(house.id)
            // Let InitializationDelegate's prune observer remove the draft re-actively
            // once Room emits the updated values.
            performUpdateHouse(scope, state, house, forceMerge = true)
            state.duplicateHouseConfirmation.value = null
        }
    }

    fun dismissDuplicateConfirmation(state: HomeState, validateCurrentDay: (Boolean) -> Boolean) {
        state.duplicateHouseConfirmation.value?.let { house ->
            clashDialogJobs[house.id]?.cancel()
            clashDialogJobs.remove(house.id)
            state.duplicateHouseConfirmation.value = null
            validateCurrentDay(false)
        }
    }

    private fun performUpdateHouse(
        scope: CoroutineScope,
        state: HomeState,
        house: House,
        baselineHouse: House? = null,
        forceMerge: Boolean = false
    ) {
        scope.launch {
            try {
                val currentName = state.agentName.value
                val currentUid = state.remoteAgentUid.value ?: state.currentUserUid.value

                // Guard: Do not overwrite coworker/teammate identities during updates
                val houseWithIdentity = if (house.agentUid.isBlank() || house.agentUid == currentUid) {
                    house.copy(agentName = currentName, agentUid = currentUid ?: "")
                } else {
                    house
                }

                val dbHouses = repository.getHousesByDateAndAgent(state.data.value, currentUid ?: "")
                val drafts = state.pendingUpdateDrafts.value
                val inFlights = state.housesInFlight.value
                val latestHouses = (dbHouses.map { drafts[it.id] ?: it } + inFlights)

                val adminBypass = state.isAdmin.value
                val shouldForce = adminBypass || forceMerge
                val result = saveHouseUseCase.updateHouseWithContext(houseWithIdentity, latestHouses, baselineHouse)

                if (result.localizationChanged) {
                    state.bairro.value = result.updatedHouse.address.bairro
                    state.currentBlock.value = result.updatedHouse.address.blockNumber
                    state.currentBlockSequence.value = result.updatedHouse.address.blockSequence
                    state.currentStreet.value = result.updatedHouse.address.streetName

                    val subsequentWithIdentity = result.subsequentHouses.map {
                        if (it.agentUid.isBlank() || it.agentUid == currentUid) {
                            it.copy(agentName = currentName, agentUid = currentUid ?: "")
                        } else {
                            it
                        }
                    }
                    saveHouseUseCase.updateHouses(subsequentWithIdentity + result.updatedHouse, shouldForce)
                } else {
                    saveHouseUseCase.updateHouse(result.updatedHouse, latestHouses, shouldForce)
                }

                if (state.validationErrorHouseIds.value.contains(house.id)) {
                    state.validationErrorHouseIds.value = state.validationErrorHouseIds.value - house.id
                }

                // Let InitializationDelegate's prune observer remove the draft re-actively 
                // once Room emits the updated values.
            } catch (e: Exception) {
                AppLogger.e("HomeViewModel", "Error updating house", e)
                state.uiEvent.value = "Falha ao atualizar imóvel: ${e.message}"
                soundManager.playWarning()
            }
        }
    }

    // ──────────────────────────────────────────────────────────────
    // DELETE HOUSE
    // ──────────────────────────────────────────────────────────────
    fun deleteHouse(scope: CoroutineScope, state: HomeState, house: House, latestHousesList: List<House>, isDayClosed: Boolean) {
        val roleResult = roleEnforcer.enforce(state.isSupervisor.value, state.isAdmin.value, "excluir dados remotamente")
        if (roleResult is RoleEnforcer.RoleResult.Blocked) {
            state.uiEvent.value = roleResult.message
            soundManager.playWarning()
            return
        }

        val isAdmin = state.isAdmin.value
        if (house.editedByAdmin && !isAdmin && !state.uiState.value.isManualUnlock) {
            state.uiEvent.value = "Este imóvel foi homologado por um administrador. Desbloqueie o dia para exclui-lo."
            soundManager.playWarning()
            return
        }

        val lockResult = dayLockEnforcer.enforce(
            isDayClosed = isDayClosed,
            isManualUnlock = state.uiState.value.isManualUnlock,
            isAdmin = isAdmin,
            actionDescription = "deletar"
        )
        if (lockResult is DayLockEnforcer.LockResult.Blocked) {
            state.uiEvent.value = lockResult.message
            soundManager.playWarning()
            return
        }

        scope.launch {
            try {
                recentlyDeletedHouse = house
                state.pendingUpdateDrafts.update { it - house.id }
                state.housesInFlight.update { list ->
                    list.filter { it.listOrder != house.listOrder || it.data != house.data }
                }

                if (house.id == 0) return@launch

                saveHouseUseCase.deleteHouse(house, latestHousesList, isAdmin)
                soundManager.playPop()
            } catch (e: Exception) {
                AppLogger.e("HomeViewModel", "Error deleting house", e)
                state.uiEvent.value = "Erro ao excluir: ${e.message}"
                soundManager.playWarning()
            }
        }
    }

    // ──────────────────────────────────────────────────────────────
    // RESTORE DELETED HOUSE
    // ──────────────────────────────────────────────────────────────
    fun restoreDeletedHouse(scope: CoroutineScope, state: HomeState, latestHousesList: List<House>, isDayClosed: Boolean) {
        val lockResult = dayLockEnforcer.enforce(
            isDayClosed = isDayClosed,
            isManualUnlock = state.uiState.value.isManualUnlock,
            isAdmin = state.isAdmin.value,
            actionDescription = "restaurar"
        )
        if (lockResult is DayLockEnforcer.LockResult.Blocked) {
            state.uiEvent.value = lockResult.message
            soundManager.playWarning()
            return
        }

        recentlyDeletedHouse?.let { house ->
            scope.launch {
                val clashing = clashDetector.findClash(house, latestHousesList)

                if (clashing != null) {
                    state.pendingUpdateDrafts.update { it + (house.id to house.copy(id = house.id)) }
                    state.uiEvent.value = "Imóvel restaurado com conflito detectado."
                } else {
                    val adminBypass = state.isAdmin.value
                    saveHouseUseCase.insertHouse(house.copy(id = 0), latestHousesList, adminBypass)
                }

                recentlyDeletedHouse = null
                soundManager.playPop()
            }
        }
    }

    // ──────────────────────────────────────────────────────────────
    // REORDER / MOVE HOUSE
    // ──────────────────────────────────────────────────────────────
    fun persistListOrder(scope: CoroutineScope, state: HomeState, reorderedList: List<House>, triggerDelayedValidation: (Long) -> Unit) {
        scope.launch {
            val adminBypass = state.isAdmin.value
            val updatedList = reorderedList.mapIndexed { index, h -> h.copy(listOrder = index.toLong()) }
            val recalculated = recalculateVisitSegmentsUseCase.recalculateVisitSegments(updatedList)
            saveHouseUseCase.updateHouses(recalculated, adminBypass)
            triggerDelayedValidation(500)
        }
    }

    fun moveHouse(scope: CoroutineScope, state: HomeState, house: House, moveUp: Boolean, latestHousesList: List<House>, triggerDelayedValidation: (Long) -> Unit) {
        if (roleEnforcer.enforce(state.isSupervisor.value, state.isAdmin.value) is RoleEnforcer.RoleResult.Blocked) {
            return
        }

        val lockResult = dayLockEnforcer.enforce(
            isDayClosed = state.uiState.value.isDayClosed,
            isManualUnlock = state.uiState.value.isManualUnlock,
            isAdmin = state.isAdmin.value
        )
        if (lockResult is DayLockEnforcer.LockResult.Blocked && house.id != 0) {
            return
        }

        scope.launch {
            val currentDayHouses = latestHousesList.filter { it.data == state.data.value }
            val index = currentDayHouses.indexOfFirst { it.id == house.id }

            if (index != -1) {
                val list = currentDayHouses.toMutableList()
                if (moveUp && index > 0) {
                    Collections.swap(list, index, index - 1)
                } else if (!moveUp && index < list.size - 1) {
                    Collections.swap(list, index, index + 1)
                }
                persistListOrder(scope, state, list, triggerDelayedValidation)
            }
        }
    }

    // ──────────────────────────────────────────────────────────────
    // PRIVATE HELPERS
    // ──────────────────────────────────────────────────────────────
    private fun buildAddParams(
        state: HomeState,
        latestHousesList: List<House>,
        isDayClosed: Boolean,
        afterId: Int,
        maxOpenHouses: Int = 0
    ): AddNewHouseUseCase.Params {
        val currentAgentUid = state.remoteAgentUid.value ?: state.currentUserUid.value ?: ""
        return AddNewHouseUseCase.Params(
            agentName = state.agentName.value,
            agentUid = currentAgentUid,
            currentDate = state.data.value,
            blockNumber = state.currentBlock.value,
            streetName = state.currentStreet.value,
            blockSequence = state.currentBlockSequence.value,
            bairro = state.bairro.value,
            municipio = state.municipio.value,
            categoria = state.categoria.value,
            zona = state.zona.value,
            tipo = state.tipo.value,
            ciclo = state.ciclo.value,
            atividade = state.atividade.value,
            afterId = afterId,
            latestHousesList = latestHousesList,
            isDayClosed = isDayClosed,
            isManualUnlock = state.uiState.value.isManualUnlock,
            isAdmin = state.isAdmin.value,
            isSupervisor = state.isSupervisor.value,
            maxOpenHouses = maxOpenHouses
        )
    }

    private fun handleAddResult(result: AddNewHouseUseCase.Result, scope: CoroutineScope, state: HomeState) {
        when (result) {
            is AddNewHouseUseCase.Result.Success -> {
                state.highlightedHouseId.value = result.newId.toInt()
                scope.launch {
                    delay(2000)
                    if (state.highlightedHouseId.value == result.newId.toInt()) {
                        state.highlightedHouseId.value = null
                    }
                }
                soundManager.playPop()
            }
            is AddNewHouseUseCase.Result.Blocked -> {
                state.uiEvent.value = result.message
                soundManager.playWarning()
            }
            is AddNewHouseUseCase.Result.LimitReached -> {
                soundManager.playWarning()
                state.situationLimitConfirmation.value = result.houseTemplate
            }
            is AddNewHouseUseCase.Result.Error -> {
                AppLogger.e("HomeViewModel", "Error adding house", result.exception)
                state.uiEvent.value = "Erro ao inserir: ${result.exception.message}"
                soundManager.playWarning()
            }
        }
    }

    private fun removeInFlight(state: HomeState, house: House) {
        state.housesInFlight.update { list ->
            list.filter { it.listOrder != house.listOrder || it.data != house.data }
        }
    }

    private fun markAsRecentlyEdited(scope: CoroutineScope, state: HomeState, houseId: Int) {
        scope.launch {
            val now = System.currentTimeMillis()
            state.recentlyEditedHouseIds.update { it + (houseId to now) }
            delay(4000)
            state.recentlyEditedHouseIds.update { current ->
                if (current[houseId] == now) current - houseId else current
            }
        }
    }
}
