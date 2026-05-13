package com.antigravity.healthagent.data.local.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.antigravity.healthagent.data.local.AppDatabase
import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.local.model.PropertyType
import com.antigravity.healthagent.data.local.model.Situation
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
                        bairro = "CENTRO",
                        blockNumber = blockNumber,
                        blockSequence = "A",
                        streetName = "RUA TESTE $blockIndex",
                        number = houseIndex.toString(),
                        sequence = 1,
                        complement = 0,
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
            blockNum = duplicatedHouse.blockNumber,
            blockSeq = duplicatedHouse.blockSequence,
            street = duplicatedHouse.streetName,
            num = duplicatedHouse.number,
            seq = duplicatedHouse.sequence,
            compl = duplicatedHouse.complement,
            bairro = duplicatedHouse.bairro,
            segment = duplicatedHouse.visitSegment
        )
        
        assertEquals("Deve detectar 1 clash (duplicidade) com a casa já existente", 1, clashCount)
    }
}
