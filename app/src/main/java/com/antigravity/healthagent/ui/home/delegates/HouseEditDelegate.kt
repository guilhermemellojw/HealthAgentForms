package com.antigravity.healthagent.ui.home.delegates

import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.local.model.Situation
import com.antigravity.healthagent.domain.model.DailyContext
import com.antigravity.healthagent.domain.model.VisitAddress
import com.antigravity.healthagent.data.local.model.PropertyType
import com.antigravity.healthagent.domain.repository.HouseRepository
import com.antigravity.healthagent.domain.usecase.SaveHouseUseCase
import com.antigravity.healthagent.domain.usecase.PredictHouseValuesUseCase
import com.antigravity.healthagent.domain.usecase.RecalculateVisitSegmentsUseCase
import com.antigravity.healthagent.domain.usecase.ClashDetector
import com.antigravity.healthagent.domain.usecase.DayLockEnforcer
import com.antigravity.healthagent.domain.usecase.RoleEnforcer
import com.antigravity.healthagent.utils.SoundManager
import com.antigravity.healthagent.domain.logger.AppLogger
import com.antigravity.healthagent.utils.formatStreetName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HouseEditDelegate @Inject constructor(
    private val repository: HouseRepository,
    private val saveHouseUseCase: SaveHouseUseCase,
    private val predictHouseValuesUseCase: PredictHouseValuesUseCase,
    private val recalculateVisitSegmentsUseCase: RecalculateVisitSegmentsUseCase,
    private val clashDetector: ClashDetector,
    private val dayLockEnforcer: DayLockEnforcer,
    private val roleEnforcer: RoleEnforcer,
    private val soundManager: SoundManager
) {
    private var isAddingHouse = false
    private var lastAddClickTime = 0L

    private val clashDialogJobs = ConcurrentHashMap<Int, Job>()
    private val houseUpdateJobs = ConcurrentHashMap<Int, Job>()
    private var recentlyDeletedHouse: House? = null

    fun addNewHouseAt(
        scope: CoroutineScope,
        state: HomeState,
        afterId: Int,
        latestHousesList: List<House>,
        isDayClosed: Boolean
    ) {
        val roleResult = roleEnforcer.enforce(state.isSupervisor.value, state.isAdmin.value, "adicionar dados remotamente")
        if (roleResult is RoleEnforcer.RoleResult.Blocked) {
            state.uiEvent.value = roleResult.message
            soundManager.playWarning()
            return
        }

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
                val lockResult = dayLockEnforcer.enforce(
                    isDayClosed = isDayClosed,
                    isManualUnlock = state.uiState.value.isManualUnlock,
                    isAdmin = state.isAdmin.value,
                    actionDescription = "adicionar"
                )
                if (lockResult is DayLockEnforcer.LockResult.Blocked) {
                    state.uiEvent.value = lockResult.message
                    soundManager.playWarning()
                    isAddingHouse = false
                    return@launch
                }
                val isAdmin = state.isAdmin.value

                val currentDayHouses = latestHousesList.filter { it.data == state.data.value }
                val targetIndex = if (afterId == -1) -1 else currentDayHouses.indexOfFirst { it.id == afterId }

                val template = if (targetIndex != -1) currentDayHouses[targetIndex] else currentDayHouses.firstOrNull()

                val prediction = if (template != null) {
                    predictHouseValuesUseCase.predictBasedOnHistory(latestHousesList, template)
                } else {
                    PredictHouseValuesUseCase.HousePrediction("", 0, 0, PropertyType.R, Situation.NONE)
                }

                val finalPropertyType = if (prediction.propertyType != PropertyType.EMPTY) {
                    prediction.propertyType
                } else if (template?.propertyType != null && template.propertyType != PropertyType.EMPTY) {
                    template.propertyType
                } else {
                    PropertyType.R
                }

                val finalBairro = template?.address?.bairro?.takeIf { it.isNotBlank() } ?: state.bairro.value.uppercase()
                val finalMunicipio = template?.context?.municipio?.takeIf { it.isNotBlank() } ?: state.municipio.value.trim().uppercase()
                val finalCategoria = template?.context?.categoria?.takeIf { it.isNotBlank() } ?: state.categoria.value.trim().uppercase()
                val finalZona = template?.context?.zona?.takeIf { it.isNotBlank() } ?: state.zona.value.trim().uppercase()
                val finalTipo = template?.context?.tipo ?: state.tipo.value
                val finalCiclo = template?.context?.ciclo?.takeIf { it.isNotBlank() } ?: state.ciclo.value.trim().uppercase()
                val finalAtividade = template?.context?.atividade ?: state.atividade.value

                var houseToInsert = House(
                    context = DailyContext(
                        municipio = finalMunicipio,
                        categoria = finalCategoria,
                        zona = finalZona,
                        tipo = finalTipo,
                        ciclo = finalCiclo,
                        atividade = finalAtividade
                    ),
                    address = VisitAddress(
                        blockNumber = template?.address?.blockNumber ?: state.currentBlock.value,
                        blockSequence = template?.address?.blockSequence ?: state.currentBlockSequence.value,
                        streetName = template?.address?.streetName ?: state.currentStreet.value,
                        number = prediction.number,
                        sequence = prediction.sequence,
                        complement = prediction.complement,
                        bairro = finalBairro
                    ),
                    propertyType = finalPropertyType,
                    situation = prediction.situation,
                    data = state.data.value,
                    agentName = state.agentName.value.uppercase(),
                    agentUid = state.remoteAgentUid.value ?: state.currentUserUid.value ?: "",
                    listOrder = 0
                )

                houseToInsert = clashDetector.autoIncrementToAvoidClash(
                    houseToInsert, latestHousesList, includeVisitSegment = false
                )

                val mutableList = currentDayHouses.toMutableList()
                if (targetIndex == -1) {
                    mutableList.add(0, houseToInsert)
                } else {
                    mutableList.add(targetIndex + 1, houseToInsert)
                }

                val updatedList = mutableList.mapIndexed { index, h -> h.copy(listOrder = index.toLong()) }
                val recalculated = recalculateVisitSegmentsUseCase.recalculateVisitSegments(updatedList)

                val newlyAdded = recalculated.find { it.id == 0 }
                val others = recalculated.filter { it.id != 0 }

                if (others.isNotEmpty()) {
                    repository.updateHouses(others, isAdmin)
                }
                if (newlyAdded != null) {
                    val newId = repository.insertHouse(newlyAdded, isAdmin)
                    state.highlightedHouseId.value = newId.toInt()
                    scope.launch {
                        delay(2000)
                        if (state.highlightedHouseId.value == newId.toInt()) {
                            state.highlightedHouseId.value = null
                        }
                    }
                }

                soundManager.playPop()
            } catch (e: Exception) {
                AppLogger.e("HomeViewModel", "Error adding house at position", e)
                state.uiEvent.value = "Erro ao inserir: ${e.message}"
                soundManager.playWarning()
            } finally {
                isAddingHouse = false
            }
        }
    }

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
        val roleResult = roleEnforcer.enforce(state.isSupervisor.value, state.isAdmin.value, "adicionar dados remotamente")
        if (roleResult is RoleEnforcer.RoleResult.Blocked) {
            state.uiEvent.value = roleResult.message
            soundManager.playWarning()
            return
        }

        val currentTime = System.currentTimeMillis()
        if (isAddingHouse || currentTime - lastAddClickTime < 100) return

        isAddingHouse = true
        lastAddClickTime = currentTime

        if (state.agentName.value.isBlank()) {
            state.uiEvent.value = "Aguardando carregamento do perfil..."
            isAddingHouse = false
            return
        }

        val lockResult = dayLockEnforcer.enforce(
            isDayClosed = isDayClosed,
            isManualUnlock = state.uiState.value.isManualUnlock,
            isAdmin = state.isAdmin.value,
            actionDescription = "adicionar"
        )
        if (lockResult is DayLockEnforcer.LockResult.Blocked) {
            state.uiEvent.value = lockResult.message
            soundManager.playWarning()
            isAddingHouse = false
            return
        }
        val isAdmin = state.isAdmin.value

        val currentTotal = latestHousesList.count { it.data == state.data.value }
        val safetyLimit = (maxOpenHouses * 3).coerceAtLeast(150)

        if (currentTotal >= safetyLimit && !state.uiState.value.isManualUnlock && !isAdmin) {
            soundManager.playWarning()
            state.situationLimitConfirmation.value = House(data = state.data.value)
            isAddingHouse = false
            return
        }

        val clashingDraftIds = state.isDuplicateIds.value
        val hasClashes = clashingDraftIds.isNotEmpty()

        if (hasClashes) {
            state.uiEvent.value = "Resolva os conflitos (em vermelho) antes de adicionar um novo imóvel."
            soundManager.playWarning()
            onHouseClick(clashingDraftIds.first())
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

        val currentAgentUid = activeRemoteUid ?: myUid!!
        val currentAgentName = state.agentName.value

        val currentInFlights = state.housesInFlight.value
        val fullyUpToDateHouses = (latestHousesList.filter { it.id != 0 } + currentInFlights).distinctBy {
            if (it.id != 0) it.id.toString() else "in_flight_${it.listOrder}"
        }

        val isDayEmpty = fullyUpToDateHouses.none { it.data == state.data.value }
        var prediction: PredictHouseValuesUseCase.HousePrediction
        var initialBlock = state.currentBlock.value
        var initialStreet = state.currentStreet.value
        var initialBlockSeq = state.currentBlockSequence.value

        if (isDayEmpty) {
            val lastGlobalHouse = fullyUpToDateHouses.maxByOrNull { it.listOrder }

            if (lastGlobalHouse != null) {
                initialBlock = lastGlobalHouse.address.blockNumber
                initialStreet = lastGlobalHouse.address.streetName
                initialBlockSeq = lastGlobalHouse.address.blockSequence

                state.currentBlock.value = initialBlock
                state.currentStreet.value = initialStreet
                state.currentBlockSequence.value = initialBlockSeq
                state.bairro.value = lastGlobalHouse.address.bairro
                state.municipio.value = lastGlobalHouse.context.municipio
                state.agentName.value = lastGlobalHouse.agentName

                prediction = predictHouseValuesUseCase.predictBasedOnHistory(fullyUpToDateHouses, lastGlobalHouse)
            } else {
                prediction = PredictHouseValuesUseCase.HousePrediction("", 0, 0, PropertyType.EMPTY, Situation.NONE)
            }
        } else {
            prediction = predictHouseValuesUseCase.predictNextHouseValues(
                fullyUpToDateHouses,
                state.data.value,
                state.currentBlock.value.trim().uppercase(),
                state.currentStreet.value.trim().formatStreetName()
            )
        }

        val lastHouseRef = if (isDayEmpty) {
            fullyUpToDateHouses.maxByOrNull { it.listOrder }
        } else {
            fullyUpToDateHouses.filter { it.data == state.data.value }.maxByOrNull { it.listOrder }
        }

        val finalPropertyType = if (prediction.propertyType != PropertyType.EMPTY) {
            prediction.propertyType
        } else if (lastHouseRef?.propertyType != null && lastHouseRef.propertyType != PropertyType.EMPTY) {
            lastHouseRef.propertyType
        } else {
            PropertyType.R
        }

        val finalBairro = lastHouseRef?.address?.bairro?.takeIf { it.isNotBlank() } ?: state.bairro.value.trim().uppercase()
        val finalMunicipio = lastHouseRef?.context?.municipio?.takeIf { it.isNotBlank() } ?: state.municipio.value.trim().uppercase()
        val finalCategoria = lastHouseRef?.context?.categoria?.takeIf { it.isNotBlank() } ?: state.categoria.value.trim().uppercase()
        val finalZona = lastHouseRef?.context?.zona?.takeIf { it.isNotBlank() } ?: state.zona.value.trim().uppercase()
        val finalTipo = lastHouseRef?.context?.tipo ?: state.tipo.value
        val finalCiclo = lastHouseRef?.context?.ciclo?.takeIf { it.isNotBlank() } ?: state.ciclo.value.trim().uppercase()
        val finalAtividade = lastHouseRef?.context?.atividade ?: state.atividade.value

        val maxOrder = fullyUpToDateHouses.maxOfOrNull { it.listOrder } ?: 0L
        val currentDayHouses = fullyUpToDateHouses.filter { it.data == state.data.value }.sortedBy { it.listOrder }
        val newStreet = initialStreet.trim().formatStreetName()

        var predictedSegment = 0
        var lastStreetName = ""
        currentDayHouses.forEach { h ->
            val s = h.address.streetName.trim().uppercase()
            if (lastStreetName.isNotEmpty() && s != lastStreetName) {
                predictedSegment++
            }
            lastStreetName = s
        }
        if (lastStreetName.isNotEmpty() && newStreet.uppercase() != lastStreetName) {
            predictedSegment++
        }

        var houseToInsert = House(
            id = 0,
            address = VisitAddress(
                blockNumber = initialBlock.trim().uppercase(),
                blockSequence = initialBlockSeq.trim().uppercase(),
                streetName = initialStreet.trim().formatStreetName(),
                number = prediction.number.trim().uppercase(),
                sequence = prediction.sequence,
                complement = prediction.complement,
                bairro = finalBairro
            ),
            propertyType = finalPropertyType,
            situation = prediction.situation,
            context = DailyContext(
                municipio = finalMunicipio,
                categoria = finalCategoria,
                zona = finalZona,
                tipo = finalTipo,
                ciclo = finalCiclo,
                atividade = finalAtividade
            ),
            agentName = currentAgentName.trim().uppercase(),
            agentUid = currentAgentUid,
            data = state.data.value,
            visitSegment = predictedSegment,
            listOrder = maxOrder + 1
        )

        houseToInsert = clashDetector.autoIncrementToAvoidClash(
            houseToInsert, fullyUpToDateHouses, includeVisitSegment = true
        )

        markAsRecentlyEdited(scope, state, 0)
        state.housesInFlight.update { it + houseToInsert }

        // RELEASE THE UI SEMAPHORE LOCK IMMEDIATELY
        isAddingHouse = false

        scope.launch {
            try {
                val newId = saveHouseUseCase.insertHouse(houseToInsert, latestHousesList, isAdmin)

                val dbHousesAfter = repository.getHousesByDateAndAgent(state.data.value, currentAgentUid)
                val currentDrafts = state.pendingUpdateDrafts.value
                val currentInFlights = state.housesInFlight.value
                val refreshedLatestHouses = (dbHousesAfter.map { currentDrafts[it.id] ?: it } + currentInFlights)

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
                        isAdmin
                    )
                }

                soundManager.playPop()

                markAsRecentlyEdited(scope, state, newId.toInt())
                triggerDelayedValidation()
            } catch (e: Exception) {
                AppLogger.e("HomeViewModel", "Error adding new house", e)
                state.uiEvent.value = "Erro ao adicionar imóvel: ${e.message}"
                soundManager.playWarning()
                state.housesInFlight.update { list ->
                    list.filter { it.listOrder != houseToInsert.listOrder || it.data != houseToInsert.data }
                }
            }
        }
    }

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

        val houseIsWorked = (house.situation == Situation.NONE || house.situation == Situation.EMPTY)
        val originalIsWorked = original != null && (original.situation == Situation.NONE || original.situation == Situation.EMPTY)

        if (houseIsWorked && !originalIsWorked) {
            val workedCount = latestHousesList.count { it.data == state.data.value && (it.situation == Situation.NONE || it.situation == Situation.EMPTY) }
            val isUnlocked = state.uiState.value.isManualUnlock
            val isAdmin = state.isAdmin.value

            if (workedCount >= maxOpenHouses && maxOpenHouses > 0 && !isUnlocked && !isAdmin) {
                soundManager.playWarning()
                state.situationLimitConfirmation.value = house
                return
            }
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
