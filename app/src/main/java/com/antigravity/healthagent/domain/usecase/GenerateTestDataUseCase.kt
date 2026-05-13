package com.antigravity.healthagent.domain.usecase

import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.local.model.PropertyType
import com.antigravity.healthagent.data.local.model.Situation
import com.antigravity.healthagent.data.local.model.DayActivity
import com.antigravity.healthagent.data.repository.HouseRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

class GenerateTestDataUseCase @Inject constructor(
    private val repository: HouseRepository
) {

    suspend operator fun invoke(
        agentName: String,
        agentUid: String,
        currentDate: String,
        bairro: String = "CENTRO",
        numberOfBlocks: Int = 5,
        housesPerBlock: Int = 20
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val houses = mutableListOf<House>()
            var listOrderCounter = 1000 // Iniciar com um list order alto para ficar no final
            
            // Garantir que a atividade do dia está aberta
            val currentActivity = repository.getDayActivity(currentDate, agentUid)
            if (currentActivity == null) {
                repository.updateDayActivity(
                    DayActivity(
                        date = currentDate,
                        agentName = agentName,
                        agentUid = agentUid,
                        status = "NORMAL",
                        isClosed = false,
                        isSynced = false
                    )
                )
            }

            for (blockIndex in 1..numberOfBlocks) {
                val blockNumber = String.format("%03d", blockIndex)
                for (houseIndex in 1..housesPerBlock) {
                    val house = House(
                        data = currentDate,
                        agentName = agentName,
                        agentUid = agentUid,
                        bairro = bairro,
                        blockNumber = blockNumber,
                        blockSequence = "A",
                        streetName = "RUA TESTE MOCK ${blockIndex}",
                        number = houseIndex.toString(),
                        sequence = 1,
                        complement = 0,
                        propertyType = PropertyType.R,
                        situation = Situation.NONE,
                        visitSegment = 1,
                        listOrder = listOrderCounter++.toLong(),
                        lastUpdated = System.currentTimeMillis()
                    )
                    houses.add(house)
                }
            }

            // Inserir em batch (a inserção normal do repository insere uma a uma ou temos batch?)
            // O HouseRepository tem insertHouse. Vamos inserir uma a uma para simular fluxo normal,
            // ou se tiver insertHouses, usamos. Como não tenho certeza da assinatura do insertHouses, 
            // iterar e inserir via repository.insertHouse() é mais seguro.
            for (house in houses) {
                repository.insertHouse(house)
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
