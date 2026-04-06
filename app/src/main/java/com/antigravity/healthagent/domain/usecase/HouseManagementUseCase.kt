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
    private val streetRepository: StreetRepository
) {

    suspend fun insertHouse(house: House, allHouses: List<House>): Long {
        return repository.runInTransaction {
            val sanitized = sanitizeHouse(house)
            val id = repository.insertHouse(sanitized)
            
            val dayHouses = allHouses.filter { it.data == sanitized.data }
            val withNewHouse = (dayHouses + sanitized.copy(id = id.toInt())).sortedBy { it.listOrder }
            val recalculated = recalculateVisitSegments(withNewHouse)
            repository.updateHouses(recalculated)
            streetRepository.saveCustomStreet(sanitized.streetName, sanitized.bairro)
            id
        }
    }

    suspend fun updateHouse(house: House, allHouses: List<House>) {
        repository.runInTransaction {
            val sanitized = sanitizeHouse(house)
            val originalHouse = allHouses.find { it.id == house.id }
            
            val affectedDates = mutableSetOf(sanitized.data)
            originalHouse?.let { affectedDates.add(it.data) }
            
            val housesToUpdate = allHouses.filter { it.data in affectedDates }.map {
                if (it.id == house.id) sanitized else it
            }
            
            val finalUpdated = housesToUpdate.groupBy { it.data }.flatMap { (date, dayHouses) ->
                recalculateVisitSegments(dayHouses.sortedBy { it.listOrder })
            }
            
            repository.updateHouses(finalUpdated)
            streetRepository.saveCustomStreet(sanitized.streetName, sanitized.bairro)
        }
    }

    suspend fun updateHouses(houses: List<House>) {
        // Assume these are already recalculated if coming from a flow that does it
        repository.updateHouses(houses)
    }

    suspend fun deleteHouse(house: House, allHouses: List<House>) {
        repository.runInTransaction {
            val dayHouses = allHouses.filter { it.data == house.data }
            repository.deleteHouse(house)
            val remaining = dayHouses.filter { it.id != house.id }.sortedBy { it.listOrder }
            val recalculated = recalculateVisitSegments(remaining)
            repository.updateHouses(recalculated)
        }
    }

    suspend fun deleteProduction(date: String, agentName: String) {
        repository.deleteProduction(date, agentName)
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
        
        val sequenceUpdated = if (localizationChanged) {
            allHouses.map { h ->
                if (h.id == house.id) {
                    sanitized
                } else if (h.data == sanitized.data && h.listOrder > sanitized.listOrder && originalHouse != null) {
                    // Propagate location change ONLY IF the subsequent house has the exact same ORIGINAL address
                    val matchesOriginal = h.bairro.equals(originalHouse.bairro, ignoreCase = true) &&
                                        h.blockNumber.equals(originalHouse.blockNumber, ignoreCase = true) &&
                                        h.blockSequence.equals(originalHouse.blockSequence, ignoreCase = true) &&
                                        h.streetName.equals(originalHouse.streetName, ignoreCase = true)
                    
                    if (matchesOriginal) {
                        val updated = h.copy(
                            bairro = sanitized.bairro,
                            blockNumber = sanitized.blockNumber,
                            blockSequence = sanitized.blockSequence,
                            streetName = sanitized.streetName,
                            isSynced = false,
                            lastUpdated = System.currentTimeMillis()
                        )
                        
                        // DEFERRED CLASH CHECK: Only propagate if it doesn't create a duplication in the DB.
                        // If it would clash, we skip propagation for THIS house to avoid data loss (REPLACE strategy).
                        val identityClash = allHouses.any { other ->
                            other.id != updated.id &&
                            other.data == updated.data &&
                            other.agentUid == updated.agentUid &&
                            other.agentName.equals(updated.agentName, ignoreCase = true) &&
                            other.blockNumber.equals(updated.blockNumber, ignoreCase = true) &&
                            other.blockSequence.equals(updated.blockSequence, ignoreCase = true) &&
                            other.streetName.equals(updated.streetName, ignoreCase = true) &&
                            other.number.equals(updated.number, ignoreCase = true) &&
                            other.sequence == updated.sequence &&
                            other.complement == updated.complement &&
                            other.bairro.equals(updated.bairro, ignoreCase = true) &&
                            other.visitSegment == updated.visitSegment
                        }
                        
                        if (!identityClash) {
                            h.copy(
                                bairro = sanitized.bairro,
                                blockNumber = sanitized.blockNumber,
                                blockSequence = sanitized.blockSequence,
                                streetName = sanitized.streetName
                            )
                        } else {
                            h
                        }
                    } else {
                        h
                    }
                } else {
                    h
                }
            }
        } else {
            allHouses.map { if (it.id == house.id) sanitized else it }
        }

        // 3. Recalculate segments for the affected day
        val dayToRecalculate = sanitized.data
        val affectedHouses = sequenceUpdated.filter { it.data == dayToRecalculate }.sortedBy { it.listOrder }
        val recalculatedDay = recalculateVisitSegments(affectedHouses)
        
        // Find the specific updated house in the recalculated list
        val finalUpdatedHouse = recalculatedDay.find { it.id == house.id } ?: sanitized
        
        // The list of "houses to update" should be those that changed
        val housesThatChanged = recalculatedDay.filter { dayHouse ->
            val original = allHouses.find { it.id == dayHouse.id }
            original == null || original != dayHouse
        }
        
        return HouseUpdateResult(finalUpdatedHouse, housesThatChanged, localizationChanged)
    }

    fun recalculateVisitSegments(houses: List<House>): List<House> {
        if (houses.isEmpty()) return emptyList()
        
        var currentSegment = 0
        var lastStreet = ""
        
        return houses.sortedBy { it.listOrder }.map { house ->
            val street = house.streetName.trim().uppercase()
            if (lastStreet.isNotEmpty() && street != lastStreet) {
                currentSegment++
            }
            lastStreet = street
            house.copy(visitSegment = currentSegment)
        }
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

        val normalizedNumber = house.number.trim().uppercase().let { 
            if (it == "0") "" else it 
        }
        val normalizedSequence = house.sequence
        val normalizedComplement = house.complement

        return house.copy(
            blockNumber = house.blockNumber.trim().uppercase(),
            blockSequence = house.blockSequence.trim().uppercase(),
            streetName = house.streetName.trim().formatStreetName(),
            number = normalizedNumber,
            sequence = normalizedSequence,
            complement = normalizedComplement,
            municipio = house.municipio.trim().uppercase(),
            bairro = house.bairro.trim().uppercase(),
            agentName = house.agentName.trim().uppercase(),
            categoria = house.categoria.trim().uppercase(),
            zona = house.zona.trim().uppercase(),
            ciclo = house.ciclo.trim().uppercase(),
            situation = situation,
            data = house.data.replace("/", "-").trim(),
            isSynced = false, // Force re-sync on every local edit
            lastUpdated = System.currentTimeMillis(),
            a1 = a1, a2 = a2, b = b, c = c, d1 = d1, d2 = d2, e = e, eliminados = elims, larvicida = larv
        )
    }

    data class HousePrediction(
        val number: String,
        val sequence: Int,
        val complement: Int, // Added complement field
        val propertyType: PropertyType,
        val situation: Situation
    )

    fun predictNextHouseValues(
        houses: List<House>, 
        currentDate: String,
        blockNumber: String,
        streetName: String
    ): HousePrediction {
        // Filter by context (Block/Street) REGARDLESS of date to enable cross-day trends.
        // CRITICAL: Filter out incomplete houses (blank numbers) to ensure reliable prediction bounds
        val contextHouses = houses.filter { 
            it.blockNumber.equals(blockNumber, ignoreCase = true) &&
            it.streetName.equals(streetName, ignoreCase = true) &&
            it.number.isNotBlank()
        }.sortedBy { it.listOrder }

        if (contextHouses.isEmpty()) {
            return HousePrediction("", 0, 0, PropertyType.EMPTY, Situation.NONE)
        }

        val last = contextHouses.last()
        return calculatePrediction(contextHouses, last)
    }

    fun predictBasedOnHistory(
        allHouses: List<House>,
        referenceHouse: House
    ): HousePrediction {
        // Find other houses in the same context (Block/Street) as the reference house
        // Filter out incomplete ones to avoid "empty" baseline predictions
        val contextHouses = allHouses.filter {
            it.blockNumber.equals(referenceHouse.blockNumber, ignoreCase = true) &&
            it.streetName.equals(referenceHouse.streetName, ignoreCase = true) &&
            it.number.isNotBlank()
        }.sortedBy { it.listOrder } // Ensure global order

        return calculatePrediction(contextHouses, referenceHouse)
    }

    private fun calculatePrediction(
        contextHouses: List<House>,
        last: House
    ): HousePrediction {
        val lastNum = last.number.filter { it.isDigit() }.toIntOrNull() ?: 0

        // Use listOrder + data for identity matching instead of object equality.
        val lastIndexInContext = contextHouses.indexOfFirst { it.listOrder == last.listOrder && it.data == last.data }
        
        // Single House Case: No history to establish a trend. Simple increment.
        if (contextHouses.size < 2 || lastIndexInContext < 1) {
             return when {
                 last.complement > 0 -> {
                     HousePrediction(last.number, last.sequence, last.complement + 1, last.propertyType, Situation.NONE)
                 }
                 last.sequence > 0 -> {
                     HousePrediction(last.number, last.sequence + 1, 0, last.propertyType, Situation.NONE)
                 }
                 lastNum > 0 -> {
                     HousePrediction((lastNum + 1).toString(), 0, 0, last.propertyType, Situation.NONE)
                 }
                 else -> HousePrediction(last.number, 0, 0, last.propertyType, Situation.NONE)
             }
        }

        val preLast = contextHouses[lastIndexInContext - 1]
        val preLastNum = preLast.number.filter { it.isDigit() }.toIntOrNull() ?: 0
        
        // Priority 1: House Number Trend (only if numbers were different)
        if (last.number != preLast.number) {
            val nextNumber = if (lastNum > 0 && preLastNum > 0) {
                val diff = lastNum - preLastNum
                val predicted = lastNum + diff
                val predictedValue = if (predicted >= 1 && predicted != lastNum) predicted else (lastNum + 1)
                
                // If both last and preLast had the same non-numeric suffix/prefix, try to preserve it
                val lastSuffix = last.number.filter { !it.isDigit() }
                val preLastSuffix = preLast.number.filter { !it.isDigit() }
                
                if (lastSuffix.isNotEmpty() && lastSuffix == preLastSuffix) {
                    last.number.replace(lastNum.toString(), predictedValue.toString())
                } else {
                    predictedValue.toString()
                }
            } else {
                if (lastNum > 0) (lastNum + 1).toString() else last.number
            }
            return HousePrediction(nextNumber, 0, 0, last.propertyType, Situation.NONE)
        }

        // --- SUB-UNIT PRIORITY ---
        // Numbers are identical, so we are working on sub-units.
        // We MUST check Complement first, as it is the deepest level.
        
        // Priority 2: Complement Increment
        if (last.complement > 0 || last.complement != preLast.complement) {
            return HousePrediction(last.number, last.sequence, last.complement + 1, last.propertyType, Situation.NONE)
        }

        // Priority 3: Sequence Increment
        if (last.sequence > 0 || last.sequence != preLast.sequence) {
             return HousePrediction(last.number, last.sequence + 1, 0, last.propertyType, Situation.NONE)
        }

        // Priority 4: Fallback (Numbers same, no seq/comp trend) -> Start a sequence
        return HousePrediction(last.number, 1, 0, last.propertyType, Situation.NONE)
    }

    suspend fun migrateStreetNamesToFormat() {
        val allHouses = repository.getAllHousesSnapshot()
        val toUpdate = allHouses.filter { it.streetName != it.streetName.formatStreetName() }
        if (toUpdate.isNotEmpty()) {
            val updated = toUpdate.map { it.copy(streetName = it.streetName.formatStreetName()) }
            repository.updateHouses(updated)
        }
    }

    suspend fun migrateBairrosToUppercase() {
        val allHouses = repository.getAllHousesSnapshot()
        val toUpdate = allHouses.filter { it.bairro != it.bairro.trim().uppercase() }
        if (toUpdate.isNotEmpty()) {
            val updated = toUpdate.map { it.copy(bairro = it.bairro.trim().uppercase()) }
            repository.updateHouses(updated)
        }
    }

    suspend fun migrateDateFormats() {
        val allHouses = repository.getAllHousesSnapshot()
        val housesToUpdate = allHouses.filter { it.data.contains("/") }
        
        if (housesToUpdate.isNotEmpty()) {
            android.util.Log.i("HouseManagement", "Migrating ${housesToUpdate.size} legacy date formats (/) to standard (-)")
            val updatedHouses = housesToUpdate.map { it.copy(data = it.data.replace("/", "-")) }
            repository.updateHouses(updatedHouses)
        }
        
        val allActivities = repository.getAllDayActivitiesSnapshot()
        val activitiesToUpdate = allActivities.filter { it.date.contains("/") }
        
        if (activitiesToUpdate.isNotEmpty()) {
             android.util.Log.i("HouseManagement", "Migrating ${activitiesToUpdate.size} legacy activity dates (/) to standard (-)")
             activitiesToUpdate.forEach { activity ->
                 val newDate = activity.date.replace("/", "-")
                 // Since date is part of primary key, we might need a special transaction or delete/insert
                 // Repository doesn't have a direct 'updatePrimaryKey' but SyncRepositoryImpl does some similar stuff.
                 // In Room, for primary key changes, usually we delete and re-insert.
                 repository.runInTransaction {
                     repository.deleteProduction(activity.date, activity.agentName, activity.agentUid)
                     repository.updateDayActivity(activity.copy(date = newDate))
                 }
             }
        }
    }
}
