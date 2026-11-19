package com.cp36.roomscanner.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.cp36.roomscanner.data.ProjectEntity
import com.cp36.roomscanner.data.ProjectRepository
import com.cp36.roomscanner.data.SettingsRepository
import com.cp36.roomscanner.domain.geometry.PlanOps
import com.cp36.roomscanner.domain.model.LengthUnit
import com.cp36.roomscanner.domain.model.UnitFormat
import com.cp36.roomscanner.ui.common.ConfirmDialog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: ProjectRepository,
    val settingsRepository: SettingsRepository,
) : ViewModel() {
    val projects = repository.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun delete(id: Long) = viewModelScope.launch { repository.delete(id) }

    fun createManual(name: String, width: Double, length: Double, height: Double, onCreated: (Long) -> Unit) {
        viewModelScope.launch {
            onCreated(repository.create(PlanOps.rectangle(name, width, length, height)))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(viewModel: HomeViewModel, onNewScan: () -> Unit, onOpen: (Long) -> Unit, onSettings: () -> Unit) {
    val projects by viewModel.projects.collectAsStateWithLifecycle()
    val settings by viewModel.settingsRepository.settings.collectAsStateWithLifecycle()
    var showManual by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<ProjectEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Smart Room Scanner") },
                actions = { IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "Settings") } },
            )
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End) {
                FloatingActionButton(onClick = { showManual = true }) { Icon(Icons.Filled.Draw, "Draw manually") }
                Spacer(Modifier.height(12.dp))
                ExtendedFloatingActionButton(
                    onClick = onNewScan,
                    icon = { Icon(Icons.Filled.CameraAlt, null) },
                    text = { Text("New scan") },
                )
            }
        },
    ) { padding ->
        val list = projects
        when {
            list == null -> Unit
            list.isEmpty() -> EmptyState(Modifier.padding(padding))
            else -> LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = 160.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(list, key = { it.id }) { p ->
                    ProjectCard(p, settings.unit, onClick = { onOpen(p.id) }, onDelete = { toDelete = p })
                }
            }
        }
    }

    if (showManual) {
        ManualRoomDialog(
            unit = settings.unit,
            onDismiss = { showManual = false },
            onCreate = { name, w, l, h ->
                showManual = false
                viewModel.createManual(name, w, l, h, onOpen)
            },
        )
    }
    toDelete?.let { p ->
        ConfirmDialog("Delete project", "Delete \"${p.name}\"? This can't be undone.", "Delete",
            onDismiss = { toDelete = null }) { viewModel.delete(p.id); toDelete = null }
    }
}

@Composable
private fun EmptyState(modifier: Modifier) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.GridOn, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(16.dp))
            Text("No rooms yet", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                "Tap New scan to measure a room with the camera, or the pencil to draw one by hand.",
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ProjectCard(p: ProjectEntity, unit: LengthUnit, onClick: () -> Unit, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(p.name, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "${UnitFormat.area(p.area, unit)} · perimeter ${UnitFormat.length(p.perimeter, unit)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(p.updatedAt)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, "Delete") }
        }
    }
}

/** Manual mode for phones without ARCore: start from a rectangle, then edit it in the plan editor. */
@Composable
private fun ManualRoomDialog(unit: LengthUnit, onDismiss: () -> Unit, onCreate: (String, Double, Double, Double) -> Unit) {
    var name by remember { mutableStateOf("Room") }
    var w by remember { mutableStateOf(UnitFormat.toInput(4.0, unit)) }
    var l by remember { mutableStateOf(UnitFormat.toInput(3.0, unit)) }
    var h by remember { mutableStateOf(UnitFormat.toInput(2.7, unit)) }
    val wm = UnitFormat.parse(w, unit); val lm = UnitFormat.parse(l, unit); val hm = UnitFormat.parse(h, unit)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Draw a room manually") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Start with a rectangle; add corners, doors and windows in the editor.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                val kb = KeyboardOptions(keyboardType = if (unit == LengthUnit.FT_IN) KeyboardType.Text else KeyboardType.Decimal)
                Row {
                    OutlinedTextField(w, { w = it }, label = { Text("Width") }, suffix = { Text(unit.inputSuffix) },
                        singleLine = true, isError = wm == null, keyboardOptions = kb, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(l, { l = it }, label = { Text("Length") }, suffix = { Text(unit.inputSuffix) },
                        singleLine = true, isError = lm == null, keyboardOptions = kb, modifier = Modifier.weight(1f))
                }
                OutlinedTextField(h, { h = it }, label = { Text("Ceiling height") }, suffix = { Text(unit.inputSuffix) },
                    singleLine = true, isError = hm == null, keyboardOptions = kb)
            }
        },
        confirmButton = {
            TextButton(
                enabled = wm != null && lm != null && hm != null,
                onClick = { onCreate(name.ifBlank { "Room" }, wm!!, lm!!, hm!!) },
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
