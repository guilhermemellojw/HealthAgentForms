package com.antigravity.healthagent.ui.quarteiroes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class QuarteiroesViewModel @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val kmlManager: KmlManager
) : ViewModel() {

    private val _kmlUri = MutableStateFlow<android.net.Uri?>(null)
    val kmlUri: StateFlow<android.net.Uri?> = _kmlUri.asStateFlow()

    private val _kmlFolders = MutableStateFlow<List<KmlFolder>>(emptyList())
    val kmlFolders: StateFlow<List<KmlFolder>> = _kmlFolders.asStateFlow()

    private val _mapType = MutableStateFlow(com.google.maps.android.compose.MapType.NORMAL)
    val mapType: StateFlow<com.google.maps.android.compose.MapType> = _mapType.asStateFlow()

    private val PREFS_NAME = "quarteiroes_prefs"
    private val KEY_KML_URI = "kml_uri"
    private val KEY_MAP_TYPE = "map_type"

    init {
        loadSavedKml()
        loadSavedMapType()
    }

    fun setKmlUri(uri: android.net.Uri) {
        saveKmlUri(uri)
        _kmlUri.value = uri
        loadKmlData(uri)
    }

    private fun loadKmlData(uri: android.net.Uri) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    val result = kmlManager.parseKml(inputStream)
                    result.fold(
                        onSuccess = { folders ->
                            _kmlFolders.value = folders
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
                // Action 1: Toggle Target & Recursively set children to same state
                setFolderAndChildrenVisibility(folder, targetIsVisible)
            } else {
                // Check children
                val (newChildren, foundInChild) = updateFolderVisibilityRecursive(folder.children, targetId, targetIsVisible)
                if (foundInChild) {
                    foundInThisList = true
                    // Action 2: If we are enabling a child, we must enable the parent (this folder)
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
        
        // Take persistable permission
        try {
            val takeFlags: Int = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, takeFlags)
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    private fun saveMapType(type: com.google.maps.android.compose.MapType) {
        val sharedPrefs = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
        sharedPrefs.edit().putString(KEY_MAP_TYPE, type.name).apply()
    }

    private fun loadSavedKml() {
        val sharedPrefs = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
        val uriString = sharedPrefs.getString(KEY_KML_URI, null)
        uriString?.let {
            try {
                val uri = android.net.Uri.parse(it)
                _kmlUri.value = uri
                loadKmlData(uri)
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
}
