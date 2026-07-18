package com.antigravity.healthagent.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import com.antigravity.healthagent.domain.model.TreatmentData
import com.antigravity.healthagent.domain.model.GeoCapture
import com.google.android.gms.maps.model.LatLng
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.CheckCircle
import com.antigravity.healthagent.data.local.model.House

enum class TreatmentField { A1, A2, B, C, D1, D2, E, ELIMINADOS, LARVICIDA, COM_FOCO }

@Composable
fun TreatmentDialog(
    house: House,
    onDismiss: () -> Unit,
    onConfirm: (TreatmentData, GeoCapture) -> Unit,
    onFieldChange: (TreatmentField, Any) -> Unit = { _, _ -> },
    onComFocoChange: (Boolean) -> Unit = {},
    onGeoChange: (GeoCapture) -> Unit = {},
    onGetLocation: (callback: (LatLng) -> Unit) -> Unit = {},
    isEasyMode: Boolean = false
) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    var a1 by remember(house.id) { mutableIntStateOf(house.treatment.a1) }
    var a2 by remember(house.id) { mutableIntStateOf(house.treatment.a2) }
    var b by remember(house.id) { mutableIntStateOf(house.treatment.b) }
    var c by remember(house.id) { mutableIntStateOf(house.treatment.c) }
    var d1 by remember(house.id) { mutableIntStateOf(house.treatment.d1) }
    var d2 by remember(house.id) { mutableIntStateOf(house.treatment.d2) }
    var e by remember(house.id) { mutableIntStateOf(house.treatment.e) }
    var eliminados by remember(house.id) { mutableIntStateOf(house.treatment.eliminados) }
    var larvicida by remember(house.id) { mutableDoubleStateOf(house.treatment.larvicida) }
    var comFoco by remember(house.id) { mutableStateOf(house.treatment.comFoco) }

    var latitude by remember(house.id) { mutableStateOf(house.geo.latitude) }
    var longitude by remember(house.id) { mutableStateOf(house.geo.longitude) }
    var focusCaptureTime by remember(house.id) { mutableStateOf(house.geo.focusCaptureTime) }

    var showMapPicker by remember { mutableStateOf(false) }

    LaunchedEffect(comFoco) {
        if (comFoco && latitude == null && longitude == null) {
            onGetLocation { latLng ->
                latitude = latLng.latitude
                longitude = latLng.longitude
                focusCaptureTime = System.currentTimeMillis()
                onGeoChange(GeoCapture(latitude, longitude, focusCaptureTime))
            }
        }
    }

    if (showMapPicker) {
        LocationPickerDialog(
            initialLocation = if (latitude != null && longitude != null) LatLng(latitude!!, longitude!!) else null,
            onDismiss = { showMapPicker = false },
            onConfirm = { latLng ->
                latitude = latLng.latitude
                longitude = latLng.longitude
                focusCaptureTime = System.currentTimeMillis()
                onGeoChange(GeoCapture(latitude, longitude, focusCaptureTime))
                showMapPicker = false
            },
            isEasyMode = isEasyMode
        )
    }

    fun emitFieldChange(field: TreatmentField, value: Any) {
        onFieldChange(field, value)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "Tratamento",
                fontWeight = FontWeight.ExtraBold,
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(if (isEasyMode) 12.dp else 8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                item {
                    Text(
                        "Depósitos Inspecionados",
                        fontWeight = FontWeight.Bold,
                        style = if (isEasyMode) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium
                    )
                    HorizontalDivider(modifier = Modifier.padding(top = 4.dp), thickness = 0.5.dp)
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(if (isEasyMode) 12.dp else 8.dp)) {
                        CounterInput("A1", a1, { a1 = it; emitFieldChange(TreatmentField.A1, it) }, Modifier.weight(1f), isEasyMode)
                        CounterInput("A2", a2, { a2 = it; emitFieldChange(TreatmentField.A2, it) }, Modifier.weight(1f), isEasyMode)
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(if (isEasyMode) 12.dp else 8.dp)) {
                        CounterInput("B", b, { b = it; emitFieldChange(TreatmentField.B, it) }, Modifier.weight(1f), isEasyMode)
                        CounterInput("C", c, { c = it; emitFieldChange(TreatmentField.C, it) }, Modifier.weight(1f), isEasyMode)
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(if (isEasyMode) 12.dp else 8.dp)) {
                        CounterInput("D1", d1, { d1 = it; emitFieldChange(TreatmentField.D1, it) }, Modifier.weight(1f), isEasyMode)
                        CounterInput("D2", d2, { d2 = it; emitFieldChange(TreatmentField.D2, it) }, Modifier.weight(1f), isEasyMode)
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(if (isEasyMode) 12.dp else 8.dp)) {
                        CounterInput("E", e, { e = it; emitFieldChange(TreatmentField.E, it) }, Modifier.weight(1f), isEasyMode)
                        CounterInput("Eliminados", eliminados, { eliminados = it; emitFieldChange(TreatmentField.ELIMINADOS, it) }, Modifier.weight(1f), isEasyMode)
                    }
                }
                item {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), thickness = 0.5.dp)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.Start
                        ) {
                            Text(
                                "Larvicida (g)",
                                fontWeight = FontWeight.Bold,
                                style = if (isEasyMode) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(if (isEasyMode) 8.dp else 4.dp),
                                modifier = Modifier.padding(top = 4.dp)
                            ) {
                                IconButton(
                                    onClick = { if (larvicida >= 0.5) { larvicida -= 0.5; emitFieldChange(TreatmentField.LARVICIDA, larvicida) } },
                                    modifier = Modifier.size(if (isEasyMode) 48.dp else 40.dp)
                                ) {
                                    Text("-", fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary, fontSize = if (isEasyMode) 24.sp else 22.sp)
                                }
                                Text(
                                    String.format(java.util.Locale("pt", "BR"), "%.1f", larvicida),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    style = TextStyle(
                                        fontSize = if (isEasyMode) 20.sp else 18.sp,
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                )
                                IconButton(
                                    onClick = { larvicida += 0.5; emitFieldChange(TreatmentField.LARVICIDA, larvicida) },
                                    modifier = Modifier.size(if (isEasyMode) 48.dp else 40.dp)
                                ) {
                                    Text("+", fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary, fontSize = if (isEasyMode) 24.sp else 22.sp)
                                }
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            border = androidx.compose.foundation.BorderStroke(
                                width = if (comFoco) 2.dp else 1.dp,
                                color = if (comFoco) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                            ),
                            color = if (comFoco) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f) else MaterialTheme.colorScheme.surface,
                            modifier = Modifier
                                .padding(start = 12.dp)
                                .toggleable(
                                    value = comFoco,
                                    onValueChange = { comFoco = it; onComFocoChange(it); emitFieldChange(TreatmentField.COM_FOCO, it) },
                                    role = androidx.compose.ui.semantics.Role.Checkbox
                                )
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Checkbox(
                                    checked = comFoco,
                                    onCheckedChange = null,
                                    colors = CheckboxDefaults.colors(
                                        checkedColor = MaterialTheme.colorScheme.error,
                                        uncheckedColor = MaterialTheme.colorScheme.onSurfaceVariant
                                    ),
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    "Com Foco",
                                    style = if (isEasyMode) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.labelLarge,
                                    fontWeight = if (comFoco) FontWeight.ExtraBold else FontWeight.Bold,
                                    color = if (comFoco) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                if (comFoco) {
                    item {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), thickness = 0.5.dp)
                        Text(
                            "Localização do Foco",
                            fontWeight = FontWeight.Bold,
                            style = if (isEasyMode) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (latitude != null && longitude != null) {
                                Surface(
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.weight(1f).height(if (isEasyMode) 52.dp else 44.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center,
                                        modifier = Modifier.padding(horizontal = 8.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.CheckCircle,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            "Capturado",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            } else if (comFoco) {
                                Surface(
                                    color = MaterialTheme.colorScheme.errorContainer,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.weight(1f).height(if (isEasyMode) 52.dp else 44.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center,
                                        modifier = Modifier.padding(horizontal = 8.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Warning,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            "Sem GPS",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }

                            OutlinedButton(
                                onClick = {
                                    onGetLocation { latLng ->
                                        latitude = latLng.latitude
                                        longitude = latLng.longitude
                                        focusCaptureTime = System.currentTimeMillis()
                                        onGeoChange(GeoCapture(latitude, longitude, focusCaptureTime))
                                    }
                                },
                                modifier = Modifier.weight(1f).height(if (isEasyMode) 52.dp else 44.dp),
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 4.dp)
                            ) {
                                Icon(Icons.Default.MyLocation, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("GPS", fontSize = 12.sp)
                            }

                            OutlinedButton(
                                onClick = { showMapPicker = true },
                                modifier = Modifier.weight(1f).height(if (isEasyMode) 52.dp else 44.dp),
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 4.dp)
                            ) {
                                Icon(Icons.Default.Map, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Mapa", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f).height(if (isEasyMode) 52.dp else 48.dp),
                    shape = RoundedCornerShape(if (isEasyMode) 16.dp else 12.dp)
                ) {
                    Text("Cancelar", fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = {
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                        onConfirm(
                            TreatmentData(
                                a1 = a1, a2 = a2, b = b, c = c, d1 = d1, d2 = d2, e = e,
                                eliminados = eliminados, larvicida = larvicida, comFoco = comFoco
                            ),
                            GeoCapture(
                                latitude = latitude, longitude = longitude, focusCaptureTime = focusCaptureTime
                            )
                        )
                    },
                    modifier = Modifier.weight(1.3f).height(if (isEasyMode) 52.dp else 48.dp),
                    shape = RoundedCornerShape(if (isEasyMode) 16.dp else 12.dp)
                ) {
                    Text("Salvar", fontWeight = FontWeight.Bold)
                }
            }
        }
    )
}
