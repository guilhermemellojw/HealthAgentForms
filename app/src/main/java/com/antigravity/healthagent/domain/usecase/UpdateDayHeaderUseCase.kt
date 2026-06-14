package com.antigravity.healthagent.domain.usecase

import com.antigravity.healthagent.domain.repository.HouseRepository
import javax.inject.Inject

class UpdateDayHeaderUseCase @Inject constructor(
    private val repository: HouseRepository,
    private val saveHouseUseCase: SaveHouseUseCase
) {
    suspend operator fun invoke(
        agentUid: String,
        data: String,
        bairro: String,
        municipio: String,
        categoria: String,
        zona: String,
        tipo: Int,
        ciclo: String,
        atividade: Int
    ) {
        val dayHouses = repository.getHousesByDateAndAgent(data, agentUid)
        if (dayHouses.isNotEmpty()) {
            val updated = dayHouses.map { hh ->
                val finalBairro = if (hh.address.bairro.isBlank()) bairro.uppercase() else hh.address.bairro.uppercase()
                hh.copy(
                    address = hh.address.copy(bairro = finalBairro),
                    context = hh.context.copy(
                        municipio = municipio.uppercase(),
                        categoria = categoria.uppercase(),
                        zona = zona.uppercase(),
                        tipo = tipo,
                        ciclo = ciclo,
                        atividade = atividade
                    )
                )
            }
            saveHouseUseCase.updateHouses(updated)
        }
    }
}
