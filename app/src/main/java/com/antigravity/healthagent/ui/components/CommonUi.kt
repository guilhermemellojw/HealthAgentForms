package com.antigravity.healthagent.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.*
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.blur
import kotlinx.coroutines.delay
import android.os.Build
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.shadow
import coil.compose.AsyncImage

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
    focusRequester: androidx.compose.ui.focus.FocusRequester? = null
) {
    var isFocusedInternal by remember { mutableStateOf(false) }
    
    val targetBorderColor = when {
        isError -> MaterialTheme.colorScheme.error
        isFocusedInternal -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 0.7f else 0.3f)
    }
    
    // Defer animations to only when focused or in error to save UI cycles during scroll
    val animatedBorderColor = if (isFocusedInternal || isError) {
        androidx.compose.animation.animateColorAsState(targetValue = targetBorderColor, label = "borderColor").value
    } else targetBorderColor
    
    val targetBorderWidth = if (isFocusedInternal || (isEasyMode && isError)) 2.dp else if (isError) 1.5.dp else 1.dp
    val animatedBorderWidth = if (isFocusedInternal || isError) {
        androidx.compose.animation.core.animateDpAsState(targetValue = targetBorderWidth, label = "borderWidth").value
    } else targetBorderWidth

    val containerColor = if (isError) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f) 
                        else Color.Transparent
    val contentAlpha = if (enabled) 1f else 0.4f
    
    val shapeCornerRadius = if (isEasyMode) 16.dp else 12.dp

    Column(
        modifier = modifier
            .drawBehind {
                val radius = shapeCornerRadius.toPx()
                val outline = androidx.compose.ui.graphics.Outline.Rounded(
                    androidx.compose.ui.geometry.RoundRect(
                        left = 0f,
                        top = 0f,
                        right = size.width,
                        bottom = size.height,
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius)
                    )
                )
                // Draw background
                drawOutline(
                    outline = outline,
                    color = containerColor
                )
                // Draw border
                drawOutline(
                    outline = outline,
                    color = animatedBorderColor,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = animatedBorderWidth.toPx())
                )
            }
            .padding(vertical = if (isEasyMode) 8.dp else 4.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
             text = label.uppercase(),
             style = MaterialTheme.typography.labelSmall,
             color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
             fontSize = if (isEasyMode) 10.sp else 9.sp,
             fontWeight = FontWeight.Black,
             maxLines = 1,
             overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )
        // Clickable Box if onClick provided (e.g. for DatePicker)
        if (readOnly && onClick != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (isEasyMode) 40.dp else 28.dp)
                    .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
                contentAlignment = Alignment.Center
            ) {
                 Text(
                    text = value,
                    style = TextStyle(
                        textAlign = TextAlign.Center, 
                        fontSize = if (isEasyMode) 18.sp else 15.sp, 
                        fontWeight = FontWeight.Bold
                    ),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha)
                )
            }
        } else {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                textStyle = TextStyle(
                    textAlign = TextAlign.Center,
                    fontSize = if (isEasyMode) 18.sp else 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha)
                ),
                singleLine = true,
                decorationBox = { innerTextField ->
                    Box(contentAlignment = Alignment.Center) {
                        innerTextField()
                    }
                },
                keyboardOptions = keyboardOptions,
                readOnly = readOnly,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (isEasyMode) 40.dp else 28.dp)
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
                width = if (isEasyMode && value > 0) 2.dp else 1.dp,
                color = if (isEasyMode && value > 0) MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                else MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
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
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
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
    var expanded by remember { mutableStateOf(false) }

    val targetBorderColor = when {
        isError -> MaterialTheme.colorScheme.error
        expanded -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 0.7f else 0.3f)
    }
    val animatedBorderColor by androidx.compose.animation.animateColorAsState(targetValue = targetBorderColor, label = "ddBorderColor")
    
    val targetBorderWidth = if (expanded || (isEasyMode && isError)) 2.dp else 1.dp
    val animatedBorderWidth by androidx.compose.animation.core.animateDpAsState(targetValue = targetBorderWidth, label = "ddBorderWidth")

    val containerColor = if (isError) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f) 
                        else Color.Transparent
    val contentAlpha = if (enabled) 1f else 0.4f
    
    val shapeCornerRadius = if (isEasyMode) 16.dp else 12.dp


    Box(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind {
                    val radius = shapeCornerRadius.toPx()
                    val outline = androidx.compose.ui.graphics.Outline.Rounded(
                        androidx.compose.ui.geometry.RoundRect(
                            left = 0f,
                            top = 0f,
                            right = size.width,
                            bottom = size.height,
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius)
                        )
                    )
                    // Draw background
                    drawOutline(
                        outline = outline,
                        color = containerColor
                    )
                    // Draw border
                    drawOutline(
                        outline = outline,
                        color = animatedBorderColor,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = animatedBorderWidth.toPx())
                    )
                }
                .padding(vertical = if (isEasyMode) 8.dp else 4.dp, horizontal = if (isEasyMode) 8.dp else 4.dp),
             horizontalAlignment = Alignment.CenterHorizontally
        ) {
             Text(
                 text = label.uppercase(),
                 style = MaterialTheme.typography.labelSmall,
                 color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
                 fontSize = if (isEasyMode) 10.sp else 9.sp,
                 fontWeight = FontWeight.Black,
                 maxLines = 1,
                 overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
             )
            
            // Value and Icon row
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (isEasyMode) 40.dp else 28.dp)
                    .then(if (enabled) Modifier.clickable { expanded = true } else Modifier),
                contentAlignment = Alignment.Center
            ) {
                Row(
                   modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp),
                   horizontalArrangement = Arrangement.Center,
                   verticalAlignment = Alignment.CenterVertically
                ) {
                   Text(
                       text = currentValue,
                       color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha),
                       fontSize = if (isEasyMode) 18.sp else 15.sp,
                       fontWeight = FontWeight.Bold,
                       modifier = Modifier.weight(1f),
                       textAlign = TextAlign.Center,
                       maxLines = 1,
                       overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                   )
                   Icon(
                       imageVector = Icons.Default.ArrowDropDown,
                       contentDescription = null,
                       tint = MaterialTheme.colorScheme.primary.copy(alpha = contentAlpha),
                       modifier = Modifier.size(if (isEasyMode) 24.dp else 20.dp)
                   )
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
                        onOptionSelected(option)
                        expanded = false
                    }
                )
            }
        }
    }
}



@Composable
fun PremiumCard(
    modifier: Modifier = Modifier,
    isSolarMode: Boolean = false,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    containerColor: Color = if (isSolarMode) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.18f).compositeOver(MaterialTheme.colorScheme.surface)
    } else {
        MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
    },
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = containerColor
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .let { if (onClick != null) it.clickable(onClick = onClick) else it }
                .padding(contentPadding),
            content = content
        )
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
    // Filter suggestions based on input, but don't show the one exactly matching what's typed
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
                properties = androidx.compose.ui.window.PopupProperties(focusable = false),
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
    debounceTime: Long = 500L,
    isError: Boolean = false,
    enabled: Boolean = true,
    isEasyMode: Boolean = false,
    focusRequester: androidx.compose.ui.focus.FocusRequester? = null
) {
    // Local state to hold the immediate value
    var text by remember { mutableStateOf(initialValue) }
    var isFocused by remember { mutableStateOf(false) }
    
    // Update local state when external initialValue changes (e.g. from DB reload), 
    // BUT ONLY IF we are not currently focused/typing.
    LaunchedEffect(initialValue) {
        if (text != initialValue && !isFocused) {
             text = initialValue
        }
    }

    // Debounce Logic: When text changes, wait X ms then call onValueChange
    // Only trigger if we are focused (user is typing) or text is significantly different from initial
    LaunchedEffect(text) {
        if (text != initialValue && isFocused) {
            kotlinx.coroutines.delay(debounceTime)
            onValueChange(text)
        }
    }

    CompactInputBox(
        label = label,
        value = text,
        onValueChange = { newText ->
            text = newText
        },
        modifier = modifier,
        keyboardOptions = keyboardOptions,
        readOnly = readOnly,
        isError = isError,
        enabled = enabled,
        isEasyMode = isEasyMode,
        onFocusChanged = { isFocused = it },
        focusRequester = focusRequester
    )
}

@Composable
fun EmptyStateView(
    message: String,
    subMessage: String = "",
    icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Default.Info,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            modifier = Modifier.size(120.dp),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
            shape = androidx.compose.foundation.shape.CircleShape
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                )
            }
        }
        
        Spacer(Modifier.height(24.dp))
        
        Text(
            text = message,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        
        if (subMessage.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = subMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp)
            )
        }
    }
}

@Composable
fun MeshGradient(
    modifier: Modifier = Modifier,
    colors: List<Color> = listOf(
        MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
        MaterialTheme.colorScheme.secondary.copy(alpha = 0.6f),
        MaterialTheme.colorScheme.tertiary.copy(alpha = 0.4f),
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
    )
) {
    Box(
        modifier = modifier
            .background(
                Brush.linearGradient(
                    colors = colors,
                    start = Offset.Zero,
                    end = Offset.Infinite
                )
            )
            .drawBehind {
                drawCircle(
                    color = colors[0].copy(alpha = 0.3f),
                    radius = size.width * 0.8f,
                    center = Offset(size.width * 0.2f, size.height * 0.2f)
                )
                drawCircle(
                    color = colors[1].copy(alpha = 0.2f),
                    radius = size.width * 0.6f,
                    center = Offset(size.width * 0.8f, size.height * 0.7f)
                )
            }
    )
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .let { 
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    it.blur(20.dp)
                } else it
            }
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
            .border(
                BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
                RoundedCornerShape(16.dp)
            )
    ) {
        Box(modifier = Modifier.padding(16.dp)) {
            content()
        }
    }
}

@Composable
fun UserIconMenu(
    user: com.antigravity.healthagent.domain.repository.AuthUser,
    onLogout: () -> Unit,
    onSwitchAccount: () -> Unit,
    onOpenSettings: () -> Unit = {},
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onPrimary
) {
    var showUserMenu by remember { mutableStateOf(false) }
    
    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .clickable { showUserMenu = true },
            contentAlignment = Alignment.Center
        ) {
            UserAvatar(
                user = user,
                size = 34.dp
            )
        }
        DropdownMenu(
            expanded = showUserMenu,
            onDismissRequest = { showUserMenu = false }
        ) {
            DropdownMenuItem(
                text = { 
                    Column {
                        Text(user.displayName ?: "Usuário", fontWeight = FontWeight.Bold)
                        Text(user.email ?: "", style = MaterialTheme.typography.bodySmall)
                        if (!user.agentName.isNullOrBlank()) {
                            Text("Agente: ${user.agentName}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                },
                onClick = { },
                enabled = false
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Configurações") },
                onClick = { 
                    showUserMenu = false
                    onOpenSettings()
                },
                leadingIcon = { Icon(Icons.Default.Settings, null) }
            )
            DropdownMenuItem(
                text = { Text("Trocar Conta") },
                onClick = { 
                    showUserMenu = false
                    onSwitchAccount()
                },
                leadingIcon = { Icon(Icons.Default.SwapHoriz, null) }
            )
        }
    }
}

@Composable
fun UserAvatar(
    user: com.antigravity.healthagent.domain.repository.AuthUser,
    size: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier
) {
    val avatarColors = listOf(
        Color(0xFF4285F4), // Google Blue
        Color(0xFFEA4335), // Google Red
        Color(0xFFFBBC04), // Google Yellow
        Color(0xFF34A853), // Google Green
        Color(0xFF673AB7), // Deep Purple
        Color(0xFFF44336), // Red
        Color(0xFF2196F3), // Blue
        Color(0xFF4CAF50)  // Green
    )
    
    val backgroundColor = remember(user.uid) {
        avatarColors[user.uid.hashCode().let { if (it < 0) -it else it } % avatarColors.size]
    }

    Surface(
        modifier = modifier
            .size(size)
            .shadow(elevation = 2.dp, shape = CircleShape)
            .border(1.dp, Color.White.copy(alpha = 0.4f), CircleShape),
        shape = CircleShape,
        color = backgroundColor
    ) {
        if (!user.photoUrl.isNullOrEmpty()) {
            AsyncImage(
                model = user.photoUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().clip(CircleShape),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop
            )
        } else {
            val initial = remember(user.displayName, user.email) {
                (user.displayName ?: user.email ?: "?").take(1).uppercase()
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                Text(
                    text = initial,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = (size.value * 0.5).sp,
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassTopAppBar(
    title: @Composable () -> Unit,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    scrollBehavior: TopAppBarScrollBehavior? = null,
    user: com.antigravity.healthagent.domain.repository.AuthUser? = null,
    onLogout: () -> Unit = {},
    onSwitchAccount: () -> Unit = {},
    onOpenSettings: () -> Unit = {}
) {
    var showUserMenu by remember { mutableStateOf(false) }
    val containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
    
    Box(modifier = Modifier.fillMaxWidth()) {
        MeshGradient(
            modifier = Modifier
                .matchParentSize()
                .let { 
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        it.blur(40.dp)
                    } else it
                },
            colors = listOf(
                MaterialTheme.colorScheme.primary,
                MaterialTheme.colorScheme.primary.copy(alpha = 0.9f),
                MaterialTheme.colorScheme.secondary.copy(alpha = 0.8f)
            )
        )
        
        TopAppBar(
            title = title,
            navigationIcon = navigationIcon,
            actions = {
                actions()
                if (user != null) {
                    UserIconMenu(
                        user = user,
                        onLogout = onLogout,
                        onSwitchAccount = onSwitchAccount,
                        onOpenSettings = onOpenSettings
                    )
                }
            },
            scrollBehavior = scrollBehavior,
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent,
                scrolledContainerColor = Color.Transparent,
                titleContentColor = MaterialTheme.colorScheme.onPrimary,
                navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                actionIconContentColor = MaterialTheme.colorScheme.onPrimary
            )
        )

    }
}

@Composable
fun GlassNavigationBar(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    Box(modifier = modifier.fillMaxWidth()) {
        MeshGradient(
            modifier = Modifier
                .matchParentSize()
                .let {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        it.blur(40.dp)
                    } else it
                },
            colors = listOf(
                MaterialTheme.colorScheme.primary,
                MaterialTheme.colorScheme.primary.copy(alpha = 0.9f),
                MaterialTheme.colorScheme.secondary.copy(alpha = 0.8f)
            )
        )

        NavigationBar(
            containerColor = Color.Transparent,
            content = content
        )
    }
}

