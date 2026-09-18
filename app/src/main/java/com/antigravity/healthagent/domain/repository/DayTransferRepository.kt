package com.antigravity.healthagent.domain.repository

import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.local.model.DayActivity
import kotlinx.coroutines.flow.Flow

enum class DayTransferStatus(val label: String) {
    PENDING("Aguardando destino"),
    ACCEPTED("Recebido"),
    DECLINED("Recusado"),
    CANCELLED("Cancelado"),
    EXPIRED("Expirado")
}

/**
 * Transferência de um dia inteiro de produção entre agentes (agente → agente),
 * sem interferência do admin. Requer internet (só online).
 *
 * Fluxo: origem cria uma oferta PENDING em `day_transfers`; o destino aceita,
 * re-atribui as casas do dia e as insere localmente (sync de dono empurra para a
 * própria nuvem); a origem apaga o dia removido após o aceite.
 *
 * O doc é propositalmente leve (metadados + counts): as casas ficam na nuvem do
 * agente de origem e o destino as lê diretamente de `agents/{fromUid}/houses`
 * (leitura liberada a qualquer agente logado pelo firestore.rules).
 */
data class DayTransfer(
    val id: String = "",
    val fromUid: String = "",
    val fromName: String = "",
    val toUid: String = "",
    val toAgentName: String = "",
    val toKey: String = "",
    val fromDate: String = "",
    val finalDate: String = "",
    val status: String = DayTransferStatus.PENDING.name,
    val houseCount: Int = 0,
    val offeredAt: Long = 0,
    val acceptedAt: Long = 0
) {
    val statusEnum: DayTransferStatus
        get() = try { DayTransferStatus.valueOf(status.uppercase()) } catch (e: Exception) { DayTransferStatus.PENDING }
}

interface DayTransferRepository {

    /**
     * Cria a oferta com doc-id determinístico `{fromUid}_{date}_{toKey}`.
     * Falha se já existir uma oferta (PENDING) do mesmo dia para o mesmo destino.
     */
    suspend fun offerDay(
        fromUid: String,
        fromName: String,
        toAgentName: String,
        fromDate: String,
        houseCount: Int
    ): Result<DayTransfer>

    suspend fun fetchTransfer(transferId: String): Result<DayTransfer?>

    suspend fun fetchOutgoing(fromUid: String): Result<List<DayTransfer>>

    /** Ofertas PENDING recebidas pelo agente atual (por UID ou nome canônico). */
    suspend fun fetchIncoming(myUid: String, myName: String): Result<List<DayTransfer>>

    fun observeOutgoing(fromUid: String): Flow<List<DayTransfer>>

    fun observeIncoming(myUid: String, myName: String): Flow<List<DayTransfer>>

    /** Destino aceita a produção: marca ACCEPTED e registra o finalDate resolvido. */
    suspend fun markAccepted(transferId: String, finalDate: String, toUid: String): Result<Unit>

    /** Destino recusa a oferta. */
    suspend fun markDeclined(transferId: String): Result<Unit>

    /** Origem cancela uma oferta pendente. */
    suspend fun cancelOffer(transferId: String): Result<Unit>

    /** Apaga o documento da transferência (fim de ciclo após a origem concluir). */
    suspend fun deleteTransfer(transferId: String): Result<Unit>

    /** Lê as casas da data diretamente da nuvem do agente de origem. */
    suspend fun fetchSourceHouses(fromUid: String, fromName: String, fromDate: String): Result<List<House>>

    /** Lê o header do dia (day_activities) diretamente da nuvem do agente de origem. */
    suspend fun fetchSourceActivity(fromUid: String, fromName: String, fromDate: String): Result<DayActivity?>
}