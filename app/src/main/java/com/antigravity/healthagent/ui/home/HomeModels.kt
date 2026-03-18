package com.antigravity.healthagent.ui.home

data class AuditSummary(
    val date: String,
    val totalWorked: Int,
    val totalTreated: Int,
    val totalClosed: Int,
    val totalRefused: Int,
    val totalAbsent: Int,
    val totalVacant: Int,
    val a1: Int,
    val a2: Int,
    val b: Int,
    val c: Int,
    val d1: Int,
    val d2: Int,
    val e: Int,
    val eliminados: Int,
    val totalLarvicide: Double
)

data class DayErrorSummary(
    val date: String, 
    val errorCount: Int
)

data class WeeklySummaryTotals(
    val totalHouses: Int = 0,
    val totalTratados: Int = 0,
    val totalFoci: Int = 0,
    val totalFechados: Int = 0,
    val totalRecusados: Int = 0,
    val totalAbsent: Int = 0,
    val totalVacant: Int = 0
)

data class DashboardTotals(
    val totalHouses: Int = 0,
    val a1: Int = 0,
    val a2: Int = 0,
    val b: Int = 0,
    val c: Int = 0,
    val d1: Int = 0,
    val d2: Int = 0,
    val e: Int = 0,
    val eliminados: Int = 0,
    val larvicida: Double = 0.0,
    val totalFocos: Int = 0,
    val totalRegisteredHouses: Int = 0,
    val recused: Int = 0,
    val absent: Int = 0,
    val closed: Int = 0,
    val vacant: Int = 0
)

data class DaySummary(
    val date: String,
    val totalHouses: Int,
    val status: String
)

data class BoletimSummary(
    val date: String,
    val agentName: String,
    val totals: DashboardTotals,
    val blocks: List<BlockSummary>,
    val status: String = ""
)

data class BlockSummary(
    val number: String,
    val sequence: String,
    val bairro: String,
    val isCompleted: Boolean,
    val isLocalidadeConcluded: Boolean,
    val totalHouses: Int = 0, // Abertos
    val totalVisits: Int = 0,  // Visitas
    val focos: Int = 0
)

data class HouseUiState(
    val house: com.antigravity.healthagent.data.local.model.House,
    val invalidFields: Set<String>,
    val highlightErrors: Boolean,
    val isTreated: Boolean,
    val blockDisplay: String,
    val formattedStreet: String,
    val treatmentShortSummary: String
)

data class BlockSegment(
    val blockNumber: String,
    val blockSequence: String,
    val startDate: String,
    val endDate: String,
    val isConcluded: Boolean,
    val conclusionDate: String?,
    val houses: List<com.antigravity.healthagent.data.local.model.House>
) {
    val label: String
        get() {
            val base = if (blockSequence.isNotBlank()) "$blockNumber / $blockSequence" else blockNumber
            return if (isConcluded) "$base (Concluído $conclusionDate)" else "$base (Em Aberto)"
        }
    
    val id: String
         get() = "${blockNumber}_${blockSequence}_${if(isConcluded) "C" else "O"}_${startDate}"
}
