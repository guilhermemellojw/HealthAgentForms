package com.antigravity.healthagent.domain.usecase

import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.local.model.PropertyType
import com.antigravity.healthagent.data.local.model.Situation
import com.antigravity.healthagent.utils.formatStreetName
import javax.inject.Inject

class PredictHouseValuesUseCase @Inject constructor() {

    data class HousePrediction(
        val number: String,
        val sequence: Int,
        val complement: Int,
        val propertyType: PropertyType,
        val situation: Situation
    )

    fun predictNextHouseValues(
        houses: List<House>, 
        currentDate: String,
        blockNumber: String,
        streetName: String
    ): HousePrediction {
        val contextHouses = houses.filter { 
            val hBlock = it.address.blockNumber.trim().uppercase()
            val hStreet = it.address.streetName.trim().formatStreetName()
            hBlock == blockNumber && hStreet == streetName && 
            (it.address.number.isNotBlank() || it.address.sequence > 0 || it.address.complement > 0)
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
        val refBlock = referenceHouse.address.blockNumber.trim().uppercase()
        val refStreet = referenceHouse.address.streetName.trim().formatStreetName()
        val contextHouses = allHouses.filter {
            val hBlock = it.address.blockNumber.trim().uppercase()
            val hStreet = it.address.streetName.trim().formatStreetName()
            hBlock == refBlock && hStreet == refStreet &&
            (it.address.number.isNotBlank() || it.address.sequence > 0 || it.address.complement > 0)
        }.sortedBy { it.listOrder }

        return calculatePrediction(contextHouses, referenceHouse)
    }

    private fun calculatePrediction(
        contextHouses: List<House>,
        last: House
    ): HousePrediction {
        val lastNum = last.address.number.filter { it.isDigit() }.toIntOrNull() ?: 0
        val lastIndexInContext = contextHouses.indexOfFirst { it.listOrder == last.listOrder && it.data == last.data }
        
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
                     HousePrediction(last.address.number, 1, 0, last.propertyType, Situation.NONE)
                 }
             }
        }

        val preLast = contextHouses[lastIndexInContext - 1]
        val preLastNum = preLast.address.number.filter { it.isDigit() }.toIntOrNull() ?: 0
        
        if (last.address.number != preLast.address.number && last.address.number.isNotBlank()) {
            val nextNumber = if (lastNum > 0 && preLastNum > 0) {
                val diff = lastNum - preLastNum
                val predicted = lastNum + diff
                val predictedValue = if (predicted >= 1 && predicted != lastNum) predicted else (lastNum + 1)
                
                val lastSuffix = last.address.number.filter { !it.isDigit() }
                val preLastSuffix = preLast.address.number.filter { !it.isDigit() }
                
                if (lastSuffix.isNotEmpty() && lastSuffix == preLastSuffix) {
                    val replaced = last.address.number.replace(lastNum.toString(), predictedValue.toString())
                    if (replaced == last.address.number) predictedValue.toString() else replaced
                } else {
                    predictedValue.toString()
                }
            } else {
                if (lastNum > 0) (lastNum + 1).toString() else last.address.number
            }
            
            return if (nextNumber == last.address.number) {
                HousePrediction(nextNumber, 1, 0, last.propertyType, Situation.NONE)
            } else {
                HousePrediction(nextNumber, 0, 0, last.propertyType, Situation.NONE)
            }
        }

        if (last.address.complement > 0 || last.address.complement != preLast.address.complement) {
            return HousePrediction(last.address.number, last.address.sequence, last.address.complement + 1, last.propertyType, Situation.NONE)
        }
 
        if (last.address.sequence > 0 || last.address.sequence != preLast.address.sequence) {
             return HousePrediction(last.address.number, last.address.sequence + 1, 0, last.propertyType, Situation.NONE)
        }
 
        return HousePrediction(last.address.number, 1, 0, last.propertyType, Situation.NONE)
    }
}
