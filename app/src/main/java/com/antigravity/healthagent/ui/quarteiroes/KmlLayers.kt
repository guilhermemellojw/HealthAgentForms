package com.antigravity.healthagent.ui.quarteiroes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.google.maps.android.compose.*
import com.antigravity.healthagent.utils.formatStreetName
import com.google.maps.android.compose.Polygon
import com.google.maps.android.compose.Polyline

@Composable
fun RenderKmlFolders(folders: List<KmlFolder>) {
    folders.forEach { folder ->
        if (folder.isVisible) {
            RenderKmlFolders(folders = folder.children)
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
                 color = Color(style?.lineStyle?.color ?: android.graphics.Color.BLACK),
                 width = style?.lineStyle?.width ?: 5f
             )
        }
        is KmlGeometry.Polygon -> {
            Polygon(
                points = geometry.outerBoundary,
                fillColor = Color(style?.polyStyle?.color ?: android.graphics.Color.TRANSPARENT),
                strokeColor = Color(style?.lineStyle?.color ?: android.graphics.Color.BLACK),
                strokeWidth = style?.lineStyle?.width ?: 2f,
                visible = true
            )
        }
        is KmlGeometry.Point -> {
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
                 color = Color(style?.lineStyle?.color ?: android.graphics.Color.BLACK),
                 width = style?.lineStyle?.width?.coerceAtLeast(3f) ?: 5f
             )
        }
        is KmlGeometry.Polygon -> {
            Polygon(
                points = geometry.outerBoundary,
                fillColor = if (style?.polyStyle?.fill == true) Color(style.polyStyle.color) else Color.Transparent,
                strokeColor = Color(style?.lineStyle?.color ?: android.graphics.Color.BLACK),
                strokeWidth = style?.lineStyle?.width ?: 2f,
                visible = true
            )
        }
        is KmlGeometry.Point -> {
            val iconStyle = style?.iconStyle
            
            val parsedColor = if (iconStyle != null && iconStyle.color != 0) iconStyle.color else -1
            
            val fallbackColor = if (parsedColor == -1 || parsedColor == 0) Color.Red else Color(parsedColor)
            val tintColor = if (parsedColor != -1 && parsedColor != 0) Color(parsedColor) else null

            var iconBitmap by remember(iconStyle?.href) { mutableStateOf<ImageBitmap?>(null) }
            val context = LocalContext.current
            
            LaunchedEffect(iconStyle?.href) {
                if (iconStyle?.href != null) {
                    val request = ImageRequest.Builder(context)
                        .data(iconStyle.href)
                        .allowHardware(false)
                        .build()
                    val result = ImageLoader(context).execute(request)
                    if (result is SuccessResult) {
                         iconBitmap = result.drawable.toBitmap().asImageBitmap()
                    }
                }
            }

            MarkerComposable(
                keys = arrayOf(iconBitmap ?: Unit, geometry.coordinate),
                state = remember(geometry.coordinate) { MarkerState(position = geometry.coordinate) },
                title = name,
                snippet = description,
                alpha = if (tintColor != null) tintColor.alpha else 1f
            ) {
                 if (iconStyle?.href != null) {
                     val sizeDp = (32 * (iconStyle.scale)).coerceAtLeast(24f).dp
                     
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
                         Icon(
                             imageVector = Icons.Default.Place,
                             contentDescription = null,
                             tint = Color.Blue,
                             modifier = Modifier.size(sizeDp)
                         )
                     }
                 } else {
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
        Spacer(modifier = Modifier.size((indentLevel * 16).dp))

        if (hasChildren) {
            IconButton(
                onClick = onExpandToggle,
                modifier = Modifier.size(iconSize)
            ) {
                Icon(
                    imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (isExpanded) "Recolher" else "Expandir",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(if (isEasyMode) 28.dp else 24.dp)
                )
            }
        } else {
            Spacer(modifier = Modifier.size(iconSize))
        }

        Text(
            text = name,
            style = textStyle,
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp),
            fontWeight = if (hasChildren) FontWeight.Bold else FontWeight.Normal
        )

        Switch(
            checked = isVisible,
            onCheckedChange = onVisibilityToggle,
            modifier = Modifier.padding(start = 8.dp).then(if (isEasyMode) Modifier.scale(1.2f) else Modifier)
        )
    }
}
