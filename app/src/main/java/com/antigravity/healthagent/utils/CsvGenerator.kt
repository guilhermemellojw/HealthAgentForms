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
            builder.append("${house.municipio},")
            builder.append("${house.bairro},")
            builder.append("${house.blockNumber},")
            builder.append("${house.streetName.replace(",", " ")},") // Basic escape for commas
            builder.append("${house.number},")
            builder.append("${house.sequence ?: ""},")
            builder.append("${house.complement ?: ""},")
            builder.append("${house.tipo},")
            builder.append("${house.zona},")
            builder.append("${house.categoria},")
            builder.append("${house.ciclo},")
            builder.append("${house.atividade},")
            builder.append("${house.situation.code},")
            builder.append("${house.propertyType.code},")
            
            // Treatment Stats
            builder.append("${house.a1},")
            builder.append("${house.a2},")
            builder.append("${house.b},")
            builder.append("${house.c},")
            builder.append("${house.d1},")
            builder.append("${house.d2},")
            builder.append("${house.e},")
            builder.append("${house.eliminados},")
            builder.append("${house.larvicida}")
            builder.append("\n")
        }
        
        return builder.toString()
    }
}
