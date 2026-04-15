package com.antigravity.healthagent.ui.auth

import android.app.Activity
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import com.antigravity.healthagent.R
// import com.antigravity.healthagent.ui.components.MeshGradient
import com.antigravity.healthagent.ui.components.GlassCard
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.vectorResource
import androidx.compose.material.icons.filled.Warning
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.launch
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person

@Composable
fun LoginScreen(
    viewModel: LoginViewModel,
    onLoginSuccess: () -> Unit
) {
    val authState by viewModel.authState.collectAsState()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    // We navigate away when authenticated
    LaunchedEffect(authState) {
        if (authState is AuthState.Authenticated) {
            onLoginSuccess()
        }
    }

    var showContent by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        showContent = true
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colorResource(id = R.color.app_background_dark))
    ) {
        
        AnimatedVisibility(
            visible = showContent,
            enter = fadeIn(tween(1000)) + slideInVertically(tween(1000)) { it / 8 },
            modifier = Modifier.align(Alignment.Center)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                GlassCard(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(36.dp)
                    ) {
                        // Header section
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "Eu ACE",
                                style = MaterialTheme.typography.displaySmall,
                                fontWeight = FontWeight.Black,
                                color = MaterialTheme.colorScheme.primary,
                                letterSpacing = (-1).sp
                            )
                            Text(
                                text = "Vigilância em Saúde",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(140.dp)
                                .padding(4.dp)
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.logo_vigilancia),
                                contentDescription = "Logo",
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        val displayError = when (val state = authState) {
                            is AuthState.Error -> state.message
                            is AuthState.WaitingForAuthorization -> state.error
                            else -> null
                        }

                        if (displayError != null) {
                            Surface(
                                color = Color(0xFFB00020), // High-visibility error red
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.fillMaxWidth().shadow(8.dp, RoundedCornerShape(16.dp))
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Icon(Icons.Default.Warning, null, tint = Color.White)
                                    Text(
                                        text = displayError,
                                        color = Color.White,
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }

                        if (authState is AuthState.WaitingForAuthorization) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = Color(0xFFFF5252).copy(alpha = 0.15f),
                                    modifier = Modifier.size(80.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Default.Lock,
                                            contentDescription = null,
                                            modifier = Modifier.size(40.dp),
                                            tint = Color(0xFFFF5252)
                                        )
                                    }
                                }
                                
                                Text(
                                    text = "Acesso Pendente",
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Black,
                                    color = Color(0xFFFF5252)
                                )
                                Text(
                                    text = "Sua conta (${(authState as AuthState.WaitingForAuthorization).user.email}) aguarda autorização.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    color = Color.White.copy(alpha = 0.8f)
                                )
                                
                                val requestSent by viewModel.requestSent.collectAsState()
                                var requestedName by remember { mutableStateOf("") }
                                
                                if (requestSent) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.8f),
                                        shape = RoundedCornerShape(16.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = "Solicitação enviada! Fale com seu supervisor.",
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                            modifier = Modifier.padding(16.dp)
                                        )
                                    }
                                } else {
                                    OutlinedTextField(
                                        value = requestedName,
                                        onValueChange = { requestedName = it },
                                        placeholder = { Text("Nome do Agente (Opcional)") },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(16.dp),
                                        singleLine = true,
                                        colors = OutlinedTextFieldDefaults.colors(
                                            unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                                        )
                                    )
                                    
                                    Button(
                                        onClick = { viewModel.requestAccess(requestedName.ifBlank { null }) },
                                        modifier = Modifier.fillMaxWidth().height(56.dp),
                                        shape = RoundedCornerShape(16.dp)
                                    ) {
                                        Text("Solicitar Autorização", fontWeight = FontWeight.Bold)
                                    }
                                }
                                
                                TextButton(onClick = { viewModel.signOut() }) {
                                    Text("Entrar com outra conta", fontWeight = FontWeight.Bold)
                                }
                            }
                        } else {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(20.dp)
                            ) {
                                Text(
                                    text = "Faça login com sua conta Google para sincronizar seus dados.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                                )

                                Surface(
                                    onClick = {
                                        coroutineScope.launch {
                                            try {
                                                val credentialManager = CredentialManager.create(context)
                                                val webClientId = context.getString(R.string.default_web_client_id)
                                                val googleIdOption = GetGoogleIdOption.Builder()
                                                    .setFilterByAuthorizedAccounts(false)
                                                    .setServerClientId(webClientId)
                                                    .setAutoSelectEnabled(true)
                                                    .setNonce("login_nonce_${System.currentTimeMillis()}")
                                                    .build()
                                                val request = GetCredentialRequest.Builder()
                                                    .addCredentialOption(googleIdOption)
                                                    .build()
                                                val result = credentialManager.getCredential(
                                                    request = request,
                                                    context = context
                                                )
                                                val credential = result.credential
                                                if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                                                    val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                                                    viewModel.signInWithGoogle(googleIdTokenCredential.idToken)
                                                }
                                            } catch (e: androidx.credentials.exceptions.GetCredentialCancellationException) {
                                                Log.i("LoginScreen", "User cancelled")
                                            } catch (e: Exception) {
                                                Log.e("LoginScreen", "Login error", e)
                                                viewModel.setError("Erro no login: ${e.message}")
                                            }
                                        }
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(64.dp)
                                        .shadow(8.dp, RoundedCornerShape(20.dp)),
                                    shape = RoundedCornerShape(20.dp),
                                    color = MaterialTheme.colorScheme.surface,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        if (authState is AuthState.Loading) {
                                            CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                                        } else {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.Center,
                                                modifier = Modifier.padding(horizontal = 24.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Person,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(24.dp),
                                                    tint = MaterialTheme.colorScheme.primary
                                                )
                                                Spacer(modifier = Modifier.width(16.dp))
                                                Text(
                                                    text = "Entrar com Google",
                                                    style = MaterialTheme.typography.titleMedium,
                                                    fontWeight = FontWeight.Black,
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
