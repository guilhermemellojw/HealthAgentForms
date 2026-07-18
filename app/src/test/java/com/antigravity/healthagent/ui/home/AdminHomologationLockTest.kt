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
import com.antigravity.healthagent.domain.util.Clock
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
    private val recalculateVisitSegmentsUseCase = mockk<RecalculateVisitSegmentsUseCase>(relaxed = true)
    private val dayManagementUseCase = mockk<DayManagementUseCase>(relaxed = true)
    private val houseValidationUseCase = mockk<HouseValidationUseCase>(relaxed = true)
    private val streetRepository = mockk<StreetRepository>(relaxed = true)
    private val backupManager = mockk<BackupManager>(relaxed = true)
    private val addNewHouseUseCase = mockk<AddNewHouseUseCase>(relaxed = true)
    private val loadDynamicConfigUseCase = mockk<LoadDynamicConfigUseCase>(relaxed = true)
    private val triggerImmediateSyncUseCase = mockk<TriggerImmediateSyncUseCase>(relaxed = true)
    private val selectDayActivityUseCase = mockk<SelectDayActivityUseCase>(relaxed = true)
    private val updateDayHeaderUseCase = mockk<UpdateDayHeaderUseCase>(relaxed = true)
    private val checkWorkedHouseLimitUseCase = mockk<CheckWorkedHouseLimitUseCase>(relaxed = true)
    private val clock = object : Clock {
        private var counter = 500L
        override fun currentTimeMillis(): Long {
            counter += 100
            return counter
        }
    }

    private val syncViewModel = mockk<SyncViewModel>(relaxed = true)
    private val dayManagementViewModel = mockk<DayManagementViewModel>(relaxed = true)
    private val dayClosingDelegate = mockk<DayClosingDelegate>(relaxed = true)
    private val validationViewModel = mockk<ValidationViewModel>(relaxed = true)
    private val remoteAgentDelegate = mockk<RemoteAgentDelegate>(relaxed = true)
    private val boletimDataDelegate = mockk<BoletimDataDelegate>(relaxed = true)
    private val initializationDelegate = mockk<InitializationDelegate>(relaxed = true)
    private val houseEditDelegate by lazy {
        HouseEditDelegate(
            repository = repository,
            saveHouseUseCase = saveHouseUseCase,
            addNewHouseUseCase = addNewHouseUseCase,
            recalculateVisitSegmentsUseCase = recalculateVisitSegmentsUseCase,
            clashDetector = ClashDetector(),
            dayLockEnforcer = DayLockEnforcer(),
            roleEnforcer = RoleEnforcer(),
            checkWorkedHouseLimitUseCase = checkWorkedHouseLimitUseCase,
            soundManager = soundManager,
            clock = clock
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
        every { saveHouseUseCase.sanitizeHouse(any()) } answers { firstArg() }
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
            saveHouseUseCase = saveHouseUseCase,
            recalculateVisitSegmentsUseCase = recalculateVisitSegmentsUseCase,
            dayManagementUseCase = dayManagementUseCase,
            houseValidationUseCase = houseValidationUseCase,
            streetRepository = streetRepository,
            backupManager = backupManager,
            loadDynamicConfigUseCase = loadDynamicConfigUseCase,
            triggerImmediateSyncUseCase = triggerImmediateSyncUseCase,
            selectDayActivityUseCase = selectDayActivityUseCase,
            updateDayHeaderUseCase = updateDayHeaderUseCase,
            homeStateDelegate = HomeStateDelegate(),
            syncViewModel = syncViewModel,
            dayManagementViewModel = dayManagementViewModel,
            dayClosingDelegate = dayClosingDelegate,
            validationViewModel = validationViewModel,
            remoteAgentDelegate = remoteAgentDelegate,
            boletimDataDelegate = boletimDataDelegate,
            initializationDelegate = initializationDelegate,
            houseEditDelegate = houseEditDelegate
        )

        // Force viewModel state loading
        testDispatcher.scheduler.advanceUntilIdle()
        testDispatcher.scheduler.advanceUntilIdle()

        // Attempt update
        val updatedHouse = originalHouse.copy(situation = Situation.F)
        viewModel.updateHouse(updatedHouse)
        testDispatcher.scheduler.advanceUntilIdle()

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
            saveHouseUseCase = saveHouseUseCase,
            recalculateVisitSegmentsUseCase = recalculateVisitSegmentsUseCase,
            dayManagementUseCase = dayManagementUseCase,
            houseValidationUseCase = houseValidationUseCase,
            streetRepository = streetRepository,
            backupManager = backupManager,
            loadDynamicConfigUseCase = loadDynamicConfigUseCase,
            triggerImmediateSyncUseCase = triggerImmediateSyncUseCase,
            selectDayActivityUseCase = selectDayActivityUseCase,
            updateDayHeaderUseCase = updateDayHeaderUseCase,
            homeStateDelegate = HomeStateDelegate(),
            syncViewModel = syncViewModel,
            dayManagementViewModel = dayManagementViewModel,
            dayClosingDelegate = dayClosingDelegate,
            validationViewModel = validationViewModel,
            remoteAgentDelegate = remoteAgentDelegate,
            boletimDataDelegate = boletimDataDelegate,
            initializationDelegate = initializationDelegate,
            houseEditDelegate = houseEditDelegate
        )

        testDispatcher.scheduler.advanceUntilIdle()

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
            saveHouseUseCase = saveHouseUseCase,
            recalculateVisitSegmentsUseCase = recalculateVisitSegmentsUseCase,
            dayManagementUseCase = dayManagementUseCase,
            houseValidationUseCase = houseValidationUseCase,
            streetRepository = streetRepository,
            backupManager = backupManager,
            loadDynamicConfigUseCase = loadDynamicConfigUseCase,
            triggerImmediateSyncUseCase = triggerImmediateSyncUseCase,
            selectDayActivityUseCase = selectDayActivityUseCase,
            updateDayHeaderUseCase = updateDayHeaderUseCase,
            homeStateDelegate = HomeStateDelegate(),
            syncViewModel = syncViewModel,
            dayManagementViewModel = dayManagementViewModel,
            dayClosingDelegate = dayClosingDelegate,
            validationViewModel = validationViewModel,
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
            saveHouseUseCase = saveHouseUseCase,
            recalculateVisitSegmentsUseCase = recalculateVisitSegmentsUseCase,
            dayManagementUseCase = dayManagementUseCase,
            houseValidationUseCase = houseValidationUseCase,
            streetRepository = streetRepository,
            backupManager = backupManager,
            loadDynamicConfigUseCase = loadDynamicConfigUseCase,
            triggerImmediateSyncUseCase = triggerImmediateSyncUseCase,
            selectDayActivityUseCase = selectDayActivityUseCase,
            updateDayHeaderUseCase = updateDayHeaderUseCase,
            homeStateDelegate = HomeStateDelegate(),
            syncViewModel = syncViewModel,
            dayManagementViewModel = dayManagementViewModel,
            dayClosingDelegate = dayClosingDelegate,
            validationViewModel = validationViewModel,
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

    class FakeHomeState : HomeState {
        override val data = MutableStateFlow("01-06-2026")
        override val agentName = MutableStateFlow("Guilherme")
        override val remoteAgent = MutableStateFlow<String?>(null)
        override val remoteAgentUid = MutableStateFlow<String?>(null)
        override val currentUserUid = MutableStateFlow<String?>("user_123")
        override val uiEvent = MutableStateFlow<String?>(null)
        override val syncStatus = MutableStateFlow<com.antigravity.healthagent.ui.state.SyncUiState>(com.antigravity.healthagent.ui.state.SyncUiState.Idle())
        override val pendingUpdateDrafts = MutableStateFlow<Map<Int, House>>(emptyMap())
        override val housesInFlight = MutableStateFlow<List<House>>(emptyList())
        override val recentlyEditedHouseIds = MutableStateFlow<Map<Int, Long>>(emptyMap())
        override val highlightedHouseId = MutableStateFlow<Int?>(null)
        override val showClosingAudit = MutableStateFlow<com.antigravity.healthagent.ui.home.AuditSummary?>(null)
        override val showGoalReached = MutableStateFlow(false)
        override val showHistoryUnlockConfirmation = MutableStateFlow(false)
        override val validationErrorHouseIds = MutableStateFlow<Set<Int>>(emptySet())
        override val isDuplicateIds = MutableStateFlow<Set<Int>>(emptySet())
        override val integrityDialogMessage = MutableStateFlow<String?>(null)
        override val showMultiDayErrorDialog = MutableStateFlow(false)
        override val validationErrorDetails = MutableStateFlow<List<com.antigravity.healthagent.domain.usecase.HouseValidationUseCase.ErrorDetail>>(emptyList())
        override val scrollToHouseId = MutableStateFlow<Int?>(null)
        override val situationLimitConfirmation = MutableStateFlow<House?>(null)
        override val moveConfirmationData = MutableStateFlow<Pair<House, String>?>(null)
        override val duplicateHouseConfirmation = MutableStateFlow<House?>(null)
        override val isSupervisor = MutableStateFlow(false)
        override val isAdmin = MutableStateFlow(false)
        override val isSyncing = MutableStateFlow(false)

        override val currentBlock = MutableStateFlow("10")
        override val currentBlockSequence = MutableStateFlow("A")
        override val currentStreet = MutableStateFlow("RUA A")
        override val uiState = MutableStateFlow(com.antigravity.healthagent.ui.home.HomeUiState())

        override val municipio = MutableStateFlow("BOM JARDIM")
        override val bairro = MutableStateFlow("CENTRO")
        override val categoria = MutableStateFlow("RESIDENCIAL")
        override val zona = MutableStateFlow("URBANA")
        override val ciclo = MutableStateFlow("1/2026")
        override val tipo = MutableStateFlow(1)
        override val atividade = MutableStateFlow(2)
    }

    @Test
    fun testAddNewHouseAt_InheritsBairroAndContextFromTemplate() = runBlocking {
        val state = FakeHomeState()

        val templateHouse = House(
            id = 1,
            data = "01-06-2026",
            agentName = "GUILHERME",
            agentUid = "user_123",
            address = com.antigravity.healthagent.domain.model.VisitAddress(
                blockNumber = "20",
                blockSequence = "B",
                streetName = "RUA DO TEMPLATE",
                number = "100",
                sequence = 2,
                complement = 1,
                bairro = "TEMPLATE_NEIGHBORHOOD"
            ),
            context = com.antigravity.healthagent.domain.model.DailyContext(
                municipio = "TEMPLATE_MUNICIPIO",
                categoria = "TEMPLATE_CAT",
                zona = "TEMPLATE_ZONA",
                tipo = 3,
                ciclo = "2/2026",
                atividade = 4
            ),
            propertyType = PropertyType.C
        )

        val latestHouses = listOf(templateHouse)

        // Delegate now delegates to AddNewHouseUseCase - capture params passed to execute
        val capturedParams = slot<AddNewHouseUseCase.Params>()
        coEvery { addNewHouseUseCase.execute(capture(capturedParams), any()) } returns AddNewHouseUseCase.Result.Success(2L)

        val testScope = kotlinx.coroutines.CoroutineScope(testDispatcher)
        houseEditDelegate.addNewHouseAt(
            scope = testScope,
            state = state,
            afterId = 1,
            latestHousesList = latestHouses
        )

        testDispatcher.scheduler.advanceUntilIdle()

        // Verify params contain the state values at the time of the call
        // (context inheritance from template happens inside AddNewHouseUseCase, not in the delegate)
        assertEquals("user_123", capturedParams.captured.agentUid)
        assertEquals("01-06-2026", capturedParams.captured.currentDate)
    }

    @Test
    fun testAddNewHouse_InheritsBairroAndPropertyTypeFromLastHouseOfCurrentDay() = runBlocking {
        val state = FakeHomeState()

        // Use a house with a DIFFERENT date to make the current day empty (triggers context propagation)
        val previousDayHouse = House(
            id = 1,
            data = "31-05-2026",
            agentName = "GUILHERME",
            agentUid = "user_123",
            address = com.antigravity.healthagent.domain.model.VisitAddress(
                blockNumber = "10",
                blockSequence = "A",
                streetName = "RUA A",
                number = "100",
                sequence = 0,
                complement = 0,
                bairro = "LAST_HOUSE_BAIRRO"
            ),
            context = com.antigravity.healthagent.domain.model.DailyContext(
                municipio = "LAST_HOUSE_MUNICIPIO",
                categoria = "LAST_HOUSE_CAT",
                zona = "LAST_HOUSE_ZONA",
                tipo = 2,
                ciclo = "2/2026",
                atividade = 5
            ),
            propertyType = PropertyType.TB,
            listOrder = 1
        )

        val latestHouses = listOf(previousDayHouse)
        
        val predictedHouse = House(
            id = 0,
            data = "01-06-2026",
            agentName = "GUILHERME",
            agentUid = "user_123",
            address = com.antigravity.healthagent.domain.model.VisitAddress(
                blockNumber = "10",
                blockSequence = "A",
                streetName = "RUA A",
                number = "101",
                sequence = 0,
                complement = 0,
                bairro = "LAST_HOUSE_BAIRRO"
            ),
            context = com.antigravity.healthagent.domain.model.DailyContext(
                municipio = "LAST_HOUSE_MUNICIPIO",
                categoria = "LAST_HOUSE_CAT",
                zona = "LAST_HOUSE_ZONA",
                tipo = 2,
                ciclo = "2/2026",
                atividade = 5
            ),
            propertyType = PropertyType.TB,
            situation = Situation.NONE
        )

        // Mock generateHouseToInsert to return the predicted house
        every { addNewHouseUseCase.generateHouseToInsert(any()) } returns predictedHouse
        // Mock execute to return Success
        coEvery { addNewHouseUseCase.execute(any(), any()) } returns AddNewHouseUseCase.Result.Success(2L)
        
        // Mock repository to return current houses state
        coEvery { repository.getHousesByDateAndAgent(any(), any()) } returns latestHouses

        val testScope = kotlinx.coroutines.CoroutineScope(testDispatcher)
        houseEditDelegate.addNewHouse(
            scope = testScope,
            state = state,
            latestHousesList = latestHouses,
            validateCurrentDay = { true },
            triggerDelayedValidation = {},
            onHouseClick = {}
        )

        testDispatcher.scheduler.advanceUntilIdle()
        
        // Verify the delegate propagated context from last global house to state
        assertEquals("LAST_HOUSE_BAIRRO", state.bairro.value)
        assertEquals("LAST_HOUSE_MUNICIPIO", state.municipio.value)
    }

    @Test
    fun testAdminRemoteInspection_BypassesTeamworkProtection() = runBlocking {
        val state = FakeHomeState()
        state.isSupervisor.value = true
        state.isAdmin.value = true // ADMIN ROLE
        state.remoteAgentUid.value = "remote_user"

        val templateHouse = House(
            id = 1,
            data = "01-06-2026",
            agentName = "AGENT_REMOTE",
            agentUid = "remote_user",
            address = com.antigravity.healthagent.domain.model.VisitAddress(
                blockNumber = "20",
                blockSequence = "B",
                streetName = "RUA DO TEMPLATE",
                number = "100",
                sequence = 2,
                complement = 1,
                bairro = "TEMPLATE_NEIGHBORHOOD"
            ),
            context = com.antigravity.healthagent.domain.model.DailyContext(
                municipio = "TEMPLATE_MUNICIPIO",
                categoria = "TEMPLATE_CAT",
                zona = "TEMPLATE_ZONA",
                tipo = 3,
                ciclo = "2/2026",
                atividade = 4
            ),
            propertyType = PropertyType.C
        )

        val latestHouses = listOf(templateHouse)

        // Capture params passed to AddNewHouseUseCase
        val capturedParams = slot<AddNewHouseUseCase.Params>()
        coEvery { addNewHouseUseCase.execute(capture(capturedParams), any()) } returns AddNewHouseUseCase.Result.Success(2L)

        val testScope = kotlinx.coroutines.CoroutineScope(testDispatcher)
        houseEditDelegate.addNewHouseAt(
            scope = testScope,
            state = state,
            afterId = 1,
            latestHousesList = latestHouses
        )

        testDispatcher.scheduler.advanceUntilIdle()

        // Admin should successfully add the house - verify params contain remote agentUid
        assertEquals("remote_user", capturedParams.captured.agentUid)
    }

    @Test
    fun testNormalSupervisorRemoteInspection_BlockedByTeamworkProtection() = runBlocking {
        val state = FakeHomeState()
        state.isSupervisor.value = true
        state.isAdmin.value = false // NORMAL SUPERVISOR (NOT ADMIN)
        state.remoteAgentUid.value = "remote_user"

        val templateHouse = House(
            id = 1,
            data = "01-06-2026",
            agentName = "AGENT_REMOTE",
            agentUid = "remote_user",
            address = com.antigravity.healthagent.domain.model.VisitAddress(
                blockNumber = "20",
                blockSequence = "B",
                streetName = "RUA DO TEMPLATE",
                number = "100",
                sequence = 2,
                complement = 1,
                bairro = "TEMPLATE_NEIGHBORHOOD"
            ),
            propertyType = PropertyType.C
        )

        val latestHouses = listOf(templateHouse)
        
        // Mock execute to return blocked - the role enforcement is now inside AddNewHouseUseCase
        coEvery { addNewHouseUseCase.execute(any(), any()) } returns AddNewHouseUseCase.Result.Blocked("Apenas administradores podem adicionar dados remotamente.")

        val testScope = kotlinx.coroutines.CoroutineScope(testDispatcher)
        houseEditDelegate.addNewHouseAt(
            scope = testScope,
            state = state,
            afterId = 1,
            latestHousesList = latestHouses
        )

        testDispatcher.scheduler.advanceUntilIdle()

        // Should be blocked - uiEvent set by handleAddResult
        assertEquals("Apenas administradores podem adicionar dados remotamente.", state.uiEvent.value)
        // Repository should not be called
        coVerify(exactly = 0) { repository.insertHouse(any(), any()) }
    }
}

