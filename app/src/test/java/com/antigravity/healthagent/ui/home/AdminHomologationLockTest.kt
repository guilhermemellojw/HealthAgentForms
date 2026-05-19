package com.antigravity.healthagent.ui.home

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.antigravity.healthagent.data.local.AppDatabase
import com.antigravity.healthagent.data.local.dao.HouseDao
import com.antigravity.healthagent.data.local.dao.DayActivityDao
import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.local.model.DayActivity
import com.antigravity.healthagent.data.local.model.PropertyType
import com.antigravity.healthagent.data.local.model.Situation
import com.antigravity.healthagent.data.repository.HouseRepository
import com.antigravity.healthagent.domain.repository.AuthUser
import com.antigravity.healthagent.domain.repository.UserRole
import com.antigravity.healthagent.domain.repository.SyncRepository
import com.antigravity.healthagent.data.repository.StreetRepository
import com.antigravity.healthagent.domain.repository.AgentRepository
import com.antigravity.healthagent.domain.repository.LocalizationRepository
import com.antigravity.healthagent.data.settings.SettingsManager
import com.antigravity.healthagent.ui.semanal.WeeklySummaryViewModel
import com.antigravity.healthagent.utils.SoundManager
import com.antigravity.healthagent.data.backup.BackupManager
import com.antigravity.healthagent.domain.logger.AppLogger
import com.antigravity.healthagent.domain.usecase.*
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.antigravity.healthagent.domain.model.VisitAddress

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class AdminHomologationLockTest {

    private lateinit var database: AppDatabase
    private lateinit var houseDao: HouseDao
    private lateinit var dayActivityDao: DayActivityDao
    
    private val repository = mockk<HouseRepository>(relaxed = true)
    private val settingsManager = mockk<SettingsManager>(relaxed = true)
    private val soundManager = mockk<SoundManager>(relaxed = true)
    private val syncRepository = mockk<SyncRepository>(relaxed = true)
    private val saveHouseUseCase = mockk<SaveHouseUseCase>(relaxed = true)
    private val predictHouseValuesUseCase = mockk<PredictHouseValuesUseCase>(relaxed = true)
    private val recalculateVisitSegmentsUseCase = mockk<RecalculateVisitSegmentsUseCase>(relaxed = true)
    private val performLocalDatabaseMigrationUseCase = mockk<PerformLocalDatabaseMigrationUseCase>(relaxed = true)
    private val dayManagementUseCase = mockk<DayManagementUseCase>(relaxed = true)
    private val houseValidationUseCase = mockk<HouseValidationUseCase>(relaxed = true)
    private val streetRepository = mockk<StreetRepository>(relaxed = true)
    private val backupManager = mockk<BackupManager>(relaxed = true)
    private val generateTestDataUseCase = mockk<GenerateTestDataUseCase>(relaxed = true)
    private val cleanupBrokenHousesUseCase = mockk<CleanupBrokenHousesUseCase>(relaxed = true)
    private val agentRepository = mockk<AgentRepository>(relaxed = true)
    private val localizationRepository = mockk<LocalizationRepository>(relaxed = true)

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        houseDao = database.houseDao()
        dayActivityDao = database.dayActivityDao()

        // Default mock behaviors
        coEvery { repository.runInTransaction(any<suspend () -> Any>()) } answers {
            runBlocking { (args[0] as suspend () -> Any).invoke() }
        }
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
        database.close()
    }

    @Test
    fun testUpdateHouse_BlockedWhenHomologatedAndNotAdmin() = runBlocking {
        val originalHouse = House(
            id = 1,
            data = "18-05-2026",
            agentName = "Guilherme",
            agentUid = "user_123",
            address = VisitAddress("100", "RUA A", "001", "", 0, 0, ""),
            propertyType = PropertyType.R,
            situation = Situation.NONE,
            editedByAdmin = true // Homologated
        )

        // Mock auth and user settings to return a standard agent (not admin)
        val standardUser = AuthUser(
            uid = "user_123",
            email = "guilherme@health.gov",
            displayName = "Guilherme",
            photoUrl = null,
            role = UserRole.AGENT
        )
        every { settingsManager.cachedUser } returns flowOf(standardUser)
        every { settingsManager.remoteAgentUid } returns flowOf(null)
        every { settingsManager.remoteAgentName } returns flowOf(null)
        every { settingsManager.easyMode } returns flowOf(false)
        every { settingsManager.solarMode } returns flowOf(false)

        every { repository.getPersonalHousesFlow(any(), any()) } returns flowOf(listOf(originalHouse))
        every { repository.getAllHouses(any()) } returns flowOf(listOf(originalHouse))
        every { repository.allActivitiesFlow } returns flowOf(emptyList())

        val viewModel = HomeViewModel(
            repository = repository,
            settingsManager = settingsManager,
            soundManager = soundManager,
            syncRepository = syncRepository,
            saveHouseUseCase = saveHouseUseCase,
            predictHouseValuesUseCase = predictHouseValuesUseCase,
            recalculateVisitSegmentsUseCase = recalculateVisitSegmentsUseCase,
            performLocalDatabaseMigrationUseCase = performLocalDatabaseMigrationUseCase,
            dayManagementUseCase = dayManagementUseCase,
            houseValidationUseCase = houseValidationUseCase,
            streetRepository = streetRepository,
            backupManager = backupManager,
            generateTestDataUseCase = generateTestDataUseCase,
            cleanupBrokenHousesUseCase = cleanupBrokenHousesUseCase,
            agentRepository = agentRepository,
            localizationRepository = localizationRepository
        )

        // Force viewModel state loading
        testDispatcher.scheduler.advanceUntilIdle()

        // Attempt update
        val updatedHouse = originalHouse.copy(situation = Situation.F)
        viewModel.updateHouse(updatedHouse)

        // Verify update was blocked (never called repository.updateHouse)
        coVerify(exactly = 0) { repository.updateHouse(any(), any()) }
        coVerify(exactly = 0) { repository.updateHouse(updatedHouse, any()) }
        assertEquals("Este imóvel foi homologado por um administrador e não pode ser editado.", viewModel.uiEvent.value)
    }

    @Test
    fun testDeleteHouse_BlockedWhenHomologatedAndNotAdmin() = runBlocking {
        val originalHouse = House(
            id = 1,
            data = "18-05-2026",
            agentName = "Guilherme",
            agentUid = "user_123",
            address = VisitAddress("100", "RUA A", "001", "", 0, 0, ""),
            propertyType = PropertyType.R,
            situation = Situation.NONE,
            editedByAdmin = true // Homologated
        )

        val standardUser = AuthUser(
            uid = "user_123",
            email = "guilherme@health.gov",
            displayName = "Guilherme",
            photoUrl = null,
            role = UserRole.AGENT
        )
        every { settingsManager.cachedUser } returns flowOf(standardUser)
        every { settingsManager.remoteAgentUid } returns flowOf(null)
        every { settingsManager.remoteAgentName } returns flowOf(null)
        every { settingsManager.easyMode } returns flowOf(false)
        every { settingsManager.solarMode } returns flowOf(false)

        every { repository.getPersonalHousesFlow(any(), any()) } returns flowOf(listOf(originalHouse))
        every { repository.getAllHouses(any()) } returns flowOf(listOf(originalHouse))
        every { repository.allActivitiesFlow } returns flowOf(emptyList())

        val viewModel = HomeViewModel(
            repository = repository,
            settingsManager = settingsManager,
            soundManager = soundManager,
            syncRepository = syncRepository,
            saveHouseUseCase = saveHouseUseCase,
            predictHouseValuesUseCase = predictHouseValuesUseCase,
            recalculateVisitSegmentsUseCase = recalculateVisitSegmentsUseCase,
            performLocalDatabaseMigrationUseCase = performLocalDatabaseMigrationUseCase,
            dayManagementUseCase = dayManagementUseCase,
            houseValidationUseCase = houseValidationUseCase,
            streetRepository = streetRepository,
            backupManager = backupManager,
            generateTestDataUseCase = generateTestDataUseCase,
            cleanupBrokenHousesUseCase = cleanupBrokenHousesUseCase,
            agentRepository = agentRepository,
            localizationRepository = localizationRepository
        )

        testDispatcher.scheduler.advanceUntilIdle()

        // Attempt delete
        viewModel.deleteHouse(originalHouse)

        // Verify delete was blocked (never called repository.deleteHouse)
        coVerify(exactly = 0) { repository.deleteHouse(any(), any()) }
        assertEquals("Este imóvel foi homologado por um administrador e não pode ser excluído.", viewModel.uiEvent.value)
    }

    @Test
    fun testUpdateDayStatus_BlockedWhenHomologatedAndNotAdmin() = runBlocking {
        val date = "18-05-2026"
        val existingActivity = DayActivity(
            date = date,
            agentUid = "user_123",
            agentName = "Guilherme",
            status = "NORMAL",
            editedByAdmin = true // Homologated
        )

        val standardUser = AuthUser(
            uid = "user_123",
            email = "guilherme@health.gov",
            displayName = "Guilherme",
            photoUrl = null,
            role = UserRole.AGENT
        )
        every { settingsManager.cachedUser } returns flowOf(standardUser)
        every { settingsManager.remoteAgentUid } returns flowOf(null)
        every { settingsManager.remoteAgentName } returns flowOf(null)
        every { settingsManager.easyMode } returns flowOf(false)
        every { settingsManager.solarMode } returns flowOf(false)

        every { repository.getDayActivities(any(), any()) } returns flowOf(listOf(existingActivity))
        every { repository.allActivitiesFlow } returns flowOf(listOf(existingActivity))
        coEvery { repository.getAllDayActivitiesOnce(any()) } returns listOf(existingActivity)
        coEvery { repository.getDayActivity(date, any()) } returns existingActivity

        val viewModel = WeeklySummaryViewModel(
            repository = repository,
            settingsManager = settingsManager,
            soundManager = soundManager,
            dayManagementUseCase = dayManagementUseCase,
            syncRepository = syncRepository
        )

        testDispatcher.scheduler.advanceUntilIdle()

        // Attempt update status
        viewModel.updateDayStatus(date, "FERIADO")
        
        // Wait for coroutine to run
        testDispatcher.scheduler.advanceUntilIdle()

        // Verify update was blocked (never called repository.updateDayActivity)
        coVerify(exactly = 0) { repository.updateDayActivity(any(), any()) }
        assertTrue(viewModel.uiEvent.value?.contains("Este dia foi homologado por um administrador") == true)
    }
}
