package com.cp36.roomscanner

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.cp36.roomscanner.data.SettingsRepository
import com.cp36.roomscanner.ui.editor.EditorScreen
import com.cp36.roomscanner.ui.home.HomeScreen
import com.cp36.roomscanner.ui.scan.ScanScreen
import com.cp36.roomscanner.ui.settings.SettingsScreen
import com.cp36.roomscanner.ui.theme.RoomScannerTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val settings by settingsRepository.settings.collectAsStateWithLifecycle()
            RoomScannerTheme(settings.theme) {
                val nav = rememberNavController()
                NavHost(navController = nav, startDestination = "home") {
                    composable("home") {
                        HomeScreen(
                            viewModel = hiltViewModel(),
                            onNewScan = { nav.navigate("scan") },
                            onOpen = { id -> nav.navigate("editor/$id") },
                            onSettings = { nav.navigate("settings") },
                        )
                    }
                    composable("scan") {
                        ScanScreen(
                            viewModel = hiltViewModel(),
                            onBack = { nav.popBackStack() },
                            onFinished = { id ->
                                nav.navigate("editor/$id") { popUpTo("home") }
                            },
                        )
                    }
                    composable(
                        "editor/{id}",
                        arguments = listOf(navArgument("id") { type = NavType.LongType }),
                    ) {
                        EditorScreen(viewModel = hiltViewModel(), onBack = { nav.popBackStack() })
                    }
                    composable("settings") {
                        SettingsScreen(viewModel = hiltViewModel(), onBack = { nav.popBackStack() })
                    }
                }
            }
        }
    }
}
