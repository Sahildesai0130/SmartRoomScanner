package com.cp36.roomscanner.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cp36.roomscanner.data.SettingsRepository
import com.cp36.roomscanner.data.ThemeMode
import com.cp36.roomscanner.domain.model.LengthUnit
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(val repository: SettingsRepository) : ViewModel()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val s by viewModel.repository.settings.collectAsStateWithLifecycle()
    val repo = viewModel.repository
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp)) {
            Section("Units")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                LengthUnit.entries.forEachIndexed { i, u ->
                    SegmentedButton(
                        selected = s.unit == u,
                        onClick = { repo.update { it.copy(unit = u) } },
                        shape = SegmentedButtonDefaults.itemShape(i, LengthUnit.entries.size),
                    ) { Text(u.label) }
                }
            }

            Section("Geometry processing")
            ToggleRow(
                "Refine walls with PCA",
                "Fit wall lines through the 3D feature points ARCore sees on walls, then intersect them for more accurate corners.",
                s.refineWithPca,
            ) { v -> repo.update { it.copy(refineWithPca = v) } }
            ToggleRow(
                "Square corners automatically",
                "After a scan, straighten walls that are nearly 90° using least-squares orthogonal snapping.",
                s.autoSquare,
            ) { v -> repo.update { it.copy(autoSquare = v) } }
            Text("Snapping tolerance: ${s.squareTolerance.toInt()}°", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = s.squareTolerance.toFloat(),
                onValueChange = { v -> repo.update { it.copy(squareTolerance = v.toDouble()) } },
                valueRange = 3f..20f,
                steps = 16,
            )

            Section("Appearance")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                ThemeMode.entries.forEachIndexed { i, m ->
                    SegmentedButton(
                        selected = s.theme == m,
                        onClick = { repo.update { it.copy(theme = m) } },
                        shape = SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size),
                    ) { Text(m.name.lowercase().replaceFirstChar { it.uppercase() }) }
                }
            }

            Section("About")
            Text(
                "Smart Room Scanner · Group CP36\nAR-based room measurement and 2D floor plan generation.\n" +
                    "Accuracy depends on the phone and lighting; use \"Verify with tape\" on one wall to correct the scale.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(20.dp))
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(8.dp))
    HorizontalDivider()
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
