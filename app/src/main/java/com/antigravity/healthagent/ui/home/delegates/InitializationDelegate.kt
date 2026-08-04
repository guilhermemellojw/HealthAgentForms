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
import com.antigravity.healthagent.ui.home.mapDayIncremental
import com.antigravity.healthagent.ui.home.CachedHouseUi
import com.antigravity.healthagent.ui.state.SyncUiState
import com.antigravity.healthagent.domain.logger.AppLogger
import com.antigravity.healthagent.utils.formatStreetName
import com.antigravity.healthagent.utils.normalize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.flowOn
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
    private var initJob: Job? = null
    private data class HouseUpdate(
        val houses: List<com.antigravity.healthagent.ui.home.HouseUiState>,
        val totals: DashboardTotals,
        val errorIds: Set<Int>,
        val duplicateIds: Set<Int>,
        val dayErrorCount: Int
    )

    fun initialize(
        scope: CoroutineScope,
        viewModel: HomeViewModel,
        dayHousesFlow: kotlinx.coroutines.flow.StateFlow<Pair<String, List<House>>>,
        allHousesFlow: kotlinx.coroutines.flow.StateFlow<List<House>>,
        easyMode: kotlinx.coroutines.flow.StateFlow<Boolean>,
        solarMode: kotlinx.coroutines.flow.StateFlow<Boolean>,
        editingToolsMode: kotlinx.coroutines.flow.StateFlow<Boolean>,
        maxOpenHouses: kotlinx.coroutines.flow.StateFlow<Int>,
        generateHouseKey: (House) -> String,
        calculateDashboardTotals: (List<House>) -> DashboardTotals
    ) {
        initJob?.cancel()
        val trackedJob = Job(scope.coroutineContext[Job])
        initJob = trackedJob
        // Load initial state from SettingsManager
        scope.launch(trackedJob) {
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
        scope.launch(trackedJob) {
            viewModel.data.collect {
                viewModel.validationErrorHouseIds.value = emptySet()
                viewModel.validationErrorDetails.value = emptyList()
                viewModel.isDuplicateIds.value = emptySet()
                viewModel.integrityDialogMessage.value = null
            }
        }

        // CRITICAL: Auto-load header context ONLY when active date changes OR database finishes initial load
        scope.launch(trackedJob) {
            var lastInitializedDate: String? = null
            dayHousesFlow.collect { (date, dayHouses) ->
                if (lastInitializedDate != date && dayHouses.isNotEmpty()) {
                    val ref = dayHouses.first()
                    viewModel.municipio.value = ref.context.municipio.uppercase()
                    viewModel.bairro.value = ref.address.bairro.uppercase()
                    viewModel.categoria.value = ref.context.categoria.uppercase()
                    viewModel.zona.value = ref.context.zona.uppercase()
                    viewModel.tipo.value = ref.context.tipo
                    viewModel.ciclo.value = ref.context.ciclo.uppercase()
                    viewModel.atividade.value = ref.context.atividade

                    // SURGICAL: Auto-load block and street from the last house of the day to ensure property prediction continuity
                    val lastRef = dayHouses.last()
                    viewModel.currentBlock.value = lastRef.address.blockNumber.uppercase()
                    viewModel.currentBlockSequence.value = lastRef.address.blockSequence.uppercase()
                    viewModel.currentStreet.value = lastRef.address.streetName

                    lastInitializedDate = date
                }
            }
        }

        // CRITICAL: Basic Header Info Observer
        scope.launch(trackedJob) {
            combine(
                viewModel.data, viewModel.agentName, viewModel.municipio, viewModel.bairro,
                viewModel.zona, viewModel.ciclo, viewModel.tipo, viewModel.atividade,
                viewModel.isSupervisor, viewModel.isAdmin, viewModel.currentBlock,
                viewModel.currentBlockSequence, viewModel.currentStreet
            ) { args ->
                args
            }.collect { args ->
                viewModel.uiState.update { current ->
                    current.copy(
                        data = args[0] as String,
                        agentName = args[1] as String,
                        municipality = args[2] as String,
                        neighborhood = args[3] as String,
                        zone = args[4] as String,
                        cycle = args[5] as String,
                        type = args[6] as Int,
                        activity = args[7] as Int,
                        isSupervisor = args[8] as Boolean,
                        isAdmin = args[9] as Boolean,
                        currentBlock = args[10] as String,
                        currentBlockSequence = args[11] as String,
                        currentStreet = args[12] as String
                    )
                }
            }
        }

        // CRITICAL: House List + Dashboard Totals + Validation (merged to reduce uiState.update calls)
        //
        // pendingUpdateDrafts is included as an input so that when the merged collector
        // is triggered by recentlyEditedHouseIds (which fires before dayHousesFlow
        // re-emits on Dispatchers.Default), the mapping always applies the latest draft
        // values. This eliminates the flicker where the collector would momentarily
        // overwrite the UI with stale (pre-draft) data from the DB flow.
        //
        // Day-scoped: consumes dayHousesFlow (date, houses) so edits only cost O(day).
        // Cards are mapped incrementally (mapDayIncremental) — only changed houses
        // are re-rendered; the cache is reset on day change.
        scope.launch(trackedJob) {
            var lastMappedDay: String? = null
            var mappedCache: Map<Int, CachedHouseUi> = emptyMap()
            @Suppress("UNCHECKED_CAST")
            combine(
                dayHousesFlow, viewModel.recentlyEditedHouseIds,
                viewModel.highlightedHouseId, viewModel.currentUserUid,
                viewModel.validationErrorHouseIds, viewModel.isDuplicateIds,
                viewModel.pendingUpdateDrafts, viewModel.housesInFlight
            ) { args ->
                val (day, dayDb) = args[0] as Pair<String, List<House>>
                val recentlyEdited = args[1] as Map<Int, Long>
                val highlightedId = args[2] as Int?
                val myUid = args[3] as String
                val errorIds = args[4] as Set<Int>
                val duplicateIds = args[5] as Set<Int>
                val drafts = args[6] as Map<Int, House>
                val inFlights = args[7] as List<House>

                val dayNorm = day.replace("/", "-")
                val dayInFlights = inFlights.filter { inFlight ->
                    inFlight.data.replace("/", "-") == dayNorm &&
                    !dayDb.any { db -> db.generatePhysicalKey() == inFlight.generatePhysicalKey() }
                }
                val dayHouses = (dayDb + dayInFlights).sortedBy { it.listOrder }

                val dayHousesWithDrafts = dayHouses.map { house ->
                    drafts[house.id] ?: house
                }

                val dayT0 = System.currentTimeMillis()
                val totals = calculateDashboardTotals(dayHousesWithDrafts)

                val mapped = run {
                    if (lastMappedDay != dayNorm) {
                        lastMappedDay = dayNorm
                        mappedCache = emptyMap()
                    }
                    val result = mapDayIncremental(
                        houses = dayHousesWithDrafts,
                        previous = mappedCache,
                        generateHouseKey = generateHouseKey,
                        recentlyEditedHouseIds = recentlyEdited,
                        highlightedId = highlightedId,
                        myUid = myUid
                    ) { house, isDuplicate, isRecentlyEdited, isHighlighted, isMine ->
                        HouseUiStateMapper.map(
                            house = house,
                            houseValidationUseCase = houseValidationUseCase,
                            isDuplicate = isDuplicate,
                            isRecentlyEdited = isRecentlyEdited,
                            isHighlighted = isHighlighted,
                            isMine = isMine
                        )
                    }
                    mappedCache = result.cache
                    result.states
                }

                val dayErrorCount = dayHousesWithDrafts.count { it.id in errorIds }
                val dayMs = System.currentTimeMillis() - dayT0
                if (dayMs > 40) {
                    AppLogger.d("PERF", "DAYMAP_MS ms=$dayMs n=${dayHousesWithDrafts.size}")
                }
                HouseUpdate(mapped, totals, errorIds, duplicateIds, dayErrorCount)
            }.flowOn(Dispatchers.Default).collect { update ->
                viewModel.uiState.update { it.copy(
                    houses = update.houses,
                    dashboardTotals = update.totals,
                    pendingCount = update.totals.worked,
                    validationErrorHouseIds = update.errorIds,
                    isDuplicateIds = update.duplicateIds,
                    strictPendingCount = update.dayErrorCount
                ) }
            }
        }

        // CRITICAL: Day Lock Status Observer
        scope.launch(trackedJob) {
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
        scope.launch(trackedJob) {
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
        scope.launch(trackedJob) {
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
        scope.launch(trackedJob) {
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
        scope.launch(trackedJob) {
            viewModel.syncStatus.collect { status ->
                viewModel.uiState.update { current ->
                    current.copy(syncStatus = status)
                }
            }
        }

        // PRUNE OBSERVER
        scope.launch(trackedJob) {
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
                            dbMatch.address.number.normalize() == draft.address.number.normalize() &&
                            dbMatch.address.sequence == draft.address.sequence &&
                            dbMatch.address.complement == draft.address.complement &&
                            dbMatch.propertyType == draft.propertyType &&
                            dbMatch.situation == draft.situation &&
                            dbMatch.address.streetName.formatStreetName() == draft.address.streetName.formatStreetName() &&
                            dbMatch.address.blockNumber.normalize() == draft.address.blockNumber.normalize() &&
                            dbMatch.address.blockSequence.normalize() == draft.address.blockSequence.normalize() &&
                            dbMatch.address.bairro.normalize() == draft.address.bairro.normalize() &&
                            dbMatch.observation.normalize() == draft.observation.normalize() &&
                            dbMatch.treatment.a1 == draft.treatment.a1 && dbMatch.treatment.a2 == draft.treatment.a2 &&
                            dbMatch.treatment.b == draft.treatment.b && dbMatch.treatment.c == draft.treatment.c &&
                            dbMatch.treatment.d1 == draft.treatment.d1 && dbMatch.treatment.d2 == draft.treatment.d2 &&
                            dbMatch.treatment.e == draft.treatment.e && dbMatch.treatment.eliminados == draft.treatment.eliminados &&
                            dbMatch.treatment.larvicida == draft.treatment.larvicida && dbMatch.treatment.comFoco == draft.treatment.comFoco &&
                            dbMatch.quarteiraoConcluido == draft.quarteiraoConcluido &&
                            dbMatch.localidadeConcluida == draft.localidadeConcluida

                        if (isDraftSynced) {
                            AppLogger.d("PERSIST_DEBUG", "PRUNE_SYNCED: draft id=$id n='${draft.address.number}' s=${draft.address.sequence} c=${draft.address.complement} pt=${draft.propertyType.code}")
                            resolvedIds.add(id)
                        } else if (!stillClashes && dbMatch != null && dbMatch.lastUpdated == draft.lastUpdated) {
                            AppLogger.d("PERSIST_DEBUG", "PRUNE_EXACT: draft id=$id n='${draft.address.number}' s=${draft.address.sequence} c=${draft.address.complement} pt=${draft.propertyType.code}")
                            resolvedIds.add(id)
                        } else {
                            if (dbMatch != null) {
                                AppLogger.d("PERSIST_DEBUG", "PRUNE_KEEP: draft id=$id n='${draft.address.number}' s=${draft.address.sequence} c=${draft.address.complement} pt=${draft.propertyType.code} db_n='${dbMatch.address.number}' db_s=${dbMatch.address.sequence} db_c=${dbMatch.address.complement} db_pt=${dbMatch.propertyType.code} db_lu=${dbMatch.lastUpdated} draft_lu=${draft.lastUpdated}")
                            } else {
                                AppLogger.d("PERSIST_DEBUG", "PRUNE_KEEP_NODB: draft id=$id (no dbMatch)")
                            }
                        }
                    }

                    if (resolvedIds.isNotEmpty()) currentDrafts - resolvedIds else currentDrafts
                }

                viewModel.housesInFlight.update { inFlights ->
                    if (inFlights.isEmpty()) return@update inFlights
                    val dbKeys = dbHouses.mapTo(HashSet()) { it.generatePhysicalKey() }
                    inFlights.filter { inFlight ->
                        !dbKeys.contains(inFlight.generatePhysicalKey())
                    }
                }
            }
        }

        // Defer non-critical background work
        scope.launch(trackedJob + Dispatchers.IO) {
            delay(1000)
            try {
                performLocalDatabaseMigrationUseCase.runMigrationsIfNeeded()
            } catch (e: Exception) {
                AppLogger.e("HomeViewModel", "Error running migrations", e)
            }
        }

    }

    fun cancel() {
        initJob?.cancel()
        initJob = null
    }
}
