package com.antigravity.healthagent.domain.usecase

import com.antigravity.healthagent.data.local.model.DayActivity
import com.antigravity.healthagent.data.local.model.Situation
import com.antigravity.healthagent.domain.logger.AppLogger
import com.antigravity.healthagent.domain.repository.DayTransfer
import com.antigravity.healthagent.domain.repository.DayTransferRepository
import com.antigravity.healthagent.domain.repository.DayTransferStatus
import com.antigravity.healthagent.domain.repository.HouseRepository
import com.antigravity.healthagent.utils.TimeManager
import javax.inject.Inject

class AcceptDayTransferUseCase @Inject constructor(
    private val dayTransferRepository: DayTransferRepository,
    private val houseRepository: HouseRepository,
    private val dayManagementUseCase: DayManagementUseCase
) {

    private companion object {
        const val MAX_DATE_ATTEMPTS = 30
    }

    /**
     * Aceita a transferência: resolve o finalDate (data de origem se livre,
     * senão próximo dia útil livre), re-atribui as casas ao agente atual,
     * insere localmente e marca a oferta como ACCEPTED na nuvem.
     *
     * @return finalDate resolvido, ou falha com mensagem amigável.
     */
    suspend operator fun invoke(
        transfer: DayTransfer,
        myUid: String,
        myName: String,
        maxOpenHouses: Int = 0
    ): Result<String> {
        return try {
            val myNorm = myName.trim().uppercase().replace(Regex("\\s+"), " ")
            if (myUid.isBlank()) {
                return Result.failure(Exception("Não foi possível identificar sua conta. Refaça o login."))
            }
            if (transfer.statusEnum != DayTransferStatus.PENDING) {
                return Result.failure(Exception("Esta oferta já foi finalizada"))
            }
            if (transfer.toUid.isNotBlank() && transfer.toUid != myUid) {
                return Result.failure(Exception("Esta oferta não é destinada a você"))
            }
            if (transfer.toKey != myNorm && transfer.toUid != myUid) {
                return Result.failure(Exception("Esta oferta foi endereçada a outro agente"))
            }

            val normalizedFromDate = transfer.fromDate.replace("/", "-")

            val finalDate = resolveFinalDate(transfer, normalizedFromDate, myUid)
                ?: return Result.failure(Exception("Não foi encontrado um dia útil livre para receber esta produção"))

            val sourceHousesResult = dayTransferRepository.fetchSourceHouses(
                transfer.fromUid, transfer.fromName, normalizedFromDate
            )
            if (sourceHousesResult.isFailure) {
                return Result.failure(sourceHousesResult.exceptionOrNull() ?: Exception("Falha ao ler produção de origem"))
            }
            val sourceActivityResult = dayTransferRepository.fetchSourceActivity(
                transfer.fromUid, transfer.fromName, normalizedFromDate
            )
            if (sourceActivityResult.isFailure) {
                return Result.failure(sourceActivityResult.exceptionOrNull() ?: Exception("Falha ao ler cabeçalho de origem"))
            }

            val sourceHouses = sourceHousesResult.getOrNull().orEmpty()
            val sourceActivity = sourceActivityResult.getOrNull()

            if (sourceHouses.isEmpty() && sourceActivity == null) {
                return Result.failure(Exception("Nenhuma produção encontrada em $normalizedFromDate na nuvem de ${transfer.fromName}"))
            }

            if (maxOpenHouses > 0 && sourceHouses.isNotEmpty()) {
                val destHouses = houseRepository.getHousesByDateAndAgent(finalDate, myUid)
                val destWorked = destHouses.count { it.situation == Situation.NONE || it.situation == Situation.EMPTY }
                val incomingWorked = sourceHouses.count { it.situation == Situation.NONE || it.situation == Situation.EMPTY }
                if (destWorked + incomingWorked > maxOpenHouses) {
                    return Result.failure(
                        Exception("Este dia excede o teto diário de $maxOpenHouses imóveis trabalhados (${destWorked + incomingWorked}). Escolha outro destino/rota.")
                    )
                }
            }

            val now = TimeManager.currentTimeMillis()
            val reassignedHouses = sourceHouses.map { house ->
                house.copy(
                    id = 0,
                    uuid = "",
                    agentUid = myUid,
                    agentName = myNorm,
                    data = finalDate,
                    isSynced = false,
                    lastUpdated = now,
                    editedByAdmin = false,
                    localidadeConcluida = false,
                    quarteiraoConcluido = false
                ).apply { cloudId = null }
            }

            houseRepository.runInTransaction {
                if (reassignedHouses.isNotEmpty()) {
                    houseRepository.updateHouses(reassignedHouses, force = false)
                }
                val existingHeader = houseRepository.getDayActivity(finalDate, myUid)
                if (existingHeader == null && sourceActivity != null) {
                    houseRepository.updateDayActivity(
                        DayActivity(
                            date = finalDate,
                            status = sourceActivity.status,
                            isClosed = false,
                            isManualUnlock = false,
                            agentName = myNorm,
                            agentUid = myUid,
                            isSynced = false,
                            editedByAdmin = false,
                            lastUpdated = now
                        ),
                        force = false
                    )
                }
            }

            val markResult = dayTransferRepository.markAccepted(transfer.id, finalDate, myUid)
            if (markResult.isFailure) {
                AppLogger.e("AcceptDayTransfer", "Falha ao marcar ACCEPTED (dados já inseridos localmente)")
                return Result.failure(
                    markResult.exceptionOrNull() ?: Exception("Dados inseridos, mas falha ao confirmar o aceite. Tente novamente.")
                )
            }

            Result.success(finalDate)
        } catch (e: Exception) {
            AppLogger.e("AcceptDayTransfer", "Erro ao aceitar transferência", e)
            Result.failure(e)
        }
    }

    /**
     * Data de origem se livre; caso contrário, itera pelos próximos dias úteis
     * (pula SAB/DOM e FERIADO/TEMPO CHUVOSO via getNextBusinessDay) até achar
     * um sem header nem casas do destino.
     */
    private suspend fun resolveFinalDate(transfer: DayTransfer, fromDate: String, myUid: String): String? {
        val normalized = fromDate.replace("/", "-")
        if (!isOccupied(normalized, myUid)) return normalized

        var current = normalized
        for (i in 0 until MAX_DATE_ATTEMPTS) {
            val candidate = dayManagementUseCase.getNextBusinessDay(current, myUid)
            if (candidate.isBlank()) return null
            if (candidate == current) return null
            current = candidate
            if (!isOccupied(current, myUid)) return current
        }
        return null
    }

    private suspend fun isOccupied(date: String, myUid: String): Boolean {
        val activity = houseRepository.getDayActivity(date, myUid)
        if (activity != null) return true
        return houseRepository.getHousesByDateAndAgent(date, myUid).isNotEmpty()
    }
}