package com.antigravity.healthagent.domain.usecase

import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.local.model.PropertyType
import com.antigravity.healthagent.data.local.model.Situation
import com.antigravity.healthagent.data.repository.HouseRepository
import com.antigravity.healthagent.data.repository.StreetRepository
import com.antigravity.healthagent.utils.formatStreetName
import javax.inject.Inject

class HouseManagementUseCase @Inject constructor(
    private val repository: HouseRepository,
    private val streetRepository: StreetRepository,
    private val syncRepository: com.antigravity.healthagent.domain.repository.SyncRepository
) {

    suspend fun insertHouse(house: House) {
        val sanitized = sanitizeHouse(house)
        repository.insertHouse(sanitized)
        streetRepository.saveCustomStreet(sanitized.streetName, sanitized.bairro)
    }

    suspend fun updateHouse(house: House) {
        val sanitized = sanitizeHouse(house)
        repository.updateHouse(sanitized)
        streetRepository.saveCustomStreet(sanitized.streetName, sanitized.bairro)
    }

    suspend fun updateHouses(houses: List<House>) {
        repository.updateHouses(houses)
    }

    suspend fun deleteHouse(house: House) {
        repository.deleteHouse(house)
        syncRepository.recordHouseDeletion(house)
    }

    suspend fun deleteProduction(date: String, agentName: String) {
        repository.deleteProduction(date, agentName)
        syncRepository.recordActivityDeletion(date, agentName)
    }

    suspend fun updateHouseWithContext(
        house: House,
        allHouses: List<House>
    ): HouseUpdateResult {
        val originalHouse = allHouses.find { it.id == house.id }
        
        // 1. Sanitize Data
        val sanitized = sanitizeHouse(house)
        
        // 2. Check for localization changes
        val localizationChanged = originalHouse != null && (
            originalHouse.bairro != sanitized.bairro ||
            originalHouse.blockNumber != sanitized.blockNumber ||
            originalHouse.blockSequence != sanitized.blockSequence ||
            originalHouse.streetName != sanitized.streetName
        )
        
        val housesToUpdate = if (localizationChanged) {
            allHouses.filter { 
                it.data == sanitized.data && it.listOrder > sanitized.listOrder 
            }.map { 
                it.copy(
                    bairro = sanitized.bairro,
                    blockNumber = sanitized.blockNumber,
                    blockSequence = sanitized.blockSequence,
                    streetName = sanitized.streetName
                )
            }
        } else {
            emptyList()
        }
        
        return HouseUpdateResult(sanitized, housesToUpdate, localizationChanged)
    }

    data class HouseUpdateResult(
        val updatedHouse: House,
        val subsequentHouses: List<House>,
        val localizationChanged: Boolean
    )

    private fun sanitizeHouse(house: House): House {
        val totalDeposits = house.a1 + house.a2 + house.b + house.c + house.d1 + house.d2 + house.e
        val hasTreatment = totalDeposits > 0 || house.eliminados > 0 || house.larvicida > 0.0

        var situation = house.situation
        var a1 = house.a1; var a2 = house.a2; var b = house.b; var c = house.c; 
        var d1 = house.d1; var d2 = house.d2; var e = house.e; var elims = house.eliminados; 
        var larv = house.larvicida

        // Strict Consistency: ONLY Situation.NONE (Worked) can have treatment
        if (situation != Situation.NONE && hasTreatment) {
            // Clear treatment if situation changed from NONE to something else
            a1 = 0; a2 = 0; b = 0; c = 0; d1 = 0; d2 = 0; e = 0; elims = 0; larv = 0.0
        }

        return house.copy(
            blockNumber = house.blockNumber.trim().uppercase(),
            blockSequence = house.blockSequence.trim().uppercase(),
            streetName = house.streetName.trim().formatStreetName(),
            number = house.number.trim().uppercase(),
            municipio = house.municipio.trim().uppercase(),
            bairro = house.bairro.trim().formatStreetName(),
            agentName = house.agentName.trim().uppercase(),
            categoria = house.categoria.trim().uppercase(),
            zona = house.zona.trim().uppercase(),
            ciclo = house.ciclo.trim().uppercase(),
            situation = situation,
            data = house.data.replace("/", "-").trim(),
            a1 = a1, a2 = a2, b = b, c = c, d1 = d1, d2 = d2, e = e, eliminados = elims, larvicida = larv
        )
    }

    data class HousePrediction(
        val number: String,
        val sequence: Int?,
        val complement: Int?, // Added complement field
        val propertyType: PropertyType,
        val situation: Situation
    )

    fun predictNextHouseValues(
        houses: List<House>, 
        currentDate: String,
        blockNumber: String,
        streetName: String
    ): HousePrediction {
        // Filter by date AND context (Block/Street)
        val currentHouses = houses.filter { 
            it.data == currentDate && 
            it.blockNumber.equals(blockNumber, ignoreCase = true) &&
            it.streetName.equals(streetName, ignoreCase = true)
        }.sortedBy { it.listOrder }

        if (currentHouses.isEmpty()) {
            return HousePrediction("", null, null, PropertyType.EMPTY, Situation.NONE)
        }

        val last = currentHouses.last()

        // If this is the second house (only 1 exists so far), try to bridge with previous workday
        if (currentHouses.size == 1) {
            val previousHouse = houses
                .filter { 
                    it.data != currentDate && 
                    it.blockNumber.equals(blockNumber, ignoreCase = true) &&
                    it.streetName.equals(streetName, ignoreCase = true)
                }
                .maxByOrNull { it.listOrder }
            
            if (previousHouse != null) {
                return calculatePrediction(listOf(previousHouse, last), last)
            }
        }

        return calculatePrediction(currentHouses, last)
    }

    fun predictBasedOnHistory(
        allHouses: List<House>,
        referenceHouse: House
    ): HousePrediction {
        // Find other houses in the same context (Block/Street) as the reference house
        val contextHouses = allHouses.filter {
            it.blockNumber.equals(referenceHouse.blockNumber, ignoreCase = true) &&
            it.streetName.equals(referenceHouse.streetName, ignoreCase = true)
        }.sortedBy { it.listOrder } // Ensure global order

        return calculatePrediction(contextHouses, referenceHouse)
    }

    private fun calculatePrediction(
        contextHouses: List<House>,
        last: House
    ): HousePrediction {
        if (contextHouses.size < 2) {
             val nextNum = try {
                (last.number.toInt() + 1).toString()
            } catch (e: Exception) { "" }
            return HousePrediction(nextNum, last.sequence, last.complement, last.propertyType, Situation.NONE)
        }

        // Find the house immediately preceding 'last' in this context
        // We can't just take size-2 because 'last' might not be the last in the list (though usually is)
        val lastIndex = contextHouses.indexOfFirst { it.id == last.id }
        if (lastIndex < 1) {
             // Fallback if last is the first one found
             val nextNum = try {
                (last.number.toInt() + 1).toString()
            } catch (e: Exception) { "" }
            return HousePrediction(nextNum, last.sequence, last.complement, last.propertyType, Situation.NONE)
        }
        
        val preLast = contextHouses[lastIndex - 1]

        // Number prediction
        val nextNumber = try {
            val lastNum = last.number.toInt()
            val preLastNum = preLast.number.toInt()
            val diff = lastNum - preLastNum
            (lastNum + diff).toString()
        } catch (e: Exception) {
            try {
                (last.number.toInt() + 1).toString()
            } catch (e2: Exception) { last.number }
        }

        // Sequence prediction
        val nextSequence = if (last.number == preLast.number) {
            val lastSeq = last.sequence ?: 0
            val preLastSeq = preLast.sequence ?: 0
            val diff = lastSeq - preLastSeq
            val next = lastSeq + diff
            if (next > 0) next else null
        } else {
            // If numbers are different, see if sequence was constant
            if (last.sequence == preLast.sequence) last.sequence else null
        }
        
        // Complement Prediction
        val nextComplement = if (last.number == preLast.number) {
             // Same number logic
             if (last.sequence == preLast.sequence) {
                 // Same Number AND Sequence -> Predict Complement
                 val lastCompl = last.complement ?: 0
                 val preLastCompl = preLast.complement ?: 0
                 val diff = lastCompl - preLastCompl
                 // If diff is 0 (e.g. repeated manually), assume +1, else follow diff
                 val effectiveDiff = if (diff == 0) 1 else diff
                 val next = lastCompl + effectiveDiff
                 if (next > 0) next else null
             } else {
                 // Sequence number changed -> Reset complement (usually starts at 1 or null)
                 null
             }
        } else {
             // Number changed -> Check if complement was constant? Unlikely. Reset.
             null
        }

        // PropertyType prediction
        val nextPropertyType = if (last.propertyType == preLast.propertyType) last.propertyType else last.propertyType

        // Situation prediction -> Always NONE for new
        val nextSituation = Situation.NONE

        return HousePrediction(
            number = nextNumber,
            sequence = nextSequence,
            complement = nextComplement,
            propertyType = nextPropertyType,
            situation = nextSituation
        )
    }

    suspend fun migrateStreetNamesToFormat() {
        val allHouses = repository.getAllHousesOnce()
        val toUpdate = allHouses.filter { it.streetName != it.streetName.formatStreetName() }
        if (toUpdate.isNotEmpty()) {
            val updated = toUpdate.map { it.copy(streetName = it.streetName.formatStreetName()) }
            repository.updateHouses(updated)
        }
    }

    suspend fun migrateBairrosToTitleCase() {
        val allHouses = repository.getAllHousesOnce()
        val toUpdate = allHouses.filter { it.bairro != it.bairro.formatStreetName() }
        if (toUpdate.isNotEmpty()) {
            val updated = toUpdate.map { it.copy(bairro = it.bairro.formatStreetName()) }
            repository.updateHouses(updated)
        }
    }

    suspend fun migrateDateFormats() {
        val allHouses = repository.getAllHousesOnce()
        val housesToUpdate = allHouses.filter { it.data.contains("/") }
        
        if (housesToUpdate.isNotEmpty()) {
            android.util.Log.i("HouseManagement", "Migrating ${housesToUpdate.size} legacy date formats (/) to standard (-)")
            val updatedHouses = housesToUpdate.map { it.copy(data = it.data.replace("/", "-")) }
            repository.updateHouses(updatedHouses)
        }
        
        val allActivities = repository.getAllDayActivitiesOnce()
        val activitiesToUpdate = allActivities.filter { it.date.contains("/") }
        
        if (activitiesToUpdate.isNotEmpty()) {
             android.util.Log.i("HouseManagement", "Migrating ${activitiesToUpdate.size} legacy activity dates (/) to standard (-)")
             activitiesToUpdate.forEach { activity ->
                 val newDate = activity.date.replace("/", "-")
                 // Since date is part of primary key, we might need a special transaction or delete/insert
                 // Repository doesn't have a direct 'updatePrimaryKey' but SyncRepositoryImpl does some similar stuff.
                 // In Room, for primary key changes, usually we delete and re-insert.
                 repository.runInTransaction {
                     repository.deleteProduction(activity.date, activity.agentName)
                     repository.updateDayActivity(activity.copy(date = newDate))
                 }
             }
        }
    }
}
