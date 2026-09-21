package com.antigravity.healthagent.utils

import com.antigravity.healthagent.data.local.model.House

object BoletimDataMapper {

    data class BlockStats(
        val workedBlocks: List<Pair<String, String>>,
        val completedBlocks: List<Pair<String, String>>,
        val quarteiraoConcluido: Boolean,
        val localidadeConcluida: Boolean
    )

    fun createChunks(houses: List<House>): List<List<House>> {
        val sortedHouses = houses
        val houseChunks = mutableListOf<List<House>>()

        if (sortedHouses.isNotEmpty()) {
            var currentBairro = sortedHouses.first().address.bairro.trim().uppercase()
            var currentGroup = mutableListOf<House>()

            for (house in sortedHouses) {
                val houseBairro = house.address.bairro.trim().uppercase()
                val messageChanged = !houseBairro.equals(currentBairro, ignoreCase = true)
                val groupFull = currentGroup.size >= 20

                if (messageChanged || groupFull) {
                    houseChunks.add(currentGroup)
                    currentGroup = mutableListOf()
                    currentBairro = houseBairro
                }
                currentGroup.add(house)
            }
            if (currentGroup.isNotEmpty()) {
                houseChunks.add(currentGroup)
            }
        }

        return if (houseChunks.isEmpty()) listOf(emptyList()) else houseChunks
    }

    fun calculateBlockStats(allHouses: List<House>, chunk: List<House>): BlockStats {
        if (chunk.isEmpty()) return BlockStats(emptyList(), emptyList(), false, false)

        val blockToLastIndex = mutableMapOf<String, Int>()
        allHouses.forEachIndexed { index, h ->
            val key = "${h.address.blockNumber}|${h.address.blockSequence}|${h.address.bairro.trim().uppercase()}"
            blockToLastIndex[key] = index
        }

        val chunkHouseIds = chunk.map { it.id }.toSet()
        val workedBlocks = chunk.map { Pair(it.address.blockNumber, it.address.blockSequence) }.distinct()
        val currentBairro = chunk.firstOrNull()?.address?.bairro ?: ""
        val completedBlocks = mutableListOf<Pair<String, String>>()

        workedBlocks.forEach { (bNum, bSeq) ->
            val blockHousesInChunk = chunk.filter { it.address.blockNumber == bNum && it.address.blockSequence == bSeq }
            val hasManual = blockHousesInChunk.any { it.quarteiraoConcluido }

            val key = "$bNum|$bSeq|${currentBairro.trim().uppercase()}"
            val lastIndexInFull = blockToLastIndex[key] ?: -1

            val lastHouseInFull = if (lastIndexInFull != -1) allHouses[lastIndexInFull] else null
            val isLastHouseInChunk = lastHouseInFull != null && chunkHouseIds.contains(lastHouseInFull.id)
            val hasSuccessor = lastIndexInFull != -1 && lastIndexInFull < allHouses.size - 1

            val autoConcluido = isLastHouseInChunk && hasSuccessor

            if (hasManual || autoConcluido) {
                completedBlocks.add(Pair(bNum, bSeq))
            }
        }

        val quarteiraoConcluido = completedBlocks.isNotEmpty()
        val localidadeConcluida = chunk.any { it.localidadeConcluida }

        return BlockStats(workedBlocks, completedBlocks, quarteiraoConcluido, localidadeConcluida)
    }
}
