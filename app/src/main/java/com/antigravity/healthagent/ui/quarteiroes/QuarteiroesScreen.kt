package com.antigravity.healthagent.ui.quarteiroes

import android.Manifest
import android.content.pm.PackageManager
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MyLocation
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
    isEasyMode: Boolean = false
) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val kmlFolders by viewModel.kmlFolders.collectAsState()

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

    Box(modifier = Modifier.fillMaxSize()) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            properties = mapProperties,
            uiSettings = uiSettings
        ) {
            // Render visible KML Data
            RenderKmlFolders(folders = kmlFolders)
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

        // Bottom Right Controls: My Location and Import
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            horizontalAlignment = Alignment.End
        ) {
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
                        } catch (e: SecurityException) {
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
                            onToggle = { id, isVisible ->
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

@Composable
fun RenderKmlFolders(folders: List<KmlFolder>) {
    folders.forEach { folder ->
        if (folder.isVisible) {
            // Render Children Folders
            RenderKmlFolders(folders = folder.children)
            // Render Placemarks
            folder.placemarks.forEach { placemark ->
                RenderPlacemark(placemark)
            }
        }
    }
}

@Composable
fun RenderPlacemark(placemark: KmlPlacemark) {
    val style = placemark.style
    
    when (val geometry = placemark.geometry) {
        is KmlGeometry.LineString -> {
             Polyline(
                 points = geometry.coordinates,
                 color = androidx.compose.ui.graphics.Color(style?.lineStyle?.color ?: android.graphics.Color.BLACK),
                 width = style?.lineStyle?.width ?: 5f
             )
        }
        is KmlGeometry.Polygon -> {
            Polygon(
                points = geometry.outerBoundary,
                fillColor = androidx.compose.ui.graphics.Color(style?.polyStyle?.color ?: android.graphics.Color.TRANSPARENT),
                strokeColor = androidx.compose.ui.graphics.Color(style?.lineStyle?.color ?: android.graphics.Color.BLACK),
                strokeWidth = style?.lineStyle?.width ?: 2f,
                visible = true
            )
        }
        is KmlGeometry.Point -> {
            // Updated to use MarkerComposable for custom icons
            RenderGeometry(geometry, style, placemark.name, placemark.description)
        }
        is KmlGeometry.MultiGeometry -> {
             geometry.geometries.forEach { subGeom ->
                 RenderGeometry(subGeom, style, placemark.name, placemark.description)
             }
        }
    }
}

@Composable
fun RenderGeometry(geometry: KmlGeometry, style: KmlStyle?, name: String, description: String?) {
    when (geometry) {
        is KmlGeometry.LineString -> {
             Polyline(
                 points = geometry.coordinates,
                 color = androidx.compose.ui.graphics.Color(style?.lineStyle?.color ?: android.graphics.Color.BLACK),
                 width = style?.lineStyle?.width?.coerceAtLeast(3f) ?: 5f // Ensure visibility
             )
        }
        is KmlGeometry.Polygon -> {
            Polygon(
                points = geometry.outerBoundary,
                fillColor = if (style?.polyStyle?.fill == true) androidx.compose.ui.graphics.Color(style.polyStyle.color) else androidx.compose.ui.graphics.Color.Transparent,
                strokeColor = androidx.compose.ui.graphics.Color(style?.lineStyle?.color ?: android.graphics.Color.BLACK),
                strokeWidth = style?.lineStyle?.width ?: 2f,
                visible = true
            )
        }
        is KmlGeometry.Point -> {
            val iconStyle = style?.iconStyle
            
            // Determine effective color
            val parsedColor = if (iconStyle != null && iconStyle.color != 0) iconStyle.color else -1 // -1 is White
            
            val fallbackColor = if (parsedColor == -1 || parsedColor == 0) androidx.compose.ui.graphics.Color.Red else androidx.compose.ui.graphics.Color(parsedColor)
            val tintColor = if (parsedColor != -1 && parsedColor != 0) androidx.compose.ui.graphics.Color(parsedColor) else null

            // Explicit Image Loading logic
            var iconBitmap by remember(iconStyle?.href) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
            val context = LocalContext.current
            
            LaunchedEffect(iconStyle?.href) {
                if (iconStyle?.href != null) {
                    val request = ImageRequest.Builder(context)
                        .data(iconStyle.href)
                        .allowHardware(false) // Important for Canvas drawing usually
                        .build()
                    val result = ImageLoader(context).execute(request)
                    if (result is SuccessResult) {
                         iconBitmap = result.drawable.toBitmap().asImageBitmap()
                    }
                }
            }

            MarkerComposable(
                keys = arrayOf(iconBitmap ?: Unit, geometry.coordinate), // Force Re-render when bitmap loads
                state = MarkerState(position = geometry.coordinate),
                title = name,
                snippet = description,
                alpha = if (tintColor != null) tintColor.alpha else 1f
            ) {
                 if (iconStyle?.href != null) {
                     val sizeDp = (32 * (iconStyle.scale)).coerceAtLeast(24f).dp // Ensure minimum size
                     
                     val currentIcon = iconBitmap
                     
                     if (currentIcon != null) {
                         Image(
                             bitmap = currentIcon,
                             contentDescription = name,
                             modifier = Modifier.size(sizeDp),
                             contentScale = ContentScale.Fit,
                             colorFilter = if (tintColor != null) ColorFilter.tint(tintColor) else null
                         )
                     } else {
                         // Loading State
                         Icon(
                             imageVector = Icons.Default.Place,
                             contentDescription = null,
                             tint = Color.Blue, // Debug: Blue for Loading
                             modifier = Modifier.size(sizeDp)
                         )
                     }
                 } else {
                     // Fallback to default Icon
                      Icon(
                         imageVector = Icons.Default.Place,
                         contentDescription = name,
                         tint = fallbackColor,
                         modifier = Modifier.size(48.dp)
                     )
                 }
            }
        }
        is KmlGeometry.MultiGeometry -> {
             geometry.geometries.forEach { subGeom ->
                 RenderGeometry(subGeom, style, name, description)
             }
        }
    }
}

@Composable
fun FolderItem(
    folder: KmlFolder,
    onToggle: (String, Boolean) -> Unit,
    indentLevel: Int = 0,
    isEasyMode: Boolean = false
) {
    var isExpanded by androidx.compose.runtime.saveable.rememberSaveable(folder.id) { mutableStateOf(false) }

    Column {
        LayerItem(
            name = folder.name,
            isVisible = folder.isVisible,
            hasChildren = folder.children.isNotEmpty(),
            isExpanded = isExpanded,
            onExpandToggle = { isExpanded = !isExpanded },
            onVisibilityToggle = { isVisible -> onToggle(folder.id, isVisible) },
            indentLevel = indentLevel,
            isEasyMode = isEasyMode
        )
        
        if (isExpanded) {
            folder.children.forEach { child ->
                FolderItem(
                    folder = child,
                    onToggle = onToggle,
                    indentLevel = indentLevel + 1,
                    isEasyMode = isEasyMode
                )
            }
        }
    }
}

@Composable
fun LayerItem(
    name: String,
    isVisible: Boolean,
    hasChildren: Boolean,
    isExpanded: Boolean,
    onExpandToggle: () -> Unit,
    onVisibilityToggle: (Boolean) -> Unit,
    indentLevel: Int,
    isEasyMode: Boolean
) {
    val verticalPadding = if (isEasyMode) 12.dp else 4.dp
    val textStyle = if (isEasyMode) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium
    val iconSize = if (isEasyMode) 40.dp else 32.dp

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { 
                onVisibilityToggle(!isVisible) 
            }
            .padding(vertical = verticalPadding, horizontal = 8.dp)
    ) {
        // Indentation
        Spacer(modifier = Modifier.size((indentLevel * 16).dp))

        // Expand/Collapse Chevron
        if (hasChildren) {
            androidx.compose.material3.IconButton(
                onClick = onExpandToggle,
                modifier = Modifier.size(iconSize)
            ) {
                Icon(
                    imageVector = if (isExpanded) androidx.compose.material.icons.Icons.Default.ExpandLess else androidx.compose.material.icons.Icons.Default.ExpandMore,
                    contentDescription = if (isExpanded) "Recolher" else "Expandir",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(if (isEasyMode) 28.dp else 24.dp)
                )
            }
        } else {
            // Placeholder for alignment
            Spacer(modifier = Modifier.size(iconSize))
        }

        // Name
        Text(
            text = name,
            style = textStyle,
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp),
            fontWeight = if (hasChildren) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal
        )

        // Visibility Switch
        androidx.compose.material3.Switch(
            checked = isVisible,
            onCheckedChange = onVisibilityToggle,
            modifier = Modifier.padding(start = 8.dp).then(if (isEasyMode) Modifier.scale(1.2f) else Modifier)
        )
    }
}
