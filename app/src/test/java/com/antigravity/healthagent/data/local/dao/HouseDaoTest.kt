package com.antigravity.healthagent.data.local.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.antigravity.healthagent.data.local.AppDatabase
import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.local.model.PropertyType
import com.antigravity.healthagent.data.local.model.Situation
import com.antigravity.healthagent.domain.model.VisitAddress
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class HouseDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var houseDao: HouseDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Criando o banco de dados diretamente na memória RAM (não salva no disco)
        database = Room.inMemoryDatabaseBuilder(
            context,
            AppDatabase::class.java
        )
        .allowMainThreadQueries() // Permitido apenas em testes
        .build()

        houseDao = database.houseDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun insertMassiveDataAndTestDuplication() = runBlocking {
        val agentName = "MOCK AGENT"
        val agentUid = "mock_uid_123"
        val data = "06-05-2026"
        val housesToInsert = mutableListOf<House>()

        // Gerando 5 quarteirões com 20 casas cada (100 casas)
        for (blockIndex in 1..5) {
            val blockNumber = String.format("%03d", blockIndex)
            for (houseIndex in 1..20) {
                housesToInsert.add(
                    House(
                        data = data,
                        agentName = agentName,
                        agentUid = agentUid,
                        address = VisitAddress(
                            bairro = "CENTRO",
                            blockNumber = blockNumber,
                            blockSequence = "A",
                            streetName = "RUA TESTE $blockIndex",
                            number = houseIndex.toString(),
                            sequence = 1,
                            complement = 0
                        ),
                        propertyType = PropertyType.R,
                        situation = Situation.NONE,
                        visitSegment = 1,
                        listOrder = ((blockIndex * 100) + houseIndex).toLong(),
                        lastUpdated = System.currentTimeMillis()
                    )
                )
            }
        }

        // 1. Inserir casas
        housesToInsert.forEach {
            houseDao.insertHouse(it)
        }

        // 2. Verificar se todas foram inseridas
        val retrievedHouses = houseDao.getHousesByAgentSnapshot(agentUid)
        assertEquals("Deve haver 100 casas inseridas", 100, retrievedHouses.size)

        // 3. Tentar inserir uma casa duplicada exata (deve conflitar pelo unique index do AppDatabase)
        val duplicatedHouse = housesToInsert[0]
        
        val clashCount = houseDao.checkClash(
            uid = duplicatedHouse.agentUid,
            date = duplicatedHouse.data,
            blockNum = duplicatedHouse.address.blockNumber,
            blockSeq = duplicatedHouse.address.blockSequence,
            street = duplicatedHouse.address.streetName,
            num = duplicatedHouse.address.number,
            seq = duplicatedHouse.address.sequence,
            compl = duplicatedHouse.address.complement,
            bairro = duplicatedHouse.address.bairro,
            segment = duplicatedHouse.visitSegment
        )
        
        assertEquals("Deve detectar 1 clash (duplicidade) com a casa já existente", 1, clashCount)
    }

    @Test
    fun benchmarkSequentialInsertPerformance() = runBlocking {
        val agentName = "PERF AGENT"
        val agentUid = "perf_uid_999"
        val date = "06-05-2026"
        val count = 50 // Test with 50 sequential additions

        // --- 1. CURRENT SLOW IMPLEMENTATION ---
        val startTimeCurrent = System.currentTimeMillis()
        for (i in 1..count) {
            val newHouse = House(
                data = date,
                agentName = agentName,
                agentUid = agentUid,
                address = VisitAddress(
                    bairro = "CENTRO",
                    blockNumber = "001",
                    blockSequence = "A",
                    streetName = "RUA TESTE",
                    number = i.toString(),
                    sequence = 1,
                    complement = 0
                ),
                propertyType = PropertyType.R,
                situation = Situation.NONE,
                visitSegment = 1,
                listOrder = i.toLong(),
                lastUpdated = System.currentTimeMillis()
            )
            val insertedId = houseDao.insertHouse(newHouse)

            // Simulating repository.updateHouses(allHouses)
            val dayHouses = houseDao.getHousesByDateAndAgent(date, agentUid)
            dayHouses.forEach { house ->
                // SELECT 1: Clash check
                houseDao.checkNaturalKeyConflict(
                    excludeId = house.id,
                    date = house.data,
                    agentUid = house.agentUid,
                    blockNumber = house.address.blockNumber,
                    blockSequence = house.address.blockSequence,
                    streetName = house.address.streetName,
                    number = house.address.number,
                    sequence = house.address.sequence,
                    complement = house.address.complement,
                    bairro = house.address.bairro,
                    visitSegment = house.visitSegment
                )
                // SELECT 2: Existing record check
                houseDao.getHouseById(house.id.toLong())
            }
            // Batch update all day houses
            houseDao.upsertHouses(dayHouses)
        }
        val endTimeCurrent = System.currentTimeMillis()
        val currentDuration = endTimeCurrent - startTimeCurrent

        // Clear DB for a clean optimized run
        database.clearAllTables()

        // --- 2. OPTIMIZED FAST IMPLEMENTATION ---
        val startTimeOpt = System.currentTimeMillis()
        for (i in 1..count) {
            val newHouse = House(
                data = date,
                agentName = agentName,
                agentUid = agentUid,
                address = VisitAddress(
                    bairro = "CENTRO",
                    blockNumber = "001",
                    blockSequence = "A",
                    streetName = "RUA TESTE",
                    number = i.toString(),
                    sequence = 1,
                    complement = 0
                ),
                propertyType = PropertyType.R,
                situation = Situation.NONE,
                visitSegment = 1,
                listOrder = i.toLong(),
                lastUpdated = System.currentTimeMillis()
            )
            val insertedId = houseDao.insertHouse(newHouse)

            // Simulating optimized repository.updateHouses(allHouses)
            val dayHouses = houseDao.getHousesByDateAndAgent(date, agentUid)

            // Step 1: Batch fetch existing database houses
            val existingHouses = houseDao.getHousesByDateAndAgent(date, agentUid)
            val existingMap = existingHouses.associateBy { it.id }

            // Step 2: Filter to find truly changed ones
            val changedHouses = dayHouses.filter { house ->
                val existing = existingMap[house.id]
                existing == null || 
                house.address != existing.address ||
                house.propertyType != existing.propertyType ||
                house.situation != existing.situation ||
                house.data != existing.data ||
                house.agentName != existing.agentName ||
                house.listOrder != existing.listOrder ||
                house.visitSegment != existing.visitSegment ||
                house.agentUid != existing.agentUid
            }

            // Step 3: Conflict checks, tombstone checks, and writes only for changed
            changedHouses.forEach { house ->
                houseDao.checkNaturalKeyConflict(
                    excludeId = house.id,
                    date = house.data,
                    agentUid = house.agentUid,
                    blockNumber = house.address.blockNumber,
                    blockSequence = house.address.blockSequence,
                    streetName = house.address.streetName,
                    number = house.address.number,
                    sequence = house.address.sequence,
                    complement = house.address.complement,
                    bairro = house.address.bairro,
                    visitSegment = house.visitSegment
                )
                houseDao.getHouseById(house.id.toLong())
            }
            if (changedHouses.isNotEmpty()) {
                houseDao.upsertHouses(changedHouses)
            }
        }
        val endTimeOpt = System.currentTimeMillis()
        val optDuration = endTimeOpt - startTimeOpt

        val factor = if (optDuration > 0) currentDuration.toDouble() / optDuration.toDouble() else currentDuration.toDouble()
        val factorStr = String.format(java.util.Locale.US, "%.2f", factor)
        println("=== BENCHMARK DE DESEMPENHO ===")
        println("Inserções Sequenciais de 50 Imóveis:")
        println("- Implementação Atual: ${currentDuration}ms")
        println("- Implementação Otimizada: ${optDuration}ms")
        println("- Fator de Melhoria: ${factorStr}x mais rápido!")
        println("===============================")
    }
}

