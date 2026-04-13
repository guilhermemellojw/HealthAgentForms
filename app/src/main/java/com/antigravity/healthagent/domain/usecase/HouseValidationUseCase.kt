package com.antigravity.healthagent.domain.usecase

import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.local.model.PropertyType
import com.antigravity.healthagent.data.local.model.Situation
import javax.inject.Inject

class HouseValidationUseCase @Inject constructor() {

    data class ErrorDetail(
        val houseId: Int,
        val streetName: String,
        val location: String, // e.g. "Nº 123"
        val description: String,
        val isDuplicate: Boolean = false
    )

    data class ValidationResult(
        val isValid: Boolean,
        val errorHouseIds: Set<Int> = emptySet(),
        val errorDetails: List<ErrorDetail> = emptyList(),
        val dialogMessage: String? = null
    )

    fun validateCurrentDay(currentDate: String, allHouses: List<House>, strict: Boolean = true): ValidationResult {
        val currentHouses = allHouses.filter { it.data == currentDate }.sortedBy { it.listOrder }
        if (currentHouses.isEmpty()) return ValidationResult(isValid = true)

        val errorDetails = mutableListOf<ErrorDetail>()
        val errorHouseIds = mutableSetOf<Int>()

        // 1. Duplicate Validation (Bairro + Address, ignore Segment)
        val duplicateGroups = currentHouses.groupBy { house ->
            "${house.bairro.trim().uppercase()}_${house.blockNumber.trim().uppercase()}_${house.blockSequence.trim().uppercase()}_${house.streetName.trim().uppercase()}_${house.number.trim().uppercase()}_${house.sequence}_${house.complement}"
        }.filter { it.value.size > 1 }

        duplicateGroups.forEach { (_, houses) ->
            houses.forEach { house ->
                errorHouseIds.add(house.id)
                val parts = mutableListOf<String>()
                if (house.number.isNotBlank()) parts.add("Nº ${house.number}")
                if (house.sequence > 0) parts.add("Seq. ${house.sequence}")
                if (house.complement > 0) parts.add("Comp. ${house.complement}")
                
                val location = parts.joinToString(" - ").ifBlank { "Sem Identificação" }

                errorDetails.add(ErrorDetail(
                    houseId = house.id,
                    streetName = house.streetName,
                    location = location,
                    description = "Endereço Duplicado (Tudo igual: Nº, Seq e Comp)",
                    isDuplicate = true
                ))
            }
        }

        // 2. Field Validation
        currentHouses.forEach { house ->
            val invalidFields = getInvalidFields(house, strict)
            if (invalidFields.isNotEmpty()) {
                errorHouseIds.add(house.id)
                val missingFields = mutableListOf<String>()
                val isMissingNumAndSeq = house.number.isBlank() && house.sequence == 0
                if (isMissingNumAndSeq) missingFields.add("Número/Sequência")
                if (house.propertyType == PropertyType.EMPTY) missingFields.add("Tipo")
                // Situation.EMPTY is allowed and treated as "Aberto" (NONE)
                if (house.situation == Situation.EMPTY) {
                     // No longer considered missing if we treat it as Aberto, 
                     // but in strict mode we might want to encourage NONE.
                     // However, based on the requirement to turn it into open, we should allow it.
                }
                if (house.agentName.isBlank()) missingFields.add("Agente")
                if (house.bairro.isBlank()) missingFields.add("Bairro")
                if (house.streetName.isBlank()) missingFields.add("Logradouro")
                if (house.blockNumber.isBlank()) missingFields.add("Quarteirão")
                
                val totalDeposits = house.a1 + house.a2 + house.b + house.c + house.d1 + house.d2 + house.e
                val hasTreatment = totalDeposits > 0 || house.eliminados > 0 || house.larvicida > 0.0 || house.comFoco
                
                val isWorked = house.situation == Situation.NONE || house.situation == Situation.EMPTY
                if (!isWorked && hasTreatment) missingFields.add("Tratamento Indevido")
                if (house.larvicida > 0.0 && totalDeposits == 0) missingFields.add("Larvicida sem Depósitos")
                if (totalDeposits > 0 && house.larvicida == 0.0) missingFields.add("Depósitos sem Larvicida")
                
                val parts = mutableListOf<String>()
                if (house.number.isNotBlank()) parts.add("Nº ${house.number}")
                if (house.sequence > 0) parts.add("Seq. ${house.sequence}")
                if (house.complement > 0) parts.add("Comp. ${house.complement}")
                val location = parts.joinToString(" - ").ifBlank { "Sem Identificação" }

                errorDetails.add(ErrorDetail(
                    houseId = house.id,
                    streetName = house.streetName,
                    location = location,
                    description = "Pendências: ${missingFields.joinToString(", ")}"
                ))
            }
        }

        return ValidationResult(
            isValid = errorHouseIds.isEmpty(),
            errorHouseIds = errorHouseIds,
            errorDetails = errorDetails,
            dialogMessage = if (errorDetails.isNotEmpty()) "Existem pendências de dados" else null
        )
    }

    // Extracted from private HomeViewModel method
    fun isHouseValid(house: House, strict: Boolean = true): Boolean {
        return getInvalidFields(house, strict).isEmpty()
    }

    fun getInvalidFields(house: House, strict: Boolean = true): List<String> {
        val invalidFields = mutableListOf<String>()
        
        val hasNumberOrSeq = house.number.isNotBlank() || house.sequence > 0
        if (!hasNumberOrSeq) invalidFields.add("number")
        
        if (house.propertyType == PropertyType.EMPTY) invalidFields.add("propertyType")
        
        // Situation.EMPTY is no longer considered invalid as it's healed to NONE in data layers
        if (strict && house.situation == Situation.EMPTY) {
            // invalidFields.add("situation")
        }

        if (house.agentName.isBlank()) invalidFields.add("agentName")
        if (house.bairro.isBlank()) invalidFields.add("bairro")
        if (house.streetName.isBlank()) invalidFields.add("streetName")
        if (house.blockNumber.isBlank()) invalidFields.add("blockNumber")

        val totalDeposits = house.a1 + house.a2 + house.b + house.c + house.d1 + house.d2 + house.e
        val hasTreatment = totalDeposits > 0 || house.eliminados > 0 || house.larvicida > 0.0 || house.comFoco
        
        val isWorked = house.situation == Situation.NONE || house.situation == Situation.EMPTY
        if (!isWorked && hasTreatment) {
            invalidFields.add("situation_treatment")
        }

        if (house.larvicida > 0.0 && totalDeposits == 0) {
            invalidFields.add("larvicide_inspection")
        }

        if (totalDeposits > 0 && house.larvicida == 0.0) {
            invalidFields.add("treatment_without_larvicide")
        }

        return invalidFields
    }
    
}
