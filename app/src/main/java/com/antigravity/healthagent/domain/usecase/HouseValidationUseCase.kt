package com.antigravity.healthagent.domain.usecase

import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.local.model.PropertyType
import com.antigravity.healthagent.data.local.model.Situation
import com.antigravity.healthagent.domain.model.TreatmentData
import com.antigravity.healthagent.utils.normalize
import com.antigravity.healthagent.utils.formatStreetName
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
        val normalizedCurrentDate = currentDate.replace("/", "-")
        val currentHouses = allHouses.filter { it.data.replace("/", "-") == normalizedCurrentDate }.sortedBy { it.listOrder }
        if (currentHouses.isEmpty()) return ValidationResult(isValid = true)

        val errorDetails = mutableListOf<ErrorDetail>()
        val errorHouseIds = mutableSetOf<Int>()

        // 1. Duplicate Validation (Bairro + Address)
        val duplicateGroups = currentHouses.groupBy { house ->
            generateIdentitySignature(house)
        }.filter { it.value.size > 1 }

        duplicateGroups.forEach { (_, houses) ->
            houses.forEach { house ->
                errorHouseIds.add(house.id)
                errorDetails.add(ErrorDetail(
                    houseId = house.id,
                    streetName = house.address.streetName,
                    location = getFullAddressDisplay(house),
                    description = "Endereço Duplicado no mesmo segmento (Mova este imóvel ou altere o número)",
                    isDuplicate = true
                ))
            }
        }

        // 2. Field Validation
        currentHouses.forEach { house ->
            val invalidFields = getInvalidFields(house, strict)
            if (invalidFields.isNotEmpty()) {
                errorHouseIds.add(house.id)
                val missingFields = getMissingFieldLabels(house, invalidFields)
                
                errorDetails.add(ErrorDetail(
                    houseId = house.id,
                    streetName = house.address.streetName,
                    location = getFullAddressDisplay(house),
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

    fun isHouseValid(house: House, strict: Boolean = true): Boolean {
        return getInvalidFields(house, strict).isEmpty()
    }

    fun getInvalidFields(house: House, strict: Boolean = true): List<String> {
        val invalidFields = mutableListOf<String>()
        
        // Basic identification
        if (!house.address.isComplete) {
            if (house.address.number.isBlank() && house.address.sequence <= 0) invalidFields.add("number")
            if (house.address.bairro.isBlank()) invalidFields.add("bairro")
            if (house.address.streetName.isBlank()) invalidFields.add("streetName")
            if (house.address.blockNumber.isBlank()) invalidFields.add("blockNumber")
        }
        
        if (house.agentName.isBlank()) invalidFields.add("agentName")
        if (house.propertyType == PropertyType.EMPTY) invalidFields.add("propertyType")

        // Treatment logic
        val treatment = extractTreatmentData(house)
        val isWorked = house.situation == Situation.NONE || house.situation == Situation.EMPTY
        val totalDeposits = treatment.a1 + treatment.a2 + treatment.b + treatment.c + treatment.d1 + treatment.d2 + treatment.e

        if (!isWorked && treatment.hasAnyTreatment) {
            invalidFields.add("situation_treatment")
        }

        if (treatment.larvicida > 0.0 && totalDeposits == 0) {
            invalidFields.add("larvicide_inspection")
        }

        if (totalDeposits > 0 && treatment.larvicida == 0.0) {
            invalidFields.add("treatment_without_larvicide")
        }

        return invalidFields
    }

    private fun extractTreatmentData(house: House) = TreatmentData(
        a1 = house.treatment.a1, a2 = house.treatment.a2, b = house.treatment.b, c = house.treatment.c,
        d1 = house.treatment.d1, d2 = house.treatment.d2, e = house.treatment.e,
        eliminados = house.treatment.eliminados, larvicida = house.treatment.larvicida, comFoco = house.treatment.comFoco
    )

    private fun generateIdentitySignature(house: House): String {
        return "${house.address.generateAddressSignature()}|${house.visitSegment}".uppercase()
    }

    private fun getFullAddressDisplay(house: House): String {
        return house.address.fullIdDisplay
    }

    private fun getMissingFieldLabels(house: House, invalidFields: List<String>): List<String> {
        val labels = mutableListOf<String>()
        if (invalidFields.contains("number")) labels.add("Número/Sequência")
        if (invalidFields.contains("propertyType")) labels.add("Tipo")
        if (invalidFields.contains("agentName")) labels.add("Agente")
        if (invalidFields.contains("bairro")) labels.add("Bairro")
        if (invalidFields.contains("streetName")) labels.add("Logradouro")
        if (invalidFields.contains("blockNumber")) labels.add("Quarteirão")
        if (invalidFields.contains("situation_treatment")) labels.add("Tratamento Indevido")
        if (invalidFields.contains("larvicide_inspection")) labels.add("Larvicida sem Depósitos")
        if (invalidFields.contains("treatment_without_larvicide")) labels.add("Depósitos sem Larvicida")
        return labels
    }
}
