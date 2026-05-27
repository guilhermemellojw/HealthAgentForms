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
import com.antigravity.healthagent.domain.repository.HouseRepository
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
import com.antigravity.healthagent.ui.home.delegates.*

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

    private val syncDelegate = mockk<SyncDelegate>(relaxed = true)
    private val dayNavigationDelegate = mockk<DayNavigationDelegate>(relaxed = true)
    private val dayClosingDelegate = mockk<DayClosingDelegate>(relaxed = true)
    private val validationDelegate = mockk<ValidationDelegate>(relaxed = true)
    private val remoteAgentDelegate = mockk<RemoteAgentDelegate>(relaxed = true)
    private val boletimDataDelegate = mockk<BoletimDataDelegate>(relaxed = true)
    private val initializationDelegate = mockk<InitializationDelegate>(relaxed = true)
    private val houseEditDelegate by lazy {
        HouseEditDelegate(
            repository = repository,
            saveHouseUseCase = saveHouseUseCase,
            predictHouseValuesUseCase = predictHouseValuesUseCase,
            recalculateVisitSegmentsUseCase = recalculateVisitSegmentsUseCase,
            clashDetector = ClashDetector(),
            dayLockEnforcer = DayLockEnforcer(),
            roleEnforcer = RoleEnforcer(),
            soundManager = soundManager
        )
    }

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
        every { repository.getParticipatoryHousesFlow(any()) } returns flowOf(emptyList())
        every { repository.getPersonalHousesFlow(any(), any()) } returns flowOf(emptyList())
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
        every { repository.getAllHousesSnapshotFlow() } returns flowOf(listOf(originalHouse))
        every { repository.getParticipatoryHousesFlow(any()) } returns flowOf(listOf(originalHouse))
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
            localizationRepository = localizationRepository,
            clashDetector = ClashDetector(),
            dayLockEnforcer = DayLockEnforcer(),
            roleEnforcer = RoleEnforcer(),
            syncDelegate = syncDelegate,
            dayNavigationDelegate = dayNavigationDelegate,
            dayClosingDelegate = dayClosingDelegate,
            validationDelegate = validationDelegate,
            remoteAgentDelegate = remoteAgentDelegate,
            boletimDataDelegate = boletimDataDelegate,
            initializationDelegate = initializationDelegate,
            houseEditDelegate = houseEditDelegate
        )

        // Force viewModel state loading
        testDispatcher.scheduler.advanceUntilIdle()

        // Attempt update
        val updatedHouse = originalHouse.copy(situation = Situation.F)
        viewModel.updateHouse(updatedHouse)

        // Verify update was blocked (never called repository.updateHouse)
        coVerify(exactly = 0) { repository.updateHouse(any(), any()) }
        coVerify(exactly = 0) { repository.updateHouse(updatedHouse, any()) }
        assertEquals("Este imóvel foi homologado por um administrador. Desbloqueie o dia para editá-lo.", viewModel.uiEvent.value)
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
        every { repository.getAllHousesSnapshotFlow() } returns flowOf(listOf(originalHouse))
        every { repository.getParticipatoryHousesFlow(any()) } returns flowOf(listOf(originalHouse))
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
            localizationRepository = localizationRepository,
            clashDetector = ClashDetector(),
            dayLockEnforcer = DayLockEnforcer(),
            roleEnforcer = RoleEnforcer(),
            syncDelegate = syncDelegate,
            dayNavigationDelegate = dayNavigationDelegate,
            dayClosingDelegate = dayClosingDelegate,
            validationDelegate = validationDelegate,
            remoteAgentDelegate = remoteAgentDelegate,
            boletimDataDelegate = boletimDataDelegate,
            initializationDelegate = initializationDelegate,
            houseEditDelegate = houseEditDelegate
        )

        testDispatcher.scheduler.advanceUntilIdle()

        // Attempt delete
        viewModel.deleteHouse(originalHouse)

        // Verify delete was blocked (never called repository.deleteHouse)
        coVerify(exactly = 0) { repository.deleteHouse(any(), any()) }
        assertEquals("Este imóvel foi homologado por um administrador. Desbloqueie o dia para exclui-lo.", viewModel.uiEvent.value)
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
            syncRepository = syncRepository,
            roleEnforcer = RoleEnforcer()
        )

        testDispatcher.scheduler.advanceUntilIdle()

        // Attempt update status
        viewModel.updateDayStatus(date, "FERIADO")
        
        // Wait for coroutine to run
        testDispatcher.scheduler.advanceUntilIdle()

        // Verify update was blocked (never called repository.updateDayActivity)
        assertEquals("Erro ao atualizar status: Este dia foi homologado por um administrador. Desbloqueie o dia para alterar o status.", viewModel.uiEvent.value)
    }

    @Test
    fun testUpdateHouse_AllowedWhenHomologatedAndManualUnlockActive() = runBlocking {
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
        every { repository.getAllHousesSnapshotFlow() } returns flowOf(listOf(originalHouse))
        every { repository.getParticipatoryHousesFlow(any()) } returns flowOf(listOf(originalHouse))
        every { repository.allActivitiesFlow } returns flowOf(emptyList())
        
        // Mock the manual unlock day activity flow
        val dayActivity = DayActivity(
            date = "18-05-2026",
            agentUid = "user_123",
            agentName = "Guilherme",
            status = "NORMAL",
            editedByAdmin = true,
            isManualUnlock = true // Manual Unlock Active!
        )
        every { repository.getDayActivityFlow(any(), any()) } returns flowOf(dayActivity)

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
            localizationRepository = localizationRepository,
            clashDetector = ClashDetector(),
            dayLockEnforcer = DayLockEnforcer(),
            roleEnforcer = RoleEnforcer(),
            syncDelegate = syncDelegate,
            dayNavigationDelegate = dayNavigationDelegate,
            dayClosingDelegate = dayClosingDelegate,
            validationDelegate = validationDelegate,
            remoteAgentDelegate = remoteAgentDelegate,
            boletimDataDelegate = boletimDataDelegate,
            initializationDelegate = initializationDelegate,
            houseEditDelegate = houseEditDelegate
        )

        viewModel.navigateToDate("18-05-2026")
        viewModel.uiState.value = viewModel.uiState.value.copy(isManualUnlock = true)
        testDispatcher.scheduler.advanceUntilIdle()

        // Attempt update
        val updatedHouse = originalHouse.copy(situation = Situation.F)
        viewModel.updateHouse(updatedHouse)

        testDispatcher.scheduler.advanceUntilIdle()

        // Verify update was allowed (called saveHouseUseCase.updateHouse)
        coVerify(exactly = 1) { saveHouseUseCase.updateHouse(any(), any(), any()) }
    }

    @Test
    fun testDeleteHouse_AllowedWhenHomologatedAndManualUnlockActive() = runBlocking {
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
        every { repository.getAllHousesSnapshotFlow() } returns flowOf(listOf(originalHouse))
        every { repository.getParticipatoryHousesFlow(any()) } returns flowOf(listOf(originalHouse))
        every { repository.allActivitiesFlow } returns flowOf(emptyList())

        // Mock the manual unlock day activity flow
        val dayActivity = DayActivity(
            date = "18-05-2026",
            agentUid = "user_123",
            agentName = "Guilherme",
            status = "NORMAL",
            editedByAdmin = true,
            isManualUnlock = true // Manual Unlock Active!
        )
        every { repository.getDayActivityFlow(any(), any()) } returns flowOf(dayActivity)

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
            localizationRepository = localizationRepository,
            clashDetector = ClashDetector(),
            dayLockEnforcer = DayLockEnforcer(),
            roleEnforcer = RoleEnforcer(),
            syncDelegate = syncDelegate,
            dayNavigationDelegate = dayNavigationDelegate,
            dayClosingDelegate = dayClosingDelegate,
            validationDelegate = validationDelegate,
            remoteAgentDelegate = remoteAgentDelegate,
            boletimDataDelegate = boletimDataDelegate,
            initializationDelegate = initializationDelegate,
            houseEditDelegate = houseEditDelegate
        )

        viewModel.navigateToDate("18-05-2026")
        viewModel.uiState.value = viewModel.uiState.value.copy(isManualUnlock = true)
        testDispatcher.scheduler.advanceUntilIdle()

        // Attempt delete
        viewModel.deleteHouse(originalHouse)

        testDispatcher.scheduler.advanceUntilIdle()

        // Verify delete was allowed (called saveHouseUseCase.deleteHouse)
        coVerify(exactly = 1) { saveHouseUseCase.deleteHouse(any(), any(), any()) }
    }

    @Test
    fun testUpdateDayStatus_AllowedWhenHomologatedAndManualUnlockActive() = runBlocking {
        val date = "18-05-2026"
        val existingActivity = DayActivity(
            date = date,
            agentUid = "user_123",
            agentName = "Guilherme",
            status = "NORMAL",
            editedByAdmin = true, // Homologated
            isManualUnlock = true // Manual Unlock Active!
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
            syncRepository = syncRepository,
            roleEnforcer = RoleEnforcer()
        )

        testDispatcher.scheduler.advanceUntilIdle()

        // Attempt update status
        viewModel.updateDayStatus(date, "FERIADO")
        
        // Wait for coroutine to run
        testDispatcher.scheduler.advanceUntilIdle()

        // Verify update was allowed (called repository.updateDayActivity)
        coVerify(exactly = 1) { repository.updateDayActivity(any(), any()) }
    }
}
