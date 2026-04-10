package com.antigravity.healthagent.data.repository

import android.content.Context
import com.antigravity.healthagent.domain.repository.MapRepository
import com.google.maps.android.compose.MapType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MapRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : MapRepository {

    private val PREFS_NAME = "quarteiroes_prefs"
    private val KEY_KML_URI = "kml_uri"
    private val KEY_KML_LOCAL_PATH = "kml_local_path"
    private val KEY_MAP_TYPE = "map_type"

    private val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _mapType = MutableStateFlow(loadMapType())
    override val mapType: StateFlow<MapType> = _mapType.asStateFlow()

    private val _kmlUri = MutableStateFlow(loadKmlUri())
    override val kmlUri: StateFlow<android.net.Uri?> = _kmlUri.asStateFlow()

    private val _kmlLocalPath = MutableStateFlow(loadKmlLocalPath())
    override val kmlLocalPath: StateFlow<String?> = _kmlLocalPath.asStateFlow()

    override fun setMapType(type: MapType) {
        sharedPrefs.edit().putString(KEY_MAP_TYPE, type.name).apply()
        _mapType.value = type
    }

    override fun setKmlUri(uri: android.net.Uri) {
        sharedPrefs.edit().putString(KEY_KML_URI, uri.toString()).apply()
        _kmlUri.value = uri
    }

    override fun setKmlLocalPath(path: String) {
        sharedPrefs.edit().putString(KEY_KML_LOCAL_PATH, path).apply()
        _kmlLocalPath.value = path
    }

    override fun clearKmlConfig() {
        sharedPrefs.edit().remove(KEY_KML_URI).remove(KEY_KML_LOCAL_PATH).apply()
        _kmlUri.value = null
        _kmlLocalPath.value = null
    }

    private fun loadMapType(): MapType {
        val typeName = sharedPrefs.getString(KEY_MAP_TYPE, null)
        return try {
            if (typeName != null) MapType.valueOf(typeName) else MapType.NORMAL
        } catch (e: Exception) {
            MapType.NORMAL
        }
    }

    private fun loadKmlUri(): android.net.Uri? {
        return sharedPrefs.getString(KEY_KML_URI, null)?.let { android.net.Uri.parse(it) }
    }

    private fun loadKmlLocalPath(): String? {
        return sharedPrefs.getString(KEY_KML_LOCAL_PATH, null)
    }
}
