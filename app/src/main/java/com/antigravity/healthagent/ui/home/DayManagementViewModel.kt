package com.antigravity.healthagent.ui.home

import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.local.model.Situation
import com.antigravity.healthagent.domain.repository.HouseRepository
import com.antigravity.healthagent.domain.usecase.ClashDetector
import com.antigravity.healthagent.domain.usecase.DayManagementUseCase
import com.antigravity.healthagent.utils.SoundManager
import com.antigravity.healthagent.domain.logger.AppLogger
import com.antigravity.healthagent.utils.DateUtils
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DayManagementViewModel @Inject constructor(
    private val repository: HouseRepository,
    private val dayManagementUseCase: DayManagementUseCase,
    private val clashDetector: ClashDetector,
    private val soundManager: SoundManager
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val dateFormatter get() = DateUtils.DASH_DATE.get()

    fun cancelScope() { scope.cancel() }

    fun moveDateBackward(
        data: MutableStateFlow<String>,
        uiEvent: MutableStateFlow<String?>
    ) {
        scope.launch {
            try {
                val calendar = Calendar.getInstance()
                val date = try { dateFormatter.parse(data.value) } catch (e: Exception) { null }
                calendar.time = date ?: Date()

                do {
                    calendar.add(Calendar.DAY_OF_YEAR, -1)
                } while (calendar.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY || calendar.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY)
                data.value = dateFormatter.format(calendar.time)
                soundManager.playPop()
            } catch (e: Exception) {
                uiEvent.value = "Erro ao navegar: ${e.message}"
            }
        }
    }

    fun moveDateForward(
        data: MutableStateFlow<String>,
        uiEvent: MutableStateFlow<String?>
    ) {
        scope.launch {
            try {
                val calendar = Calendar.getInstance()
                val date = try { dateFormatter.parse(data.value) } catch (e: Exception) { null }
                calendar.time = date ?: Date()

                do {
                    calendar.add(Calendar.DAY_OF_YEAR, 1)
                } while (calendar.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY || calendar.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY)
                data.value = dateFormatter.format(calendar.time)
                soundManager.playPop()
            } catch (e: Exception) {
                uiEvent.value = "Erro ao navegar: ${e.message}"
            }
        }
    }

    fun onDateSelected(
        data: MutableStateFlow<String>
    ) {
        data.value = data.value.replace("/", "-")
        soundManager.playPop()
    }

    fun goToToday(
        data: MutableStateFlow<String>
    ) {
        data.value = dateFormatter.format(Date())
        soundManager.playPop()
    }

    fun moveHouseToDate(
        data: MutableStateFlow<String>,
        uiEvent: MutableStateFlow<String?>,
        uiState: MutableStateFlow<HomeUiState>,
        isAdmin: MutableStateFlow<Boolean>,
        agentName: MutableStateFlow<String>,
        remoteAgentUid: MutableStateFlow<String?>,
        currentUserUid: MutableStateFlow<String?>,
        housesInFlight: MutableStateFlow<List<House>>,
        pendingUpdateDrafts: MutableStateFlow<Map<Int, House>>,
        house: House,
        newDate: String,
        maxOpenHouses: Int,
        isDayClosed: Boolean
    ) {
        val isUnlocked = uiState.value.isManualUnlock

        if (isDayClosed && !isUnlocked && !isAdmin.value) {
            uiEvent.value = "O dia de ORIGEM está FECHADO. Desbloqueie primeiro."
            soundManager.playWarning()
            return
        }

        scope.launch {
            val uid = remoteAgentUid.value ?: currentUserUid.value ?: ""
            val dbHouses = repository.getHousesByDateAndAgent(newDate.replace("/", "-"), uid)
            val existingWorkedInDb = dbHouses.count { it.situation == Situation.NONE || it.situation == Situation.EMPTY }
            val houseIsWorked = house.situation == Situation.NONE || house.situation == Situation.EMPTY

            if (existingWorkedInDb >= maxOpenHouses && maxOpenHouses > 0 && houseIsWorked) {
                soundManager.playWarning()
                uiEvent.value = "Impossível mover: Meta Diária do dia de destino atingida!"
                return@launch
            }
            performMoveHouse(
                data = data,
                uiEvent = uiEvent,
                isAdmin = isAdmin,
                agentName = agentName,
                remoteAgentUid = remoteAgentUid,
                currentUserUid = currentUserUid,
                housesInFlight = housesInFlight,
                pendingUpdateDrafts = pendingUpdateDrafts,
                house = house,
                newDate = newDate
            )
        }
    }

    private fun performMoveHouse(
        data: MutableStateFlow<String>,
        uiEvent: MutableStateFlow<String?>,
        isAdmin: MutableStateFlow<Boolean>,
        agentName: MutableStateFlow<String>,
        remoteAgentUid: MutableStateFlow<String?>,
        currentUserUid: MutableStateFlow<String?>,
        housesInFlight: MutableStateFlow<List<House>>,
        pendingUpdateDrafts: MutableStateFlow<Map<Int, House>>,
        house: House,
        newDate: String
    ) {
        scope.launch {
            val uid = remoteAgentUid.value ?: currentUserUid.value ?: ""
            val destActivity = dayManagementUseCase.getDayActivity(newDate, uid)
            val isDestUnlocked = destActivity?.isManualUnlock == true
            if (dayManagementUseCase.isDateLocked(destActivity) && !isDestUnlocked && !isAdmin.value) {
                uiEvent.value = "A data de DESTINO está FECHADA. Acesse-a e destranque."
                soundManager.playWarning()
                return@launch
            }

            val updatedHouse = house.copy(
                data = newDate,
                agentName = agentName.value,
                agentUid = uid
            )

            val dbHouses = repository.getHousesByDateAndAgent(newDate, uid)
            val overlays = pendingUpdateDrafts.value.values.filter { it.data == newDate } +
                    housesInFlight.value.filter { it.data == newDate }
            val latestHouses = (dbHouses + overlays).distinctBy { it.id }

            val clashingHouse = clashDetector.findClash(updatedHouse, latestHouses)

            if (clashingHouse != null) {
                pendingUpdateDrafts.update { it + (updatedHouse.id to updatedHouse) }
                uiEvent.value = "Conflito detectado no destino! Resolva em vermelho."
                soundManager.playWarning()
                data.value = newDate
            } else {
                if (updatedHouse.id == 0) {
                    housesInFlight.update { inFlights ->
                        inFlights.map { if (it.listOrder == house.listOrder) updatedHouse else it }
                    }
                } else {
                    repository.updateHouse(updatedHouse.copy(isSynced = false, lastUpdated = System.currentTimeMillis()))
                }

                pendingUpdateDrafts.update { it - updatedHouse.id }

                uiEvent.value = "Imóvel movido com sucesso para $newDate"
                soundManager.playPop()
                data.value = newDate
            }
        }
    }

    fun moveHousesToDate(
        data: MutableStateFlow<String>,
        uiEvent: MutableStateFlow<String?>,
        remoteAgentUid: MutableStateFlow<String?>,
        currentUserUid: MutableStateFlow<String?>,
        housesInFlight: MutableStateFlow<List<House>>,
        oldDate: String,
        newDate: String
    ) {
        scope.launch {
            try {
                val currentUid = remoteAgentUid.value ?: currentUserUid.value

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

                housesInFlight.update { inFlights ->
                    inFlights.map {
                        if (it.data.replace("/", "-") == normalizedOldDate && it.agentUid == currentUid) {
                            it.copy(data = normalizedNewDate)
                        } else it
                    }
                }

                uiEvent.value = "Produção movida com sucesso!"
                soundManager.playPop()

                if (data.value.replace("/", "-") == normalizedOldDate) {
                    data.value = normalizedNewDate
                }

            } catch (e: Exception) {
                uiEvent.value = "Erro ao mover produção: ${e.message}"
                AppLogger.e("DayManagementViewModel", "Erro ao mover produção", e)
            }
        }
    }

    fun confirmMoveHouse(
        moveConfirmationData: MutableStateFlow<Pair<House, String>?>,
        data: MutableStateFlow<String>,
        uiEvent: MutableStateFlow<String?>,
        isAdmin: MutableStateFlow<Boolean>,
        agentName: MutableStateFlow<String>,
        remoteAgentUid: MutableStateFlow<String?>,
        currentUserUid: MutableStateFlow<String?>,
        housesInFlight: MutableStateFlow<List<House>>,
        pendingUpdateDrafts: MutableStateFlow<Map<Int, House>>
    ) {
        moveConfirmationData.value?.let { (house, date) ->
            performMoveHouse(
                data = data,
                uiEvent = uiEvent,
                isAdmin = isAdmin,
                agentName = agentName,
                remoteAgentUid = remoteAgentUid,
                currentUserUid = currentUserUid,
                housesInFlight = housesInFlight,
                pendingUpdateDrafts = pendingUpdateDrafts,
                house = house,
                newDate = date
            )
            moveConfirmationData.value = null
        }
    }

    fun dismissMoveConfirmation(moveConfirmationData: MutableStateFlow<Pair<House, String>?>) {
        moveConfirmationData.value = null
    }
}
