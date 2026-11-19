package com.luo.skip

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.luo.skip.ui.AppState
import com.luo.skip.ui.rememberAppState
import com.luo.skip.ui.screens.ExcludedAppsScreen
import com.luo.skip.ui.screens.HomeScreen
import com.luo.skip.ui.screens.OnboardingScreen
import com.luo.skip.ui.screens.SettingsScreen
import com.luo.skip.ui.screens.StrategyScreen
import com.luo.skip.ui.theme.LocalSkipColors
import com.luo.skip.ui.theme.SkipTheme

private enum class Screen { Home, Excluded, Strategy, Settings, Onboarding }

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val appState = rememberAppState()
            val dark = when (appState.config.themeMode) {
                "light" -> false
                "dark" -> true
                else -> isSystemInDarkTheme()
            }
            SkipTheme(dark = dark) {
                val c = LocalSkipColors.current
                val view = LocalView.current
                SideEffect {
                    val controller = WindowCompat.getInsetsController(window, view)
                    controller.isAppearanceLightStatusBars = !c.isDark
                    controller.isAppearanceLightNavigationBars = !c.isDark
                }
                Surface(Modifier.fillMaxSize(), color = c.bg) {
                    SkipNavHost(appState)
                }
            }
        }
    }
}

@Composable
private fun SkipNavHost(appState: AppState) {
    var screen by remember { mutableStateOf<Screen?>(null) }
    val c = LocalSkipColors.current

    LaunchedEffect(appState.loaded) {
        if (appState.loaded && screen == null) {
            screen = if (appState.config.onboarded) Screen.Home else Screen.Onboarding
        }
    }

    val current = screen
    BackHandler(enabled = current != null && current != Screen.Home && current != Screen.Onboarding) {
        screen = Screen.Home
    }

    Box(Modifier.fillMaxSize().background(c.bg)) {
        when (current) {
            null -> Unit
            Screen.Onboarding -> OnboardingScreen(appState) { screen = Screen.Home }
            Screen.Home -> HomeScreen(
                appState = appState,
                onOpenSettings = { screen = Screen.Settings },
                onOpenExcluded = { screen = Screen.Excluded },
                onOpenStrategy = { screen = Screen.Strategy },
            )
            Screen.Excluded -> ExcludedAppsScreen(appState) { screen = Screen.Home }
            Screen.Strategy -> StrategyScreen(appState) { screen = Screen.Home }
            Screen.Settings -> SettingsScreen(appState) { screen = Screen.Home }
        }
    }
}
