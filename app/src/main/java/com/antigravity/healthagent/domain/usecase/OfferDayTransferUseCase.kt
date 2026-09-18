package com.antigravity.healthagent.domain.usecase

import com.antigravity.healthagent.domain.repository.DayTransfer
import com.antigravity.healthagent.domain.repository.DayTransferRepository
import com.antigravity.healthagent.domain.repository.HouseRepository
import javax.inject.Inject

class OfferDayTransferUseCase @Inject constructor(
    private val dayTransferRepository: DayTransferRepository,
    private val houseRepository: HouseRepository
) {

    suspend operator fun invoke(
        date: String,
        myUid: String,
        myName: String,
        targetName: String
    ): Result<DayTransfer> {
        val normalizedDate = date.replace("/", "-")
        val targetNorm = targetName.trim().uppercase()
        val myNorm = myName.trim().uppercase().replace(Regex("\\s+"), " ")

        if (myUid.isBlank() || myNorm.isBlank()) {
            return Result.failure(Exception("Não foi possível identificar sua conta. Refaça o login."))
        }
        if (targetNorm.isBlank()) {
            return Result.failure(Exception("Selecione um agente de destino"))
        }
        if (targetNorm == myNorm) {
            return Result.failure(Exception("Não é possível transferir um dia para si mesmo"))
        }

        val activity = houseRepository.getDayActivity(normalizedDate, myUid)
        if (activity?.isClosed == true && activity.isManualUnlock != true) {
            return Result.failure(Exception("O dia ${normalizedDate} está fechado. Desbloqueie-o para transferir."))
        }

        val houses = houseRepository.getHousesByDateAndAgent(normalizedDate, myUid)
        if (houses.isEmpty()) {
            return Result.failure(Exception("Não há produção cadastrada para $normalizedDate"))
        }

        return dayTransferRepository.offerDay(
            fromUid = myUid,
            fromName = myNorm,
            toAgentName = targetNorm,
            fromDate = normalizedDate,
            houseCount = houses.size
        )
    }
}