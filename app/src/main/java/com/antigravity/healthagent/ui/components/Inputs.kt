package com.antigravity.healthagent.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay

@Composable
fun CompactInputBox(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboardOptions: KeyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
    readOnly: Boolean = false,
    onClick: (() -> Unit)? = null,
    isError: Boolean = false,
    enabled: Boolean = true,
    isEasyMode: Boolean = false,
    onFocusChanged: (Boolean) -> Unit = {},
    focusRequester: FocusRequester? = null
) {
    var isFocusedInternal by remember { mutableStateOf(false) }
    
    val isStrictlyLocked = !enabled || (readOnly && onClick == null)
    
    val targetBorderColor = when {
        isError -> MaterialTheme.colorScheme.error
        isFocusedInternal -> MaterialTheme.colorScheme.primary
        isStrictlyLocked -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
        else -> MaterialTheme.colorScheme.primary.copy(alpha = if (isEasyMode) 0.7f else 0.7f)
    }
    
    val animatedBorderColor = if (isFocusedInternal || isError) {
        animateColorAsState(targetValue = targetBorderColor, label = "borderColor").value
    } else targetBorderColor
    
    val targetBorderWidth = if (isFocusedInternal || (isEasyMode && isError)) 2.dp else if (isEasyMode) 1.5.dp else if (isError) 1.5.dp else 1.dp
    val animatedBorderWidth = if (isFocusedInternal || isError) {
        animateDpAsState(targetValue = targetBorderWidth, label = "borderWidth").value
    } else targetBorderWidth

    val containerColor = if (isError) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f) 
                        else if (isStrictlyLocked) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.03f)
                        else Color.Transparent
    val contentAlpha = if (enabled && (!readOnly || onClick != null)) 1f else 0.7f
    
    val shapeCornerRadius = if (isEasyMode) 16.dp else 12.dp

    Column(
        modifier = modifier
            .drawBehind {
                val radius = shapeCornerRadius.toPx()
                val outline = Outline.Rounded(
                    androidx.compose.ui.geometry.RoundRect(
                        left = 0f,
                        top = 0f,
                        right = size.width,
                        bottom = size.height,
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius)
                    )
                )
                drawOutline(
                    outline = outline,
                    color = containerColor
                )
                drawOutline(
                    outline = outline,
                    color = animatedBorderColor,
                    style = Stroke(width = animatedBorderWidth.toPx())
                )
            }
            .padding(vertical = if (isEasyMode) 8.dp else 4.dp, horizontal = 8.dp)
            .then(if (enabled && onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (!isEasyMode) {
            Text(
                 text = label.uppercase(),
                 style = MaterialTheme.typography.labelSmall,
                 color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
                 fontSize = 9.sp,
                 fontWeight = FontWeight.Black,
                 maxLines = 1,
                 overflow = TextOverflow.Ellipsis
            )
        }
        if (readOnly && onClick != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (isEasyMode) 64.dp else 40.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = value.ifBlank { "—" },
                    style = TextStyle(
                        textAlign = TextAlign.Center, 
                        fontSize = if (isEasyMode) 22.sp else 15.sp, 
                        fontWeight = FontWeight.ExtraBold
                    ),
                    color = (if (isEasyMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface).copy(alpha = contentAlpha),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        } else {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                textStyle = TextStyle(
                    textAlign = TextAlign.Center,
                    fontSize = if (isEasyMode) 22.sp else 15.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = (if (isEasyMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface).copy(alpha = contentAlpha)
                ),
                singleLine = true,
                decorationBox = { innerTextField ->
                    if (isEasyMode) {
                        Column(
                            modifier = Modifier.fillMaxSize().padding(top = 2.dp, bottom = 2.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = label.uppercase(),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black,
                                textAlign = TextAlign.Center,
                                letterSpacing = 0.5.sp
                            )
                            
                            Box(
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                innerTextField()
                            }
                        }
                    } else {
                        Box(contentAlignment = Alignment.Center) {
                            if (value.isEmpty()) {
                                Text(
                                    text = "—",
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                    style = TextStyle(
                                        textAlign = TextAlign.Center,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                            }
                            innerTextField()
                        }
                    }
                },
                keyboardOptions = keyboardOptions,
                readOnly = readOnly,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (isEasyMode) 64.dp else 28.dp)
                    .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }
                    .onFocusChanged { 
                        isFocusedInternal = it.isFocused
                        onFocusChanged(it.isFocused) 
                    }
            )
        }
    }
}

@Composable
fun CounterInput(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    isEasyMode: Boolean = false
) {
    Column(
        modifier = modifier
            .background(
                Color.Transparent,
                RoundedCornerShape(if (isEasyMode) 16.dp else 12.dp)
            )
            .border(
                width = if (isEasyMode) 1.5.dp else 1.dp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                shape = RoundedCornerShape(if (isEasyMode) 16.dp else 12.dp)
            )
            .padding(if (isEasyMode) 8.dp else 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = if (isEasyMode && value > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = if (isEasyMode) 10.sp else 9.sp,
            fontWeight = FontWeight.Black,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            IconButton(
                onClick = { if (value > 0) onValueChange(value - 1) },
                modifier = Modifier.size(if (isEasyMode) 48.dp else 32.dp)
            ) {
                Text("-", fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary, fontSize = if (isEasyMode) 24.sp else 20.sp)
            }
            Text(
                text = value.toString(),
                style = TextStyle(fontSize = if (isEasyMode) 20.sp else 16.sp, fontWeight = FontWeight.ExtraBold),
                color = if (isEasyMode && value > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
            IconButton(
                onClick = { onValueChange(value + 1) },
                modifier = Modifier.size(if (isEasyMode) 48.dp else 32.dp)
            ) {
                Text("+", fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary, fontSize = if (isEasyMode) 24.sp else 20.sp)
            }
        }
        
        if (isEasyMode) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                listOf(0, 1, 2).forEach { quickVal ->
                    Surface(
                        onClick = { onValueChange(quickVal) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                        color = if (value == quickVal) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        tonalElevation = 2.dp
                    ) {
                        Text(
                            text = quickVal.toString(),
                            modifier = Modifier.padding(vertical = 6.dp),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = if (value == quickVal) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CompactDropdown(
    label: String,
    currentValue: String,
    options: List<String>,
    displayOptions: List<String> = options,
    onOptionSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    enabled: Boolean = true,
    isEasyMode: Boolean = false
) {
    val focusManager = LocalFocusManager.current
    var expanded by remember { mutableStateOf(false) }

    var displayValue by remember(currentValue) { mutableStateOf(currentValue) }
    LaunchedEffect(currentValue) { displayValue = currentValue }

    val targetBorderColor = when {
        isError -> MaterialTheme.colorScheme.error
        expanded -> MaterialTheme.colorScheme.primary
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
        else -> MaterialTheme.colorScheme.primary.copy(alpha = if (isEasyMode) 0.7f else 0.7f)
    }
    val animatedBorderColor by animateColorAsState(targetValue = targetBorderColor, label = "ddBorderColor")
    
    val targetBorderWidth = if (expanded || (isEasyMode && isError)) 2.dp else if (isEasyMode) 1.5.dp else 1.dp
    val animatedBorderWidth by animateDpAsState(targetValue = targetBorderWidth, label = "ddBorderWidth")

    val containerColor = if (isError) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f) 
                        else if (!enabled) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.03f)
                        else Color.Transparent
    val contentAlpha = if (enabled) 1f else 0.7f
    
    val shapeCornerRadius = if (isEasyMode) 16.dp else 12.dp

    Box(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind {
                    val radius = shapeCornerRadius.toPx()
                    val outline = Outline.Rounded(
                        androidx.compose.ui.geometry.RoundRect(
                            left = 0f,
                            top = 0f,
                            right = size.width,
                            bottom = size.height,
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius)
                        )
                    )
                    drawOutline(
                        outline = outline,
                        color = containerColor
                    )
                    drawOutline(
                        outline = outline,
                        color = animatedBorderColor,
                        style = Stroke(width = animatedBorderWidth.toPx())
                    )
                }
                .padding(vertical = if (isEasyMode) 8.dp else 4.dp, horizontal = if (isEasyMode) 8.dp else 4.dp),
             horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (!isEasyMode) {
                 Text(
                     text = label.uppercase(),
                     style = MaterialTheme.typography.labelSmall,
                     color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
                     fontSize = 9.sp,
                     fontWeight = FontWeight.Black,
                     maxLines = 1,
                     overflow = TextOverflow.Ellipsis
                 )
            }
            
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (isEasyMode) 64.dp else 28.dp)
                    .then(if (enabled) Modifier.clickable { 
                        focusManager.clearFocus()
                        expanded = true 
                    } else Modifier),
                contentAlignment = Alignment.Center
            ) {
                if (isEasyMode) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(top = 2.dp, bottom = 2.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = label.uppercase(),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Black,
                            textAlign = TextAlign.Center,
                            letterSpacing = 0.5.sp
                        )
                        
                        Row(
                           modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 4.dp),
                           horizontalArrangement = Arrangement.Center,
                           verticalAlignment = Alignment.CenterVertically
                        ) {
                           Text(
                                text = displayValue,
                                color = (if (isEasyMode && displayValue != "-") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface).copy(alpha = contentAlpha),
                                fontSize = 22.sp,
                               fontWeight = FontWeight.ExtraBold,
                               modifier = Modifier.weight(1f),
                               textAlign = TextAlign.Center,
                               maxLines = 1,
                               overflow = TextOverflow.Ellipsis
                           )
                           Icon(
                               Icons.Default.ArrowDropDown,
                               contentDescription = null,
                               tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                               modifier = Modifier.size(20.dp)
                           )
                        }
                    }
                } else {
                    Row(
                       modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp),
                       horizontalArrangement = Arrangement.Center,
                       verticalAlignment = Alignment.CenterVertically
                    ) {
                       Text(
                            text = displayValue,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha),
                            fontSize = 15.sp,
                           fontWeight = FontWeight.Bold,
                           modifier = Modifier.weight(1f),
                           textAlign = TextAlign.Center,
                           maxLines = 1,
                           overflow = TextOverflow.Ellipsis
                       )
                       Icon(
                           Icons.Default.ArrowDropDown,
                           contentDescription = null,
                           tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                           modifier = Modifier.size(16.dp)
                       )
                    }
                }
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(MaterialTheme.colorScheme.surface)
        ) {
            options.forEachIndexed { index, option ->
                DropdownMenuItem(
                    text = { 
                        Text(
                            text = displayOptions.getOrElse(index) { option }, 
                            style = MaterialTheme.typography.bodyMedium
                        ) 
                    },
                    onClick = {
                        focusManager.clearFocus()
                        displayValue = option
                        onOptionSelected(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
fun AutocompleteInputBox(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    suggestions: List<String>,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    enabled: Boolean = true,
    readOnly: Boolean = false
) {
    var expanded by remember { mutableStateOf(false) }
    val filteredSuggestions = remember(value, suggestions) {
        if (value.isBlank()) emptyList()
        else suggestions.filter { 
            it.contains(value, ignoreCase = true) && !it.equals(value, ignoreCase = true) 
        }.take(5)
    }
    
    Box(modifier = modifier) {
        CompactInputBox(
            label = label,
            value = value,
            onValueChange = { 
                if (!readOnly) {
                    onValueChange(it)
                    expanded = true
                }
            },
            isError = isError,
            enabled = enabled,
            readOnly = readOnly,
            modifier = Modifier.fillMaxWidth()
        )
        
        if (expanded && filteredSuggestions.isNotEmpty()) {
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                properties = PopupProperties(focusable = false),
                modifier = Modifier.width(IntrinsicSize.Min).background(MaterialTheme.colorScheme.surface)
            ) {
                filteredSuggestions.forEach { suggestion ->
                    DropdownMenuItem(
                        text = { Text(suggestion, style = MaterialTheme.typography.bodyMedium) },
                        onClick = {
                            onValueChange(suggestion)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun DebouncedCompactInputBox(
    label: String,
    initialValue: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboardOptions: KeyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
    readOnly: Boolean = false,
    debounceTime: Long = 400L,
    isError: Boolean = false,
    enabled: Boolean = true,
    isEasyMode: Boolean = false,
    focusRequester: FocusRequester? = null,
    key: Any? = null
) {
    var text by remember(key) { mutableStateOf(initialValue) }
    var isFocused by remember(key) { mutableStateOf(false) }
    var hasInteracted by remember(key) { mutableStateOf(false) }
    
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    
    LaunchedEffect(initialValue, key) {
        if (text != initialValue && !isFocused && !hasInteracted) {
            text = initialValue
        }
    }

    LaunchedEffect(text, key) {
        if (text != initialValue && isFocused) {
            delay(debounceTime)
            if (isFocused) {
                currentOnValueChange(text)
            }
        }
    }

    LaunchedEffect(initialValue) {
        if (hasInteracted && text == initialValue) {
            hasInteracted = false
        }
    }

    val onFocusChangedInternal = remember(initialValue) {
        { focused: Boolean ->
            isFocused = focused
            if (!focused) {
                if (text != initialValue) {
                    hasInteracted = true
                    currentOnValueChange(text)
                }
                hasInteracted = false
            }
        }
    }

    CompactInputBox(
        label = label,
        value = text,
        onValueChange = { newText ->
            hasInteracted = true
            text = newText
        },
        modifier = modifier,
        keyboardOptions = keyboardOptions,
        readOnly = readOnly,
        isError = isError,
        enabled = enabled,
        isEasyMode = isEasyMode,
        onFocusChanged = onFocusChangedInternal,
        focusRequester = focusRequester
    )
}
