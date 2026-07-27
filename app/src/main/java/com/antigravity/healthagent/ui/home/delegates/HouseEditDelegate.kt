package com.antigravity.healthagent.ui.home.delegates

import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.domain.repository.HouseRepository
import com.antigravity.healthagent.domain.usecase.AddNewHouseUseCase
import com.antigravity.healthagent.domain.usecase.SaveHouseUseCase
import com.antigravity.healthagent.domain.usecase.RecalculateVisitSegmentsUseCase
import com.antigravity.healthagent.domain.usecase.ClashDetector
import com.antigravity.healthagent.domain.usecase.DayLockEnforcer
import com.antigravity.healthagent.domain.usecase.RoleEnforcer
import com.antigravity.healthagent.domain.usecase.CheckWorkedHouseLimitUseCase
import com.antigravity.healthagent.domain.util.Clock
import com.antigravity.healthagent.utils.SoundManager
import com.antigravity.healthagent.domain.logger.AppLogger
import com.antigravity.healthagent.utils.formatStreetName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HouseEditDelegate @Inject constructor(
    private val repository: HouseRepository,
    private val saveHouseUseCase: SaveHouseUseCase,
    private val addNewHouseUseCase: AddNewHouseUseCase,
    private val recalculateVisitSegmentsUseCase: RecalculateVisitSegmentsUseCase,
    private val clashDetector: ClashDetector,
    private val dayLockEnforcer: DayLockEnforcer,
    private val roleEnforcer: RoleEnforcer,
    private val checkWorkedHouseLimitUseCase: CheckWorkedHouseLimitUseCase,
    private val soundManager: SoundManager,
    private val clock: Clock
) {
    private var isAddingHouse = false
    private var lastAddClickTime = 0L

    private val writeMutex = Mutex()
    private val addHouseMutex = Mutex()

    private val clashDialogJobs = ConcurrentHashMap<Int, Job>()
    private val houseUpdateJobs = ConcurrentHashMap<Int, Job>()
    private val houseWriteJobs = ConcurrentHashMap<Int, Job>()
    private var recentlyDeletedHouse: House? = null

    fun cancelAll() {
        clashDialogJobs.forEach { (_, job) -> job.cancel() }
        houseUpdateJobs.forEach { (_, job) -> job.cancel() }
        houseWriteJobs.forEach { (_, job) -> job.cancel() }
        clashDialogJobs.clear()
        houseUpdateJobs.clear()
        houseWriteJobs.clear()
        recentlyDeletedHouse = null
        isAddingHouse = false
    }

    // ──────────────────────────────────────────────────────────────
    // ADD HOUSE AT SPECIFIC POSITION (Insert between existing rows)
    // ──────────────────────────────────────────────────────────────
    fun addNewHouseAt(
        scope: CoroutineScope,
        state: HomeState,
        afterId: Int,
        latestHousesList: List<House>
    ) {
        val currentTime = clock.currentTimeMillis()
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
                val params = buildAddParams(state, latestHousesList, afterId = afterId)
                val result = addHouseMutex.withLock {
                    addNewHouseUseCase.execute(params)
                }
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
        validateCurrentDay: (Boolean) -> Boolean,
        triggerDelayedValidation: () -> Unit,
        onHouseClick: (Int) -> Unit
    ) {
        val currentTime = clock.currentTimeMillis()
        if (isAddingHouse || currentTime - lastAddClickTime < 300) return
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

        val params = buildAddParams(state, fullyUpToDateHouses, afterId = -2)

        // Generate the house optimistically (for in-flight display)
        val houseToInsert = addNewHouseUseCase.generateHouseToInsert(params)

        markAsRecentlyEdited(scope, state, 0)
        state.housesInFlight.update { it + houseToInsert }

        scope.launch {
            try {
                val result = addHouseMutex.withLock {
                    addNewHouseUseCase.execute(params, preparedHouse = houseToInsert)
                }

                when (result) {
                    is AddNewHouseUseCase.Result.Success -> {
                        val newId = result.newId

                        val dbHousesAfter = writeMutex.withLock {
                            repository.getHousesByDateAndAgent(state.data.value, currentAgentUid)
                        }
                        val currentDrafts = state.pendingUpdateDrafts.value
                        val currentInFlightsNow = state.housesInFlight.value
                        val refreshedLatestHouses = (dbHousesAfter.map { currentDrafts[it.id] ?: it } + currentInFlightsNow)

                        val finalInFlightState = state.housesInFlight.value.find {
                            it.listOrder == houseToInsert.listOrder && it.data == houseToInsert.data
                        }

                        if (finalInFlightState != null && finalInFlightState != houseToInsert) {
                            saveHouseUseCase.updateHouse(
                                finalInFlightState.copy(id = newId.toInt()),
                                refreshedLatestHouses,
                                params.isAdmin
                            )
                        }

                        removeInFlight(state, houseToInsert)

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
            } finally {
                isAddingHouse = false
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
        triggerDelayedValidation: () -> Unit
    ) {
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
            maxOpenHouses = state.uiState.value.maxOpenHouses,
            isAdmin = state.isAdmin.value,
            isManualUnlock = state.uiState.value.isManualUnlock
        )
        if (limitResult is CheckWorkedHouseLimitUseCase.Result.LimitExceeded) {
            soundManager.playWarning()
            state.uiEvent.value = "Limite de ${state.uiState.value.maxOpenHouses} imóveis abertos atingido. Feche o dia ou desbloqueie para editar."
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

        val sanitizedHouse = saveHouseUseCase.sanitizeHouse(house)
        val updatedHouse = sanitizedHouse.copy(lastUpdated = clock.currentTimeMillis())
        state.pendingUpdateDrafts.update { it + (updatedHouse.id to updatedHouse) }

        AppLogger.d("PERSIST_DEBUG", "DRAFT_SET: house=${updatedHouse.id} pt=${updatedHouse.propertyType.code} lastUpdated=${updatedHouse.lastUpdated}")

        if (clashingHouse != null) {
            AppLogger.w("PERSIST_DEBUG", "CLASH: house=${updatedHouse.id} n=${updatedHouse.address.number} s=${updatedHouse.address.sequence} c=${updatedHouse.address.complement} pt=${updatedHouse.propertyType.code} clashes with house=${clashingHouse.id} n=${clashingHouse.address.number} s=${clashingHouse.address.sequence} c=${clashingHouse.address.complement}")
        }

        clashDialogJobs[house.id]?.cancel()
        clashDialogJobs.remove(house.id)

        AppLogger.d("PERSIST_DEBUG", "SCHEDULE: house=${updatedHouse.id} n=${updatedHouse.address.number} s=${updatedHouse.address.sequence} c=${updatedHouse.address.complement} pt=${updatedHouse.propertyType.code}")
        performUpdateHouseWithDebounce(scope, state, updatedHouse, original)

        markAsRecentlyEdited(scope, state, updatedHouse.id)
        triggerDelayedValidation()
    }

    fun updateHouseField(
        scope: CoroutineScope,
        state: HomeState,
        houseId: Int,
        latestHousesList: List<House>,
        triggerDelayedValidation: () -> Unit,
        update: (House) -> House
    ) {
        val currentLatest = latestHousesList.find { it.id == houseId } ?: return
        val draft = state.pendingUpdateDrafts.value[houseId]
        val latestBase = draft ?: currentLatest
        
        val newHouse = update(latestBase)
        updateHouse(scope, state, newHouse, latestHousesList, triggerDelayedValidation)
    }

    private fun performUpdateHouseWithDebounce(
        scope: CoroutineScope,
        state: HomeState,
        house: House,
        baselineHouse: House? = null
    ) {
        houseUpdateJobs[house.id]?.cancel()
        houseWriteJobs[house.id]?.cancel()

        houseUpdateJobs[house.id] = scope.launch {
            try {
                val isHeavy = baselineHouse != null && (
                    baselineHouse.address.streetName != house.address.streetName ||
                    baselineHouse.address.blockNumber != house.address.blockNumber ||
                    baselineHouse.address.bairro != house.address.bairro
                )

                delay(if (isHeavy) 800L else 300L)

                AppLogger.d("PERSIST_DEBUG", "DEBOUNCE_FIRE: house=${house.id} n=${house.address.number} s=${house.address.sequence} c=${house.address.complement}")
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
        houseWriteJobs[house.id]?.cancel()
        houseWriteJobs[house.id] = scope.launch {
            try {
                writeMutex.withLock {
                    AppLogger.d("PERSIST_DEBUG", "WRITE_START: house=${house.id} n=${house.address.number} s=${house.address.sequence} c=${house.address.complement} pt=${house.propertyType.code}")
                    val currentName = state.agentName.value
                    val currentUid = state.remoteAgentUid.value ?: state.currentUserUid.value

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

                    val dbTimestamp = clock.currentTimeMillis()

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

                    state.pendingUpdateDrafts.update { drafts ->
                        val existing = drafts[house.id]
                        if (existing != null) {
                            drafts + (house.id to existing.copy(lastUpdated = dbTimestamp))
                        } else {
                            drafts
                        }
                    }

                    AppLogger.d("PERSIST_DEBUG", "WRITE_OK: house=${house.id} n=${house.address.number} s=${house.address.sequence} c=${house.address.complement} pt=${house.propertyType.code} dbTs=${dbTimestamp}")
                } // writeMutex.withLock end
            } catch (e: kotlinx.coroutines.CancellationException) {
                AppLogger.w("PERSIST_DEBUG", "WRITE_CANCELLED: house=${house.id} n=${house.address.number} s=${house.address.sequence} c=${house.address.complement}")
                throw e
            } catch (e: Exception) {
                AppLogger.e("PERSIST_DEBUG", "WRITE_FAIL: house=${house.id} n=${house.address.number} s=${house.address.sequence} c=${house.address.complement} error=${e.message}")
                state.uiEvent.value = "Falha ao atualizar imóvel: ${e.message}"
                state.pendingUpdateDrafts.update { it - house.id }
                soundManager.playWarning()
            }
        }
    }

    // ──────────────────────────────────────────────────────────────
    // DELETE HOUSE
    // ──────────────────────────────────────────────────────────────
    fun deleteHouse(scope: CoroutineScope, state: HomeState, house: House, latestHousesList: List<House>) {
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
            isDayClosed = state.uiState.value.isDayClosed,
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

                writeMutex.withLock {
                    saveHouseUseCase.deleteHouse(house, latestHousesList, isAdmin)
                }
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
    fun restoreDeletedHouse(scope: CoroutineScope, state: HomeState, latestHousesList: List<House>) {
        val lockResult = dayLockEnforcer.enforce(
            isDayClosed = state.uiState.value.isDayClosed,
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
            
            // Re-read DB for the affected day before writing to avoid
            // overwriting concurrent edits to other fields (e.g., propertyType).
            val currentDate = state.data.value
            val currentUid = state.remoteAgentUid.value ?: state.currentUserUid.value ?: ""
            val dbHousesForDay = if (currentUid.isNotBlank()) {
                repository.getHousesByDateAndAgent(currentDate, currentUid)
                    .filter { it.data == currentDate }
                    .associateBy { it.id }
            } else emptyMap<Int, House>()
            
            // Merge reordered houses with fresh DB state
            val merged = recalculated.map { h ->
                dbHousesForDay[h.id]?.copy(
                    listOrder = h.listOrder,
                    visitSegment = h.visitSegment,
                    isSynced = false,
                    lastUpdated = clock.currentTimeMillis()
                ) ?: h.copy(isSynced = false, lastUpdated = clock.currentTimeMillis())
            }
            
            saveHouseUseCase.updateHouses(merged, adminBypass)
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
        afterId: Int
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
            isDayClosed = state.uiState.value.isDayClosed,
            isManualUnlock = state.uiState.value.isManualUnlock,
            isAdmin = state.isAdmin.value,
            isSupervisor = state.isSupervisor.value,
            maxOpenHouses = state.uiState.value.maxOpenHouses
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
            list.filter { it.generateIdentityKey() != house.generateIdentityKey() }
        }
    }

    private fun markAsRecentlyEdited(scope: CoroutineScope, state: HomeState, houseId: Int) {
        scope.launch {
            val now = clock.currentTimeMillis()
            state.recentlyEditedHouseIds.update { it + (houseId to now) }
            delay(4000)
            state.recentlyEditedHouseIds.update { current ->
                if (current[houseId] == now) current - houseId else current
            }
        }
    }
}
