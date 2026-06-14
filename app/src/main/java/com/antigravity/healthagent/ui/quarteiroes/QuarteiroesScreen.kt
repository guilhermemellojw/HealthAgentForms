package com.antigravity.healthagent.ui.quarteiroes

import android.Manifest
import android.content.pm.PackageManager
import com.antigravity.healthagent.domain.logger.AppLogger
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.pulltorefresh.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import com.antigravity.healthagent.ui.components.GlassTopAppBar
import com.antigravity.healthagent.ui.components.CustomSyncPullIndicator
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.antigravity.healthagent.utils.formatStreetName
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polygon
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.MarkerComposable
import coil.compose.SubcomposeAsyncImage
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.Image
import androidx.compose.material.icons.filled.Place
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.core.graphics.drawable.toBitmap
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.scale

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun QuarteiroesScreen(
    viewModel: QuarteiroesViewModel = hiltViewModel(),
    isEasyMode: Boolean = false,
    user: com.antigravity.healthagent.domain.repository.AuthUser? = null,
    onLogout: () -> Unit = {},
    onSwitchAccount: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onSyncPullActive: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val kmlFolders by viewModel.kmlFolders.collectAsState()
    val focusHouses by viewModel.focusHouses.collectAsState()
    var showFoci by remember { mutableStateOf(true) }

    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        hasLocationPermission = isGranted
    }

    LaunchedEffect(Unit) {
        if (!hasLocationPermission) {
            permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            try {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) {
                AppLogger.e("QuarteiroesScreen", "Failed to take persistable permission", e)
            }
            viewModel.setKmlUri(it)
        }
    }

    val mapType by viewModel.mapType.collectAsState()
    var showMapTypeMenu by remember { mutableStateOf(false) }
    var showLayersSheet by remember { mutableStateOf(false) }

    val bomJardim = LatLng(-22.151944, -42.418889)
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(bomJardim, 15f)
    }

    val kmlBounds by viewModel.kmlBounds.collectAsState()
    
    // Auto-zoom to KML bounds when they change
    LaunchedEffect(kmlBounds) {
        kmlBounds?.let { bounds ->
            cameraPositionState.animate(
                update = CameraUpdateFactory.newLatLngBounds(bounds, 100),
                durationMs = 1000
            )
        }
    }

    val uiSettings = remember {
        MapUiSettings(
            zoomControlsEnabled = false,
            myLocationButtonEnabled = false,
            compassEnabled = true
        )
    }
    
    val mapProperties = remember(hasLocationPermission, mapType) {
        MapProperties(
            isMyLocationEnabled = hasLocationPermission,
            mapType = mapType
        )
    }

    // Easy Mode Sizes
    val fabSize = if (isEasyMode) 64.dp else 56.dp
    val fabIconSize = if (isEasyMode) 32.dp else 24.dp

    Scaffold(
        topBar = {
            GlassTopAppBar(
                title = { Text("Mapa de Quarteirões", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black) },
                user = user,
                onLogout = onLogout,
                onSwitchAccount = onSwitchAccount,
                onOpenSettings = onOpenSettings
            )
        },
        containerColor = Color.Transparent
    ) { padding ->
        val isLoading by viewModel.isLoading.collectAsState()
        val pullToRefreshState = rememberPullToRefreshState()

        val isPullActive = pullToRefreshState.distanceFraction > 0.01f || isLoading
        LaunchedEffect(isPullActive) {
            onSyncPullActive(isPullActive)
        }
        DisposableEffect(Unit) {
            onDispose {
                onSyncPullActive(false)
            }
        }

        PullToRefreshBox(
            isRefreshing = isLoading,
            onRefresh = { viewModel.refreshData() },
            state = pullToRefreshState,
            indicator = {
                CustomSyncPullIndicator(
                    state = pullToRefreshState,
                    isRefreshing = isLoading,
                    isSolarMode = false
                )
            },
            modifier = Modifier.padding(padding).fillMaxSize()
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                GoogleMap(
                    modifier = Modifier.fillMaxSize(),
                    cameraPositionState = cameraPositionState,
                    properties = mapProperties,
                    uiSettings = uiSettings
                ) {
            // Render visible KML Data
            RenderKmlFolders(folders = kmlFolders)
            
            // Render Focus Points (Radar)
            if (showFoci) {
                focusHouses.forEach { house ->
                    val lat = house.geo.latitude
                    val lng = house.geo.longitude
                    if (lat != null && lng != null) {
                        val position = LatLng(lat, lng)
                        MarkerComposable(
                            keys = arrayOf(house.id, position),
                            state = remember(house.id) { MarkerState(position = position) },
                            title = "${house.agentName.substringBefore("@").uppercase()}: FOCO: ${house.address.streetName.formatStreetName()} nº ${house.address.number}",
                            snippet = "Quadra: ${house.address.blockNumber} | ${house.address.bairro}"
                        ) {
                            Box(
                                modifier = Modifier.size(36.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Surface(
                                    color = MaterialTheme.colorScheme.error,
                                    shape = androidx.compose.foundation.shape.CircleShape,
                                    modifier = Modifier.fillMaxSize(),
                                    shadowElevation = 4.dp
                                ) {
                                    Icon(
                                        Icons.Default.BugReport,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.padding(6.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Top Left Controls: Layers (Quarteirões)
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = 16.dp, start = 16.dp),
            horizontalAlignment = Alignment.Start
        ) {
             FloatingActionButton(
                onClick = { showLayersSheet = true },
                modifier = Modifier.size(fabSize)
            ) {
                Icon(
                    Icons.Default.Map, 
                    contentDescription = "Camadas",
                    modifier = Modifier.size(fabIconSize)
                )
            }
        }

        // Top Right Controls: Map Type
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 16.dp, end = 16.dp),
            horizontalAlignment = Alignment.End
        ) {
            Box {
                FloatingActionButton(
                    onClick = { showMapTypeMenu = true },
                    modifier = Modifier.size(fabSize)
                ) {
                    Icon(
                        Icons.Default.Layers, 
                        contentDescription = "Tipos de Mapa",
                        modifier = Modifier.size(fabIconSize)
                    )
                }
                
                DropdownMenu(
                    expanded = showMapTypeMenu,
                    onDismissRequest = { showMapTypeMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Normal", fontSize = if (isEasyMode) 18.sp else 16.sp) },
                        onClick = { 
                            viewModel.setMapType(com.google.maps.android.compose.MapType.NORMAL)
                            showMapTypeMenu = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Satélite", fontSize = if (isEasyMode) 18.sp else 16.sp) },
                        onClick = { 
                            viewModel.setMapType(com.google.maps.android.compose.MapType.SATELLITE)
                            showMapTypeMenu = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Híbrido", fontSize = if (isEasyMode) 18.sp else 16.sp) },
                        onClick = { 
                            viewModel.setMapType(com.google.maps.android.compose.MapType.HYBRID)
                            showMapTypeMenu = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Terreno", fontSize = if (isEasyMode) 18.sp else 16.sp) },
                        onClick = { 
                            viewModel.setMapType(com.google.maps.android.compose.MapType.TERRAIN)
                            showMapTypeMenu = false
                        }
                    )
                }
            }
        }

        // Bottom Right Controls: My Location, Focus Radar and Import
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            horizontalAlignment = Alignment.End
        ) {
            // Focus Radar Zoom
            if (focusHouses.isNotEmpty()) {
                val validFocusHouses = focusHouses.filter { it.geo.latitude != null && it.geo.longitude != null }
                if (validFocusHouses.isNotEmpty()) {
                    FloatingActionButton(
                        onClick = {
                            showFoci = !showFoci
                            if (showFoci) {
                                scope.launch {
                                    if (validFocusHouses.size == 1) {
                                        val house = validFocusHouses.first()
                                        cameraPositionState.animate(
                                            update = CameraUpdateFactory.newLatLngZoom(
                                                LatLng(house.geo.latitude!!, house.geo.longitude!!),
                                                18f
                                            ),
                                            durationMs = 800
                                        )
                                    } else {
                                        val builder = com.google.android.gms.maps.model.LatLngBounds.builder()
                                        validFocusHouses.forEach {
                                            builder.include(LatLng(it.geo.latitude!!, it.geo.longitude!!))
                                        }
                                        cameraPositionState.animate(
                                            update = CameraUpdateFactory.newLatLngBounds(builder.build(), 150),
                                            durationMs = 800
                                        )
                                    }
                                }
                            }
                        },
                        modifier = Modifier.padding(bottom = 16.dp).size(fabSize),
                        containerColor = if (showFoci) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (showFoci) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    ) {
                        Icon(
                            Icons.Default.BugReport, 
                            contentDescription = if (showFoci) "Ocultar Focos" else "Ver Focos",
                            modifier = Modifier.size(fabIconSize)
                        )
                    }
                }
            }

             FloatingActionButton(
                onClick = {
                    if (hasLocationPermission) {
                        try {
                            val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
                            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                                if (location != null) {
                                    scope.launch {
                                        cameraPositionState.animate(
                                            CameraUpdateFactory.newLatLngZoom(
                                                LatLng(location.latitude, location.longitude),
                                                17f
                                            )
                                        )
                                    }
                                } else {
                                    Toast.makeText(context, "Localização não encontrada", Toast.LENGTH_SHORT).show()
                                }
                            }
                        } catch (_: SecurityException) {
                            Toast.makeText(context, "Erro ao obter localização", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                    }
                },
                modifier = Modifier.padding(bottom = 16.dp).size(fabSize)
            ) {
                Icon(
                    Icons.Default.MyLocation, 
                    contentDescription = "Minha Localização",
                    modifier = Modifier.size(fabIconSize)
                )
            }

            FloatingActionButton(
                onClick = {
                    launcher.launch(arrayOf("application/vnd.google-earth.kml+xml", "application/xml", "*/*"))
                },
                modifier = Modifier.size(fabSize)
            ) {
                Icon(
                    Icons.Default.Add, 
                    contentDescription = "Importar KML",
                    modifier = Modifier.size(fabIconSize)
                )
            }
        }

        if (showLayersSheet) {
            ModalBottomSheet(
                onDismissRequest = { showLayersSheet = false }
            ) {
                Column(
                    modifier = Modifier
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = "Quarteirões",
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp)
                    )
                    
                    if (kmlFolders.isEmpty()) {
                        Text("Nenhuma camada encontrada.")
                    }

                    kmlFolders.forEach { folder ->
                        FolderItem(
                            folder = folder, 
                            onToggle = { id: String, isVisible: Boolean ->
                                viewModel.toggleFolderVisibility(id, isVisible)
                            },
                            isEasyMode = isEasyMode
                        )
                    }
                    Spacer(modifier = Modifier.padding(bottom = 32.dp))
                }
            }
        }
    }
    }
}
}


