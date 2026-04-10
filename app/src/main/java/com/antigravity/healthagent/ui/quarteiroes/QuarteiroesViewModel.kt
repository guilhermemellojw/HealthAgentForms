package com.antigravity.healthagent.ui.quarteiroes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import com.antigravity.healthagent.data.repository.HouseRepository
import com.antigravity.healthagent.data.local.model.House
import com.antigravity.healthagent.data.settings.SettingsManager
import com.antigravity.healthagent.domain.repository.AuthRepository
import com.antigravity.healthagent.domain.repository.MapRepository
import com.antigravity.healthagent.data.util.KmlStorageService
import kotlinx.coroutines.flow.*
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.MapType

@HiltViewModel
class QuarteiroesViewModel @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val kmlManager: KmlManager,
    private val houseRepository: HouseRepository,
    private val settingsManager: SettingsManager,
    private val authRepository: AuthRepository,
    private val mapRepository: MapRepository,
    private val kmlStorageService: KmlStorageService
) : ViewModel() {
    
    val focusHouses: StateFlow<List<House>> = combine(
        settingsManager.cachedUser,
        settingsManager.remoteAgentUid
    ) { user, remoteUid ->
        val name = user?.agentName ?: user?.email ?: ""
        val uid = remoteUid ?: user?.uid ?: ""
        name to uid
    }.flatMapLatest { (name, uid) ->
        if (name == "Admin" || name == "Supervisor") {
            houseRepository.getAllHousesSnapshotFlow()
        } else if (name.isNotBlank()) {
            houseRepository.getAllHouses(name, uid)
        } else {
            flowOf(emptyList())
        }
    }
        .map { houses -> 
            houses.filter { it.comFoco && it.latitude != null && it.longitude != null } 
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    val kmlUri: StateFlow<android.net.Uri?> = mapRepository.kmlUri
    val mapType: StateFlow<com.google.maps.android.compose.MapType> = mapRepository.mapType

    private val _kmlFolders = MutableStateFlow<List<KmlFolder>>(emptyList())
    val kmlFolders: StateFlow<List<KmlFolder>> = _kmlFolders.asStateFlow()

    init {
        // Load initially from repository
        mapRepository.kmlLocalPath.value?.let { path ->
            val file = File(path)
            if (file.exists()) {
                loadKmlData(android.net.Uri.fromFile(file))
            }
        }
    }

    fun setKmlUri(uri: android.net.Uri) {
        // 1. Take permissions and store
        kmlStorageService.takePersistablePermission(uri)
        mapRepository.setKmlUri(uri)
        
        // 2. Copy to internal storage for permanent access
        val localPath = kmlStorageService.copyKmlToInternal(uri)
        if (localPath != null) {
            mapRepository.setKmlLocalPath(localPath)
            loadKmlData(android.net.Uri.fromFile(File(localPath)))
        } else {
             // Fallback to direct URI if copy fails
            loadKmlData(uri)
        }
    }

    private val _kmlBounds = MutableStateFlow<com.google.android.gms.maps.model.LatLngBounds?>(null)
    val kmlBounds: StateFlow<com.google.android.gms.maps.model.LatLngBounds?> = _kmlBounds.asStateFlow()

    private fun loadKmlData(uri: android.net.Uri) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    val result = kmlManager.parseKml(inputStream)
                    result.fold(
                        onSuccess = { folders ->
                            _kmlFolders.value = folders
                            
                            val builder = com.google.android.gms.maps.model.LatLngBounds.builder()
                            var totalPlacemarks = 0
                            var hasPoints = false
                            
                            folders.forEach { folder ->
                                folder.getBounds()?.let { 
                                    builder.include(it.northeast)
                                    builder.include(it.southwest)
                                    hasPoints = true
                                }
                                
                                fun countPlacemarks(f: KmlFolder): Int {
                                    return f.placemarks.size + f.children.sumOf { countPlacemarks(it) }
                                }
                                totalPlacemarks += countPlacemarks(folder)
                            }
                            
                            if (hasPoints) {
                                _kmlBounds.value = builder.build()
                            }
                            
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                android.widget.Toast.makeText(context, "KML carregado: $totalPlacemarks elementos encontrados", android.widget.Toast.LENGTH_LONG).show()
                            }
                        },
                        onFailure = { e ->
                            e.printStackTrace()
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                android.widget.Toast.makeText(context, "Erro ao ler KML: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                            }
                        }
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
                if (e is SecurityException || e.message?.contains("Permission Denial", ignoreCase = true) == true) {
                    if (uri.scheme != "file") {
                         android.util.Log.e("QuarteiroesViewModel", "Permission denied on external URI, internal copy failure.")
                    } else {
                         mapRepository.clearKmlConfig()
                         _kmlFolders.value = emptyList()
                    }
                }
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    android.widget.Toast.makeText(context, "Erro ao abrir arquivo: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun toggleFolderVisibility(folderId: String, isVisible: Boolean) {
        val currentFolders = _kmlFolders.value
        val (updatedFolders, _) = updateFolderVisibilityRecursive(currentFolders, folderId, isVisible)
        _kmlFolders.value = updatedFolders
    }

    private fun updateFolderVisibilityRecursive(
        folders: List<KmlFolder>,
        targetId: String,
        targetIsVisible: Boolean
    ): Pair<List<KmlFolder>, Boolean> {
        var foundInThisList = false
        val newFolders = folders.map { folder ->
            if (folder.id == targetId) {
                foundInThisList = true
                setFolderAndChildrenVisibility(folder, targetIsVisible)
            } else {
                val (newChildren, foundInChild) = updateFolderVisibilityRecursive(folder.children, targetId, targetIsVisible)
                if (foundInChild) {
                    foundInThisList = true
                    val shouldBeVisible = if (targetIsVisible) true else folder.isVisible
                    folder.copy(isVisible = shouldBeVisible, children = newChildren)
                } else {
                    folder
                }
            }
        }
        return newFolders to foundInThisList
    }

    private fun setFolderAndChildrenVisibility(folder: KmlFolder, isVisible: Boolean): KmlFolder {
        return folder.copy(
            isVisible = isVisible,
            children = folder.children.map { setFolderAndChildrenVisibility(it, isVisible) }
        )
    }

    fun setMapType(type: com.google.maps.android.compose.MapType) {
        mapRepository.setMapType(type)
    }

    fun refreshData() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                // Reload KML from local path in repository
                mapRepository.kmlLocalPath.value?.let { path ->
                    val file = File(path)
                    if (file.exists()) {
                        loadKmlData(android.net.Uri.fromFile(file))
                    }
                }
                kotlinx.coroutines.delay(1000)
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun getCurrentUserUid(): String? {
        return authRepository.getCurrentUserUid()
    }
}
