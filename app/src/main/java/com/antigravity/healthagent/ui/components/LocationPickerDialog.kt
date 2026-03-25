package com.antigravity.healthagent.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationPickerDialog(
    initialLocation: LatLng? = null,
    onDismiss: () -> Unit,
    onConfirm: (LatLng) -> Unit,
    isEasyMode: Boolean = false
) {
    val bomJardim = LatLng(-22.1519, -42.4189)
    val startLocation = initialLocation ?: bomJardim
    
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(startLocation, 17f)
    }
    
    var selectedLocation by remember { mutableStateOf(startLocation) }
    
    val uiSettings = remember {
        MapUiSettings(
            zoomControlsEnabled = true,
            myLocationButtonEnabled = true,
            compassEnabled = true
        )
    }
    
    val mapProperties = remember {
        MapProperties(
            isMyLocationEnabled = true,
            mapType = MapType.HYBRID
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            "Selecionar Localização",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Black
                        )
                        Text(
                            "Toque no mapa ou arraste o marcador",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Fechar")
                    }
                }

                // Map Content
                Box(modifier = Modifier.weight(1f)) {
                    val markerState = remember { MarkerState(position = selectedLocation) }
                    
                    // Sync markerState with selectedLocation when it changes (map click)
                    LaunchedEffect(selectedLocation) {
                        markerState.position = selectedLocation
                    }
                    
                    // Sync selectedLocation with markerState when it changes (dragging)
                    LaunchedEffect(markerState.position) {
                        selectedLocation = markerState.position
                    }

                    GoogleMap(
                        modifier = Modifier.fillMaxSize(),
                        cameraPositionState = cameraPositionState,
                        properties = mapProperties,
                        uiSettings = uiSettings,
                        onMapClick = {
                            selectedLocation = it
                        }
                    ) {
                        Marker(
                            state = markerState,
                            title = "Localização do Foco",
                            draggable = true
                        )
                    }
                }

                // Footer Actions
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f).height(56.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text("Cancelar")
                    }
                    Button(
                        onClick = { onConfirm(selectedLocation) },
                        modifier = Modifier.weight(1.2f).height(56.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text("Confirmar", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
