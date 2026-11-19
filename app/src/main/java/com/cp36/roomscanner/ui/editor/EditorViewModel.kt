package com.cp36.roomscanner.ui.editor

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cp36.roomscanner.data.ProjectRepository
import com.cp36.roomscanner.data.SettingsRepository
import com.cp36.roomscanner.domain.geometry.Geometry
import com.cp36.roomscanner.domain.geometry.PlanOps
import com.cp36.roomscanner.domain.model.Opening
import com.cp36.roomscanner.domain.model.OpeningType
import com.cp36.roomscanner.domain.model.RoomPlan
import com.cp36.roomscanner.domain.model.RoomStats
import com.cp36.roomscanner.domain.model.Vec2
import com.cp36.roomscanner.export.ExportFormat
import com.cp36.roomscanner.export.Exporters
import com.cp36.roomscanner.ui.plan.Selection
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import javax.inject.Inject

data class EditorUiState(
    val loading: Boolean = true,
    val plan: RoomPlan = RoomPlan(),
    val stats: RoomStats? = null,
    val selection: Selection? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val message: String? = null,
)

@HiltViewModel
class EditorViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repository: ProjectRepository,
    val settingsRepository: SettingsRepository,
    private val json: Json,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val projectId: Long = savedState.get<Long>("id") ?: 0L
    private var createdAt = System.currentTimeMillis()
    private val undoStack = ArrayDeque<RoomPlan>()
    private val redoStack = ArrayDeque<RoomPlan>()
    private var saveJob: Job? = null

    private val _ui = MutableStateFlow(EditorUiState())
    val ui: StateFlow<EditorUiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            val p = repository.get(projectId)
            if (p == null) {
                _ui.update { it.copy(loading = false, message = "Project not found") }
            } else {
                createdAt = p.createdAt
                _ui.update { it.copy(loading = false, plan = p.plan, stats = Geometry.stats(p.plan)) }
            }
        }
    }

    private val plan get() = _ui.value.plan

    /** Applies an edit: records undo history, recalculates, autosaves. */
    private fun commit(newPlan: RoomPlan, recordHistory: Boolean = true, selection: Selection? = _ui.value.selection) {
        if (recordHistory) {
            undoStack.addLast(plan)
            if (undoStack.size > 100) undoStack.removeFirst()
            redoStack.clear()
        }
        val sel = selection?.takeIf { isValid(it, newPlan) }
        _ui.update {
            it.copy(plan = newPlan, stats = Geometry.stats(newPlan), selection = sel, canUndo = undoStack.isNotEmpty(), canRedo = redoStack.isNotEmpty())
        }
        scheduleSave()
    }

    private fun isValid(s: Selection, p: RoomPlan) = when (s) {
        is Selection.Corner -> s.index in p.corners.indices
        is Selection.Wall -> s.index in p.corners.indices
        is Selection.OpeningSel -> p.openings.any { it.id == s.id }
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(400)
            repository.save(projectId, plan, createdAt)
        }
    }

    fun select(s: Selection?) = _ui.update { it.copy(selection = s) }
    fun message(m: String?) = _ui.update { it.copy(message = m) }

    fun undo() {
        val prev = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(plan)
        commit(prev, recordHistory = false)
        _ui.update { it.copy(canUndo = undoStack.isNotEmpty(), canRedo = redoStack.isNotEmpty()) }
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(plan)
        commit(next, recordHistory = false)
        _ui.update { it.copy(canUndo = undoStack.isNotEmpty(), canRedo = redoStack.isNotEmpty()) }
    }

    // ---------------------------------------------------------------- Editing

    /** Call once when a drag begins so the whole drag is one undo step. */
    fun beginCornerDrag(index: Int) {
        undoStack.addLast(plan)
        redoStack.clear()
        _ui.update { it.copy(selection = Selection.Corner(index)) }
    }

    fun dragCorner(index: Int, to: Vec2) {
        // Snap to a 1 cm grid while dragging.
        val snapped = Vec2(Math.round(to.x * 100) / 100.0, Math.round(to.y * 100) / 100.0)
        commit(PlanOps.moveCorner(plan, index, snapped), recordHistory = false)
    }

    fun setWallLength(wall: Int, length: Double) = commit(PlanOps.setWallLength(plan, wall, length))

    fun calibrate(wall: Int, trueLength: Double) {
        val before = plan.wallLength(wall)
        commit(PlanOps.calibrate(plan, wall, trueLength))
        val pct = (trueLength / before - 1) * 100
        message(String.format(java.util.Locale.US, "Whole plan scaled by %+.1f%% to match your tape", pct))
    }

    fun splitWall(wall: Int) = commit(PlanOps.splitWall(plan, wall), selection = Selection.Corner(wall + 1))

    fun deleteCorner(index: Int) {
        if (plan.corners.size <= 3) { message("A room needs at least 3 corners"); return }
        commit(PlanOps.deleteCorner(plan, index), selection = null)
    }

    fun squareCorners() {
        val tol = settingsRepository.settings.value.squareTolerance
        commit(PlanOps.square(plan, tol))
        message("Corners squared to 90° (walls within ${tol.toInt()}°)")
    }

    fun addOpening(type: OpeningType, wall: Int) {
        val p = PlanOps.addOpening(plan, type, wall)
        commit(p, selection = Selection.OpeningSel(p.openings.last().id))
    }

    fun updateOpening(o: Opening) = commit(PlanOps.updateOpening(plan, o))
    fun removeOpening(id: Long) = commit(PlanOps.removeOpening(plan, id), selection = null)
    fun rename(name: String) = commit(plan.copy(name = name.ifBlank { plan.name }))
    fun setCeiling(h: Double) = commit(plan.copy(ceilingHeight = h))

    // ---------------------------------------------------------------- Export

    fun export(context: Context, format: ExportFormat) {
        viewModelScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    Exporters.export(appContext, plan, format, settingsRepository.settings.value.unit, json)
                }
                Exporters.share(context, file, format)
            } catch (e: Exception) {
                message("Export failed: ${e.message}")
            }
        }
    }

    override fun onCleared() {
        // Make sure the last edit is saved even if the debounce hasn't fired.
        val p = plan
        if (!_ui.value.loading && p.corners.isNotEmpty()) {
            kotlinx.coroutines.runBlocking { repository.save(projectId, p, createdAt) }
        }
    }
}
