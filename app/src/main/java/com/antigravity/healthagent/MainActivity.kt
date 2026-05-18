package com.antigravity.healthagent

import android.os.Bundle
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.tooling.preview.Preview
import com.antigravity.healthagent.ui.theme.HealthAgentFormsTheme
import com.antigravity.healthagent.ui.home.DayErrorSummary
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.Scaffold
import androidx.compose.ui.zIndex
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Group
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import dagger.hilt.android.AndroidEntryPoint
import com.antigravity.healthagent.ui.semanal.SemanalScreen
import com.antigravity.healthagent.ui.quarteiroes.QuarteiroesScreen
import androidx.compose.material.icons.filled.LocationOn
import androidx.activity.compose.BackHandler
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.mutableLongStateOf
import android.widget.Toast
import android.app.Activity
import androidx.compose.runtime.LaunchedEffect
import com.antigravity.healthagent.ui.components.AppExitDialog
import com.antigravity.healthagent.utils.SoundManager
import com.antigravity.healthagent.ui.auth.LoginScreen
import com.antigravity.healthagent.ui.auth.LoginViewModel
import com.antigravity.healthagent.ui.auth.AuthState
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Edit
import com.antigravity.healthagent.ui.components.*
import com.antigravity.healthagent.ui.home.MainScreen
import com.antigravity.healthagent.domain.repository.UserRole
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    
    @Inject
    lateinit var soundManager: SoundManager

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        setContent {
            val viewModel: com.antigravity.healthagent.ui.home.HomeViewModel = hiltViewModel()
            val loginViewModel: LoginViewModel = hiltViewModel()
            
            val themeMode by viewModel.themeMode.collectAsState()
            val themeColor by viewModel.themeColor.collectAsState()
            val solarMode by viewModel.solarMode.collectAsState()
            
            val authState by loginViewModel.authState.collectAsState()

            val tMode = themeMode
            val tColor = themeColor

            if (tMode != null && tColor != null && authState !is AuthState.Loading) {
                HealthAgentFormsTheme(
                    themeMode = tMode,
                    themeColor = tColor,
                    solarMode = solarMode
                ) {
                    when (authState) {
                        is AuthState.Authenticated -> {
                            MainScreen(loginViewModel, viewModel)
                        }
                        else -> {
                            LoginScreen(
                                viewModel = loginViewModel,
                                onLoginSuccess = { /* Automatically handled by state */ }
                            )
                        }
                    }
                }
            } else {
                // Keep the splash screen on screen until we have the theme and auth state is ready
                splashScreen.setKeepOnScreenCondition { 
                    themeMode == null || themeColor == null || authState is AuthState.Loading 
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        soundManager.release()
    }
}