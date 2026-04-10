package com.antigravity.healthagent.domain.repository

import com.google.maps.android.compose.MapType
import kotlinx.coroutines.flow.StateFlow

interface MapRepository {
    val mapType: StateFlow<MapType>
    val kmlUri: StateFlow<android.net.Uri?>
    val kmlLocalPath: StateFlow<String?>
    
    fun setMapType(type: MapType)
    fun setKmlUri(uri: android.net.Uri)
    fun setKmlLocalPath(path: String)
    fun clearKmlConfig()
}
