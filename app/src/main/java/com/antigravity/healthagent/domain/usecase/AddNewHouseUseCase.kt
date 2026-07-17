package com.antigravity.healthagent.domain.usecase

import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.local.model.PropertyType
import com.antigravity.healthagent.data.local.model.Situation
import com.antigravity.healthagent.domain.model.heal
import com.antigravity.healthagent.domain.model.DailyContext
import com.antigravity.healthagent.domain.model.VisitAddress
import com.antigravity.healthagent.domain.repository.HouseRepository
import com.antigravity.healthagent.domain.usecase.DayLockEnforcer.LockResult
import com.antigravity.healthagent.domain.usecase.RoleEnforcer.RoleResult
import com.antigravity.healthagent.utils.formatStreetName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

class AddNewHouseUseCase @Inject constructor(
    private val repository: HouseRepository,
    private val roleEnforcer: RoleEnforcer,
    private val dayLockEnforcer: DayLockEnforcer,
    private val predictHouseValuesUseCase: PredictHouseValuesUseCase,
    private val clashDetector: ClashDetector,
    private val recalculateVisitSegmentsUseCase: RecalculateVisitSegmentsUseCase,
    private val saveHouseUseCase: SaveHouseUseCase
) {

    sealed interface Result {
        data class Success(val newId: Long) : Result
        data class Blocked(val message: String) : Result
        data class LimitReached(val houseTemplate: House) : Result
        data class Error(val exception: Exception) : Result
    }

    data class Params(
        val agentName: String,
        val agentUid: String,
        val currentDate: String,
        val blockNumber: String,
        val streetName: String,
        val blockSequence: String,
        val bairro: String,
        val municipio: String,
        val categoria: String,
        val zona: String,
        val tipo: Int,
        val ciclo: String,
        val atividade: Int,
        val afterId: Int, // -2: append to end, -1: insert at the beginning (0), >0: insert after this ID
        val latestHousesList: List<House>,
        val isDayClosed: Boolean,
        val isManualUnlock: Boolean,
        val isAdmin: Boolean,
        val isSupervisor: Boolean,
        val maxOpenHouses: Int
    )

    fun generateHouseToInsert(params: Params): House {
        val currentDayHouses = params.latestHousesList.filter { it.data == params.currentDate }
        val fullyUpToDateHouses = params.latestHousesList
        val isDayEmpty = currentDayHouses.isEmpty()
        val prediction: PredictHouseValuesUseCase.HousePrediction
        var targetIndex = -1
        var template: House? = null

        if (params.afterId >= -1) {
            targetIndex = if (params.afterId == -1) -1 else currentDayHouses.indexOfFirst { it.id == params.afterId }
            template = if (targetIndex != -1) currentDayHouses[targetIndex] else currentDayHouses.firstOrNull()
            prediction = if (template != null) {
                predictHouseValuesUseCase.predictBasedOnHistory(fullyUpToDateHouses, template)
            } else {
                PredictHouseValuesUseCase.HousePrediction("", 0, 0, PropertyType.R, Situation.NONE)
            }
        } else {
            if (isDayEmpty) {
                val lastGlobalHouse = fullyUpToDateHouses.maxByOrNull { it.listOrder }
                if (lastGlobalHouse != null) {
                    template = lastGlobalHouse
                    prediction = predictHouseValuesUseCase.predictBasedOnHistory(fullyUpToDateHouses, lastGlobalHouse)
                } else {
                    prediction = PredictHouseValuesUseCase.HousePrediction("", 0, 0, PropertyType.EMPTY, Situation.NONE)
                }
            } else {
                prediction = predictHouseValuesUseCase.predictNextHouseValues(
                    fullyUpToDateHouses,
                    params.currentDate,
                    params.blockNumber.trim().uppercase(),
                    params.streetName.trim().formatStreetName()
                )
            }
        }

        val finalPropertyType = if (prediction.propertyType != PropertyType.EMPTY) {
            prediction.propertyType
        } else if (template?.propertyType != null && template.propertyType != PropertyType.EMPTY) {
            template.propertyType
        } else {
            PropertyType.R
        }

        val finalBairro = template?.address?.bairro?.takeIf { it.isNotBlank() } ?: params.bairro.trim().uppercase()
        val finalMunicipio = template?.context?.municipio?.takeIf { it.isNotBlank() } ?: params.municipio.trim().uppercase()
        val finalCategoria = template?.context?.categoria?.takeIf { it.isNotBlank() } ?: params.categoria.trim().uppercase()
        val finalZona = template?.context?.zona?.takeIf { it.isNotBlank() } ?: params.zona.trim().uppercase()
        val finalTipo = template?.context?.tipo ?: params.tipo
        val finalCiclo = template?.context?.ciclo?.takeIf { it.isNotBlank() } ?: params.ciclo.trim().uppercase()
        val finalAtividade = template?.context?.atividade ?: params.atividade

        val block = template?.address?.blockNumber ?: params.blockNumber
        val blockSeq = template?.address?.blockSequence ?: params.blockSequence
        val street = template?.address?.streetName ?: params.streetName

        val maxOrder = fullyUpToDateHouses.maxOfOrNull { it.listOrder } ?: 0L

        var predictedSegment = 0
        if (params.afterId < -1) {
            val newStreet = street.trim().formatStreetName()
            var lastStreetName = ""
            currentDayHouses.sortedBy { it.listOrder }.forEach { h ->
                val s = h.address.streetName.trim().uppercase()
                if (lastStreetName.isNotEmpty() && s != lastStreetName) {
                    predictedSegment++
                }
                lastStreetName = s
            }
            if (lastStreetName.isNotEmpty() && newStreet.uppercase() != lastStreetName) {
                predictedSegment++
            }
        }

        var houseToInsert = House(
            id = 0,
            address = VisitAddress(
                blockNumber = block.trim().uppercase(),
                blockSequence = blockSeq.trim().uppercase(),
                streetName = street.trim().formatStreetName(),
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
            agentName = params.agentName.trim().uppercase(),
            agentUid = params.agentUid,
            data = params.currentDate,
            visitSegment = predictedSegment,
            listOrder = maxOrder + 1
        )

        val healedHouse = houseToInsert.copy(situation = houseToInsert.situation.heal())

        return clashDetector.autoIncrementToAvoidClash(
            healedHouse,
            fullyUpToDateHouses,
            includeVisitSegment = (params.afterId < -1)
        )
    }

    suspend fun execute(params: Params, preparedHouse: House? = null): Result = withContext(Dispatchers.IO) {
        try {
            // 1. Role Enforcement
            val roleResult = roleEnforcer.enforce(
                isSupervisor = params.isSupervisor,
                isAdmin = params.isAdmin,
                actionDescription = "adicionar dados remotamente"
            )
            if (roleResult is RoleResult.Blocked) {
                return@withContext Result.Blocked(roleResult.message)
            }

            // 2. Day Lock Enforcement
            val lockResult = dayLockEnforcer.enforce(
                isDayClosed = params.isDayClosed,
                isManualUnlock = params.isManualUnlock,
                isAdmin = params.isAdmin,
                actionDescription = "adicionar"
            )
            if (lockResult is LockResult.Blocked) {
                return@withContext Result.Blocked(lockResult.message)
            }

            val currentDayHouses = params.latestHousesList.filter { it.data == params.currentDate }

            // 3. Safety Limit Check
            val currentTotal = currentDayHouses.size
            val safetyLimit = (params.maxOpenHouses * 3).coerceAtLeast(150)
            if (currentTotal >= safetyLimit && !params.isManualUnlock && !params.isAdmin) {
                val templateHouse = House(data = params.currentDate)
                return@withContext Result.LimitReached(templateHouse)
            }

            // 4. Generate or use prepared house
            val houseToInsert = preparedHouse ?: generateHouseToInsert(params)

            // 5. Save the house
            var newId: Long = 0
            if (params.afterId >= -1) {
                // Re-order and recalculate logic for addNewHouseAt
                val targetIndex = if (params.afterId == -1) -1 else currentDayHouses.indexOfFirst { it.id == params.afterId }
                val mutableList = currentDayHouses.filter { it.id != 0 }.sortedBy { it.listOrder }.toMutableList()
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
                    repository.updateHouses(others, params.isAdmin)
                }
                if (newlyAdded != null) {
                    newId = repository.insertHouse(newlyAdded, params.isAdmin)
                }
            } else {
                // Simple append logic
                newId = saveHouseUseCase.insertHouse(houseToInsert, params.latestHousesList, params.isAdmin)
            }

            Result.Success(newId)
        } catch (e: Exception) {
            Result.Error(e)
        }
    }
}
