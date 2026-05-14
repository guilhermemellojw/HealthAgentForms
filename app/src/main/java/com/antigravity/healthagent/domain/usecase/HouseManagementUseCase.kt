package com.antigravity.healthagent.domain.usecase

import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.local.model.PropertyType
import com.antigravity.healthagent.data.local.model.Situation
import com.antigravity.healthagent.domain.model.TreatmentData
import com.antigravity.healthagent.data.repository.HouseRepository
import com.antigravity.healthagent.data.repository.StreetRepository
import com.antigravity.healthagent.utils.formatStreetName
import com.antigravity.healthagent.utils.normalize
import javax.inject.Inject

class HouseManagementUseCase @Inject constructor(
    private val repository: HouseRepository,
    private val streetRepository: StreetRepository
) {

    suspend fun insertHouse(house: House, allHouses: List<House>, force: Boolean = false): Long {
        return repository.runInTransaction {
            val sanitized = sanitizeHouse(house)
            val id = repository.insertHouse(sanitized, force)
            
            val normalizedData = sanitized.data.replace("/", "-")
            val dayHouses = allHouses.filter { it.data.replace("/", "-") == normalizedData }
            val withNewHouse = (dayHouses + sanitized.copy(id = id.toInt())).sortedBy { it.listOrder }
            val recalculated = recalculateVisitSegments(withNewHouse)
            repository.updateHouses(recalculated, force)
            streetRepository.saveCustomStreet(sanitized.address.streetName, sanitized.address.bairro)
            id
        }
    }

    suspend fun updateHouse(house: House, allHouses: List<House>, force: Boolean = false) {
        repository.runInTransaction {
            val sanitized = sanitizeHouse(house)
            val originalHouse = allHouses.find { it.id == house.id }
            
            val affectedDates = mutableSetOf(sanitized.data.replace("/", "-"))
            originalHouse?.let { affectedDates.add(it.data.replace("/", "-")) }
            
            val housesToUpdate = allHouses.filter { it.data.replace("/", "-") in affectedDates }.map {
                if (it.id == house.id) sanitized else it
            }
            
            val finalUpdated = housesToUpdate.groupBy { it.data }.flatMap { (date, dayHouses) ->
                recalculateVisitSegments(dayHouses.sortedBy { it.listOrder })
            }
            
            repository.updateHouses(finalUpdated, force)
            streetRepository.saveCustomStreet(sanitized.address.streetName, sanitized.address.bairro)
        }
    }

    suspend fun updateHouses(houses: List<House>, force: Boolean = false) {
        // Assume these are already recalculated if coming from a flow that does it
        repository.updateHouses(houses, force)
    }

    suspend fun deleteHouse(house: House, allHouses: List<House>, force: Boolean = false) {
        repository.runInTransaction {
            val normalizedData = house.data.replace("/", "-")
            val dayHouses = allHouses.filter { it.data.replace("/", "-") == normalizedData }
            repository.deleteHouse(house, force)
            val remaining = dayHouses.filter { it.id != house.id }.sortedBy { it.listOrder }
            val recalculated = recalculateVisitSegments(remaining)
            repository.updateHouses(recalculated, force)
        }
    }

    suspend fun deleteProduction(date: String, agentUid: String, force: Boolean = false) {
        repository.deleteProduction(date, agentUid, force)
    }

    suspend fun updateHouseWithContext(
        house: House,
        allHouses: List<House>,
        baselineHouse: House? = null
    ): HouseUpdateResult {
        val originalHouse = baselineHouse ?: allHouses.find { it.id == house.id }
        
        // 1. Sanitize Data
        val sanitized = sanitizeHouse(house)
        
        // 2. Check for localization changes
        val localizationChanged = originalHouse != null && (
            originalHouse.address.bairro != sanitized.address.bairro ||
            originalHouse.address.blockNumber != sanitized.address.blockNumber ||
            originalHouse.address.blockSequence != sanitized.address.blockSequence ||
            originalHouse.address.streetName != sanitized.address.streetName
        )
        
        val sequenceUpdated = if (localizationChanged) {
            allHouses.map { h ->
                if (h.id == house.id) {
                    sanitized
                } else if (h.listOrder > sanitized.listOrder && originalHouse != null) {
                    // Propagate location change ONLY IF the subsequent house has the exact same ORIGINAL address
                    val matchesOriginal = h.address.bairro.equals(originalHouse.address.bairro, ignoreCase = true) &&
                                        h.address.blockNumber.equals(originalHouse.address.blockNumber, ignoreCase = true) &&
                                        h.address.blockSequence.equals(originalHouse.address.blockSequence, ignoreCase = true) &&
                                        h.address.streetName.equals(originalHouse.address.streetName, ignoreCase = true)
                    
                    if (matchesOriginal) {
                        val updated = h.copy(
                            address = h.address.copy(
                                bairro = sanitized.address.bairro,
                                blockNumber = sanitized.address.blockNumber,
                                blockSequence = sanitized.address.blockSequence,
                                streetName = sanitized.address.streetName
                            ),
                            isSynced = false,
                            lastUpdated = System.currentTimeMillis()
                        )
                        
                        // DEFERRED CLASH CHECK: Only propagate if it doesn't create a duplication in the DB.
                        val identityClash = allHouses.any { other ->
                            other.id != updated.id &&
                            other.data == updated.data &&
                            other.agentUid == updated.agentUid &&
                            other.agentName.equals(updated.agentName, ignoreCase = true) &&
                            other.address.blockNumber.equals(updated.address.blockNumber, ignoreCase = true) &&
                            other.address.blockSequence.equals(updated.address.blockSequence, ignoreCase = true) &&
                            other.address.streetName.equals(updated.address.streetName, ignoreCase = true) &&
                            other.address.number.equals(updated.address.number, ignoreCase = true) &&
                            other.address.sequence == updated.address.sequence &&
                            other.address.complement == updated.address.complement &&
                            other.address.bairro.equals(updated.address.bairro, ignoreCase = true) &&
                            other.visitSegment == updated.visitSegment
                        }
                        
                        if (!identityClash) {
                            updated // Use the fully updated object (isSynced=false)
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

        // 3. Recalculate segments ONLY if street name changed or specifically requested
        // This prevents automatic sequence/segment shifts on every minor edit
        val dayToRecalculate = sanitized.data.replace("/", "-")
        val affectedHouses = sequenceUpdated.filter { it.data.replace("/", "-") == dayToRecalculate }.sortedBy { it.listOrder }
        val recalculatedDay = if (localizationChanged) {
            recalculateVisitSegments(affectedHouses)
        } else {
            affectedHouses
        }
        
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
            val street = house.address.streetName.formatStreetName()
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
        val totalDeposits = house.treatment.totalDeposits
        val hasTreatment = totalDeposits > 0 || house.treatment.eliminados > 0 || house.treatment.larvicida > 0.0

        var situation = house.situation
        var a1 = house.treatment.a1; var a2 = house.treatment.a2; var b = house.treatment.b; var c = house.treatment.c; 
        var d1 = house.treatment.d1; var d2 = house.treatment.d2; var e = house.treatment.e; var elims = house.treatment.eliminados; 
        var larv = house.treatment.larvicida

        // Strict Consistency: ONLY Situation.NONE (Worked) can have treatment
        if (situation != Situation.NONE && hasTreatment) {
            // Clear treatment if situation changed from NONE to something else
            a1 = 0; a2 = 0; b = 0; c = 0; d1 = 0; d2 = 0; e = 0; elims = 0; larv = 0.0
        }

        val normalizedNumber = house.address.number.trim().uppercase() // Don't clear "0" while editing
        val normalizedSequence = house.address.sequence
        val normalizedComplement = house.address.complement

        return house.copy(
            address = house.address.copy(
                blockNumber = house.address.blockNumber.normalize(),
                blockSequence = house.address.blockSequence.normalize(),
                streetName = house.address.streetName.trim().formatStreetName(),
                number = normalizedNumber,
                sequence = normalizedSequence,
                complement = normalizedComplement,
                bairro = house.address.bairro.normalize()
            ),
            context = house.context.copy(
                municipio = house.context.municipio.normalize(),
                categoria = house.context.categoria.normalize(),
                zona = house.context.zona.normalize(),
                ciclo = house.context.ciclo.normalize()
            ),
            agentName = house.agentName.normalize(),
            situation = situation,
            data = house.data.replace("/", "-").trim(),
            isSynced = false, // Force re-sync on every local edit
            lastUpdated = if (house.lastUpdated > 0) house.lastUpdated else System.currentTimeMillis(),
            treatment = TreatmentData(a1 = a1, a2 = a2, b = b, c = c, d1 = d1, d2 = d2, e = e, eliminados = elims, larvicida = larv)
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
            val hBlock = it.address.blockNumber.trim().uppercase()
            val hStreet = it.address.streetName.trim().formatStreetName()
            hBlock == blockNumber && hStreet == streetName && 
            (it.address.number.isNotBlank() || it.address.sequence > 0 || it.address.complement > 0)
        }.sortedBy { it.listOrder } // CRITICAL: Strict global chronological order by listOrder

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
        val refBlock = referenceHouse.address.blockNumber.trim().uppercase()
        val refStreet = referenceHouse.address.streetName.trim().formatStreetName()
        val contextHouses = allHouses.filter {
            val hBlock = it.address.blockNumber.trim().uppercase()
            val hStreet = it.address.streetName.trim().formatStreetName()
            hBlock == refBlock && hStreet == refStreet &&
            (it.address.number.isNotBlank() || it.address.sequence > 0 || it.address.complement > 0)
        }.sortedBy { it.listOrder } // CRITICAL: Strict global chronological order by listOrder

        return calculatePrediction(contextHouses, referenceHouse)
    }

    private fun calculatePrediction(
        contextHouses: List<House>,
        last: House
    ): HousePrediction {
        val lastNum = last.address.number.filter { it.isDigit() }.toIntOrNull() ?: 0

        // Use listOrder + data for identity matching instead of object equality.
        val lastIndexInContext = contextHouses.indexOfFirst { it.listOrder == last.listOrder && it.data == last.data }
        
        // Single House Case: No history to establish a trend. Simple increment.
        if (contextHouses.size < 2 || lastIndexInContext < 1) {
             return when {
                 last.address.complement > 0 -> {
                     HousePrediction(last.address.number, last.address.sequence, last.address.complement + 1, last.propertyType, Situation.NONE)
                 }
                 last.address.sequence > 0 -> {
                     HousePrediction(last.address.number, last.address.sequence + 1, 0, last.propertyType, Situation.NONE)
                 }
                 lastNum > 0 -> {
                     HousePrediction((lastNum + 1).toString(), 0, 0, last.propertyType, Situation.NONE)
                 }
                 else -> {
                     // If number is non-numeric and no sequence exists, START a sequence 
                     // instead of duplicating the number, which causes natural key collisions.
                     HousePrediction(last.address.number, 1, 0, last.propertyType, Situation.NONE)
                 }
             }
        }

        val preLast = contextHouses[lastIndexInContext - 1]
        val preLastNum = preLast.address.number.filter { it.isDigit() }.toIntOrNull() ?: 0
        
        // Priority 1: House Number Trend (only if numbers were different)
        if (last.address.number != preLast.address.number && last.address.number.isNotBlank()) {
            val nextNumber = if (lastNum > 0 && preLastNum > 0) {
                val diff = lastNum - preLastNum
                val predicted = lastNum + diff
                val predictedValue = if (predicted >= 1 && predicted != lastNum) predicted else (lastNum + 1)
                
                // If both last and preLast had the same non-numeric suffix/prefix, try to preserve it
                val lastSuffix = last.address.number.filter { !it.isDigit() }
                val preLastSuffix = preLast.address.number.filter { !it.isDigit() }
                
                if (lastSuffix.isNotEmpty() && lastSuffix == preLastSuffix) {
                    val replaced = last.address.number.replace(lastNum.toString(), predictedValue.toString())
                    // Safety check: if replace didn't change anything (e.g. complex format), 
                    // fallback to just the number to avoid collision.
                    if (replaced == last.address.number) predictedValue.toString() else replaced
                } else {
                    predictedValue.toString()
                }
            } else {
                if (lastNum > 0) (lastNum + 1).toString() else last.address.number
            }
            
            // CRITICAL: If the predicted number is IDENTICAL to the last one (e.g. non-numeric fallback),
            // start a sequence to avoid immediate natural key collision.
            return if (nextNumber == last.address.number) {
                HousePrediction(nextNumber, 1, 0, last.propertyType, Situation.NONE)
            } else {
                HousePrediction(nextNumber, 0, 0, last.propertyType, Situation.NONE)
            }
        }

        // Priority 2: Complement Increment
        if (last.address.complement > 0 || last.address.complement != preLast.address.complement) {
            return HousePrediction(last.address.number, last.address.sequence, last.address.complement + 1, last.propertyType, Situation.NONE)
        }
 
        // Priority 3: Sequence Increment
        if (last.address.sequence > 0 || last.address.sequence != preLast.address.sequence) {
             return HousePrediction(last.address.number, last.address.sequence + 1, 0, last.propertyType, Situation.NONE)
        }
 
        // Priority 4: Fallback (Numbers same, no seq/comp trend) -> Start a sequence
        return HousePrediction(last.address.number, 1, 0, last.propertyType, Situation.NONE)
    }

    suspend fun migrateStreetNamesToFormat() {
        val allHouses = repository.getAllHousesSnapshot()
        val toUpdate = allHouses.filter { it.address.streetName != it.address.streetName.formatStreetName() }
        if (toUpdate.isNotEmpty()) {
            val updated = toUpdate.map { it.copy(address = it.address.copy(streetName = it.address.streetName.formatStreetName())) }
            repository.updateHouses(updated, force = true)
        }
    }

    suspend fun migrateBairrosToUppercase() {
        val allHouses = repository.getAllHousesSnapshot()
        val toUpdate = allHouses.filter { it.address.bairro != it.address.bairro.trim().uppercase() }
        if (toUpdate.isNotEmpty()) {
            val updated = toUpdate.map { it.copy(address = it.address.copy(bairro = it.address.bairro.trim().uppercase())) }
            repository.updateHouses(updated, force = true)
        }
    }

    suspend fun migrateDateFormats() {
        val allHouses = repository.getAllHousesSnapshot()
        val housesToUpdate = allHouses.filter { it.data.contains("/") }
        
        if (housesToUpdate.isNotEmpty()) {
            android.util.Log.i("HouseManagement", "Migrating ${housesToUpdate.size} legacy date formats (/) to standard (-)")
            val updatedHouses = housesToUpdate.map { it.copy(data = it.data.replace("/", "-")) }
            repository.updateHouses(updatedHouses, force = true)
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
                      repository.deleteProduction(activity.date, activity.agentUid, force = true)
                      repository.updateDayActivity(activity.copy(date = newDate))
                  }
             }
        }
    }
}
