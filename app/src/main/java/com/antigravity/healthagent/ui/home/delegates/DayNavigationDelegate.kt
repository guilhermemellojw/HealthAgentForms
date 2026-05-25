package com.antigravity.healthagent.ui.home.delegates

import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.local.model.Situation
import com.antigravity.healthagent.domain.repository.HouseRepository
import com.antigravity.healthagent.domain.usecase.ClashDetector
import com.antigravity.healthagent.domain.usecase.DayManagementUseCase
import com.antigravity.healthagent.utils.SoundManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DayNavigationDelegate @Inject constructor(
    private val repository: HouseRepository,
    private val dayManagementUseCase: DayManagementUseCase,
    private val clashDetector: ClashDetector,
    private val soundManager: SoundManager
) {
    private val dateFormatter = SimpleDateFormat("dd-MM-yyyy", Locale.US)

    fun moveDateBackward(scope: CoroutineScope, state: HomeState) {
        scope.launch {
            try {
                val calendar = Calendar.getInstance()
                val date = try { dateFormatter.parse(state.data.value) } catch (e: Exception) { null }
                calendar.time = date ?: Date()

                do {
                    calendar.add(Calendar.DAY_OF_YEAR, -1)
                } while (calendar.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY || calendar.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY)
                state.data.value = dateFormatter.format(calendar.time)
                soundManager.playPop()
            } catch (e: Exception) {
                state.uiEvent.value = "Erro ao navegar: ${e.message}"
            }
        }
    }

    fun moveDateForward(scope: CoroutineScope, state: HomeState) {
        scope.launch {
            try {
                val calendar = Calendar.getInstance()
                val date = try { dateFormatter.parse(state.data.value) } catch (e: Exception) { null }
                calendar.time = date ?: Date()

                do {
                    calendar.add(Calendar.DAY_OF_YEAR, 1)
                } while (calendar.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY || calendar.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY)
                state.data.value = dateFormatter.format(calendar.time)
                soundManager.playPop()
            } catch (e: Exception) {
                state.uiEvent.value = "Erro ao navegar: ${e.message}"
            }
        }
    }

    fun onDateSelected(state: HomeState, date: String) {
        state.data.value = date.replace("/", "-")
        soundManager.playPop()
    }

    fun goToToday(state: HomeState) {
        state.data.value = dateFormatter.format(Date())
        soundManager.playPop()
    }

    fun moveHouseToDate(scope: CoroutineScope, state: HomeState, house: House, newDate: String, maxOpenHouses: Int, isDayClosed: Boolean) {
        val isUnlocked = state.uiState.value.isManualUnlock
        val isAdmin = state.isAdmin.value

        if (isDayClosed && !isUnlocked && !isAdmin) {
            state.uiEvent.value = "O dia de ORIGEM está FECHADO. Desbloqueie primeiro."
            soundManager.playWarning()
            return
        }

        val allHouses = state.housesInFlight.value + state.pendingUpdateDrafts.value.values
        // Wait, houses can be fetched from database too, so we can check state.pendingUpdateDrafts combined with db houses.
        // Wait, the caller can pass houses from current uiState/latestHouses list.
        // We will read from the state's latest houses directly.
        val targetHouses = state.pendingUpdateDrafts.value[house.id] ?: house
        val existingWorked = (state.pendingUpdateDrafts.value.values + state.housesInFlight.value).count {
            it.data == newDate && (it.situation == Situation.NONE || it.situation == Situation.EMPTY)
        } // We can also verify database houses or just use latestHouses from viewModel if we pass it, but since state has `pendingUpdateDrafts` and `housesInFlight` + DB houses flow we can get the value.
        // Wait! In HomeViewModel, `houses` is a StateFlow. We can read `houses.value` if we pass the flow, but wait, the viewmodel has `latestHouses` which we can pass or we can check repository.
        // Wait, let's look at `HomeViewModel`'s definition: `houses` is actually just a flow derived from database + overlays.
        // So checking the target date worked count in the database is more accurate anyway.
        // Let's launch a coroutine to fetch current worked houses for target date:
        scope.launch {
            val uid = state.remoteAgentUid.value ?: state.currentUserUid.value ?: ""
            val dbHouses = repository.getHousesByDateAndAgent(newDate.replace("/", "-"), uid)
            val existingWorkedInDb = dbHouses.count { it.situation == Situation.NONE || it.situation == Situation.EMPTY }
            val houseIsWorked = house.situation == Situation.NONE || house.situation == Situation.EMPTY

            if (existingWorkedInDb >= maxOpenHouses && maxOpenHouses > 0 && houseIsWorked) {
                soundManager.playWarning()
                state.uiEvent.value = "Impossível mover: Meta Diária do dia de destino atingida!"
                return@launch
            }
            performMoveHouse(scope, state, house, newDate)
        }
    }

    fun performMoveHouse(scope: CoroutineScope, state: HomeState, house: House, newDate: String) {
        scope.launch {
            val uid = state.remoteAgentUid.value ?: state.currentUserUid.value ?: ""
            val destActivity = dayManagementUseCase.getDayActivity(newDate, uid)
            val isDestUnlocked = destActivity?.isManualUnlock == true
            if (dayManagementUseCase.isDateLocked(destActivity) && !isDestUnlocked && !state.isAdmin.value) {
                state.uiEvent.value = "A data de DESTINO está FECHADA. Acesse-a e destranque."
                soundManager.playWarning()
                return@launch
            }

            val updatedHouse = house.copy(
                data = newDate,
                agentName = state.agentName.value,
                agentUid = uid
            )

            // Get latest houses in DB + overlays
            val dbHouses = repository.getHousesByDateAndAgent(newDate, uid)
            val overlays = state.pendingUpdateDrafts.value.values.filter { it.data == newDate } +
                    state.housesInFlight.value.filter { it.data == newDate }
            val latestHouses = (dbHouses + overlays).distinctBy { it.id }

            val clashingHouse = clashDetector.findClash(updatedHouse, latestHouses)

            if (clashingHouse != null) {
                state.pendingUpdateDrafts.update { it + (updatedHouse.id to updatedHouse) }
                state.uiEvent.value = "Conflito detectado no destino! Resolva em vermelho."
                soundManager.playWarning()
                state.data.value = newDate
            } else {
                if (updatedHouse.id == 0) {
                    state.housesInFlight.update { inFlights ->
                        inFlights.map { if (it.listOrder == house.listOrder) updatedHouse else it }
                    }
                } else {
                    repository.updateHouse(updatedHouse.copy(isSynced = false, lastUpdated = System.currentTimeMillis()))
                }

                state.pendingUpdateDrafts.update { it - updatedHouse.id }

                state.uiEvent.value = "Imóvel movido com sucesso para $newDate"
                soundManager.playPop()
                state.data.value = newDate
            }
        }
    }

    fun moveHousesToDate(scope: CoroutineScope, state: HomeState, oldDate: String, newDate: String) {
        scope.launch {
            try {
                val currentUid = state.remoteAgentUid.value ?: state.currentUserUid.value

                val normalizedOldDate = oldDate.replace("/", "-")
                val normalizedNewDate = newDate.replace("/", "-")

                repository.runInTransaction {
                    val housesToMove = repository.getHousesByDateAndAgent(normalizedOldDate, currentUid ?: "")

                    if (housesToMove.isNotEmpty()) {
                        val updatedHouses = housesToMove.map {
                            it.copy(
                                data = normalizedNewDate,
                                isSynced = false,
                                lastUpdated = System.currentTimeMillis()
                            )
                        }
                        repository.updateHouses(updatedHouses)
                    }

                    val oldActivity = repository.getDayActivity(normalizedOldDate, currentUid)
                    if (oldActivity != null) {
                        val existingNewActivity = repository.getDayActivity(normalizedNewDate, currentUid)
                        if (existingNewActivity == null) {
                            repository.updateDayActivity(oldActivity.copy(date = normalizedNewDate))
                        }
                        repository.deleteProduction(normalizedOldDate, currentUid)
                    }
                }

                state.housesInFlight.update { inFlights ->
                    inFlights.map {
                        if (it.data.replace("/", "-") == normalizedOldDate && it.agentUid == currentUid) {
                            it.copy(data = normalizedNewDate)
                        } else it
                    }
                }

                state.uiEvent.value = "Produção movida com sucesso!"
                soundManager.playPop()

                if (state.data.value.replace("/", "-") == normalizedOldDate) {
                    state.data.value = normalizedNewDate
                }

            } catch (e: Exception) {
                state.uiEvent.value = "Erro ao mover produção: ${e.message}"
                e.printStackTrace()
            }
        }
    }

    fun confirmMoveHouse(scope: CoroutineScope, state: HomeState) {
        state.moveConfirmationData.value?.let { (house, date) ->
            performMoveHouse(scope, state, house, date)
            state.moveConfirmationData.value = null
        }
    }

    fun dismissMoveConfirmation(state: HomeState) {
        state.moveConfirmationData.value = null
    }
}
