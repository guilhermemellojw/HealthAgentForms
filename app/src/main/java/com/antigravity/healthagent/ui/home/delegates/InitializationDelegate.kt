package com.antigravity.healthagent.ui.home.delegates

import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.local.model.Situation
import com.antigravity.healthagent.domain.repository.HouseRepository
import com.antigravity.healthagent.domain.repository.UserRole
import com.antigravity.healthagent.domain.usecase.HouseValidationUseCase
import com.antigravity.healthagent.domain.usecase.PerformLocalDatabaseMigrationUseCase
import com.antigravity.healthagent.data.settings.SettingsManager
import com.antigravity.healthagent.ui.home.DashboardTotals
import com.antigravity.healthagent.ui.home.HomeViewModel
import com.antigravity.healthagent.ui.home.HouseUiStateMapper
import com.antigravity.healthagent.ui.state.SyncUiState
import com.antigravity.healthagent.domain.logger.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InitializationDelegate @Inject constructor(
    private val performLocalDatabaseMigrationUseCase: PerformLocalDatabaseMigrationUseCase,
    private val repository: HouseRepository,
    private val settingsManager: SettingsManager,
    private val houseValidationUseCase: HouseValidationUseCase
) {

    fun initialize(
        scope: CoroutineScope,
        viewModel: HomeViewModel,
        latestHousesFlow: kotlinx.coroutines.flow.StateFlow<List<House>>,
        allHousesFlow: kotlinx.coroutines.flow.StateFlow<List<House>>,
        easyMode: kotlinx.coroutines.flow.StateFlow<Boolean>,
        solarMode: kotlinx.coroutines.flow.StateFlow<Boolean>,
        editingToolsMode: kotlinx.coroutines.flow.StateFlow<Boolean>,
        maxOpenHouses: kotlinx.coroutines.flow.StateFlow<Int>,
        generateHouseKey: (House) -> String,
        calculateDashboardTotals: (List<House>) -> DashboardTotals
    ) {
        // Load initial state from SettingsManager
        scope.launch {
            settingsManager.cachedUser.collect { user ->
                user?.let {
                    val name = it.agentName?.uppercase()?.ifBlank { null }
                        ?: it.email?.substringBefore("@")?.uppercase()
                        ?: "DESCONHECIDO"
                    viewModel.agentName.value = name
                    viewModel.currentUserUid.value = it.uid
                    viewModel.isSupervisor.value = it.role == UserRole.SUPERVISOR
                    viewModel.isAdmin.value = it.role == UserRole.ADMIN
                }
            }
        }

        // Validation observer: Clear errors immediately on date change
        scope.launch {
            viewModel.data.collect {
                viewModel.validationErrorHouseIds.value = emptySet()
                viewModel.validationErrorDetails.value = emptyList()
                viewModel.isDuplicateIds.value = emptySet()
                viewModel.integrityDialogMessage.value = null
            }
        }

        // CRITICAL: Auto-load header context ONLY when active date changes
        scope.launch {
            viewModel.data.collect { date ->
                val dayHouses = latestHousesFlow.value.filter { it.data == date }
                if (dayHouses.isNotEmpty()) {
                    val ref = dayHouses.first()
                    viewModel.municipio.value = ref.context.municipio.uppercase()
                    viewModel.bairro.value = ref.address.bairro.uppercase()
                    viewModel.categoria.value = ref.context.categoria.uppercase()
                    viewModel.zona.value = ref.context.zona.uppercase()
                    viewModel.tipo.value = ref.context.tipo
                    viewModel.ciclo.value = ref.context.ciclo.uppercase()
                    viewModel.atividade.value = ref.context.atividade
                }
            }
        }

        // CRITICAL: Basic Header Info Observer
        scope.launch {
            combine(
                viewModel.data, viewModel.agentName, viewModel.municipio, viewModel.bairro,
                viewModel.zona, viewModel.ciclo, viewModel.tipo, viewModel.atividade,
                viewModel.isSupervisor, viewModel.isAdmin
            ) { args ->
                Triple(args[0] as String, args[1] as String, args)
            }.collect { (date, name, args) ->
                viewModel.uiState.update { current ->
                    current.copy(
                        data = date,
                        agentName = name,
                        municipality = args[2] as String,
                        neighborhood = args[3] as String,
                        zone = args[4] as String,
                        cycle = args[5] as String,
                        type = args[6] as Int,
                        activity = args[7] as Int,
                        isSupervisor = args[8] as Boolean,
                        isAdmin = args[9] as Boolean
                    )
                }
            }
        }

        // CRITICAL: House List and Dashboard Totals Observer
        scope.launch {
            combine(
                latestHousesFlow, viewModel.data, viewModel.recentlyEditedHouseIds,
                viewModel.highlightedHouseId, viewModel.currentUserUid
            ) { h, d, recentlyEdited, highlightedId, myUid ->
                val dayHouses = h.filter { it.data == d }
                val totals = calculateDashboardTotals(dayHouses)

                val identityCounts = mutableMapOf<String, Int>()
                dayHouses.forEach { hh ->
                    val key = generateHouseKey(hh)
                    identityCounts[key] = (identityCounts[key] ?: 0) + 1
                }

                val mapped = dayHouses.map { hh ->
                    val key = generateHouseKey(hh)
                    HouseUiStateMapper.map(
                        house = hh,
                        houseValidationUseCase = houseValidationUseCase,
                        isDuplicate = (identityCounts[key] ?: 0) > 1,
                        isRecentlyEdited = recentlyEdited.containsKey(hh.id),
                        isHighlighted = hh.id == highlightedId,
                        isMine = hh.agentUid == myUid
                    )
                }
                mapped to totals
            }.collect { (mapped, totals) ->
                viewModel.uiState.update { it.copy(
                    houses = mapped,
                    dashboardTotals = totals,
                    pendingCount = totals.worked
                ) }
            }
        }

        // CRITICAL: Validation Errors and Duplicates Observer
        scope.launch {
            combine(
                viewModel.validationErrorHouseIds,
                viewModel.isDuplicateIds,
                latestHousesFlow,
                viewModel.data
            ) { errorIds, duplicateIds, houses, d ->
                val dayHouses = houses.filter { it.data == d }
                val dayErrorCount = dayHouses.count { it.id in errorIds }
                Triple(errorIds, duplicateIds, dayErrorCount)
            }.collect { (errorIds, duplicateIds, dayErrorCount) ->
                viewModel.uiState.update { current ->
                    current.copy(
                        validationErrorHouseIds = errorIds,
                        isDuplicateIds = duplicateIds,
                        strictPendingCount = dayErrorCount
                    )
                }
            }
        }

        // CRITICAL: Day Lock Status Observer
        scope.launch {
            combine(
                viewModel.isDayClosed,
                viewModel.isWorkdayManualUnlock
            ) { closed, unlocked ->
                closed to unlocked
            }.collect { (closed, unlocked) ->
                viewModel.uiState.update { current ->
                    current.copy(
                        isDayClosed = closed,
                        isManualUnlock = unlocked
                    )
                }
            }
        }

        // CRITICAL: Settings Observer (Easy Mode, Solar Mode, etc.)
        scope.launch {
            combine(easyMode, solarMode, editingToolsMode, maxOpenHouses) { e, s, t, m ->
                listOf(e, s, t, m)
            }.collect { args ->
                viewModel.uiState.update { it.copy(
                    isEasyMode = args[0] as Boolean,
                    isSolarMode = args[1] as Boolean,
                    isEditingToolsEnabled = args[2] as Boolean,
                    maxOpenHouses = args[3] as Int
                )}
            }
        }

        // SURGICAL PROTECTION: Detect misattributed data
        scope.launch {
            allHousesFlow.collect { houses ->
                val myName = viewModel.agentName.value.uppercase()
                val myUid = viewModel.currentUserUid.value
                val isViewingRemote = viewModel.remoteAgentUid.value != null && viewModel.remoteAgentUid.value != myUid

                if (myName.isNotBlank() && myUid != null && !isViewingRemote) {
                    val hasLeaks = houses.any {
                        it.agentUid == myUid &&
                        it.agentName.isNotBlank() &&
                        it.agentName.uppercase() != myName &&
                        !it.agentName.contains("@")
                    }
                    viewModel.uiState.update { it.copy(hasMisattributedData = hasLeaks) }
                } else {
                    viewModel.uiState.update { it.copy(hasMisattributedData = false) }
                }
            }
        }

        // Observer for Sync Info (Metadata)
        scope.launch {
            combine(
                settingsManager.lastSyncTimestamp,
                settingsManager.clockSkewMs
            ) { lastSync, skew ->
                lastSync to skew
            }.collect { (lastSync, skew) ->
                viewModel.syncStatus.update { state ->
                    if (state is SyncUiState.Success) state.copy(lastSyncTime = lastSync, clockSkewMs = skew)
                    else SyncUiState.Idle(lastSyncTime = lastSync)
                }
            }
        }

        // Propagate syncStatus to uiState in real time so the float balloon displays it reactively
        scope.launch {
            viewModel.syncStatus.collect { status ->
                viewModel.uiState.update { current ->
                    current.copy(syncStatus = status)
                }
            }
        }

        // PRUNE OBSERVER
        scope.launch {
            allHousesFlow.collect { dbHouses ->
                viewModel.pendingUpdateDrafts.update { currentDrafts ->
                    if (currentDrafts.isEmpty()) return@update currentDrafts
                    val resolvedIds = mutableListOf<Int>()

                    currentDrafts.forEach { (id, draft) ->
                        val stillClashes = dbHouses.any {
                            it.id != draft.id &&
                            it.data == draft.data &&
                            it.agentUid == draft.agentUid &&
                            it.agentName.equals(draft.agentName, ignoreCase = true) &&
                            it.address.blockNumber.equals(draft.address.blockNumber, ignoreCase = true) &&
                            it.address.blockSequence.equals(draft.address.blockSequence, ignoreCase = true) &&
                            it.address.streetName.equals(draft.address.streetName, ignoreCase = true) &&
                            it.address.number.equals(draft.address.number, ignoreCase = true) &&
                            it.address.sequence == draft.address.sequence &&
                            it.address.complement == draft.address.complement &&
                            it.address.bairro.equals(draft.address.bairro, ignoreCase = true) &&
                            it.visitSegment == draft.visitSegment
                        }

                        val dbMatch = dbHouses.find { it.id == id }
                        val isDraftSynced = dbMatch != null &&
                            dbMatch.lastUpdated >= draft.lastUpdated &&
                            dbMatch.address.number == draft.address.number &&
                            dbMatch.address.sequence == draft.address.sequence &&
                            dbMatch.address.complement == draft.address.complement &&
                            dbMatch.propertyType == draft.propertyType &&
                            dbMatch.situation == draft.situation &&
                            dbMatch.address.streetName == draft.address.streetName &&
                            dbMatch.address.blockNumber == draft.address.blockNumber &&
                            dbMatch.address.blockSequence == draft.address.blockSequence &&
                            dbMatch.address.bairro == draft.address.bairro &&
                            dbMatch.observation == draft.observation &&
                            dbMatch.treatment.a1 == draft.treatment.a1 && dbMatch.treatment.a2 == draft.treatment.a2 &&
                            dbMatch.treatment.b == draft.treatment.b && dbMatch.treatment.c == draft.treatment.c &&
                            dbMatch.treatment.d1 == draft.treatment.d1 && dbMatch.treatment.d2 == draft.treatment.d2 &&
                            dbMatch.treatment.e == draft.treatment.e && dbMatch.treatment.eliminados == draft.treatment.eliminados &&
                            dbMatch.treatment.larvicida == draft.treatment.larvicida && dbMatch.treatment.comFoco == draft.treatment.comFoco &&
                            dbMatch.quarteiraoConcluido == draft.quarteiraoConcluido &&
                            dbMatch.localidadeConcluida == draft.localidadeConcluida

                        if (isDraftSynced) {
                            resolvedIds.add(id)
                        } else if (!stillClashes && dbMatch != null && dbMatch.lastUpdated == draft.lastUpdated) {
                            resolvedIds.add(id)
                        }
                    }

                    if (resolvedIds.isNotEmpty()) currentDrafts - resolvedIds else currentDrafts
                }

                viewModel.housesInFlight.update { inFlights ->
                    if (inFlights.isEmpty()) return@update inFlights
                    inFlights.filter { inFlight ->
                        !dbHouses.any { db ->
                            (db.listOrder == inFlight.listOrder && db.data == inFlight.data &&
                             db.address.blockNumber == inFlight.address.blockNumber &&
                             db.address.streetName == inFlight.address.streetName &&
                             db.address.number == inFlight.address.number &&
                             db.address.sequence == inFlight.address.sequence &&
                             db.address.complement == inFlight.address.complement) ||
                            (db.generateIdentityKey() == inFlight.generateIdentityKey())
                        }
                    }
                }
            }
        }

        // Defer non-critical background work
        scope.launch(Dispatchers.IO) {
            delay(1000)
            try {
                performLocalDatabaseMigrationUseCase.migrateStreetNamesToFormat()
                performLocalDatabaseMigrationUseCase.migrateBairrosToUppercase()
                performLocalDatabaseMigrationUseCase.migrateDateFormats()
                repository.normalizeLocalDates()
            } catch (e: Exception) {
                AppLogger.e("HomeViewModel", "Error running migrations", e)
            }
        }

        // Load initial sync timestamp
        scope.launch {
            settingsManager.lastSyncTimestamp.collect { ts ->
                viewModel.syncStatus.update { state ->
                    SyncUiState.Idle(lastSyncTime = ts)
                }
            }
        }
    }
}
