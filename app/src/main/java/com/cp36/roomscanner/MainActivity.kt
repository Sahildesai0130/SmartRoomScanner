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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val settings by settingsRepository.settings.collectAsStateWithLifecycle()
            RoomScannerTheme(settings.theme) {
                CrashReportDialog()
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

/** Shows the error saved by RoomScannerApp after a crash, so it can be copied and reported. */
@Composable
private fun CrashReportDialog() {
    val context = LocalContext.current
    val file = remember { File(context.filesDir, RoomScannerApp.CRASH_FILE) }
    var text by remember { mutableStateOf(if (file.exists()) runCatching { file.readText() }.getOrNull() else null) }
    val clipboard = LocalClipboardManager.current
    val report = text ?: return
    fun dismiss() { file.delete(); text = null }
    AlertDialog(
        onDismissRequest = { dismiss() },
        title = { Text("The app crashed last time") },
        text = {
            SelectionContainer(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(report, fontSize = 11.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = { clipboard.setText(AnnotatedString(report)); dismiss() }) { Text("Copy & close") }
        },
        dismissButton = { TextButton(onClick = { dismiss() }) { Text("Close") } },
    )
}
