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
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import kotlinx.coroutines.flow.*

@HiltViewModel
class QuarteiroesViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val kmlManager: KmlManager,
    private val houseRepository: HouseRepository,
    private val settingsManager: SettingsManager,
    private val authRepository: AuthRepository
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

    private val _kmlUri = MutableStateFlow<android.net.Uri?>(null)
    val kmlUri: StateFlow<android.net.Uri?> = _kmlUri.asStateFlow()

    private val _kmlFolders = MutableStateFlow<List<KmlFolder>>(emptyList())
    val kmlFolders: StateFlow<List<KmlFolder>> = _kmlFolders.asStateFlow()

    private val _mapType = MutableStateFlow(com.google.maps.android.compose.MapType.NORMAL)
    val mapType: StateFlow<com.google.maps.android.compose.MapType> = _mapType.asStateFlow()

    private val PREFS_NAME = "quarteiroes_prefs"
    private val KEY_KML_URI = "kml_uri"
    private val KEY_KML_LOCAL_PATH = "kml_local_path"
    private val KEY_MAP_TYPE = "map_type"

    init {
        loadSavedKml()
        loadSavedMapType()
    }

    fun setKmlUri(uri: android.net.Uri) {
        // 1. Store URI and take persistable permission
        saveKmlUri(uri)
        _kmlUri.value = uri
        
        // 2. Copy to internal storage for permanent access
        val localPath = copyKmlToInternal(uri)
        if (localPath != null) {
            saveKmlLocalPath(localPath)
            loadKmlData(android.net.Uri.fromFile(File(localPath)))
        } else {
             // Fallback to direct URI if copy fails
            loadKmlData(uri)
        }
    }

    private fun copyKmlToInternal(uri: android.net.Uri): String? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val internalFile = File(context.filesDir, "selected_map_layers.kml")
            
            FileOutputStream(internalFile).use { outputStream ->
                inputStream.copyTo(outputStream)
            }
            internalFile.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            null
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
                            
                            // Calculate total bounds and placemark count for diagnostics
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
                    // If we failed with Permission Denial on an EXTERNAL URI, but we have a local path, try that
                    if (uri.scheme != "file") {
                         android.util.Log.e("QuarteiroesViewModel", "Permission denied on external URI, but internal copy should be used.")
                    } else {
                         clearKmlUri()
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
        _mapType.value = type
        saveMapType(type)
    }

    private fun saveKmlUri(uri: android.net.Uri) {
        val sharedPrefs = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
        sharedPrefs.edit().putString(KEY_KML_URI, uri.toString()).apply()
        
        try {
            val takeFlags: Int = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, takeFlags)
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    private fun saveKmlLocalPath(path: String) {
        val sharedPrefs = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
        sharedPrefs.edit().putString(KEY_KML_LOCAL_PATH, path).apply()
    }

    private fun clearKmlUri() {
        val sharedPrefs = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
        sharedPrefs.edit().remove(KEY_KML_URI).remove(KEY_KML_LOCAL_PATH).apply()
        _kmlUri.value = null
        _kmlFolders.value = emptyList()
    }

    private fun saveMapType(type: com.google.maps.android.compose.MapType) {
        val sharedPrefs = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
        sharedPrefs.edit().putString(KEY_MAP_TYPE, type.name).apply()
    }

    private fun loadSavedKml() {
        val sharedPrefs = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
        
        // 1. Try Local Path first (Permanent fix)
        val localPath = sharedPrefs.getString(KEY_KML_LOCAL_PATH, null)
        if (localPath != null) {
            val file = File(localPath)
            if (file.exists()) {
                android.util.Log.i("QuarteiroesViewModel", "Loading KML from internal storage: $localPath")
                loadKmlData(android.net.Uri.fromFile(file))
                return
            }
        }

        // 2. Fallback to external URI (Legacy/Transition)
        val uriString = sharedPrefs.getString(KEY_KML_URI, null)
        uriString?.let {
            try {
                val uri = android.net.Uri.parse(it)
                _kmlUri.value = uri
                
                // Try to copy to internal now if it wasn't there
                val newLocal = copyKmlToInternal(uri)
                if (newLocal != null) {
                    saveKmlLocalPath(newLocal)
                    loadKmlData(android.net.Uri.fromFile(File(newLocal)))
                } else {
                    loadKmlData(uri)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun loadSavedMapType() {
        val sharedPrefs = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
        val typeName = sharedPrefs.getString(KEY_MAP_TYPE, null)
        if (typeName != null) {
            try {
                _mapType.value = com.google.maps.android.compose.MapType.valueOf(typeName)
            } catch (e: Exception) {
                _mapType.value = com.google.maps.android.compose.MapType.NORMAL
            }
        }
    }

    fun refreshData() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                loadSavedKml()
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
