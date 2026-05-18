package com.antigravity.healthagent.ui.rg

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.repository.HouseRepository
import com.antigravity.healthagent.domain.repository.SyncRepository
import com.antigravity.healthagent.domain.usecase.GetRGBlocksUseCase
import com.antigravity.healthagent.domain.usecase.SyncDataUseCase
import com.antigravity.healthagent.data.settings.SettingsManager
import com.antigravity.healthagent.ui.home.BlockSegment
import com.antigravity.healthagent.domain.repository.UserRole
import com.antigravity.healthagent.ui.state.SyncUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

@HiltViewModel
class RgViewModel @Inject constructor(
    private val repository: HouseRepository,
    private val getRGBlocksUseCase: GetRGBlocksUseCase,
    private val settingsManager: SettingsManager,
    private val syncRepository: SyncRepository,
    private val syncDataUseCase: SyncDataUseCase
) : ViewModel() {

    private val dateFormatter = SimpleDateFormat("dd-MM-yyyy", Locale.US)

    private val _agentName = MutableStateFlow("")
    private val _currentUserUid = MutableStateFlow<String?>(null)
    private val _remoteAgentUid = MutableStateFlow<String?>(null)
    private val _isAdmin = MutableStateFlow(false)

    private val _selectedRgBairro = MutableStateFlow("")
    val selectedRgBairro: StateFlow<String> = _selectedRgBairro.asStateFlow()

    private val _rgYear = MutableStateFlow(Calendar.getInstance().get(Calendar.YEAR).toString())
    val rgYear: StateFlow<String> = _rgYear.asStateFlow()

    private val _selectedRgBlock = MutableStateFlow("")
    val selectedRgBlock: StateFlow<String> = _selectedRgBlock.asStateFlow()

    private val _syncState = MutableStateFlow<SyncUiState>(SyncUiState.Idle())
    val syncState: StateFlow<SyncUiState> = _syncState.asStateFlow()

    val isSolarMode: StateFlow<Boolean> = settingsManager.solarMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val isEasyMode: StateFlow<Boolean> = settingsManager.easyMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    
    // Default or dynamically observed from database/settings
    val municipality = MutableStateFlow("BOM JARDIM")

    init {
        // Observe settings profile cache
        viewModelScope.launch {
            settingsManager.cachedUser.collect { user ->
                user?.let {
                    val name = it.agentName?.uppercase()?.ifBlank { null }
                        ?: it.email?.substringBefore("@")?.uppercase()
                        ?: "DESCONHECIDO"
                    _agentName.value = name
                    _currentUserUid.value = it.uid
                    _isAdmin.value = it.role == UserRole.ADMIN
                }
            }
        }

        viewModelScope.launch {
            settingsManager.remoteAgentUid.collect { uid ->
                _remoteAgentUid.value = uid
            }
        }

        viewModelScope.launch {
            settingsManager.remoteAgentName.collect { name ->
                name?.uppercase()?.ifBlank { null }?.let {
                    _agentName.value = it
                }
            }
        }
    }

    private val allHousesFlow: Flow<List<House>> = combine(
        _agentName, _remoteAgentUid, _currentUserUid
    ) { name, remoteUid, currentUid ->
        val effectiveUid = remoteUid ?: currentUid ?: ""
        repository.getHousesByAgentSnapshotFlow(effectiveUid)
    }.flatMapLatest { it }
    .flowOn(Dispatchers.Default)

    private val globalHousesFlow: Flow<List<House>> = combine(
        _agentName, _remoteAgentUid, _currentUserUid, _isAdmin
    ) { name, remoteUid, currentUid, isAdminRole ->
        val isViewingRemoteAgent = remoteUid != null && remoteUid != currentUid
        
        if (isAdminRole && isViewingRemoteAgent) {
            val effectiveUid = remoteUid ?: currentUid
            repository.getHousesByAgentSnapshotFlow(effectiveUid ?: "")
        } else if (isAdminRole) {
            repository.getHousesByAgentSnapshotFlow(currentUid ?: "")
        } else {
            val effectiveUid = remoteUid ?: currentUid
            repository.getHousesByAgentSnapshotFlow(effectiveUid ?: "")
        }
    }.flatMapLatest { it }

    private val participatoryHousesFlow: Flow<List<House>> = combine(
        _agentName, _remoteAgentUid, _currentUserUid
    ) { name, remoteUid, currentUid ->
        val effectiveUid = remoteUid ?: currentUid
        if (effectiveUid != null) {
            repository.getParticipatoryHousesFlow(effectiveUid)
        } else {
            flowOf(emptyList())
        }
    }.flatMapLatest { it }
    .flowOn(Dispatchers.Default)

    val availableYears: StateFlow<List<String>> = combine(
        allHousesFlow, globalHousesFlow, _isAdmin
    ) { all, global, isAdminRole ->
        val housesToUse = if (isAdminRole) global else all
        housesToUse.mapNotNull {
            try {
                val d = dateFormatter.parse(it.data)
                if (d != null) {
                    val c = Calendar.getInstance().apply { time = d }
                    c.get(Calendar.YEAR).toString()
                } else null
            } catch (e: Exception) { null }
        }.distinct().sortedDescending()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), listOf(Calendar.getInstance().get(Calendar.YEAR).toString()))

    val rgBlocks: StateFlow<List<BlockSegment>> = combine(
        allHousesFlow,
        globalHousesFlow,
        participatoryHousesFlow,
        _selectedRgBairro,
        _rgYear,
        _isAdmin,
        _remoteAgentUid
    ) { args ->
        val h = args[0] as List<House>
        val global = args[1] as List<House>
        val participatory = args[2] as List<House>
        val b = args[3] as String
        val y = args[4] as String
        val isAdminRole = args[5] as Boolean
        val remoteUid = args[6] as String?

        val hasRemoteAgent = remoteUid != null
        val filtered = if (isAdminRole && !hasRemoteAgent) h else if (isAdminRole && hasRemoteAgent) global else participatory
        getRGBlocksUseCase(filtered, b, y)
    }
    .flowOn(Dispatchers.Default)
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val rgFilteredList: StateFlow<List<House>> = combine(rgBlocks, selectedRgBlock) { blocks, selectedId ->
        blocks.find { it.id == selectedId }?.houses ?: emptyList()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val rgBairros: StateFlow<List<String>> = combine(
        allHousesFlow, globalHousesFlow, _rgYear, _isAdmin
    ) { all, global, year, isAdminRole ->
        val housesToUse = if (isAdminRole) global else all
        filterByYear(housesToUse, year)
            .map { it.address.bairro.trim().uppercase() }
            .distinct()
            .sorted()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private fun filterByYear(houses: List<House>, year: String): List<House> {
        return houses.filter { house ->
            val date = try { dateFormatter.parse(house.data) } catch (e: Exception) { null }
            if (date != null) {
                val cal = Calendar.getInstance().apply { time = date }
                val hYear = cal.get(Calendar.YEAR).toString()
                hYear == year
            } else false
        }
    }

    fun selectRgYear(y: String) {
        _rgYear.value = y
    }

    fun selectRgBairro(b: String) {
        _selectedRgBairro.value = b
        _selectedRgBlock.value = ""
    }

    fun selectRgBlock(q: String) {
        _selectedRgBlock.value = q
    }

    fun syncDataToCloud() {
        if (_syncState.value is SyncUiState.Syncing) return
        _syncState.value = SyncUiState.Syncing(progress = 0.5f, message = "Sincronizando...")
        viewModelScope.launch {
            try {
                val currentUid = _remoteAgentUid.value ?: _currentUserUid.value
                if (currentUid != null) {
                    val houses = repository.getAllHousesOnce(currentUid)
                    val activities = repository.getAllDayActivitiesOnce(currentUid)
                    
                    val pushResult = syncRepository.pushLocalDataToCloud(houses, activities, currentUid)
                    if (pushResult.isSuccess) {
                        syncRepository.pruneOldTombstones()
                    }
                    syncRepository.pullCloudDataToLocal(currentUid)
                    _syncState.value = SyncUiState.Success(System.currentTimeMillis())
                }
            } catch (e: Exception) {
                android.util.Log.e("RgViewModel", "Sync failed", e)
                _syncState.value = SyncUiState.Error(e.message ?: "Erro na sincronização")
            } finally {
                kotlinx.coroutines.delay(2000)
                _syncState.value = SyncUiState.Idle(_syncState.value.lastSyncTime)
            }
        }
    }
}
