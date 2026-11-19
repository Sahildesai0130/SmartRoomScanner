package com.cp36.roomscanner.ui.scan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cp36.roomscanner.ar.ArFrameInfo
import com.cp36.roomscanner.ar.PointerState
import com.cp36.roomscanner.data.ProjectRepository
import com.cp36.roomscanner.data.SettingsRepository
import com.cp36.roomscanner.domain.geometry.Geometry
import com.cp36.roomscanner.domain.geometry.PlanOps
import com.cp36.roomscanner.domain.model.Opening
import com.cp36.roomscanner.domain.model.OpeningDefaults
import com.cp36.roomscanner.domain.model.OpeningType
import com.cp36.roomscanner.domain.model.RoomPlan
import com.cp36.roomscanner.domain.model.Vec2
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.min

enum class ScanPhase { FLOOR, CORNERS, CEILING, OPENINGS }

data class ScanUiState(
    val phase: ScanPhase = ScanPhase.FLOOR,
    val ceilingHeight: Double? = null,
    val openings: List<Opening> = emptyList(),
    /** First edge of a door/window waiting for its second edge. */
    val pendingEdge: Vec2? = null,
    val openingType: OpeningType = OpeningType.DOOR,
    val message: String? = null,
    val saving: Boolean = false,
)

@HiltViewModel
class ScanViewModel @Inject constructor(
    private val repository: ProjectRepository,
    val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _ui = MutableStateFlow(ScanUiState())
    val ui: StateFlow<ScanUiState> = _ui.asStateFlow()

    /** Latest camera frame, updated ~30–60 times a second from the GL thread. */
    private val _frame = MutableStateFlow<ArFrameInfo?>(null)
    val frame: StateFlow<ArFrameInfo?> = _frame.asStateFlow()

    fun onFrame(info: ArFrameInfo) {
        _frame.value = info
        if (_ui.value.phase == ScanPhase.FLOOR && info.floorY != null) {
            _ui.update { it.copy(phase = ScanPhase.CORNERS) }
        }
    }

    fun message(text: String?) = _ui.update { it.copy(message = text) }

    fun closeRoom() {
        val n = _frame.value?.corners?.size ?: 0
        if (n < 3) { message("Capture at least 3 corners first"); return }
        _ui.update { it.copy(phase = ScanPhase.CEILING) }
    }

    fun backToCorners() = _ui.update { it.copy(phase = ScanPhase.CORNERS, openings = emptyList(), pendingEdge = null) }

    // ---------------------------------------------------------------- Ceiling

    /**
     * Live ceiling height while aiming up: the camera ray is compared with the vertical line
     * above each captured corner; the closest one gives the height where the ray meets it.
     */
    fun ceilingPreview(info: ArFrameInfo): Double? {
        val floor = info.floorY ?: return null
        if (info.corners.isEmpty() || info.rayDir.y <= 0.05f) return null
        val o = info.rayOrigin; val d = info.rayDir
        val dh = Vec2(d.x.toDouble(), d.z.toDouble())
        val dh2 = dh dot dh
        if (dh2 < 1e-6) return null
        var best: Pair<Double, Double>? = null // (horizontal miss distance, height)
        for (c in info.corners) {
            val rel = Vec2((c.x - o.x).toDouble(), (c.z - o.z).toDouble())
            val t = (rel dot dh) / dh2
            if (t <= 0) continue
            val miss = (rel - dh * t).length()
            val h = o.y + d.y * t - floor
            if (best == null || miss < best.first) best = miss to h
        }
        val (miss, h) = best ?: return null
        return if (miss < 0.6 && h in 1.5..6.0) h else null
    }

    fun captureCeiling() {
        val info = _frame.value ?: return
        val h = ceilingPreview(info)
        if (h == null) message("Aim at the ceiling straight above one of the corners")
        else _ui.update { it.copy(ceilingHeight = h, phase = ScanPhase.OPENINGS, message = null) }
    }

    fun setCeilingManually(h: Double) = _ui.update { it.copy(ceilingHeight = h, phase = ScanPhase.OPENINGS) }

    // ---------------------------------------------------------------- Doors and windows

    fun setOpeningType(t: OpeningType) = _ui.update { it.copy(openingType = t, pendingEdge = null) }

    /** Tap both edges of a door or window (aim at the floor below each edge). */
    fun markOpeningEdge() {
        val info = _frame.value ?: return
        val p = info.pointer
        if (p == null || info.pointerState == PointerState.NONE) { message("Aim at the floor below the edge"); return }
        val point = p.xz()
        val corners = info.corners.map { it.xz() }
        val first = _ui.value.pendingEdge
        if (first == null) {
            _ui.update { it.copy(pendingEdge = point, message = "Now aim at the other edge") }
            return
        }
        val a = Geometry.projectOntoWalls(first, corners)
        val b = Geometry.projectOntoWalls(point, corners)
        if (a == null || b == null || a.wallIndex != b.wallIndex) {
            _ui.update { it.copy(pendingEdge = null, message = "Both edges must be on the same wall – try again") }
            return
        }
        val start = min(a.offset, b.offset)
        val width = max(a.offset, b.offset) - start
        if (width < 0.3) {
            _ui.update { it.copy(pendingEdge = null, message = "Opening too narrow – try again") }
            return
        }
        val type = _ui.value.openingType
        val d = if (type == OpeningType.DOOR) OpeningDefaults.DOOR else OpeningDefaults.WINDOW
        val id = (_ui.value.openings.maxOfOrNull { it.id } ?: 0L) + 1
        val o = Opening(id, type, a.wallIndex, start, width, d.height, d.sill)
        _ui.update { it.copy(openings = it.openings + o, pendingEdge = null, message = "${type.label()} added") }
    }

    fun undoOpening() = _ui.update {
        if (it.pendingEdge != null) it.copy(pendingEdge = null) else it.copy(openings = it.openings.dropLast(1))
    }

    // ---------------------------------------------------------------- Finish

    /**
     * Geometry processing: PCA wall refinement (optional) → least-squares orthogonal snapping
     * (optional) → save. Calls [onSaved] with the new project id.
     */
    fun finish(wallPoints: List<Vec2>, onSaved: (Long) -> Unit) {
        val info = _frame.value ?: return
        if (info.corners.size < 3) { message("Capture at least 3 corners first"); return }
        val settings = settingsRepository.settings.value
        _ui.update { it.copy(saving = true) }
        viewModelScope.launch {
            val plan = withContext(Dispatchers.Default) {
                var corners = info.corners.map { it.xz() }
                // Move the plan so the first corner is at the origin (nicer numbers in CAD).
                val origin = corners.first()
                corners = corners.map { it - origin }
                val pts = wallPoints.map { it - origin }
                if (settings.refineWithPca) corners = Geometry.refineWithWallPoints(corners, pts)
                if (settings.autoSquare) corners = Geometry.squareCorners(corners, settings.squareTolerance)
                val name = "Room " + SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).format(Date())
                PlanOps.clampOpenings(
                    RoomPlan(
                        name = name,
                        corners = corners,
                        ceilingHeight = _ui.value.ceilingHeight ?: 2.7,
                        openings = _ui.value.openings,
                        source = "AR",
                    )
                )
            }
            val id = repository.create(plan)
            _ui.update { it.copy(saving = false) }
            onSaved(id)
        }
    }
}

fun OpeningType.label() = if (this == OpeningType.DOOR) "Door" else "Window"
