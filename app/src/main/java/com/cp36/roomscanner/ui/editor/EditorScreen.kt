package com.cp36.roomscanner.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.SquareFoot
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cp36.roomscanner.domain.geometry.Geometry
import com.cp36.roomscanner.domain.model.LengthUnit
import com.cp36.roomscanner.domain.model.Opening
import com.cp36.roomscanner.domain.model.OpeningType
import com.cp36.roomscanner.domain.model.RoomPlan
import com.cp36.roomscanner.domain.model.UnitFormat
import com.cp36.roomscanner.domain.model.Vec2
import com.cp36.roomscanner.export.ExportFormat
import com.cp36.roomscanner.export.Exporters
import com.cp36.roomscanner.ui.common.ConfirmDialog
import com.cp36.roomscanner.ui.common.LengthDialog
import com.cp36.roomscanner.ui.plan.PlanDrawer
import com.cp36.roomscanner.ui.plan.PlanStyle
import com.cp36.roomscanner.ui.plan.PlanTransform
import com.cp36.roomscanner.ui.plan.Selection
import java.util.Locale

private sealed interface EditorDialog {
    data class WallLength(val wall: Int) : EditorDialog
    data class Tape(val wall: Int) : EditorDialog
    data class OpeningWidth(val id: Long) : EditorDialog
    data class OpeningOffset(val id: Long) : EditorDialog
    data class OpeningHeight(val id: Long) : EditorDialog
    data object Ceiling : EditorDialog
    data object Rename : EditorDialog
    data object Square : EditorDialog
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(viewModel: EditorViewModel, onBack: () -> Unit) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val settings by viewModel.settingsRepository.settings.collectAsStateWithLifecycle()
    val unit = settings.unit
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var dialog by remember { mutableStateOf<EditorDialog?>(null) }
    var exportMenu by remember { mutableStateOf(false) }
    var view by remember { mutableStateOf<PlanTransform?>(null) }
    var canvasSize by remember { mutableStateOf(Offset.Zero) }

    // Fit the plan to the screen once both the plan and the canvas size are known.
    LaunchedEffect(canvasSize, ui.loading) {
        if (view == null && !ui.loading && ui.plan.corners.isNotEmpty() && canvasSize.x > 0f) {
            view = PlanTransform.fit(ui.plan, canvasSize.x, canvasSize.y, 90f)
        }
    }

    LaunchedEffect(ui.message) {
        ui.message?.let { snackbar.showSnackbar(it); viewModel.message(null) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        ui.plan.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { dialog = EditorDialog.Rename },
                    )
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = viewModel::undo, enabled = ui.canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, "Undo") }
                    IconButton(onClick = viewModel::redo, enabled = ui.canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, "Redo") }
                    Box {
                        IconButton(onClick = { exportMenu = true }) { Icon(Icons.Filled.IosShare, "Export") }
                        DropdownMenu(expanded = exportMenu, onDismissRequest = { exportMenu = false }) {
                            ExportFormat.entries.forEach { f ->
                                DropdownMenuItem(
                                    text = { Text(f.label) },
                                    onClick = { exportMenu = false; viewModel.export(context, f) },
                                )
                            }
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            StatsRow(ui.plan, unit) { dialog = EditorDialog.Ceiling }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                PlanCanvas(
                    plan = ui.plan,
                    unit = unit,
                    selection = ui.selection,
                    view = view,
                    onSize = { w, h -> canvasSize = Offset(w, h) },
                    onViewChange = { view = it },
                    onTap = viewModel::select,
                    onDragStart = viewModel::beginCornerDrag,
                    onDrag = viewModel::dragCorner,
                )
                IconButton(
                    onClick = { view = PlanTransform.fit(ui.plan, canvasSize.x, canvasSize.y, 90f) },
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
                ) { Icon(Icons.Filled.CenterFocusStrong, "Fit to screen") }
            }

            SelectionPanel(
                plan = ui.plan,
                selection = ui.selection,
                unit = unit,
                onDialog = { dialog = it },
                onAddOpening = viewModel::addOpening,
                onSplit = viewModel::splitWall,
                onDeleteCorner = viewModel::deleteCorner,
                onDeleteOpening = viewModel::removeOpening,
                onSwapOpening = { o ->
                    viewModel.updateOpening(
                        if (o.type == OpeningType.DOOR) o.copy(type = OpeningType.WINDOW, height = 1.2, sillHeight = 0.9)
                        else o.copy(type = OpeningType.DOOR, height = 2.1, sillHeight = 0.0)
                    )
                },
            )
        }
    }

    val plan = ui.plan
    fun opening(id: Long) = plan.openings.firstOrNull { it.id == id }
    when (val d = dialog) {
        null -> Unit
        is EditorDialog.WallLength -> LengthDialog(
            title = "Wall ${Exporters.label(d.wall)}–${Exporters.label((d.wall + 1) % plan.corners.size)}",
            label = "Length", unit = unit, initialMetres = plan.wallLength(d.wall),
            note = "Moves the end corner along the wall. The next wall slides with it, so right angles are kept.",
            onDismiss = { dialog = null },
            onConfirm = { dialog = null; viewModel.setWallLength(d.wall, it) },
        )
        is EditorDialog.Tape -> LengthDialog(
            title = "Verify with tape",
            label = "Measured length", unit = unit, initialMetres = null,
            note = "Measure this wall with a tape and type the real length. The whole plan is scaled to match, which corrects the AR scale error on every wall.",
            confirmText = "Correct plan",
            onDismiss = { dialog = null },
            onConfirm = { dialog = null; viewModel.calibrate(d.wall, it) },
        )
        is EditorDialog.OpeningWidth -> opening(d.id)?.let { o ->
            LengthDialog("Width", "Width", unit, o.width, onDismiss = { dialog = null }) {
                dialog = null; viewModel.updateOpening(o.copy(width = it))
            }
        }
        is EditorDialog.OpeningOffset -> opening(d.id)?.let { o ->
            LengthDialog(
                "Position", "Distance from corner ${Exporters.label(o.wallIndex)}", unit, o.offset,
                onDismiss = { dialog = null },
            ) { dialog = null; viewModel.updateOpening(o.copy(offset = it)) }
        }
        is EditorDialog.OpeningHeight -> opening(d.id)?.let { o ->
            LengthDialog("Height", "Height", unit, o.height, onDismiss = { dialog = null }) {
                dialog = null; viewModel.updateOpening(o.copy(height = it))
            }
        }
        EditorDialog.Ceiling -> LengthDialog("Ceiling height", "Height", unit, plan.ceilingHeight, onDismiss = { dialog = null }) {
            dialog = null; viewModel.setCeiling(it)
        }
        EditorDialog.Rename -> RenameDialog(plan.name, onDismiss = { dialog = null }) { dialog = null; viewModel.rename(it) }
        EditorDialog.Square -> ConfirmDialog(
            "Square corners",
            "Walls within ${settings.squareTolerance.toInt()}° of square are straightened to exact 90° angles, moving the corners as little as possible (least squares).",
            "Square", onDismiss = { dialog = null },
        ) { dialog = null; viewModel.squareCorners() }
    }
}

@Composable
private fun StatsRow(plan: RoomPlan, unit: LengthUnit, onCeiling: () -> Unit) {
    val area = Geometry.area(plan.corners)
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Stat("Area", UnitFormat.area(area, unit))
        Stat("Perimeter", UnitFormat.length(Geometry.perimeter(plan.corners), unit))
        Stat("Height ✎", UnitFormat.length(plan.ceilingHeight, unit), Modifier.clickable(onClick = onCeiling))
        Stat("Volume", UnitFormat.volume(area * plan.ceilingHeight, unit))
        Stat("Walls", plan.corners.size.toString())
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun PlanCanvas(
    plan: RoomPlan,
    unit: LengthUnit,
    selection: Selection?,
    view: PlanTransform?,
    onSize: (Float, Float) -> Unit,
    onViewChange: (PlanTransform) -> Unit,
    onTap: (Selection?) -> Unit,
    onDragStart: (Int) -> Unit,
    onDrag: (Int, Vec2) -> Unit,
) {
    val density = LocalDensity.current.density
    val planState = rememberUpdatedState(plan)
    val viewState = rememberUpdatedState(view)
    val scheme = MaterialTheme.colorScheme
    val style = PlanStyle(
        wall = scheme.onSurface.toArgb(),
        floor = scheme.primary.copy(alpha = 0.10f).toArgb(),
        text = scheme.onSurface.toArgb(),
        accent = scheme.primary.toArgb(),
        density = density,
    )

    Canvas(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .onSizeChanged { onSize(it.width.toFloat(), it.height.toFloat()) }
            .pointerInput(Unit) {
                val hitRadius = 28.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val t0 = viewState.value ?: return@awaitEachGesture
                    val p0 = planState.value
                    val corner = nearestCorner(p0, t0, down.position, hitRadius)
                    var dragging = false
                    var moved = false
                    var totalPan = Offset.Zero
                    var t = t0
                    do {
                        val event = awaitPointerEvent()
                        val multi = event.changes.count { it.pressed } > 1
                        if (corner != null && !multi) {
                            val ch = event.changes.first()
                            totalPan += ch.positionChange()
                            if (!dragging && totalPan.getDistance() > viewConfiguration.touchSlop) {
                                dragging = true; moved = true
                                onDragStart(corner)
                            }
                            if (dragging) onDrag(corner, t.toWorld(ch.position.x, ch.position.y))
                            ch.consume()
                        } else {
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            totalPan += pan
                            if (multi || totalPan.getDistance() > viewConfiguration.touchSlop) {
                                moved = true
                                val c = event.calculateCentroid(useCurrent = true)
                                val s = (t.scale * zoom).coerceIn(5f, 5000f)
                                val k = s / t.scale
                                t = PlanTransform(
                                    s,
                                    c.x - (c.x - t.offsetX) * k + pan.x,
                                    c.y - (c.y - t.offsetY) * k + pan.y,
                                )
                                onViewChange(t)
                            }
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                    if (!moved) onTap(hitTest(p0, t0, down.position, hitRadius))
                }
            }
    ) {
        val t = view ?: return@Canvas
        drawIntoCanvas { PlanDrawer.draw(it.nativeCanvas, plan, t, unit, style, selection) }
    }
}

private fun nearestCorner(plan: RoomPlan, t: PlanTransform, pos: Offset, radius: Float): Int? {
    var best: Int? = null
    var bestD = radius
    plan.corners.forEachIndexed { i, c ->
        val s = t.toScreen(c)
        val d = Math.hypot(s.x - pos.x, s.y - pos.y).toFloat()
        if (d < bestD) { bestD = d; best = i }
    }
    return best
}

private fun hitTest(plan: RoomPlan, t: PlanTransform, pos: Offset, radius: Float): Selection? {
    nearestCorner(plan, t, pos, radius)?.let { return Selection.Corner(it) }
    val world = t.toWorld(pos.x, pos.y)
    val tol = radius / t.scale
    for (o in plan.openings) {
        val a = plan.wallStart(o.wallIndex); val b = plan.wallEnd(o.wallIndex)
        val u = (b - a).normalized()
        val p1 = a + u * o.offset; val p2 = a + u * (o.offset + o.width)
        if (segmentDistance(world, p1, p2) < tol) return Selection.OpeningSel(o.id)
    }
    val proj = Geometry.projectOntoWalls(world, plan.corners) ?: return null
    return if (proj.distance < tol) Selection.Wall(proj.wallIndex) else null
}

private fun segmentDistance(p: Vec2, a: Vec2, b: Vec2): Double {
    val ab = b - a
    val l2 = ab dot ab
    if (l2 < 1e-12) return (p - a).length()
    val t = (((p - a) dot ab) / l2).coerceIn(0.0, 1.0)
    return (a + ab * t - p).length()
}

@Composable
private fun SelectionPanel(
    plan: RoomPlan,
    selection: Selection?,
    unit: LengthUnit,
    onDialog: (EditorDialog) -> Unit,
    onAddOpening: (OpeningType, Int) -> Unit,
    onSplit: (Int) -> Unit,
    onDeleteCorner: (Int) -> Unit,
    onDeleteOpening: (Long) -> Unit,
    onSwapOpening: (Opening) -> Unit,
) {
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            val chips = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            when (selection) {
                null -> {
                    Text("Tap a wall, corner or opening to edit it. Drag corners to move them. Pinch to zoom.", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    Row(chips, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(onClick = { onDialog(EditorDialog.Square) }, label = { Text("Square corners") },
                            leadingIcon = { Icon(Icons.Filled.SquareFoot, null) })
                        AssistChip(onClick = { onDialog(EditorDialog.Ceiling) }, label = { Text("Ceiling height") })
                        AssistChip(onClick = { onDialog(EditorDialog.Rename) }, label = { Text("Rename") },
                            leadingIcon = { Icon(Icons.Filled.Edit, null) })
                    }
                }
                is Selection.Wall -> {
                    val i = selection.index
                    val n = plan.corners.size
                    Text(
                        "Wall ${Exporters.label(i)}–${Exporters.label((i + 1) % n)}: ${UnitFormat.length(plan.wallLength(i), unit)}",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(chips, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(onClick = { onDialog(EditorDialog.WallLength(i)) }, label = { Text("Set length") },
                            leadingIcon = { Icon(Icons.Filled.Straighten, null) })
                        AssistChip(onClick = { onDialog(EditorDialog.Tape(i)) }, label = { Text("Verify with tape") })
                        AssistChip(onClick = { onAddOpening(OpeningType.DOOR, i) }, label = { Text("+ Door") })
                        AssistChip(onClick = { onAddOpening(OpeningType.WINDOW, i) }, label = { Text("+ Window") })
                        AssistChip(onClick = { onSplit(i) }, label = { Text("Add corner") })
                    }
                }
                is Selection.Corner -> {
                    val i = selection.index
                    val angle = Geometry.interiorAngles(plan.corners).getOrNull(i) ?: 0.0
                    Text(
                        String.format(Locale.US, "Corner %s · angle %.1f°", Exporters.label(i), angle),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(chips, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(onClick = { onDeleteCorner(i) }, label = { Text("Delete corner") },
                            leadingIcon = { Icon(Icons.Filled.Delete, null) })
                    }
                }
                is Selection.OpeningSel -> {
                    val o = plan.openings.firstOrNull { it.id == selection.id }
                    if (o != null) {
                        Text(
                            (if (o.type == OpeningType.DOOR) "Door" else "Window") +
                                " · ${UnitFormat.length(o.width, unit)} wide · ${UnitFormat.length(o.height, unit)} high",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(chips, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AssistChip(onClick = { onDialog(EditorDialog.OpeningWidth(o.id)) }, label = { Text("Width") })
                            AssistChip(onClick = { onDialog(EditorDialog.OpeningOffset(o.id)) }, label = { Text("Position") })
                            AssistChip(onClick = { onDialog(EditorDialog.OpeningHeight(o.id)) }, label = { Text("Height") })
                            AssistChip(onClick = { onSwapOpening(o) },
                                label = { Text(if (o.type == OpeningType.DOOR) "Make window" else "Make door") })
                            AssistChip(onClick = { onDeleteOpening(o.id) }, label = { Text("Delete") },
                                leadingIcon = { Icon(Icons.Filled.Delete, null) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RenameDialog(current: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename room") },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(text.trim()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
