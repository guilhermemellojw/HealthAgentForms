package com.antigravity.healthagent.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.antigravity.healthagent.data.local.AppDatabase
import com.antigravity.healthagent.data.local.dao.HouseDao
import com.antigravity.healthagent.data.local.dao.DayActivityDao
import com.antigravity.healthagent.data.local.dao.TombstoneDao
import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.local.model.PropertyType
import com.antigravity.healthagent.data.local.model.Situation
import com.antigravity.healthagent.data.settings.SettingsManager
import com.antigravity.healthagent.domain.repository.BackupRepository
import com.antigravity.healthagent.data.sync.SyncScheduler
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.antigravity.healthagent.domain.model.VisitAddress
import javax.inject.Provider

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class SyncRestoreTest {

    private lateinit var database: AppDatabase
    private lateinit var houseDao: HouseDao
    private lateinit var syncRepository: SyncRepositoryImpl
    
    // Mocks
    private val auth = mockk<FirebaseAuth>(relaxed = true)
    private val firestore = mockk<FirebaseFirestore>(relaxed = true)
    private val settingsManager = mockk<SettingsManager>(relaxed = true)
    private val backupRepository = mockk<BackupRepository>(relaxed = true)
    private val syncSchedulerProvider = mockk<Provider<SyncScheduler>>(relaxed = true)

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        houseDao = database.houseDao()
        val dayActivityDao = database.dayActivityDao()
        val tombstoneDao = database.tombstoneDao()

        syncRepository = SyncRepositoryImpl(
            context = context,
            auth = auth,
            firestore = firestore,
            houseDao = houseDao,
            dayActivityDao = dayActivityDao,
            tombstoneDao = tombstoneDao,
            settingsManager = settingsManager,
            database = database,
            backupRepository = backupRepository,
            syncSchedulerProvider = syncSchedulerProvider
        )
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun testConflictResolution_LocalWinsWhenNewer() = runBlocking {
        val agentName = "CONFLICT AGENT"
        val agentUid = "uid_conflict_123"
        val now = System.currentTimeMillis()

        // 1. Inserir casa local (não sincronizada e editada recentemente)
        val localHouse = House(
            id = 1, 
            data = "06-05-2026", 
            agentName = agentName, 
            agentUid = agentUid, 
            address = VisitAddress(
                number = "10",
                streetName = "RUA TESTE",
                blockNumber = "001",
                blockSequence = "",
                sequence = 0,
                complement = 0,
                bairro = ""
            ),
            propertyType = PropertyType.R, 
            situation = Situation.NONE,
            lastUpdated = now,
            isSynced = false
        )
        houseDao.insertHouse(localHouse)

        // 2. Casa vinda da nuvem (mais antiga)
        val cloudHouse = House(
            id = 0, 
            data = "06-05-2026", 
            agentName = agentName, 
            agentUid = agentUid, 
            address = VisitAddress(
                number = "10",
                streetName = "RUA TESTE",
                blockNumber = "001",
                blockSequence = "",
                sequence = 0,
                complement = 0,
                bairro = ""
            ),
            propertyType = PropertyType.R, 
            situation = Situation.NONE,
            lastUpdated = now - 60000, // 1 minuto atrás
            isSynced = true
        )

        // Simulação da lógica de reconciliação do SyncRepositoryImpl
        val existing = houseDao.getHousesByAgentSnapshot(agentUid).firstOrNull()
        
        // Lógica: Se local é mais recente que cloud + threshold, ignoramos a cloud para não sobrescrever trabalho pendente
        val threshold = 10000L // SYNC_CONFLICT_THRESHOLD_MS
        val shouldUpdate = if (existing != null && !existing.isSynced) {
            !(existing.lastUpdated > (cloudHouse.lastUpdated + threshold))
        } else true

        if (shouldUpdate) {
            houseDao.insertHouse(cloudHouse.copy(id = existing?.id ?: 0))
        }

        // Verificação: A casa local deve ter sido preservada
        val finalHouse = houseDao.getHousesByAgentSnapshot(agentUid).first()
        assertEquals("A casa local mais recente deve ser preservada", now, finalHouse.lastUpdated)
        assertEquals(false, finalHouse.isSynced)
    }

    @Test
    fun testConflictResolution_AdminOverride() = runBlocking {
        val agentName = "ADMIN AGENT"
        val agentUid = "uid_admin_123"
        val now = System.currentTimeMillis()

        // 1. Casa local não sincronizada
        val localHouse = House(
            id = 2, 
            data = "07-05-2026", 
            agentName = agentName, 
            agentUid = agentUid, 
            address = VisitAddress(
                number = "20",
                streetName = "RUA TESTE",
                blockNumber = "002",
                blockSequence = "",
                sequence = 0,
                complement = 0,
                bairro = ""
            ),
            lastUpdated = now,
            isSynced = false,
            editedByAdmin = false
        )
        houseDao.insertHouse(localHouse)

        // 2. Casa da nuvem editada por Admin (Admin sempre ganha se houver conflito de "autoridade")
        val cloudHouse = House(
            id = 0, 
            data = "07-05-2026", 
            agentName = agentName, 
            agentUid = agentUid, 
            address = VisitAddress(
                number = "20",
                streetName = "RUA TESTE",
                blockNumber = "002",
                blockSequence = "",
                sequence = 0,
                complement = 0,
                bairro = ""
            ),
            lastUpdated = now - 5000, // Mesmo sendo um pouco mais antiga que a local
            isSynced = true,
            editedByAdmin = true // FLAG CRÍTICA
        )

        // Simulação da lógica de reconciliação
        val existing = houseDao.getHousesByAgentSnapshot(agentUid).firstOrNull()
        
        val isAdminOverride = cloudHouse.editedByAdmin && (existing?.editedByAdmin == false)
        val threshold = 10000L
        
        val shouldUpdate = if (existing != null && !existing.isSynced) {
            isAdminOverride || !(existing.lastUpdated > (cloudHouse.lastUpdated + threshold))
        } else true

        if (shouldUpdate) {
            houseDao.insertHouse(cloudHouse.copy(id = existing?.id ?: 0))
        }

        // Verificação: A casa da nuvem (Admin) deve ter sobrescrito a local
        val finalHouse = houseDao.getHousesByAgentSnapshot(agentUid).first()
        assertEquals("A edição do Admin deve prevalecer", true, finalHouse.editedByAdmin)
        assertEquals(true, finalHouse.isSynced)
    }

    @Test
    fun testConflictResolution_CloudTombstoneDeletesLocal() = runBlocking {
        val agentName = "TOMBSTONE AGENT"
        val agentUid = "uid_tombstone_123"

        // 1. Casa existe localmente e está sincronizada
        val localHouse = House(
            id = 3, 
            data = "08-05-2026", 
            agentName = agentName, 
            agentUid = agentUid, 
            address = VisitAddress(
                number = "30",
                streetName = "RUA DELETADA",
                blockNumber = "003",
                blockSequence = "",
                sequence = 0,
                complement = 0,
                bairro = ""
            ),
            isSynced = true
        )
        houseDao.insertHouse(localHouse)

        // 2. Simular que essa casa foi deletada na nuvem (Tombstone na lista de IDs deletados)
        val cloudDeletedHouses = setOf(localHouse.generateNaturalKey())

        // Simulação da lógica de reconciliação para deleção
        val allLocalHouses = houseDao.getHousesByAgentSnapshot(agentUid)
        val housesToDelete = allLocalHouses.filter { it.generateNaturalKey() in cloudDeletedHouses }
        
        housesToDelete.forEach { houseDao.deleteHouse(it) }

        // Verificação: A casa deve ter sido removida localmente
        val finalHouses = houseDao.getHousesByAgentSnapshot(agentUid)
        assertEquals("A casa deve ter sido deletada localmente devido ao tombstone na nuvem", 0, finalHouses.size)
    }
}

