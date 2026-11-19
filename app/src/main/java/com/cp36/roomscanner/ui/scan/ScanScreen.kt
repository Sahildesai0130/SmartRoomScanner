package com.cp36.roomscanner.ui.scan

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.graphics.Paint
import android.graphics.Typeface
import android.opengl.GLSurfaceView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cp36.roomscanner.ar.ArFrameInfo
import com.cp36.roomscanner.ar.ArRenderer
import com.cp36.roomscanner.ar.PointerState
import com.cp36.roomscanner.ar.Vec3
import com.cp36.roomscanner.domain.model.LengthUnit
import com.cp36.roomscanner.domain.model.OpeningType
import com.cp36.roomscanner.domain.model.UnitFormat
import com.cp36.roomscanner.ui.common.LengthDialog
import com.cp36.roomscanner.ui.theme.Amber
import com.cp36.roomscanner.ui.theme.Green
import com.cp36.roomscanner.ui.theme.Red
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import kotlinx.coroutines.delay
import kotlin.math.sqrt

private sealed interface ArSetup {
    data object Checking : ArSetup
    data object NeedPermission : ArSetup
    data class Unavailable(val reason: String) : ArSetup
    data class Ready(val session: Session) : ArSetup
}

@Composable
fun ScanScreen(viewModel: ScanViewModel, onBack: () -> Unit, onFinished: (Long) -> Unit) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val lifecycleOwner = LocalLifecycleOwner.current

    var setup by remember { mutableStateOf<ArSetup>(ArSetup.Checking) }
    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var resumeTick by remember { mutableIntStateOf(0) }
    var installRequested by remember { mutableStateOf(false) }
    var permissionAsked by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
        if (!granted) setup = ArSetup.NeedPermission
    }

    // Re-check after returning from the Play Store (ARCore install) or the permission screen.
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) resumeTick++ }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    LaunchedEffect(hasPermission, resumeTick) {
        if (setup is ArSetup.Ready) return@LaunchedEffect
        if (!hasPermission) {
            // Ask automatically only once; after that the user taps "Grant permission".
            if (!permissionAsked) {
                permissionAsked = true
                permissionLauncher.launch(Manifest.permission.CAMERA)
            } else {
                setup = ArSetup.NeedPermission
            }
            return@LaunchedEffect
        }
        // 1. Is this phone supported by ARCore?
        var availability = ArCoreApk.getInstance().checkAvailability(context)
        var tries = 0
        while (availability.isTransient && tries < 25) {
            delay(200); tries++
            availability = ArCoreApk.getInstance().checkAvailability(context)
        }
        if (availability == ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE) {
            setup = ArSetup.Unavailable("This phone does not support ARCore.")
            return@LaunchedEffect
        }
        // 2. Install / update "Google Play Services for AR" if needed, then create the session.
        try {
            when (ArCoreApk.getInstance().requestInstall(activity, !installRequested)) {
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> { installRequested = true; return@LaunchedEffect }
                ArCoreApk.InstallStatus.INSTALLED -> Unit
                else -> Unit
            }
            val session = Session(context)
            val config = Config(session)
            config.setPlaneFindingMode(Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL)
            config.setFocusMode(Config.FocusMode.AUTO)
            config.setUpdateMode(Config.UpdateMode.LATEST_CAMERA_IMAGE)
            config.setLightEstimationMode(Config.LightEstimationMode.DISABLED)
            session.configure(config)
            setup = ArSetup.Ready(session)
        } catch (e: Exception) {
            setup = ArSetup.Unavailable(e.message ?: e.javaClass.simpleName)
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when (val s = setup) {
            ArSetup.Checking -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            ArSetup.NeedPermission -> SetupMessage(
                "The camera is needed to scan the room.",
                "Grant permission", { permissionLauncher.launch(Manifest.permission.CAMERA) }, onBack,
            )
            is ArSetup.Unavailable -> SetupMessage(
                "AR scanning isn't available: ${s.reason}\n\nGo back and use \"Draw manually\" instead.",
                "Back", onBack, onBack,
            )
            is ArSetup.Ready -> ArScanContent(s.session, viewModel, activity, onBack, onFinished)
        }
    }
}

@Composable
private fun SetupMessage(text: String, action: String, onAction: () -> Unit, onBack: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, color = Color.White, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        Button(onClick = onAction) { Text(action) }
        TextButton(onClick = onBack) { Text("Cancel", color = Color.White) }
    }
}

@Composable
private fun ArScanContent(
    session: Session,
    viewModel: ScanViewModel,
    activity: Activity,
    onBack: () -> Unit,
    onFinished: (Long) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val frame by viewModel.frame.collectAsStateWithLifecycle()
    val settings by viewModel.settingsRepository.settings.collectAsStateWithLifecycle()
    var cameraError by remember { mutableStateOf<String?>(null) }
    var showCeilingDialog by remember { mutableStateOf(false) }

    @Suppress("DEPRECATION")
    val renderer = remember(session) {
        ArRenderer(session, { activity.windowManager.defaultDisplay.rotation }, viewModel::onFrame)
    }
    val glView = remember(session) {
        GLSurfaceView(context).apply {
            preserveEGLContextOnPause = true
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
    }

    DisposableEffect(lifecycleOwner, session) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> try {
                    session.resume()
                    glView.onResume()
                } catch (ex: Exception) {
                    cameraError = "Camera unavailable: ${ex.message}"
                }
                Lifecycle.Event.ON_PAUSE -> {
                    glView.onPause()
                    session.pause()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(obs)
            glView.onPause()
            session.pause()
            session.close()
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { glView }, modifier = Modifier.fillMaxSize())
        Overlay(frame, ui, settings.unit, viewModel)

        // Top bar with instructions.
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Spacer(Modifier.width(8.dp))
                InfoCard(instruction(ui, frame, settings.unit, viewModel), Modifier.weight(1f))
            }
            val msg = cameraError ?: frame?.trackingMessage ?: ui.message
            if (msg != null) {
                Spacer(Modifier.height(8.dp))
                InfoCard(msg, Modifier.fillMaxWidth(), Amber.copy(alpha = 0.9f), Color.Black)
            }
        }

        // Bottom controls.
        Surface(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            color = Color.Black.copy(alpha = 0.55f),
        ) {
            Column(Modifier.navigationBarsPadding().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                when (ui.phase) {
                    ScanPhase.FLOOR -> Text(
                        "Looking for the floor…",
                        color = Color.White,
                        modifier = Modifier.padding(12.dp),
                    )
                    ScanPhase.CORNERS -> CornerControls(
                        cornerCount = frame?.corners?.size ?: 0,
                        canAdd = frame?.pointerState != PointerState.NONE,
                        onUndo = { glView.queueEvent { renderer.removeLastCorner() } },
                        onAdd = {
                            glView.queueEvent {
                                if (!renderer.addCorner()) viewModel.message("Aim at the floor where the walls meet")
                                else viewModel.message(null)
                            }
                        },
                        onClose = viewModel::closeRoom,
                    )
                    ScanPhase.CEILING -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = viewModel::backToCorners) { Text("Back", color = Color.White) }
                        FilledTonalButton(onClick = { showCeilingDialog = true }) { Text("Type height") }
                        Button(onClick = viewModel::captureCeiling) { Text("Capture") }
                    }
                    ScanPhase.OPENINGS -> OpeningControls(
                        ui = ui,
                        onType = viewModel::setOpeningType,
                        onMark = viewModel::markOpeningEdge,
                        onUndo = viewModel::undoOpening,
                        onFinish = { viewModel.finish(renderer.wallPointsSnapshot(), onFinished) },
                    )
                }
            }
        }

        if (ui.saving) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text("Processing geometry…", color = Color.White)
                }
            }
        }
    }

    if (showCeilingDialog) {
        LengthDialog(
            title = "Ceiling height",
            label = "Height",
            unit = settings.unit,
            initialMetres = 2.7,
            onDismiss = { showCeilingDialog = false },
            onConfirm = { showCeilingDialog = false; viewModel.setCeilingManually(it) },
        )
    }
}

private fun instruction(ui: ScanUiState, frame: ArFrameInfo?, unit: LengthUnit, vm: ScanViewModel): String = when (ui.phase) {
    ScanPhase.FLOOR -> "Point the camera at the floor and move the phone slowly from side to side."
    ScanPhase.CORNERS -> {
        val n = frame?.corners?.size ?: 0
        if (n == 0) "Aim the centre pointer at the floor in a corner of the room, then tap Add corner."
        else "Corner $n captured. Walk to the next corner (go around the room in order). When the pointer is green, tap Add corner."
    }
    ScanPhase.CEILING -> {
        val h = frame?.let { vm.ceilingPreview(it) }
        "Aim at the ceiling straight above any corner and tap Capture." +
            (h?.let { "\nHeight: ${UnitFormat.length(it, unit)}" } ?: "")
    }
    ScanPhase.OPENINGS ->
        "Optional: mark doors and windows. Aim at the floor below one edge, tap Mark edge, then the other edge. Tap Finish when done."
}

@Composable
private fun InfoCard(text: String, modifier: Modifier, bg: Color = Color.Black.copy(alpha = 0.6f), fg: Color = Color.White) {
    Surface(modifier = modifier, color = bg, shape = RoundedCornerShape(14.dp)) {
        Text(text, color = fg, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun CornerControls(cornerCount: Int, canAdd: Boolean, onUndo: () -> Unit, onAdd: () -> Unit, onClose: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        FilledTonalIconButton(onClick = onUndo, enabled = cornerCount > 0) {
            Icon(Icons.AutoMirrored.Filled.Undo, "Undo corner")
        }
        Button(
            onClick = onAdd,
            enabled = canAdd,
            colors = ButtonDefaults.buttonColors(containerColor = Green),
            modifier = Modifier.height(56.dp),
        ) {
            Icon(Icons.Filled.Add, null)
            Spacer(Modifier.width(6.dp))
            Text("Add corner", fontWeight = FontWeight.SemiBold)
        }
        FilledTonalButton(onClick = onClose, enabled = cornerCount >= 3) {
            Icon(Icons.Filled.Check, null)
            Spacer(Modifier.width(4.dp))
            Text("Close room")
        }
    }
}

@Composable
private fun OpeningControls(
    ui: ScanUiState,
    onType: (OpeningType) -> Unit,
    onMark: () -> Unit,
    onUndo: () -> Unit,
    onFinish: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OpeningType.entries.forEach { t ->
                FilterChip(
                    selected = ui.openingType == t,
                    onClick = { onType(t) },
                    label = { Text(t.label(), color = Color.White) },
                )
            }
            Spacer(Modifier.width(8.dp))
            Text("${ui.openings.size} added", color = Color.White)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconButton(onClick = onUndo, enabled = ui.openings.isNotEmpty() || ui.pendingEdge != null) {
                Icon(Icons.AutoMirrored.Filled.Undo, "Undo")
            }
            FilledTonalButton(onClick = onMark) { Text(if (ui.pendingEdge == null) "Mark edge 1" else "Mark edge 2") }
            Button(onClick = onFinish, colors = ButtonDefaults.buttonColors(containerColor = Green)) {
                Icon(Icons.Filled.Check, null)
                Spacer(Modifier.width(4.dp))
                Text("Finish")
            }
        }
    }
}

// ---------------------------------------------------------------- AR overlay

@Composable
private fun Overlay(frame: ArFrameInfo?, ui: ScanUiState, unit: LengthUnit, vm: ScanViewModel) {
    val labelPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = 40f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
            setShadowLayer(6f, 0f, 0f, android.graphics.Color.BLACK)
        }
    }
    Canvas(Modifier.fillMaxSize()) {
        val info = frame ?: return@Canvas
        val pts = info.corners.map { project(info.viewProj, it, size) }
        val closed = ui.phase == ScanPhase.CEILING || ui.phase == ScanPhase.OPENINGS
        val edgeCount = if (closed && pts.size >= 3) pts.size else pts.size - 1
        val wallColor = Color(0xFF4FC3F7)

        for (i in 0 until edgeCount) {
            val j = (i + 1) % pts.size
            val a = pts[i]; val b = pts[j]
            if (a != null && b != null) {
                drawLine(wallColor, a, b, strokeWidth = 8f, cap = StrokeCap.Round)
                label(labelPaint, (a + b) / 2f, UnitFormat.length(dist(info.corners[i], info.corners[j]), unit))
            }
        }

        // Openings at floor level.
        for (o in ui.openings) {
            if (o.wallIndex >= info.corners.size) continue
            val a = info.corners[o.wallIndex]
            val b = info.corners[(o.wallIndex + 1) % info.corners.size]
            val len = dist(a, b).toFloat().coerceAtLeast(1e-3f)
            fun at(t: Double): Vec3 {
                val f = (t / len).toFloat()
                return Vec3(a.x + (b.x - a.x) * f, a.y, a.z + (b.z - a.z) * f)
            }
            val p1 = project(info.viewProj, at(o.offset), size)
            val p2 = project(info.viewProj, at(o.offset + o.width), size)
            if (p1 != null && p2 != null) {
                drawLine(if (o.type == OpeningType.DOOR) Amber else Color(0xFF80DEEA), p1, p2, strokeWidth = 16f, cap = StrokeCap.Round)
            }
        }

        // Live line from the last corner to the pointer.
        val pointer = info.pointer
        if (ui.phase == ScanPhase.CORNERS && pointer != null && pts.isNotEmpty()) {
            val last = pts.last()
            val p = project(info.viewProj, pointer, size)
            if (last != null && p != null) {
                drawLine(
                    Color.White, last, p, strokeWidth = 5f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(20f, 14f)),
                )
                label(labelPaint, (last + p) / 2f, UnitFormat.length(dist(info.corners.last(), pointer), unit))
            }
        }

        // Pending first edge of an opening.
        ui.pendingEdge?.let { e ->
            val y = info.floorY ?: return@let
            project(info.viewProj, Vec3(e.x.toFloat(), y, e.y.toFloat()), size)?.let { drawCircle(Amber, 14f, it) }
        }

        // Corner dots.
        pts.forEachIndexed { i, p ->
            if (p != null) {
                drawCircle(Color.White, 16f, p)
                drawCircle(if (i == 0) Green else wallColor, 11f, p)
            }
        }

        // Centre pointer: green = on detected floor, amber = estimated, red = no floor.
        val c = Offset(size.width / 2, size.height / 2)
        val color = when {
            ui.phase == ScanPhase.CEILING -> if (vm.ceilingPreview(info) != null) Green else Red
            info.pointerState == PointerState.ON_FLOOR -> Green
            info.pointerState == PointerState.ESTIMATED -> Amber
            else -> Red
        }
        drawCircle(Color.Black.copy(alpha = 0.4f), 30f, c, style = Stroke(10f))
        drawCircle(color, 30f, c, style = Stroke(6f))
        drawCircle(color, 6f, c)
        drawLine(color, c + Offset(-48f, 0f), c + Offset(-34f, 0f), 5f)
        drawLine(color, c + Offset(34f, 0f), c + Offset(48f, 0f), 5f)
        drawLine(color, c + Offset(0f, -48f), c + Offset(0f, -34f), 5f)
        drawLine(color, c + Offset(0f, 34f), c + Offset(0f, 48f), 5f)
    }
}

private fun DrawScope.label(paint: Paint, at: Offset, text: String) {
    drawIntoCanvas { it.nativeCanvas.drawText(text, at.x, at.y - 14f, paint) }
}

private fun dist(a: Vec3, b: Vec3): Double {
    val dx = (a.x - b.x).toDouble(); val dz = (a.z - b.z).toDouble()
    return sqrt(dx * dx + dz * dz)
}

/** World point → screen pixel using projection × view, or null if behind the camera. */
private fun project(vp: FloatArray, p: Vec3, size: Size): Offset? {
    val x = vp[0] * p.x + vp[4] * p.y + vp[8] * p.z + vp[12]
    val y = vp[1] * p.x + vp[5] * p.y + vp[9] * p.z + vp[13]
    val w = vp[3] * p.x + vp[7] * p.y + vp[11] * p.z + vp[15]
    if (w <= 0.01f) return null
    val nx = x / w; val ny = y / w
    if (nx < -4 || nx > 4 || ny < -4 || ny > 4) return null
    return Offset((nx + 1f) / 2f * size.width, (1f - ny) / 2f * size.height)
}

private fun Context.findActivity(): Activity {
    var c: Context = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    error("No activity")
}
