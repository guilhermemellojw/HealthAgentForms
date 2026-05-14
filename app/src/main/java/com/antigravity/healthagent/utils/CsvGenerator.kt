package com.antigravity.healthagent.utils

import com.antigravity.healthagent.data.local.model.House

object CsvGenerator {
    fun generateCsv(houses: List<House>): String {
        val builder = StringBuilder()
        
        // Header
        builder.append("Data,Agente,Município,Bairro,Quarteirão,Logradouro,Número,Sequência,Complemento,Tipo,Zona,Categoria,Ciclo,Atividade,Situação,Tipo Imóvel,A1,A2,B,C,D1,D2,E,Eliminados,Larvicida\n")

        // Data Rows
        houses.forEach { house ->
            builder.append("${house.data},")
            builder.append("${house.agentName},")
            builder.append("${house.context.municipio},")
            builder.append("${house.address.bairro},")
            builder.append("${house.address.blockNumber},")
            builder.append("${house.address.streetName.replace(",", " ")},") // Basic escape for commas
            builder.append("${house.address.number},")
            builder.append("${if (house.address.sequence == 0) "" else house.address.sequence},")
            builder.append("${if (house.address.complement == 0) "" else house.address.complement},")
            builder.append("${house.context.tipo},")
            builder.append("${house.context.zona},")
            builder.append("${house.context.categoria},")
            builder.append("${house.context.ciclo},")
            builder.append("${house.context.atividade},")
            builder.append("${house.situation.code},")
            builder.append("${house.propertyType.code},")
            
            // Treatment Stats
            builder.append("${house.treatment.a1},")
            builder.append("${house.treatment.a2},")
            builder.append("${house.treatment.b},")
            builder.append("${house.treatment.c},")
            builder.append("${house.treatment.d1},")
            builder.append("${house.treatment.d2},")
            builder.append("${house.treatment.e},")
            builder.append("${house.treatment.eliminados},")
            builder.append("${house.treatment.larvicida}")
            builder.append("\n")
        }
        
        return builder.toString()
    }
}
