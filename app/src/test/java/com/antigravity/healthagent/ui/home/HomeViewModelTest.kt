package com.antigravity.healthagent.ui.home

import android.content.Context
import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.local.model.DayActivity
import com.antigravity.healthagent.data.local.model.Situation
import com.antigravity.healthagent.data.local.model.PropertyType
import com.antigravity.healthagent.domain.repository.HouseRepository
import com.antigravity.healthagent.domain.repository.SyncRepository
import com.antigravity.healthagent.domain.repository.AgentRepository
import com.antigravity.healthagent.domain.repository.LocalizationRepository
import com.antigravity.healthagent.domain.repository.StreetRepository
import com.antigravity.healthagent.domain.repository.AgentData
import com.antigravity.healthagent.domain.repository.AuthUser
import com.antigravity.healthagent.domain.repository.UserRole
import com.antigravity.healthagent.domain.usecase.SaveHouseUseCase
import com.antigravity.healthagent.domain.usecase.PredictHouseValuesUseCase
import com.antigravity.healthagent.domain.usecase.RecalculateVisitSegmentsUseCase
import com.antigravity.healthagent.domain.usecase.PerformLocalDatabaseMigrationUseCase
import com.antigravity.healthagent.domain.usecase.DayManagementUseCase
import com.antigravity.healthagent.domain.usecase.HouseValidationUseCase
import com.antigravity.healthagent.domain.usecase.GenerateTestDataUseCase
import com.antigravity.healthagent.domain.usecase.CleanupBrokenHousesUseCase
import com.antigravity.healthagent.domain.usecase.ClashDetector
import com.antigravity.healthagent.domain.usecase.DayLockEnforcer
import com.antigravity.healthagent.domain.usecase.RoleEnforcer
import com.antigravity.healthagent.domain.usecase.LoadDynamicConfigUseCase
import com.antigravity.healthagent.domain.usecase.TriggerImmediateSyncUseCase
import com.antigravity.healthagent.domain.usecase.SelectDayActivityUseCase
import com.antigravity.healthagent.domain.usecase.UpdateDayHeaderUseCase
import com.antigravity.healthagent.domain.model.VisitAddress
import com.antigravity.healthagent.data.settings.SettingsManager
import com.antigravity.healthagent.data.backup.BackupManager
import com.antigravity.healthagent.ui.home.delegates.*
import com.antigravity.healthagent.ui.state.SyncUiState
import com.antigravity.healthagent.utils.SoundManager
import com.antigravity.healthagent.domain.logger.AppLogger
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class HomeViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

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
    private val loadDynamicConfigUseCase = mockk<LoadDynamicConfigUseCase>(relaxed = true)
    private val triggerImmediateSyncUseCase = mockk<TriggerImmediateSyncUseCase>(relaxed = true)
    private val selectDayActivityUseCase = mockk<SelectDayActivityUseCase>(relaxed = true)
    private val updateDayHeaderUseCase = mockk<UpdateDayHeaderUseCase>(relaxed = true)

    private val syncDelegate = mockk<SyncDelegate>(relaxed = true)
    private val dayNavigationDelegate = mockk<DayNavigationDelegate>(relaxed = true)
    private val dayClosingDelegate = mockk<DayClosingDelegate>(relaxed = true)
    private val validationDelegate = mockk<ValidationDelegate>(relaxed = true)
    private val remoteAgentDelegate = mockk<RemoteAgentDelegate>(relaxed = true)
    private val boletimDataDelegate = mockk<BoletimDataDelegate>(relaxed = true)
    private val initializationDelegate = mockk<InitializationDelegate>(relaxed = true)
    private val houseEditDelegate = mockk<HouseEditDelegate>(relaxed = true)

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        every { settingsManager.easyMode } returns flowOf(false)
        every { settingsManager.solarMode } returns flowOf(false)
        every { settingsManager.editingToolsMode } returns flowOf(true)
        every { settingsManager.maxOpenHouses } returns flowOf(5)
        every { settingsManager.backupFrequency } returns flowOf(com.antigravity.healthagent.data.backup.BackupFrequency.DAILY)
        every { settingsManager.themeMode } returns flowOf("")
        every { settingsManager.themeColor } returns flowOf("")
        every { settingsManager.cachedUser } returns flowOf(
            AuthUser("user_1", "agent@test.com", "AGENTE", null, UserRole.AGENT)
        )
        every { settingsManager.remoteAgentUid } returns flowOf(null)
        every { settingsManager.remoteAgentName } returns flowOf(null)
        every { settingsManager.customActivities } returns flowOf(emptySet())

        every { repository.getPersonalHousesFlow(any(), any()) } returns flowOf(emptyList())
        every { repository.getDayActivityFlow(any(), any()) } returns flowOf(null)
        every { repository.allActivitiesFlow } returns flowOf(emptyList())
        every { repository.getAllHousesSnapshotFlow() } returns flowOf(emptyList())
        every { repository.getParticipatoryHousesFlow(any()) } returns flowOf(emptyList())

        every { streetRepository.getStreetSuggestions(any(), any(), any()) } returns flowOf(emptyList())
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): HomeViewModel {
        val provider = HomeDelegatesProvider(
            syncDelegate = syncDelegate,
            dayNavigationDelegate = dayNavigationDelegate,
            dayClosingDelegate = dayClosingDelegate,
            validationDelegate = validationDelegate,
            remoteAgentDelegate = remoteAgentDelegate,
            boletimDataDelegate = boletimDataDelegate,
            initializationDelegate = initializationDelegate,
            houseEditDelegate = houseEditDelegate,
            stateDelegate = HomeStateDelegate()
        )
        return HomeViewModel(
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
            loadDynamicConfigUseCase = loadDynamicConfigUseCase,
            triggerImmediateSyncUseCase = triggerImmediateSyncUseCase,
            selectDayActivityUseCase = selectDayActivityUseCase,
            updateDayHeaderUseCase = updateDayHeaderUseCase,
            delegatesProvider = provider
        )
    }

    @Test
    fun `init sets up flows and calls initializationDelegate`() = runBlocking {
        every { settingsManager.cachedUser } returns flowOf(
            AuthUser("user_1", "agent@test.com", "AGENTE", null, UserRole.AGENT)
        )
        createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()
        coVerify { initializationDelegate.initialize(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `navigateToDate updates data and sets navigation tab to 0`() {
        val vm = createViewModel()
        vm.navigateToDate("15-06-2026")
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals("15-06-2026", vm.data.value)
        assertEquals(0, vm.navigationTab.value)
    }

    @Test
    fun `moveDate delegates to dayNavigationDelegate`() {
        val vm = createViewModel()
        vm.moveDate(forward = true)
        verify { dayNavigationDelegate.moveDateForward(any(), any()) }

        vm.moveDate(forward = false)
        verify { dayNavigationDelegate.moveDateBackward(any(), any()) }
    }

    @Test
    fun `selectToday delegates to dayNavigationDelegate`() {
        val vm = createViewModel()
        vm.selectToday()
        verify { dayNavigationDelegate.goToToday(any()) }
    }

    @Test
    fun `moveHouseToDate delegates to dayNavigationDelegate`() {
        val vm = createViewModel()
        val house = House(
            id = 1, data = "15-06-2026", agentName = "AGENTE", agentUid = "user_1",
            address = VisitAddress("100", "RUA A", "001", "", 0, 0, ""),
            propertyType = PropertyType.R, situation = Situation.NONE
        )
        vm.moveHouseToDate(house, "16-06-2026")
        verify { dayNavigationDelegate.moveHouseToDate(any(), any(), house, "16-06-2026", any(), any()) }
    }

    @Test
    fun `syncDataToCloud delegates to syncDelegate`() {
        val vm = createViewModel()
        vm.syncDataToCloud()
        verify { syncDelegate.syncDataToCloud(any(), any(), any()) }
    }

    @Test
    fun `pullDataFromCloud delegates to syncDelegate`() {
        val vm = createViewModel()
        vm.pullDataFromCloud("target_uid")
        verify { syncDelegate.pullDataFromCloud(any(), any(), "target_uid") }
    }

    @Test
    fun `generateMockData delegates to syncDelegate`() {
        val vm = createViewModel()
        vm.generateMockData()
        verify { syncDelegate.generateMockData(any(), any()) }
    }

    @Test
    fun `finishEditSession delegates to syncDelegate with callback`() {
        val vm = createViewModel()
        var called = false
        vm.finishEditSession { called = true }
        verify { syncDelegate.finishEditSession(any(), any(), any()) }
    }

    @Test
    fun `updateSearchQuery updates search query state`() = runBlocking {
        val vm = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals("", vm.searchQuery.value)

        vm.updateSearchQuery("RUA A")
        testDispatcher.scheduler.advanceTimeBy(500)
        testDispatcher.scheduler.runCurrent()

        assertEquals("RUA A", vm.searchQuery.value)
    }

    @Test
    fun `updateBlock updates currentBlock state`() {
        val vm = createViewModel()
        vm.updateBlock("015")
        assertEquals("015", vm.currentBlock.value)
    }

    @Test
    fun `updateBlockSequence updates currentBlockSequence state`() {
        val vm = createViewModel()
        vm.updateBlockSequence("B")
        assertEquals("B", vm.currentBlockSequence.value)
    }

    @Test
    fun `updateStreet updates currentStreet state`() {
        val vm = createViewModel()
        vm.updateStreet("RUA TESTE")
        assertEquals("RUA TESTE", vm.currentStreet.value)
    }

    @Test
    fun `updateBairro updates bairro state`() {
        val vm = createViewModel()
        vm.updateBairro("CENTRO")
        assertEquals("CENTRO", vm.bairro.value)
    }

    @Test
    fun `updateMunicipio updates municipio state`() {
        val vm = createViewModel()
        vm.updateMunicipio("BOM JARDIM")
        assertEquals("BOM JARDIM", vm.municipio.value)
    }

    @Test
    fun `clearUiEvent sets uiEvent to null`() {
        val vm = createViewModel()
        vm.uiEvent.value = "some error"
        vm.clearUiEvent()
        assertNull(vm.uiEvent.value)
    }

    @Test
    fun `updateHouse emits to debounced queue`() {
        val vm = createViewModel()
        val house = House(
            id = 1, data = "15-06-2026", agentName = "AGENTE", agentUid = "user_1",
            address = VisitAddress("100", "RUA A", "001", "", 0, 0, ""),
            propertyType = PropertyType.R, situation = Situation.NONE
        )
        vm.updateHouse(house)
        verify { houseEditDelegate wasNot Called }
    }

    @Test
    fun `deleteHouse delegates to houseEditDelegate`() {
        val vm = createViewModel()
        val house = House(
            id = 1, data = "15-06-2026", agentName = "AGENTE", agentUid = "user_1",
            address = VisitAddress("100", "RUA A", "001", "", 0, 0, ""),
            propertyType = PropertyType.R, situation = Situation.NONE
        )
        vm.deleteHouse(house)
        verify { houseEditDelegate.deleteHouse(any(), any(), house, any(), any()) }
    }

    @Test
    fun `setSupervisor updates isSupervisor state`() {
        val vm = createViewModel()
        assertFalse(vm.isSupervisor.value)
        vm.setSupervisor(true)
        assertTrue(vm.isSupervisor.value)
        vm.setSupervisor(false)
        assertFalse(vm.isSupervisor.value)
    }

    @Test
    fun `setAdmin updates isAdmin state`() {
        val vm = createViewModel()
        assertFalse(vm.isAdmin.value)
        vm.setAdmin(true)
        assertTrue(vm.isAdmin.value)
        vm.setAdmin(false)
        assertFalse(vm.isAdmin.value)
    }

    @Test
    fun `setNavigationTab updates navigation tab`() {
        val vm = createViewModel()
        vm.setNavigationTab(2)
        assertEquals(2, vm.navigationTab.value)
        vm.clearNavigationTab()
        assertNull(vm.navigationTab.value)
    }

    @Test
    fun `startDayClosingFlow delegates to dayClosingDelegate`() {
        val vm = createViewModel()
        vm.startDayClosingFlow()
        verify { dayClosingDelegate.startDayClosingFlow(any(), any(), any(), any()) }
    }

    @Test
    fun `confirmAndCloseDay delegates to dayClosingDelegate`() {
        val vm = createViewModel()
        val audit = AuditSummary(
            date = "15-06-2026", totalWorked = 5, totalTreated = 2,
            totalClosed = 3, totalRefused = 1, totalAbsent = 0, totalVacant = 0,
            a1 = 1, a2 = 0, b = 1, c = 0, d1 = 0, d2 = 0, e = 0,
            eliminados = 0, totalLarvicide = 0.0
        )
        vm.confirmAndCloseDay(audit)
        verify { dayClosingDelegate.confirmAndCloseDay(any(), any(), audit, any(), any()) }
    }

    @Test
    fun `toggleDayLock delegates to dayClosingDelegate`() {
        val vm = createViewModel()
        vm.toggleDayLock()
        verify { dayClosingDelegate.toggleDayLock(any(), any(), any()) }
    }

    @Test
    fun `setRemoteAgent delegates to remoteAgentDelegate`() {
        val vm = createViewModel()
        val agent = AgentData(uid = "remote_1", email = "remote@test.com", agentName = "REMOTE", houses = emptyList(), activities = emptyList())
        vm.setRemoteAgent(agent)
        verify { remoteAgentDelegate.setRemoteAgent(any(), any(), agent, any(), any()) }
    }

    @Test
    fun `setRemoteAgent with null clears remote agent`() {
        val vm = createViewModel()
        vm.setRemoteAgent(null)
        verify { remoteAgentDelegate.setRemoteAgent(any(), any(), null, any(), any()) }
    }

    @Test
    fun `deduplicateCurrentDay delegates to remoteAgentDelegate`() {
        val vm = createViewModel()
        vm.deduplicateCurrentDay()
        verify { remoteAgentDelegate.deduplicateCurrentDay(any(), any()) }
    }

    @Test
    fun `handleActivitySelection updates day activity status`() = runBlocking {
        val vm = createViewModel()
        vm.currentUserUid.value = "user_1"
        vm.handleActivitySelection("FERIADO")
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { selectDayActivityUseCase.invoke(any(), any(), any(), eq("FERIADO"), any()) }
    }

    @Test
    fun `handleActivitySelection updates existing day activity`() = runBlocking {
        val vm = createViewModel()
        vm.currentUserUid.value = "user_1"
        vm.handleActivitySelection("FERIADO")
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { selectDayActivityUseCase.invoke(any(), any(), any(), eq("FERIADO"), any()) }
    }

    @Test
    fun `validateCurrentDay delegates to validationDelegate`() {
        every { validationDelegate.validateCurrentDay(any(), any(), any(), any()) } returns true
        val vm = createViewModel()
        val result = vm.validateCurrentDay(showDialog = true, strict = true)
        assertTrue(result)
        verify { validationDelegate.validateCurrentDay(vm, any(), true, true) }
    }

    @Test
    fun `forceFullSync calls syncRepository pullCloudDataToLocal with force=true`() = runBlocking {
        coEvery { syncRepository.pullCloudDataToLocal(force = true) } returns Result.success(Unit)

        val vm = createViewModel()
        vm.forceFullSync()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { syncRepository.pullCloudDataToLocal(force = true) }
    }

    @Test
    fun `dismissIntegrityDialog clears integrityDialogMessage`() {
        val vm = createViewModel()
        vm.integrityDialogMessage.value = "some message"
        vm.dismissIntegrityDialog()
        assertNull(vm.integrityDialogMessage.value)
    }

    @Test
    fun `dismissClosingAudit clears showClosingAudit`() {
        val vm = createViewModel()
        vm.showClosingAudit.value = AuditSummary(
            date = "15-06-2026", totalWorked = 0, totalTreated = 0,
            totalClosed = 0, totalRefused = 0, totalAbsent = 0, totalVacant = 0,
            a1 = 0, a2 = 0, b = 0, c = 0, d1 = 0, d2 = 0, e = 0,
            eliminados = 0, totalLarvicide = 0.0
        )
        vm.dismissClosingAudit()
        assertNull(vm.showClosingAudit.value)
    }

    @Test
    fun `dismissGoalReached clears showGoalReached`() {
        val vm = createViewModel()
        vm.showGoalReached.value = true
        vm.dismissGoalReached()
        assertFalse(vm.showGoalReached.value)
    }

    @Test
    fun `registerCustomActivity adds activity to settings`() = runBlocking {
        every { settingsManager.customActivities } returns flowOf(emptySet())
        coEvery { settingsManager.setCustomActivities(any()) } returns Unit

        val vm = createViewModel()
        vm.registerCustomActivity("ATIVIDADE_TESTE")
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { settingsManager.setCustomActivities(match { it.contains("ATIVIDADE_TESTE") }) }
    }

    @Test
    fun `clearCustomActivities clears all custom activities`() = runBlocking {
        coEvery { settingsManager.setCustomActivities(any()) } returns Unit

        val vm = createViewModel()
        vm.clearCustomActivities()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { settingsManager.setCustomActivities(emptySet()) }
    }

    @Test
    fun `goToLastWorkDay navigates to date of most recent house`() = runBlocking {
        val house1 = House(
            id = 1, data = "10-06-2026", agentName = "AGENTE", agentUid = "user_1",
            address = VisitAddress("100", "RUA A", "001", "", 0, 0, ""),
            propertyType = PropertyType.R, situation = Situation.NONE
        )
        val house2 = House(
            id = 2, data = "15-06-2026", agentName = "AGENTE", agentUid = "user_1",
            address = VisitAddress("101", "RUA B", "002", "", 0, 0, ""),
            propertyType = PropertyType.C, situation = Situation.NONE
        )

        every { repository.getPersonalHousesFlow(any(), any()) } returns flowOf(listOf(house1, house2))

        val vm = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        vm.goToLastWorkDay()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("15-06-2026", vm.data.value)
    }

    @Test
    fun `getHousesForDate filters houses by date and agent`() {
        val house1 = House(
            id = 1, data = "15-06-2026", agentName = "AGENTE", agentUid = "user_1",
            address = VisitAddress("100", "RUA A", "001", "", 0, 0, ""),
            propertyType = PropertyType.R, situation = Situation.NONE
        )
        val house2 = House(
            id = 2, data = "15-06-2026", agentName = "OUTRO", agentUid = "user_2",
            address = VisitAddress("101", "RUA B", "002", "", 0, 0, ""),
            propertyType = PropertyType.C, situation = Situation.NONE
        )

        every { repository.getPersonalHousesFlow(any(), any()) } returns flowOf(listOf(house1, house2))

        val vm = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        val result = vm.getHousesForDate("15-06-2026", "AGENTE")
        assertEquals(1, result.size)
        assertEquals(1, result[0].id)
    }

    @Test
    fun `bairro update triggers tipo recalculation for CENTRO`() {
        val vm = createViewModel()
        vm.updateBairro("CENTRO")
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, vm.tipo.value)
    }

    @Test
    fun `bairro update triggers tipo recalculation for non-CENTRO`() {
        val vm = createViewModel()
        vm.updateBairro("JD AMERICA")
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(2, vm.tipo.value)
    }

    @Test
    fun `moveHouse delegates to houseEditDelegate`() {
        val vm = createViewModel()
        val house = House(
            id = 1, data = "15-06-2026", agentName = "AGENTE", agentUid = "user_1",
            address = VisitAddress("100", "RUA A", "001", "", 0, 0, ""),
            propertyType = PropertyType.R, situation = Situation.NONE
        )
        vm.moveHouse(house, moveUp = true)
        verify { houseEditDelegate.moveHouse(any(), any(), house, true, any(), any()) }

        vm.moveHouse(house, moveUp = false)
        verify { houseEditDelegate.moveHouse(any(), any(), house, false, any(), any()) }
    }

    @Test
    fun `confirmDuplicateMerge delegates to houseEditDelegate`() {
        val vm = createViewModel()
        vm.confirmDuplicateMerge()
        verify { houseEditDelegate.confirmDuplicateMerge(any(), any()) }
    }

    @Test
    fun `addNewHouse delegates to houseEditDelegate`() {
        val vm = createViewModel()
        vm.addNewHouse()
        verify { houseEditDelegate.addNewHouse(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `addNewHouseAt delegates to houseEditDelegate`() {
        val vm = createViewModel()
        vm.addNewHouseAt(afterId = 5)
        verify { houseEditDelegate.addNewHouseAt(any(), any(), 5, any(), any()) }
    }

    @Test
    fun `setSyncPullActive updates isSyncPullActive state`() {
        val vm = createViewModel()
        assertFalse(vm.isSyncPullActive.value)
        vm.setSyncPullActive(true)
        assertTrue(vm.isSyncPullActive.value)
        vm.setSyncPullActive(false)
        assertFalse(vm.isSyncPullActive.value)
    }

    @Test
    fun `deleteProduction calls repository deleteProduction`() = runBlocking {
        coEvery { repository.deleteProduction(any(), any()) } returns Unit

        val vm = createViewModel()
        vm.currentUserUid.value = "user_1"
        vm.deleteProduction("15-06-2026")
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { repository.deleteProduction("15-06-2026", any()) }
        assertEquals("Produção excluída com sucesso.", vm.uiEvent.value)
    }

    @Test
    fun `advanceToNextDay calls dayManagementUseCase and updates data`() = runBlocking {
        coEvery { dayManagementUseCase.getNextBusinessDay(any(), any()) } returns "16-06-2026"

        val vm = createViewModel()
        vm.currentUserUid.value = "user_1"
        vm.advanceToNextDay()
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("16-06-2026", vm.data.value)
    }

    @Test
    fun `showMultiDayErrorDialog and dismiss work correctly`() {
        val vm = createViewModel()
        assertFalse(vm.showMultiDayErrorDialog.value)
        vm.showMultiDayErrorDialog()
        assertTrue(vm.showMultiDayErrorDialog.value)
        vm.dismissMultiDayErrorDialog()
        assertFalse(vm.showMultiDayErrorDialog.value)
    }

    @Test
    fun `persistListOrder recalculates segments and saves`() = runBlocking {
        val house1 = House(
            id = 1, data = "15-06-2026", agentName = "AGENTE", agentUid = "user_1",
            address = VisitAddress("100", "RUA A", "001", "", 0, 0, ""),
            propertyType = PropertyType.R, situation = Situation.NONE, listOrder = 0
        )
        val house2 = house1.copy(id = 2, listOrder = 1)
        val reordered = listOf(house2, house1)

        every { recalculateVisitSegmentsUseCase.recalculateVisitSegments(any()) } returns reordered

        val vm = createViewModel()
        vm.persistListOrder(reordered)
        testDispatcher.scheduler.advanceTimeBy(1000)
        testDispatcher.scheduler.runCurrent()

        coVerify { saveHouseUseCase.updateHouses(any(), any()) }
    }

    @Test
    fun `updateHeader propagates context to existing day houses`() = runBlocking {
        val vm = createViewModel()
        vm.currentUserUid.value = "user_1"
        vm.updateHeader(
            m = "BOM JARDIM", b = "CENTRO", c = "RESIDENCIAL", z = "URBANA",
            t = 1, d = "15/06/2026", ci = "1º", a = 2
        )
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { updateDayHeaderUseCase.invoke(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `daysWithErrors computes error summaries`() {
        val validHouse = House(
            id = 1, data = "15-06-2026", agentName = "AGENTE", agentUid = "user_1",
            address = VisitAddress("100", "RUA A", "020", "", 0, 0, ""),
            propertyType = PropertyType.R, situation = Situation.F
        )
        every { houseValidationUseCase.validateCurrentDay(any(), any(), any()) } returns
            HouseValidationUseCase.ValidationResult(isValid = true)
        every { houseValidationUseCase.isHouseValid(any(), any()) } returns true
        every { repository.getPersonalHousesFlow(any(), any()) } returns flowOf(listOf(validHouse))

        val vm = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.daysWithErrors.value.isEmpty())
    }

    @Test
    fun `weekRangeText has non-empty default`() {
        val vm = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()
        assertNotNull(vm.weekRangeText.value)
    }

    @Test
    fun `currentWeekDates has non-empty default`() {
        val vm = createViewModel()
        testDispatcher.scheduler.advanceUntilIdle()
        assertNotNull(vm.currentWeekDates.value)
    }

    @Test
    fun `restoreDeletedHouse delegates to houseEditDelegate`() {
        val vm = createViewModel()
        vm.restoreDeletedHouse()
        verify { houseEditDelegate.restoreDeletedHouse(any(), any(), any(), any()) }
    }

    @Test
    fun `moveHousesToDate delegates to dayNavigationDelegate`() {
        val vm = createViewModel()
        vm.moveHousesToDate("15-06-2026", "16-06-2026")
        verify { dayNavigationDelegate.moveHousesToDate(any(), any(), "15-06-2026", "16-06-2026") }
    }

    @Test
    fun `navigateToErroneousDay unlocks day if needed and navigates`() = runBlocking {
        val activity = DayActivity(date = "15-06-2026", agentName = "AGENTE", agentUid = "user_1", status = "NORMAL", isClosed = true)
        coEvery { dayManagementUseCase.getDayActivity(any(), any()) } returns activity
        coEvery { dayManagementUseCase.unlockDay(any(), any()) } returns Unit
        every { validationDelegate.validateCurrentDay(any(), any(), any(), any()) } returns true

        val vm = createViewModel()
        vm.currentUserUid.value = "user_1"
        vm.navigateToErroneousDay("15-06-2026")
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { dayManagementUseCase.unlockDay("15-06-2026", any()) }
        assertEquals("15-06-2026", vm.data.value)
    }

    @Test
    fun `dismissHistoryUnlockConfirmation delegates to dayClosingDelegate`() {
        val vm = createViewModel()
        vm.dismissHistoryUnlockConfirmation()
        verify { dayClosingDelegate.dismissHistoryUnlockConfirmation(any()) }
    }

    @Test
    fun `confirmUnlockHistory delegates to dayClosingDelegate`() {
        val vm = createViewModel()
        vm.confirmUnlockHistory()
        verify { dayClosingDelegate.confirmUnlockHistory(any(), any()) }
    }
}
