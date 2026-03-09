package com.antigravity.healthagent.ui.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.local.model.PropertyType
import com.antigravity.healthagent.data.local.model.Situation
import com.antigravity.healthagent.data.local.model.DayActivity
import com.antigravity.healthagent.domain.repository.HouseRepository
import com.antigravity.healthagent.data.repository.StreetRepository
import com.antigravity.healthagent.domain.usecase.HouseValidationUseCase
import com.antigravity.healthagent.domain.usecase.DayManagementUseCase
import com.antigravity.healthagent.domain.usecase.HouseManagementUseCase
import com.antigravity.healthagent.utils.formatStreetName
import com.antigravity.healthagent.utils.SoundManager
import com.antigravity.healthagent.data.settings.SettingsManager
import com.antigravity.healthagent.data.backup.BackupScheduler
import com.antigravity.healthagent.data.backup.BackupManager
import com.antigravity.healthagent.data.backup.BackupData
import com.antigravity.healthagent.utils.SemanalPdfGenerator
import com.antigravity.healthagent.utils.BoletimPdfGenerator
import com.antigravity.healthagent.domain.repository.SyncRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: HouseRepository,
    private val syncRepository: SyncRepository,
    private val houseValidationUseCase: HouseValidationUseCase,
    val dayManagementUseCase: DayManagementUseCase,
    private val houseManagementUseCase: HouseManagementUseCase,
    private val soundManager: SoundManager,
    private val settingsManager: SettingsManager,
    private val backupScheduler: BackupScheduler,
    private val streetRepository: StreetRepository
) : ViewModel() {

    // --- State Definitions ---
    private val dateFormatter: SimpleDateFormat get() = SimpleDateFormat("dd-MM-yyyy", Locale.getDefault())
    private val displayDateFormatter: SimpleDateFormat get() = SimpleDateFormat("dd/MM", Locale.getDefault())
    
    private val _data = MutableStateFlow(dateFormatter.format(Date()))
    val data: StateFlow<String> = _data.asStateFlow()

    private val dateCache = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private fun getTimestamp(date: String): Long {
        return dateCache.getOrPut(date) {
            try {
                dateFormatter.parse(date)?.time ?: 0L
            } catch (e: Exception) {
                0L
            }
        }
    }

    private val _municipio = MutableStateFlow("BOM JARDIM")
    val municipio: StateFlow<String> = _municipio.asStateFlow()

    private val _bairro = MutableStateFlow("")
    val bairro: StateFlow<String> = _bairro.asStateFlow()

    private val _categoria = MutableStateFlow("BRR")
    val categoria: StateFlow<String> = _categoria.asStateFlow()

    private val _zona = MutableStateFlow("URB")
    val zona: StateFlow<String> = _zona.asStateFlow()

    private val _ciclo = MutableStateFlow("1º")
    val ciclo: StateFlow<String> = _ciclo.asStateFlow()

    private val _tipo = MutableStateFlow(2)
    val tipo: StateFlow<Int> = _tipo.asStateFlow()

    private val _atividade = MutableStateFlow(4)
    val atividade: StateFlow<Int> = _atividade.asStateFlow()

    private val _agentName = MutableStateFlow("")
    val agentName: StateFlow<String> = _agentName.asStateFlow()

    private val _currentBlock = MutableStateFlow("")
    val currentBlock: StateFlow<String> = _currentBlock.asStateFlow()

    private val _currentBlockSequence = MutableStateFlow("")
    val currentBlockSequence: StateFlow<String> = _currentBlockSequence.asStateFlow()

    private val _currentStreet = MutableStateFlow("")
    val currentStreet: StateFlow<String> = _currentStreet.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    // Global unfiltered list for initialization
    private val allHousesFlow = repository.getAllHouses()

    val houses: StateFlow<List<House>> = combine(allHousesFlow, _agentName) { list, agent ->
        list.filter { it.agentName.trim().equals(agent.trim(), ignoreCase = true) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())




    private val _uiEvent = MutableStateFlow<String?>(null)
    val uiEvent: StateFlow<String?> = _uiEvent.asStateFlow()

    private val _navigationTab = MutableStateFlow<Int?>(null)
    val navigationTab: StateFlow<Int?> = _navigationTab.asStateFlow()

    private val _showGoalReached = MutableStateFlow(false)
    val showGoalReached: StateFlow<Boolean> = _showGoalReached.asStateFlow()

    private val _validationErrorHouseIds = MutableStateFlow<Set<Int>>(emptySet())
    val validationErrorHouseIds: StateFlow<Set<Int>> = _validationErrorHouseIds.asStateFlow()

    // Redundant houseInvalidFields removed to improve performance. 
    // Validation is now handled locally within HouseRowItem.

    private val _showClosingAudit = MutableStateFlow<AuditSummary?>(null)
    val showClosingAudit: StateFlow<AuditSummary?> = _showClosingAudit.asStateFlow()

    private val _integrityDialogMessage = MutableStateFlow<String?>(null)
    val integrityDialogMessage: StateFlow<String?> = _integrityDialogMessage.asStateFlow()

    private val _showMultiDayErrorDialog = MutableStateFlow(false)
    val showMultiDayErrorDialog: StateFlow<Boolean> = _showMultiDayErrorDialog.asStateFlow()

    private val _situationLimitConfirmation = MutableStateFlow<House?>(null)
    val situationLimitConfirmation: StateFlow<House?> = _situationLimitConfirmation.asStateFlow()

    private val _showHistoryUnlockConfirmation = MutableStateFlow(false)
    val showHistoryUnlockConfirmation: StateFlow<Boolean> = _showHistoryUnlockConfirmation.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _moveConfirmationData = MutableStateFlow<Pair<House, String>?>(null)
    val moveConfirmationData: StateFlow<Pair<House, String>?> = _moveConfirmationData.asStateFlow()

    val themeMode: StateFlow<String?> = settingsManager.themeMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    
    val themeColor: StateFlow<String?> = settingsManager.themeColor
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val popSound: StateFlow<String> = settingsManager.popSound
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "SYSTEM_NOTIFICATION_1")

    val successSound: StateFlow<String> = settingsManager.successSound
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "SYSTEM_NOTIFICATION_1")

    val celebrationSound: StateFlow<String> = settingsManager.celebrationSound
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "SYSTEM_ALARM")

    val warningSound: StateFlow<String> = settingsManager.warningSound
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "SYSTEM_NOTIFICATION_2")

    val easyMode: StateFlow<Boolean> = settingsManager.easyMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val backupFrequency: StateFlow<com.antigravity.healthagent.data.backup.BackupFrequency> = settingsManager.backupFrequency
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), com.antigravity.healthagent.data.backup.BackupFrequency.DAILY)

    val solarMode: StateFlow<Boolean> = settingsManager.solarMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)


    val isAppModeSelected: StateFlow<Boolean?> = settingsManager.isAppModeSelected
        .map { it as Boolean? }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null) // Default null to wait for data


    // --- Computed State ---
    val isDayClosed: StateFlow<Boolean> = combine(_data, _agentName) { date, agent ->
        Pair(date, agent)
    }.flatMapLatest { (date, agent) ->
        repository.getDayActivityFlow(date, agent).map { it?.isClosed == true }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val filteredHouses: StateFlow<List<House>> = combine(houses, _searchQuery, _data) { list, query, date ->
        list.filter { it.data == date && (query.isBlank() || it.streetName.contains(query, ignoreCase = true) || it.blockNumber.contains(query, ignoreCase = true)) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val filteredHousesUiState: StateFlow<List<HouseUiState>> = filteredHouses.map { list ->
        list.map { house ->
            val invalidFields = houseValidationUseCase.getInvalidFields(house, strict = true).toSet()
            val totalDeposits = house.a1 + house.a2 + house.b + house.c + house.d1 + house.d2 + house.e
            val isTreated = totalDeposits > 0 || house.eliminados > 0 || house.larvicida > 0.0
            
            val treatmentParts = mutableListOf<String>()
            if (house.a1 > 0) treatmentParts.add("A1: ${house.a1}")
            if (house.a2 > 0) treatmentParts.add("A2: ${house.a2}")
            if (house.b > 0) treatmentParts.add("B: ${house.b}")
            if (house.c > 0) treatmentParts.add("C: ${house.c}")
            if (house.d1 > 0) treatmentParts.add("D1: ${house.d1}")
            if (house.d2 > 0) treatmentParts.add("D2: ${house.d2}")
            if (house.e > 0) treatmentParts.add("E: ${house.e}")
            if (house.eliminados > 0) treatmentParts.add("Elim: ${house.eliminados}")
            if (house.larvicida > 0.0) treatmentParts.add("Larv: ${house.larvicida}g")

            HouseUiState(
                house = house,
                invalidFields = invalidFields,
                highlightErrors = invalidFields.isNotEmpty(),
                isTreated = isTreated,
                blockDisplay = if (house.blockSequence.isNotBlank()) "${house.blockNumber} / ${house.blockSequence}" else house.blockNumber,
                formattedStreet = house.streetName.formatStreetName().ifBlank { "Sem Logradouro" },
                treatmentShortSummary = treatmentParts.joinToString(" | ")
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val pendingHousesCount: StateFlow<Int> = filteredHouses.map { list ->
        list.count { it.situation == Situation.NONE }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val strictPendingHousesCount: StateFlow<Int> = filteredHouses.map { list ->
        list.count { !houseValidationUseCase.isHouseValid(it, strict = true) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    fun getInvalidFields(house: House): Set<String> {
        return houseValidationUseCase.getInvalidFields(house, strict = true).toSet()
    }

    val dashboardTotals: StateFlow<DashboardTotals> = combine(houses, _data) { list, date ->
        val dayHouses = list.filter { it.data == date }
        DashboardTotals(
            totalHouses = dayHouses.count { it.situation == Situation.NONE },
            a1 = dayHouses.sumOf { it.a1 }, a2 = dayHouses.sumOf { it.a2 },
            b = dayHouses.sumOf { it.b }, c = dayHouses.sumOf { it.c },
            d1 = dayHouses.sumOf { it.d1 }, d2 = dayHouses.sumOf { it.d2 },
            e = dayHouses.sumOf { it.e }, eliminados = dayHouses.sumOf { it.eliminados },
            larvicida = dayHouses.sumOf { it.larvicida },
            comFoco = dayHouses.count { it.comFoco },
            totalRegisteredHouses = dayHouses.size
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DashboardTotals())

    val daysWithErrors: StateFlow<List<DayErrorSummary>> = houses.map { all ->
        try {
            all.groupBy { it.data }
                .mapNotNull { (date, h) -> 
                    val validationResult = houseValidationUseCase.validateCurrentDay(date, h, strict = true)
                    if (!validationResult.isValid) {
                        val errorCount = h.count { !houseValidationUseCase.isHouseValid(it, strict = true) }
                        if (errorCount > 0) DayErrorSummary(date, errorCount) else null
                    } else null
                }
                .sortedByDescending { getTimestamp(it.date) }
        } catch (e: Exception) {
            emptyList()
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val streetSuggestions: StateFlow<List<String>> = _bairro.flatMapLatest { currentB ->
        streetRepository.getStreetSuggestions(currentB)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val maxOpenHouses: StateFlow<Int> = settingsManager.maxOpenHouses
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 25)

    // --- RG State ---
    private val _rgYear = MutableStateFlow(Calendar.getInstance().get(Calendar.YEAR).toString())
    val rgYear: StateFlow<String> = _rgYear.asStateFlow()

    // Semester filter removed as per user request
    // private val _rgSemester = MutableStateFlow(if (Calendar.getInstance().get(Calendar.MONTH) < 6) 1 else 2)
    // val rgSemester: StateFlow<Int> = _rgSemester.asStateFlow()

    private val _selectedRgBairro = MutableStateFlow("")
    val selectedRgBairro: StateFlow<String> = _selectedRgBairro.asStateFlow()

    private val _selectedRgBlock = MutableStateFlow("")
    val selectedRgBlock: StateFlow<String> = _selectedRgBlock.asStateFlow()

    // Helper to filter houses by Year (Semester removed)
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

    val availableYears: StateFlow<List<String>> = houses.map { all ->
        all.mapNotNull { 
            try { 
                val d = dateFormatter.parse(it.data)
                if(d != null) {
                    val c = Calendar.getInstance().apply { time = d }
                    c.get(Calendar.YEAR).toString()
                } else null 
            } catch (e: Exception) { null }
        }.distinct().sortedDescending()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), listOf(Calendar.getInstance().get(Calendar.YEAR).toString()))

    val rgBairros: StateFlow<List<String>> = combine(houses, _rgYear) { all, year ->
        filterByYear(all, year)
            .map { it.bairro.trim().formatStreetName() }
            .distinct()
            .sorted()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Define a class to represent a Block Segment
    data class BlockSegment(
        val blockNumber: String,
        val blockSequence: String,
        val startDate: String,
        val endDate: String,
        val isConcluded: Boolean,
        val conclusionDate: String?,
        val houses: List<House>
    ) {
        val label: String
            get() {
                val base = if (blockSequence.isNotBlank()) "$blockNumber / $blockSequence" else blockNumber
                return if (isConcluded) "$base (Concluído $conclusionDate)" else "$base (Em Aberto)"
            }
        
        val id: String
             get() = "${blockNumber}_${blockSequence}_${if(isConcluded) "C" else "O"}_${startDate}"
    }

    // Helper to generate key for dropdown
    private fun getBlockSegmentKey(segment: BlockSegment): String {
        // We use a unique string representation that can be parsed back or just matched
        // Format: "Block/Seq|StartDate|EndDate" (Using pipes to avoid visible confusion, but label is what matters for UI usually if we map it, 
        // but here rgBlocks is List<String> so the string IS the UI. We need a readable string that is also unique.)
        // Strategy: The string will be the Display String. 
        // "01 / A (Concluído 10/01)"
        // "01 / A (Aberto)"
        // If there are multiple concluded segments for same block (rare in one semester but possible), date distinguishes them.
        return segment.label
    }

    // Updated to return BlockSegment objects instead of Strings
    val rgBlocks: StateFlow<List<BlockSegment>> = combine(houses, _selectedRgBairro, _rgYear) { all, bairro, year ->
        if (bairro.isBlank()) emptyList()
        else {
            // New Requirement: RG Segments can span years. Reference year is the Conclusion Year (or last year if Open).
            // 1. Get ALL houses for this bairro, regardless of year
            val bairroHouses = all.filter { it.bairro.equals(bairro, ignoreCase = true) }
            
            val segments = mutableListOf<BlockSegment>()
            
            // 2. Sort global list for this Bairro by Date -> ListOrder
            val sortedHouses = bairroHouses.sortedWith(compareBy({ getTimestamp(it.data) }, { it.listOrder }))
            
            // 3. Group by Block+Seq
            val groupedByBlock = sortedHouses.groupBy { Pair(it.blockNumber, it.blockSequence) }
            
            groupedByBlock.keys.sortedWith(compareBy({ it.first.padStart(10, '0') }, { it.second })).forEach { key ->
                val (bNum, bSeq) = key
                val blockHouses = groupedByBlock[key] ?: return@forEach 
                
                var currentSegmentHouses = mutableListOf<House>()
                
                // Group blockHouses by Date
                val housesByDate = blockHouses.groupBy { it.data }
                val sortedDates = housesByDate.keys.sortedBy { getTimestamp(it) }
                
                sortedDates.forEachIndexed { index, date ->
                    val dayHouses = housesByDate[date]?.sortedBy { it.listOrder } ?: emptyList()
                    currentSegmentHouses.addAll(dayHouses)
                    
                    val lastHouseOfDay = dayHouses.last()
                    
                    // Criterion A: Manual Flag
                    val manualConcluded = dayHouses.any { it.quarteiraoConcluido }
                    
                    // Criterion B: Auto-Conclusion (Transition to DIFFERENT block/bairro on same day)
                    // (Strictly: it means we stopped working on this block and did something else afterwards)
                    val allWorkThatDay = all.filter { it.data == date }.sortedBy { it.listOrder }
                    val indexOfLast = allWorkThatDay.indexOfFirst { it.id == lastHouseOfDay.id }
                    val autoConcluded = indexOfLast != -1 && indexOfLast < allWorkThatDay.size - 1
                    
                    if (manualConcluded || autoConcluded) {
                         // Close segment
                         val seg = BlockSegment(
                             blockNumber = bNum,
                             blockSequence = bSeq,
                             startDate = currentSegmentHouses.first().data,
                             endDate = currentSegmentHouses.last().data,
                             isConcluded = true,
                             conclusionDate = lastHouseOfDay.data,
                             houses = currentSegmentHouses.toList()
                         )
                         segments.add(seg)
                         currentSegmentHouses = mutableListOf()
                    }
                }
                
                // If anything remains, it's an Open Segment
                if (currentSegmentHouses.isNotEmpty()) {
                     val seg = BlockSegment(
                         blockNumber = bNum,
                         blockSequence = bSeq,
                         startDate = currentSegmentHouses.first().data,
                         endDate = currentSegmentHouses.last().data,
                         isConcluded = false,
                         conclusionDate = null,
                         houses = currentSegmentHouses.toList()
                     )
                     segments.add(seg)
                }
            }
            
            // 4. Filter segments by the selected Year
            // Rule: "Join entire RG into the year where it finishes"
            // If Concluded: Check Year of Conclusion Date
            // If Open: Check Year of End Date (Most recent activity)
            segments.filter { segment ->
                val refDate = segment.conclusionDate ?: segment.endDate
                try {
                    val d = dateFormatter.parse(refDate)
                    if (d != null) {
                        val c = Calendar.getInstance().apply { time = d }
                        c.get(Calendar.YEAR).toString() == year
                    } else false
                } catch (e: Exception) { false }
            }.sortedWith(compareBy({ it.blockNumber.padStart(10, '0') }, { it.blockSequence }, { getTimestamp(it.startDate) }))
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Filtered list now just finds the matching segment in rgBlocks
    val rgFilteredList: StateFlow<List<House>> = combine(rgBlocks, _selectedRgBlock) { blocks, selectedId ->
        if (selectedId.isBlank()) emptyList()
        else {
            blocks.find { it.id == selectedId }?.houses ?: emptyList()
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // --- Semanal State ---
    private val _currentWeekStart = MutableStateFlow(Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
    })

    val currentWeekDates: StateFlow<List<String>> = _currentWeekStart.map { start ->
        val dates = mutableListOf<String>()
        val cal = start.clone() as Calendar
        // Work week: Monday to Friday
        for (i in 0..4) { 
            dates.add(dateFormatter.format(cal.time))
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        dates
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val weekRangeText: StateFlow<String> = _currentWeekStart.map { start ->
        val cal = start.clone() as Calendar
        // PDF/User logic: Sunday to Saturday
        // We are already at Monday. Go back 1 to Sunday.
        cal.add(Calendar.DAY_OF_YEAR, -1)
        val sunday = cal.time
        cal.add(Calendar.DAY_OF_YEAR, 6)
        val saturday = cal.time
        
        "${displayDateFormatter.format(sunday)} a ${displayDateFormatter.format(saturday)}"
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")
    
    val weeklySummary: StateFlow<List<DaySummary>> = combine(currentWeekDates, _agentName) { dates, agent -> Pair(dates, agent) }
        .flatMapLatest { (dates, agent) ->
        val activitiesFlow = repository.getDayActivities(dates, agent)
        combine(houses, activitiesFlow) { all, activities ->
            dates.map { date ->
                val dayHouses = all.filter { it.data == date }
                val activity = activities.find { it.date == date }
                val openHousesCount = dayHouses.count { it.situation == Situation.NONE }
                DaySummary(date, openHousesCount, activity?.status ?: "")
            }
        }
    }
    .flowOn(Dispatchers.Default)
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activityOptions: StateFlow<List<String>> = settingsManager.customActivities.map { custom ->
        val allOptions = (listOf("FERIADO", "FOLGA DE ANIVERSÁRIO", "SERVIÇO INTERNO", "TEMPO CHUVOSO") + custom.toList()).distinct().sorted()
        listOf("NORMAL") + allOptions
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), listOf("NORMAL"))

    val customActivities: StateFlow<Set<String>> = settingsManager.customActivities
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    // --- Boletim State ---
    val boletimList: StateFlow<List<BoletimSummary>> = combine(houses, _agentName) { h, a -> Pair(h, a) }
        .flatMapLatest { (allHouses, agentName) ->
        val dates = allHouses.map { it.data }.distinct()
        repository.getDayActivities(dates, agentName).map { activities ->
            allHouses.groupBy { it.data }.map { (date, dayHouses) ->
                val activity = activities.find { it.date == date }
                
                // Group by block AND bairro (Fix: Distinguish blocks with same number in different bairros)
                val dayHousesSorted = dayHouses.sortedBy { it.listOrder }
                
                // Key: Triple(Bairro, BlockNumber, BlockSequence)
                // We use formatted names to avoid case sensitivity issues
                val blockMap = dayHousesSorted.groupBy { 
                    Triple(it.bairro.trim().uppercase(), it.blockNumber, it.blockSequence) 
                }
                
                val completedBlocks = mutableListOf<Triple<String, String, String>>()
                
                blockMap.forEach { (blockKey, blockHouses) ->
                    val (bairroKey, bNum, bSeq) = blockKey
                    
                    // a) Manual flag check
                    val hasManual = blockHouses.any { it.quarteiraoConcluido }
                    
                    if (hasManual) {
                        completedBlocks.add(blockKey)
                    } else {
                        // b) Auto-concluded: last house of block has a successor in day's production
                        val lastInBlock = blockHouses.last()
                        val lastInBlockId = lastInBlock.id
                        val indexOfLast = dayHousesSorted.indexOfFirst { it.id == lastInBlockId }
                        
                        // Check if there is a next house in the sorted list
                        if (indexOfLast != -1 && indexOfLast < dayHousesSorted.size - 1) {
                            // If the next house has a different Bairro/Block/Seq (which it must, by definition of grouping),
                            // then this block segment is effectively "left behind" -> Concluded
                             completedBlocks.add(blockKey)
                        }
                    }
                }
                
                val bairrosWithLocalidadeConcluida = dayHouses
                    .filter { it.localidadeConcluida }
                    .map { it.bairro }
                    .toSet()
                
                val blockSummaries = blockMap.map { (blockKey, blockHouses) ->
                    val (bairroKey, bNum, bSeq) = blockKey
                    val firstBH = blockHouses.first()
                    
                    BlockSummary(
                        number = bNum,
                        sequence = bSeq,
                        bairro = firstBH.bairro,
                        isCompleted = completedBlocks.contains(blockKey),
                        isLocalidadeConcluded = bairrosWithLocalidadeConcluida.contains(firstBH.bairro),
                        totalHouses = blockHouses.count { it.situation == Situation.NONE },
                        totalVisits = blockHouses.size,
                        focos = blockHouses.count { it.comFoco }
                    )
                }.sortedWith(compareBy({ it.isCompleted }, { it.bairro }, { it.number }))

                BoletimSummary(
                    date = date,
                    agentName = dayHouses.firstOrNull()?.agentName ?: "",
                    totals = DashboardTotals(
                        totalHouses = dayHouses.count { it.situation == Situation.NONE },
                        a1 = dayHouses.sumOf { it.a1 }, a2 = dayHouses.sumOf { it.a2 },
                        b = dayHouses.sumOf { it.b }, c = dayHouses.sumOf { it.c },
                        d1 = dayHouses.sumOf { it.d1 }, d2 = dayHouses.sumOf { it.d2 },
                        e = dayHouses.sumOf { it.e }, eliminados = dayHouses.sumOf { it.eliminados },
                        larvicida = dayHouses.sumOf { it.larvicida },
                        comFoco = dayHouses.count { it.comFoco },
                        totalRegisteredHouses = dayHouses.size
                    ),
                    blocks = blockSummaries
                )
            }.sortedByDescending { getTimestamp(it.date) }
        }
    }
    .flowOn(Dispatchers.Default)
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // --- Actions ---
    fun validateCurrentDay(showDialog: Boolean): Boolean {
        val result = houseValidationUseCase.validateCurrentDay(_data.value, houses.value, strict = true)
        if (!result.isValid) {
            _validationErrorHouseIds.value = result.errorHouseIds
            if (showDialog) {
                _integrityDialogMessage.value = result.dialogMessage
                soundManager.playWarning()
            }
        } else {
            _validationErrorHouseIds.value = emptySet()
        }
        return result.isValid
    }

    init {
        viewModelScope.launch {
            try {
                houseManagementUseCase.migrateStreetNamesToFormat()
                houseManagementUseCase.migrateBairrosToTitleCase()
            } catch (e: Exception) {
                android.util.Log.e("HomeViewModel", "Error migrating data", e)
            }
        }

        // Check for existing multi-day errors on startup
        viewModelScope.launch {
            daysWithErrors.filter { it.isNotEmpty() }.first()
            _showMultiDayErrorDialog.value = true
        }

        // Observe bairro changes for automatic tipo selection
        viewModelScope.launch {
            _bairro.collect { b ->
                if (b.equals("Centro", ignoreCase = true)) {
                    _tipo.value = 1
                } else {
                    _tipo.value = 2
                }
            }
        }

        // Observe date changes to update ciclo
        viewModelScope.launch {
            _data.collect { d ->
                _ciclo.value = calculateCicloForDate(d)
            }
        }

        // Set initial date to the last work day (most recent day with houses) or generate tutorial data
        viewModelScope.launch {
            // Check directly from repo to avoid StateFlow initial value race conditions
            val allHouses = withContext(Dispatchers.IO) {
                repository.getAllHousesOnce()
            }
            
            if (allHouses.isNotEmpty()) {
                val lastWorkDayHouse = allHouses.maxByOrNull { house ->
                    try {
                        dateFormatter.parse(house.data)?.time ?: 0L
                    } catch (e: Exception) {
                        0L
                    }
                }
                if (lastWorkDayHouse != null) {
                    _data.value = lastWorkDayHouse.data
                    _agentName.value = lastWorkDayHouse.agentName
                }
            }
        }

        // Observe date changes and inherit values from the most recent house
        viewModelScope.launch {
            combine(_data, houses) { date, allHouses ->
                val dayHouses = allHouses.filter { it.data == date }
                if (dayHouses.isNotEmpty()) {
                    dayHouses.maxByOrNull { it.listOrder }
                } else {
                    // Smart Inheritance: If current day is empty, find the absolute last worked house
                    allHouses.maxByOrNull { 
                        try {
                            dateFormatter.parse(it.data)?.time ?: 0L
                        } catch (e: Exception) { 0L }
                    }
                }
            }.collectLatest { lastHouse ->
                if (lastHouse != null) {
                    _bairro.value = lastHouse.bairro
                    _currentBlock.value = lastHouse.blockNumber
                    _currentBlockSequence.value = lastHouse.blockSequence
                    _currentStreet.value = lastHouse.streetName
                    _agentName.value = lastHouse.agentName
                    _municipio.value = lastHouse.municipio
                }
                // Clear validation highlights when switching days
                _validationErrorHouseIds.value = emptySet()
            }
        }

        // Initialize RG Selection with Last Worked Bairro
        viewModelScope.launch {
            _bairro.filter { it.isNotBlank() }.first().let { workedBairro ->
                if (_selectedRgBairro.value.isBlank()) {
                    _selectedRgBairro.value = workedBairro
                }
            }
        }

        // Initialize Auto-Backup on Startup
        viewModelScope.launch {
            backupFrequency.first().let { freq ->
                backupScheduler.scheduleBackup(freq)
            }
        }
    }



    fun selectAppMode(enableEasyMode: Boolean) {
        viewModelScope.launch {
            settingsManager.setEasyMode(enableEasyMode)
            settingsManager.setAppModeSelected(true)
        }
    }

    fun updateSolarMode(enabled: Boolean) {
        viewModelScope.launch {
            settingsManager.setSolarMode(enabled)
        }
    }


    fun toggleDayLock() {
        viewModelScope.launch {
            try {
                val closed = isDayClosed.value
                if (closed) {
                    if (dayManagementUseCase.canSafelyUnlock(_data.value)) {
                        dayManagementUseCase.unlockDay(_data.value, _agentName.value)
                    } else {
                        _showHistoryUnlockConfirmation.value = true
                    }
                } else {
                    // Lock icon only for opening. Closing now handled by startDayClosingFlow (Audit).
                    // We show a message to the user for clarity.
                    _uiEvent.value = "Para fechar o dia, realize a auditoria."
                }
            } catch (e: Exception) {
                _uiEvent.value = "Erro ao alterar estado do dia: ${e.message}"
                soundManager.playWarning()
            }
        }
    }

    fun startDayClosingFlow() {
        viewModelScope.launch {
            val workedCount = houses.value.filter { it.data == _data.value && it.situation == Situation.NONE }.size
            if (workedCount < maxOpenHouses.value) {
                _uiEvent.value = "Meta não atingida! (Trabalhados: $workedCount / ${maxOpenHouses.value})"
                return@launch
            }

            if (validateCurrentDay(showDialog = true)) {
                val summary = calculateAuditSummary(_data.value)
                _showClosingAudit.value = summary
            }
        }
    }

    fun syncDataToCloud() {
        if (_isSyncing.value) return
        _isSyncing.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val houses = repository.getAllHousesOnce()
                val activities = repository.getAllDayActivitiesOnce()
                val result = syncRepository.pushLocalDataToCloud(houses, activities)
                if (result.isSuccess) {
                    _uiEvent.value = "Dados sincronizados com sucesso."
                } else {
                    _uiEvent.value = "Falha ao sincronizar: ${result.exceptionOrNull()?.message}"
                }
            } catch (e: Exception) {
                _uiEvent.value = "Erro na sincronização."
            } finally {
                _isSyncing.value = false
            }
        }
    }

    private fun calculateAuditSummary(date: String): AuditSummary {
        val dayHouses = houses.value.filter { it.data == date }
        return AuditSummary(
            date = date,
            totalWorked = dayHouses.count { it.situation == Situation.NONE },
            totalTreated = dayHouses.count { (it.a1+it.a2+it.b+it.c+it.d1+it.d2+it.e+it.eliminados) > 0 || it.larvicida > 0.0 },
            totalClosed = dayHouses.count { it.situation == Situation.F },
            totalRefused = dayHouses.count { it.situation == Situation.REC },
            totalAbsent = dayHouses.count { it.situation == Situation.A },
            totalVacant = dayHouses.count { it.situation == Situation.V },
            a1 = dayHouses.sumOf { it.a1 }, a2 = dayHouses.sumOf { it.a2 }, b = dayHouses.sumOf { it.b },
            c = dayHouses.sumOf { it.c }, d1 = dayHouses.sumOf { it.d1 }, d2 = dayHouses.sumOf { it.d2 },
            e = dayHouses.sumOf { it.e }, eliminados = dayHouses.sumOf { it.eliminados },
            totalLarvicide = dayHouses.sumOf { it.larvicida }
        )
    }

    fun confirmAndCloseDay(audit: AuditSummary) {
        viewModelScope.launch {
            try {
                dayManagementUseCase.closeDay(audit.date, _agentName.value)
                _showClosingAudit.value = null
                _showGoalReached.value = true
            } catch (e: Exception) {
                _uiEvent.value = "Erro ao fechar o dia: ${e.message}"
                soundManager.playWarning()
            }
        }
    }

    fun advanceToNextDay() {
        viewModelScope.launch {
            try {
                val next = dayManagementUseCase.getNextBusinessDay(_data.value, _agentName.value)
                if (next.isNotBlank()) {
                    _data.value = next
                    soundManager.playPop()
                    _showGoalReached.value = false
                }
            } catch (e: Exception) {
                _uiEvent.value = "Erro ao avançar dia: ${e.message}"
            }
        }
    }

    fun moveDateBackward() {
        viewModelScope.launch {
            try {
                val calendar = Calendar.getInstance()
                // Use safe parsing
                val date = try { dateFormatter.parse(_data.value) } catch (e: Exception) { null }
                calendar.time = date ?: Date()
                
                do {
                    calendar.add(Calendar.DAY_OF_YEAR, -1)
                } while (calendar.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY || calendar.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY)
                _data.value = dateFormatter.format(calendar.time)
                soundManager.playPop()
            } catch (e: Exception) {
                _uiEvent.value = "Erro ao navegar: ${e.message}"
            }
        }
    }

    fun moveDateForward() {
        viewModelScope.launch {
            try {
                val calendar = Calendar.getInstance()
                 // Use safe parsing
                val date = try { dateFormatter.parse(_data.value) } catch (e: Exception) { null }
                calendar.time = date ?: Date()
                
                do {
                    calendar.add(Calendar.DAY_OF_YEAR, 1)
                } while (calendar.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY || calendar.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY)
                _data.value = dateFormatter.format(calendar.time)
                soundManager.playPop()
            } catch (e: Exception) {
                 _uiEvent.value = "Erro ao navegar: ${e.message}"
            }
        }
    }

    private var isAddingHouse = false
    private var lastAddClickTime = 0L

    fun addNewHouse() {
        val currentTime = System.currentTimeMillis()
        if (isAddingHouse || currentTime - lastAddClickTime < 300) return
        
        isAddingHouse = true
        lastAddClickTime = currentTime

        viewModelScope.launch {
            try {
                // Pre-check for open limit inside the safe scope
                val currentOpen = houses.value.count { it.data == _data.value && (it.situation == Situation.NONE || it.situation == Situation.EMPTY) }
                if (currentOpen >= maxOpenHouses.value && maxOpenHouses.value > 0) {
                    if (validateCurrentDay(showDialog = true)) {
                        startDayClosingFlow()
                    }
                    return@launch
                }

                // Check if the day is empty (no houses at all for this date)
                val isDayEmpty = houses.value.none { it.data == _data.value }
                var prediction: HouseManagementUseCase.HousePrediction
                var initialBlock = _currentBlock.value
                var initialStreet = _currentStreet.value
                var initialBlockSeq = _currentBlockSequence.value
                
                if (isDayEmpty) {
                    // Find the absolute last house globally (presumably from previous workdays)
                    val lastGlobalHouse = houses.value.maxByOrNull { it.listOrder }
                    
                    if (lastGlobalHouse != null) {
                        // Inherit Context
                        initialBlock = lastGlobalHouse.blockNumber
                        initialStreet = lastGlobalHouse.streetName
                        initialBlockSeq = lastGlobalHouse.blockSequence
                        
                        // Update UI State Context
                        _currentBlock.value = initialBlock
                        _currentStreet.value = initialStreet
                        _currentBlockSequence.value = initialBlockSeq
                        _bairro.value = lastGlobalHouse.bairro
                        _municipio.value = lastGlobalHouse.municipio
                        _agentName.value = lastGlobalHouse.agentName
                        
                        // Predict based on history
                        prediction = houseManagementUseCase.predictBasedOnHistory(houses.value, lastGlobalHouse)
                    } else {
                        // No history at all, just blank prediction
                        prediction = HouseManagementUseCase.HousePrediction("", null, null, com.antigravity.healthagent.data.local.model.PropertyType.EMPTY, Situation.NONE)
                    }
                } else {
                    // Standard intra-day prediction
                    prediction = houseManagementUseCase.predictNextHouseValues(
                        houses.value, 
                        _data.value,
                        _currentBlock.value,
                        _currentStreet.value
                    )
                }

                val maxOrder = houses.value.maxOfOrNull { it.listOrder } ?: 0L

                houseManagementUseCase.insertHouse(House(
                    id = 0,
                    blockNumber = initialBlock.trim().uppercase(),
                    blockSequence = initialBlockSeq.trim().uppercase(),
                    streetName = initialStreet.trim().uppercase(),
                    number = prediction.number.trim().uppercase(),
                    sequence = prediction.sequence,
                    complement = prediction.complement,
                    propertyType = prediction.propertyType,
                    situation = prediction.situation,
                    tipo = _tipo.value,
                    ciclo = _ciclo.value,
                    municipio = _municipio.value.trim().uppercase(),
                    bairro = _bairro.value.trim().formatStreetName(),
                    agentName = _agentName.value.trim().uppercase(),
                    data = _data.value,
                    listOrder = maxOrder + 1
                ))
                soundManager.playPop()
            } catch (e: Exception) {
                android.util.Log.e("HomeViewModel", "Error adding new house", e)
                _uiEvent.value = "Erro ao adicionar imóvel: ${e.message}"
                soundManager.playWarning()
            } finally {
                delay(100) // Small buffer
                isAddingHouse = false
            }
        }
    }

    fun updateHouse(house: House) {
        val original = houses.value.find { it.id == house.id }


        if (original != null && house.situation == Situation.NONE && original.situation != Situation.NONE) {
            val open = houses.value.count { it.data == _data.value && it.situation == Situation.NONE }
            if (open >= maxOpenHouses.value) {
                soundManager.playWarning()
                _situationLimitConfirmation.value = house
                return
            }
        }
        performUpdateHouse(house)
    }

    private fun performUpdateHouse(house: House) {
        viewModelScope.launch {
            try {
                val result = houseManagementUseCase.updateHouseWithContext(house, houses.value)
                if (result.localizationChanged) {
                    _bairro.value = result.updatedHouse.bairro
                    _currentBlock.value = result.updatedHouse.blockNumber
                    _currentBlockSequence.value = result.updatedHouse.blockSequence
                    _currentStreet.value = result.updatedHouse.streetName
                    houseManagementUseCase.updateHouses(result.subsequentHouses + result.updatedHouse)
                } else {
                    houseManagementUseCase.updateHouse(result.updatedHouse)
                }
                
                // If this house had a validation error highlight, remove it as it's being corrected
                if (_validationErrorHouseIds.value.contains(house.id)) {
                    _validationErrorHouseIds.value = _validationErrorHouseIds.value - house.id
                }
            } catch (e: Exception) {
                android.util.Log.e("HomeViewModel", "Error updating house", e)
            }
        }
    }

    fun deleteHouse(house: House) {
        viewModelScope.launch {
            try {
                recentlyDeletedHouse = house
                houseManagementUseCase.deleteHouse(house)
                soundManager.playPop()
            } catch (e: Exception) {
                android.util.Log.e("HomeViewModel", "Error deleting house", e)
            }
        }
    }

    private var recentlyDeletedHouse: House? = null
    fun restoreDeletedHouse() {
        recentlyDeletedHouse?.let {
            viewModelScope.launch { 
                houseManagementUseCase.insertHouse(it.copy(id = 0))
                recentlyDeletedHouse = null 
                soundManager.playPop()
            }
        }
    }

    fun persistListOrder(reorderedList: List<House>) {
        viewModelScope.launch {
            houseManagementUseCase.updateHouses(reorderedList.mapIndexed { index, h -> h.copy(listOrder = index.toLong()) })
        }
    }

    fun moveHouse(house: House, moveUp: Boolean) {
        viewModelScope.launch {
            val list = filteredHouses.value.toMutableList()
            val index = list.indexOfFirst { it.id == house.id }
            if (index != -1) {
                if (moveUp && index > 0) {
                    Collections.swap(list, index, index - 1)
                } else if (!moveUp && index < list.size - 1) {
                    Collections.swap(list, index, index + 1)
                }
                persistListOrder(list)
            }
        }
    }

    fun moveHouseToDate(house: House, newDate: String) {
        val existingOpen = houses.value.count { it.data == newDate && (it.situation == Situation.NONE || it.situation == Situation.EMPTY) }
        if (existingOpen >= maxOpenHouses.value && (house.situation == Situation.NONE || house.situation == Situation.EMPTY)) {
            _moveConfirmationData.value = house to newDate
            return
        }
        performMoveHouse(house, newDate)
    }

    private fun performMoveHouse(house: House, newDate: String) {
        viewModelScope.launch { repository.updateHouse(house.copy(data = newDate)) }
    }

    fun moveHousesToDate(oldDate: String, newDate: String) {
        viewModelScope.launch {
            val housesToMove = houses.value.filter { it.data == oldDate }
            repository.updateHouses(housesToMove.map { it.copy(data = newDate) })
        }
    }

    fun confirmMoveHouse() {
        _moveConfirmationData.value?.let { (house, date) ->
            performMoveHouse(house, date)
            _moveConfirmationData.value = null
        }
    }
    fun dismissMoveConfirmation() { _moveConfirmationData.value = null }

    fun goToToday() {
        _data.value = dateFormatter.format(Date())
    }

    fun goToLastWorkDay() {
        viewModelScope.launch {
            val allHouses = houses.value
            val lastWorkDay = allHouses.maxByOrNull { house ->
                try {
                    dateFormatter.parse(house.data)?.time ?: 0L
                } catch (e: Exception) {
                    0L
                }
            }?.data
            if (lastWorkDay != null) {
                _data.value = lastWorkDay
            }
        }
    }

    fun goToCurrentWeek() {
        _currentWeekStart.value = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        }
    }

    fun updateSearchQuery(q: String) { _searchQuery.value = q }
    fun updateBairro(b: String) { _bairro.value = b.uppercase() }
    fun updateAgentName(n: String) { _agentName.value = n.uppercase() }
    fun updateContext(b: String, s: String, st: String) {
        _currentBlock.value = b.uppercase(); _currentBlockSequence.value = s.uppercase(); _currentStreet.value = st.uppercase()
    }
    fun updateHeader(m: String, b: String, c: String, z: String, t: Int, d: String, ci: String, a: Int) {
        _municipio.value = m.uppercase()
        _bairro.value = b.uppercase()
        _categoria.value = c.uppercase()
        _zona.value = z.uppercase()
        _tipo.value = t
        _data.value = d
        _ciclo.value = ci.uppercase()
        _atividade.value = a
    }

    private fun calculateCicloForDate(dateStr: String): String {
        return try {
            val dateObj = dateFormatter.parse(dateStr)
            if (dateObj != null) {
                val cal = Calendar.getInstance()
                cal.time = dateObj
                val month = cal.get(Calendar.MONTH)
                when (month) {
                    Calendar.JANUARY, Calendar.FEBRUARY -> "1º"
                    Calendar.MARCH, Calendar.APRIL -> "2º"
                    Calendar.MAY, Calendar.JUNE -> "3º"
                    Calendar.JULY, Calendar.AUGUST -> "4º"
                    Calendar.SEPTEMBER, Calendar.OCTOBER -> "5º"
                    Calendar.NOVEMBER, Calendar.DECEMBER -> "6º"
                    else -> "1º"
                }
            } else "1º"
        } catch (e: Exception) {
            "1º"
        }
    }
    fun updateMunicipio(m: String) { _municipio.value = m.uppercase() }
    fun updateZona(z: String) { _zona.value = z.uppercase() }
    fun updateCategoria(c: String) { _categoria.value = c.uppercase() }

    // --- RG Actions ---
    fun selectRgYear(y: String) { _rgYear.value = y }
    // fun selectRgSemester(s: Int) { _rgSemester.value = s } // Removed
    fun selectRgBairro(b: String) { _selectedRgBairro.value = b; _selectedRgBlock.value = "" }
    fun selectRgBlock(q: String) { _selectedRgBlock.value = q }

    // --- Semanal Actions ---
    fun previousWeek() {
        _currentWeekStart.value = (_currentWeekStart.value.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -7) }
    }
    fun nextWeek() {
        _currentWeekStart.value = (_currentWeekStart.value.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 7) }
    }

    fun navigateToDate(date: String) {
        _data.value = date
        _navigationTab.value = 0 // Navigate to Production tab
    }

    fun clearNavigationTab() {
        _navigationTab.value = null
    }
    fun updateDayStatus(date: String, status: String) {
        viewModelScope.launch {
            try {
                val currentAgent = _agentName.value
                val oldActivities = repository.getAllDayActivitiesOnce()
                    .filter { it.agentName == currentAgent }
                    .associateBy { it.date }
                val oldStatus = oldActivities[date]?.status ?: "NORMAL"
                
                // Only trigger ripple if "Working Day" status changes
                val wasWorking = oldStatus == "NORMAL" || oldStatus.isBlank()
                val isWorking = status == "NORMAL" || status.isBlank()
                
                // Update the status first
                val existing = repository.getDayActivity(date, currentAgent)
                if (existing != null) {
                    repository.updateDayActivity(existing.copy(status = status))
                } else {
                    repository.updateDayActivity(DayActivity(date = date, status = status, agentName = currentAgent))
                }

                if (wasWorking != isWorking) {
                    // Ripple Effect: Shift production dates
                    val updatedStatusMap = oldActivities.toMutableMap()
                    updatedStatusMap[date] = DayActivity(date, status)
                    
                    val allHouses = repository.getAllHousesOnce().filter { it.agentName == currentAgent }
                    val targetDateObj = try { dateFormatter.parse(date) } catch (e: Exception) { null } ?: return@launch
                    
                    // Analyze production at or after the changed date
                    val housesToShift = allHouses.filter { 
                        val houseDate = try { dateFormatter.parse(it.data) } catch (e: Exception) { null }
                        houseDate != null && !houseDate.before(targetDateObj)
                    }
                    
                    if (housesToShift.isEmpty()) return@launch
                    
                    val productionDates = housesToShift.map { it.data }.distinct()
                    val productionDatesSet = productionDates.toSet()
                    val dateToOffset = mutableMapOf<String, Int>()
                    
                    // 1. Calculate offsets using old configuration
                    productionDates.forEach { pDate ->
                        var offset = 0
                        val cal = Calendar.getInstance()
                        cal.time = targetDateObj
                        val pDateObj = try { dateFormatter.parse(pDate) } catch (e: Exception) { null } ?: return@forEach
                        
                        while (cal.time.before(pDateObj)) {
                            val currentDateStr = dateFormatter.format(cal.time)
                            if (isWorkingDay(cal, oldActivities) || productionDatesSet.contains(currentDateStr)) {
                                offset++
                            }
                            cal.add(Calendar.DAY_OF_YEAR, 1)
                        }
                        dateToOffset[pDate] = offset
                    }
                    
                    // 2. Map offsets to new target dates using updated configuration
                    val sortedOffsets = dateToOffset.values.distinct().sorted()
                    val offsetToNewDate = mutableMapOf<Int, String>()
                    
                    var currentWorkingOffset = 0
                    val cal = Calendar.getInstance()
                    cal.time = targetDateObj
                    
                    var daysChecked = 0
                    while (currentWorkingOffset <= (sortedOffsets.lastOrNull() ?: 0) && daysChecked < 365) {
                        if (isWorkingDay(cal, updatedStatusMap)) {
                            if (sortedOffsets.contains(currentWorkingOffset)) {
                                offsetToNewDate[currentWorkingOffset] = dateFormatter.format(cal.time)
                            }
                            currentWorkingOffset++
                        }
                        cal.add(Calendar.DAY_OF_YEAR, 1)
                        daysChecked++
                    }
                    
                    // 3. Batch update records
                    val updatedHouses = housesToShift.mapNotNull { house ->
                        val offset = dateToOffset[house.data]
                        val newDate = offsetToNewDate[offset]
                        if (newDate != null && newDate != house.data) {
                            house.copy(data = newDate)
                        } else null
                    }
                    
                    if (updatedHouses.isNotEmpty()) {
                        repository.updateHouses(updatedHouses)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("HomeViewModel", "Error updating day status", e)
                _uiEvent.value = "Erro ao atualizar status do dia: ${e.message}"
            }
        }
    }
    fun addNewActivity(name: String) {
        viewModelScope.launch { settingsManager.addCustomActivity(name) }
    }
    fun removeActivity(name: String) {
        viewModelScope.launch { settingsManager.removeCustomActivity(name) }
    }
    suspend fun exportSemanalPdf(context: Context): File {
        return withContext(Dispatchers.IO) {
            val summary = weeklySummary.value
            val weekDates = summary.map { it.date }
            val allHouses = houses.value
            val activities = summary.associate { it.date to it.status }
            SemanalPdfGenerator.generatePdf(context, weekDates, allHouses, activities, agentName.value)
        }
    }

    suspend fun exportWeeklyBatchPdf(context: Context): File {
        return withContext(Dispatchers.IO) {
            val dates = currentWeekDates.value
            val allHouses = houses.value
            
            // Filter houses for the current week
            val weeklyHouses = allHouses.filter { dates.contains(it.data) }
            
            // Group by Date for the generator
            val weeklyData = weeklyHouses.groupBy { it.data }

            // Get activities for the week
            val activities = weeklySummary.value.associate { it.date to it.status }
            
            BoletimPdfGenerator.generateWeeklyBatchPdf(context, weeklyData, agentName.value, activities, dates)
        }
    }

    // --- Boletim Actions ---
    fun transferProduction(date: String, newAgent: String) {
        viewModelScope.launch {
            val housesToTransfer = houses.value.filter { it.data == date }
            repository.updateHouses(housesToTransfer.map { it.copy(agentName = newAgent) })
        }
    }

    fun getHousesForDate(date: String): List<House> {
        return houses.value.filter { it.data == date }
    }

    fun deleteProduction(date: String) {
        viewModelScope.launch {
            repository.deleteProduction(date, _agentName.value)
        }
    }

    fun exportDayDataAndShare(context: Context, date: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val currentAgent = _agentName.value
                val dayHouses = repository.getAllHousesOnce().filter { it.data == date && it.agentName == currentAgent }
                val dayActivities = repository.getAllDayActivitiesOnce().filter { it.date == date && it.agentName == currentAgent }
                val backupData = BackupData(dayHouses, dayActivities)

                // Generate Filename
                val safeAgentName = currentAgent.trim().replace(" ", "_").ifBlank { "Agente" }
                val fileName = "Producao_${safeAgentName}_${date.replace("/", "-")}.json"

                // Save to Cache Dir
                val backupDir = File(context.cacheDir, "exports")
                if (!backupDir.exists()) backupDir.mkdirs()

                val file = File(backupDir, fileName)
                BackupManager().exportToFile(file, backupData)

                // Create Share Intent
                val authority = "${context.packageName}.fileprovider"
                val uri = androidx.core.content.FileProvider.getUriForFile(context, authority, file)

                val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "application/json"
                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    putExtra(android.content.Intent.EXTRA_SUBJECT, fileName)
                    putExtra(android.content.Intent.EXTRA_TEXT, "Segue em anexo a produção do dia $date.")
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                val chooser = android.content.Intent.createChooser(shareIntent, "Compartilhar Produção")
                chooser.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)

                withContext(Dispatchers.Main) {
                    context.startActivity(chooser)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    _uiEvent.value = "Erro ao exportar dados: ${e.message}"
                    soundManager.playWarning()
                }
            }
        }
    }

    fun clearUiEvent() { _uiEvent.value = null }

    fun triggerUiEvent(message: String) {
        _uiEvent.value = message
    }
    fun dismissIntegrityDialog() { _integrityDialogMessage.value = null }
    fun dismissClosingAudit() { _showClosingAudit.value = null }
    fun dismissGoalReached() { _showGoalReached.value = false; advanceToNextDay() }
    fun navigateToErroneousDay(d: String) { 
        viewModelScope.launch {
            val activity = dayManagementUseCase.getDayActivity(d, _agentName.value)
            if (activity?.isClosed == true) {
                dayManagementUseCase.unlockDay(d, _agentName.value)
            }
            _data.value = d
            _showMultiDayErrorDialog.value = false
            // Allow UI to update to the new date before validating
            delay(300)
            validateCurrentDay(showDialog = true)
        }
    }
    fun dismissMultiDayErrorDialog() { _showMultiDayErrorDialog.value = false }
    fun showMultiDayErrorDialog() { _showMultiDayErrorDialog.value = true }

    fun confirmSituationExceeded() {
        _situationLimitConfirmation.value?.let { performUpdateHouse(it); _situationLimitConfirmation.value = null }
    }
    fun dismissSituationLimitConfirmation() { _situationLimitConfirmation.value = null }

    fun dismissHistoryUnlockConfirmation() { _showHistoryUnlockConfirmation.value = false }
    fun confirmUnlockHistory() {
        viewModelScope.launch {
            dayManagementUseCase.unlockDay(_data.value, _agentName.value)
            _showHistoryUnlockConfirmation.value = false
        }
    }

    fun backupData(context: Context, uri: android.net.Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val h = repository.getAllHousesOnce()
                val a = repository.getAllDayActivitiesOnce()
                BackupManager().exportData(context, uri, BackupData(h, a))
                withContext(Dispatchers.Main) {
                    _uiEvent.value = "Backup concluído com sucesso!"
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _uiEvent.value = "Erro ao salvar backup: ${e.message}"
                }
            }
        }
    }

    fun backupDataAndShare(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val h = repository.getAllHousesOnce()
                val a = repository.getAllDayActivitiesOnce()
                val backupData = BackupData(h, a)
                
                // Generate Filename
                val sdf = java.text.SimpleDateFormat("dd-MM-yyyy_HH-mm", java.util.Locale.getDefault())
                val now = sdf.format(java.util.Date())
                val safeAgentName = _agentName.value.trim().replace(" ", "_").ifBlank { "Agente" }
                val fileName = "Backup_${safeAgentName}_$now.json"
                
                // Save to Cache Dir (so we can share it via FileProvider)
                // Using cacheDir/backups to keep it clean
                val backupDir = File(context.cacheDir, "backups")
                if (!backupDir.exists()) backupDir.mkdirs()
                
                val file = File(backupDir, fileName)
                BackupManager().exportToFile(file, backupData)
                
                // Create Share Intent
                val authority = "${context.packageName}.fileprovider"
                val uri = androidx.core.content.FileProvider.getUriForFile(context, authority, file)
                
                val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "application/json"
                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    putExtra(android.content.Intent.EXTRA_SUBJECT, fileName)
                    putExtra(android.content.Intent.EXTRA_TEXT, "Segue em anexo o backup dos dados do agente $safeAgentName.")
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                
                val chooser = android.content.Intent.createChooser(shareIntent, "Salvar Backup em...")
                chooser.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                
                withContext(Dispatchers.Main) {
                    context.startActivity(chooser)
                    // _uiEvent.value = "Selecione onde salvar o backup" // Optional feedback
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    _uiEvent.value = "Erro ao gerar backup para compartilhamento: ${e.message}"
                    soundManager.playWarning()
                }
            }
        }
    }

    fun restoreData(context: Context, uri: android.net.Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val backupData = BackupManager().importData(context, uri)
                var targetAgent = _agentName.value.trim()
                
                // Smart Agent Adoption: If current is blank, try to find one in the backup
                val agentsInBackup = (backupData.houses.map { it.agentName } + backupData.dayActivities.map { it.agentName })
                    .filter { it.isNotBlank() }
                    .distinct()
                
                if (targetAgent.isBlank() && agentsInBackup.isNotEmpty()) {
                    targetAgent = agentsInBackup.first()
                    withContext(Dispatchers.Main) {
                        _agentName.value = targetAgent
                    }
                }

                // If backup has no agent names (legacy), use targetAgent (which might still be blank but at least we tried)
                val sanitizedHouses = backupData.houses.map { 
                    it.copy(id = 0, agentName = it.agentName.ifBlank { targetAgent }) 
                }
                val sanitizedActivities = backupData.dayActivities.map { 
                    it.copy(agentName = it.agentName.ifBlank { targetAgent }) 
                }

                // If it's a full restore, we might want to restore ALL agents data 
                // but usually the UI expects to restore into "some" context. 
                // To be safe and compatible with repository.restoreAgentData:
                if (targetAgent.isNotBlank()) {
                     repository.restoreAgentData(targetAgent, sanitizedHouses, sanitizedActivities)
                } else {
                     // Fallback to replacing all if no agent context at all (rare)
                     repository.replaceAllHouses(sanitizedHouses)
                     repository.replaceAllDayActivities(sanitizedActivities)
                }
                
                withContext(Dispatchers.Main) {
                    _uiEvent.value = "Backup restaurado com sucesso!"
                    soundManager.playPop()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _uiEvent.value = "Erro ao restaurar backup: ${e.message}"
                    soundManager.playWarning()
                }
            }
        }
    }

    fun clearAllData() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                repository.clearAllData()
                withContext(Dispatchers.Main) {
                    soundManager.playPop()
                    _uiEvent.value = "Todos os dados foram apagados."
                    _data.value = dateFormatter.format(Date())
                    _agentName.value = ""
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _uiEvent.value = "Erro ao apagar dados: ${e.message}"
                }
            }
        }
    }

    fun importDayData(context: Context, uri: android.net.Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                var targetAgent = _agentName.value.trim()
                val backupData = BackupManager().importData(context, uri)
                
                val agentsInBackup = backupData.houses.map { it.agentName }.filter { it.isNotBlank() }.distinct()
                
                if (targetAgent.isBlank() && agentsInBackup.isNotEmpty()) {
                    targetAgent = agentsInBackup.first()
                    withContext(Dispatchers.Main) {
                        _agentName.value = targetAgent
                    }
                }

                val currentHouses = houses.value
                val existingHouseKeys = currentHouses.map { 
                    "${it.streetName}|${it.number}|${it.blockNumber}|${it.data}|${it.agentName}" 
                }.toSet()

                var importedCount = 0
                var skippedCount = 0

                // Assign imported houses to the target agent to prevent mixing
                backupData.houses.forEach { house ->
                    val sanitizedHouse = house.copy(id = 0, agentName = house.agentName.ifBlank { targetAgent })
                    val key = "${sanitizedHouse.streetName}|${sanitizedHouse.number}|${sanitizedHouse.blockNumber}|${sanitizedHouse.data}|${sanitizedHouse.agentName}"
                    
                    if (!existingHouseKeys.contains(key)) {
                        repository.insertHouse(sanitizedHouse)
                        importedCount++
                    } else {
                        skippedCount++
                    }
                }

                // Also assign activities
                backupData.dayActivities.forEach { 
                    repository.updateDayActivity(it.copy(agentName = it.agentName.ifBlank { targetAgent })) 
                }

                withContext(Dispatchers.Main) {
                    val message = if (skippedCount > 0) {
                        "Importado: $importedCount, Ignorado (duplicado): $skippedCount"
                    } else {
                        "Dados importados com sucesso ($importedCount imóveis)"
                    }
                    _uiEvent.value = message
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _uiEvent.value = "Erro ao importar dados: ${e.message}"
                }
            }
        }
    }

    fun importCustomSound(uri: android.net.Uri, category: com.antigravity.healthagent.utils.SoundCategory) {
        viewModelScope.launch {
            val soundUri = "file://${uri.path}"
            when (category) {
                com.antigravity.healthagent.utils.SoundCategory.POP -> settingsManager.setPopSound(soundUri)
                com.antigravity.healthagent.utils.SoundCategory.SUCCESS -> settingsManager.setSuccessSound(soundUri)
                com.antigravity.healthagent.utils.SoundCategory.CELEBRATION -> settingsManager.setCelebrationSound(soundUri)
                com.antigravity.healthagent.utils.SoundCategory.WARNING -> settingsManager.setWarningSound(soundUri)
            }
        }
    }

    fun updatePopSound(soundId: String) {
        viewModelScope.launch { settingsManager.setPopSound(soundId) }
    }

    fun updateSuccessSound(soundId: String) {
        viewModelScope.launch { settingsManager.setSuccessSound(soundId) }
    }

    fun updateCelebrationSound(soundId: String) {
        viewModelScope.launch { settingsManager.setCelebrationSound(soundId) }
    }

    fun updateWarningSound(soundId: String) {
        viewModelScope.launch { settingsManager.setWarningSound(soundId) }
    }

    fun updateEasyMode(enabled: Boolean) {
        viewModelScope.launch {
            settingsManager.setEasyMode(enabled)
        }
    }

    fun updateThemeMode(mode: String) {
        viewModelScope.launch { settingsManager.setThemeMode(mode) }
    }

    fun updateThemeColor(color: String) {
        viewModelScope.launch { settingsManager.setThemeColor(color) }
    }

    fun updateMaxOpenHouses(max: Int) {
        viewModelScope.launch { settingsManager.setMaxOpenHouses(max) }
    }

    fun updateBackupFrequency(frequency: com.antigravity.healthagent.data.backup.BackupFrequency) {
        viewModelScope.launch {
            settingsManager.setBackupFrequency(frequency)
            backupScheduler.scheduleBackup(frequency)
        }
    }

    fun playPreview(soundId: String) {
        soundManager.playSound(soundId)
    }

    fun getSoundTitle(soundId: String, context: Context): String {
        return when {
            soundId == "SILENT" -> "Silencioso"
            soundId.startsWith("SYSTEM_") -> "Som do Sistema"
            soundId.startsWith("content://") -> {
                try {
                    val ringtone = android.media.RingtoneManager.getRingtone(context, android.net.Uri.parse(soundId))
                    ringtone?.getTitle(context) ?: "Som do Sistema"
                } catch (e: Exception) {
                    "Som do Sistema"
                }
            }
            soundId.startsWith("file://") -> "Arquivo Personalizado"
            else -> "Padrão"
        }
    }

    fun testCelebration() { soundManager.playCelebration() }

    private fun isWorkingDay(cal: Calendar, statusMap: Map<String, DayActivity>): Boolean {
        val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
        if (dayOfWeek == Calendar.SATURDAY || dayOfWeek == Calendar.SUNDAY) return false
        val dateStr = dateFormatter.format(cal.time)
        val activity = statusMap[dateStr]
        val status = activity?.status ?: "NORMAL"
        return status == "NORMAL" || status.isBlank()
    }


}
