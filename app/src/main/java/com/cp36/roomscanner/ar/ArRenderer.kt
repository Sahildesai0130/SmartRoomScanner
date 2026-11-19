package com.cp36.roomscanner.ar

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import com.cp36.roomscanner.domain.model.Vec2
import com.google.ar.core.Anchor
import com.google.ar.core.DepthPoint
import com.google.ar.core.HitResult
import com.google.ar.core.InstantPlacementPoint
import com.google.ar.core.Point
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.roundToInt

data class Vec3(val x: Float, val y: Float, val z: Float) {
    fun xz() = Vec2(x.toDouble(), z.toDouble())
}

enum class PointerState {
    /** Nothing under the pointer yet. */
    NONE,
    /** Estimated point (Instant Placement or floor height) – usable, ARCore refines it. */
    ESTIMATED,
    /** Pointer hits the detected floor plane. */
    ON_FLOOR,
    /** Pointer hits a real surface: wall, wall edge, depth point or feature point. */
    ON_WALL,
}

enum class AddResult { OK, NO_SURFACE, UNSTEADY, DUPLICATE }

/** Everything the UI needs from one camera frame. */
class ArFrameInfo(
    val tracking: Boolean,
    val trackingMessage: String?,
    val floorY: Float?,
    val pointerState: PointerState,
    val pointer: Vec3?,
    val corners: List<Vec3>,
    /** projection × view matrix, used to draw world points on top of the camera image. */
    val viewProj: FloatArray,
    val rayOrigin: Vec3,
    val rayDir: Vec3,
    val wallPointCount: Int,
    val measuring: Boolean = false,
)

/**
 * The AR engine. Runs on the GLSurfaceView's render thread:
 *  - visual-inertial SLAM (ARCore) tracks the phone in metres,
 *  - plane detection finds the floor,
 *  - ray casting (hit testing) from the screen centre gives the 3D point under the pointer,
 *  - anchors keep captured corners fixed to the real world as tracking improves,
 *  - feature points near walls are collected for PCA wall fitting.
 *
 * Methods marked "GL thread" must be called via GLSurfaceView.queueEvent.
 */
class ArRenderer(
    private val session: Session,
    private val displayRotation: () -> Int,
    private val onFrame: (ArFrameInfo) -> Unit,
) : GLSurfaceView.Renderer {

    private val background = BackgroundRenderer()
    private val anchors = ArrayList<Anchor>()
    private var floorY: Float? = null
    private var pointerPose: Pose? = null
    private var viewportChanged = false
    private var width = 1
    private var height = 1
    private var frameCount = 0
    private var lastState = PointerState.NONE

    /** Set by the scan screen when the phone supports the ARCore Depth API. */
    @Volatile var depthEnabled = false

    private val view = FloatArray(16)
    private val proj = FloatArray(16)
    private val wallPointLock = Any()
    private val wallPointKeys = HashSet<Long>()
    private val wallPoints = ArrayList<Vec2>()

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        try {
            background.createOnGlThread()
            session.setCameraTextureName(background.textureId)
        } catch (e: Exception) {
            reportError(e)
        }
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        GLES20.glViewport(0, 0, w, h)
        width = w
        height = h
        viewportChanged = true
    }

    /** Any error on the render thread would close the app, so it is caught and shown on screen instead. */
    override fun onDrawFrame(gl: GL10?) {
        try {
            drawFrame()
        } catch (e: Exception) {
            reportError(e)
        }
    }

    private fun reportError(e: Exception) {
        android.util.Log.e("ArRenderer", "AR error", e)
        try {
            onFrame(
                ArFrameInfo(
                    tracking = false,
                    trackingMessage = "AR error: ${e.javaClass.simpleName}: ${e.message}",
                    floorY = floorY,
                    pointerState = PointerState.NONE,
                    pointer = null,
                    corners = emptyList(),
                    viewProj = FloatArray(16),
                    rayOrigin = Vec3(0f, 0f, 0f),
                    rayDir = Vec3(0f, 0f, -1f),
                    wallPointCount = 0,
                )
            )
        } catch (_: Exception) {
        }
    }

    private fun drawFrame() {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        if (viewportChanged) {
            session.setDisplayGeometry(displayRotation(), width, height)
            viewportChanged = false
        }
        val frame = try { session.update() } catch (e: Exception) { return }
        background.draw(frame)

        val camera = frame.camera
        camera.getViewMatrix(view, 0)
        camera.getProjectionMatrix(proj, 0, 0.05f, 100f)
        val viewProj = FloatArray(16)
        Matrix.multiplyMM(viewProj, 0, proj, 0, view, 0)
        val (origin, dir) = centreRay(viewProj)

        val tracking = camera.trackingState == TrackingState.TRACKING
        if (tracking) updateFloor()

        var state = PointerState.NONE
        pointerPose = null
        // A "measured" point comes from real geometry; only these are used for corners.
        var measured: Pose? = null
        if (tracking) {
            // Ray cast from the screen centre. Accuracy order: floor plane > wall plane >
            // depth point > feature point. Depth/feature points are only trusted up to 4 m.
            var best: HitResult? = null
            var bestRank = Int.MAX_VALUE
            var nearest = Float.MAX_VALUE
            for (hit in frame.hitTest(width / 2f, height / 2f)) {
                val t = hit.trackable
                val rank = when {
                    t is Plane && t.type == Plane.Type.HORIZONTAL_UPWARD_FACING && t.isPoseInPolygon(hit.hitPose) -> 0
                    t is Plane && t.type == Plane.Type.VERTICAL && t.isPoseInPolygon(hit.hitPose) -> 1
                    t is DepthPoint && hit.distance < 4f -> 2
                    t is Point && hit.distance < 4f -> 3
                    else -> continue
                }
                nearest = minOf(nearest, hit.distance)
                // Don't let a plane "behind" the aimed surface win (planes can extend past walls).
                if (rank < bestRank && hit.distance <= nearest + 0.3f) { best = hit; bestRank = rank }
            }
            if (best != null) {
                measured = best.hitPose
                pointerPose = best.hitPose
                state = if (bestRank == 0) PointerState.ON_FLOOR else PointerState.ON_WALL
            }
            // Ray against the detected floor height, beyond the detected floor area (good up to ~4 m).
            val fy = floorY
            if (pointerPose == null && fy != null && dir.y < -0.15f) {
                val t = (fy - origin.y) / dir.y
                if (t in 0f..4f) {
                    pointerPose = Pose.makeTranslation(origin.x + dir.x * t, fy, origin.z + dir.z * t)
                    measured = pointerPose
                    state = PointerState.ESTIMATED
                }
            }
            // Instant Placement: shows the pointer immediately, but is never used for a corner
            // (its distance is a guess until ARCore has measured the surface).
            if (pointerPose == null) {
                val hit = try {
                    frame.hitTestInstantPlacement(width / 2f, height / 2f, 2.0f).firstOrNull()
                } catch (e: Exception) { null }
                if (hit != null) {
                    pointerPose = hit.hitPose
                    state = PointerState.ESTIMATED
                }
            }
            if (fy != null) {
                if (frameCount % 6 == 0) collectWallPoints(frame, fy)
                if (depthEnabled && frameCount % 15 == 3) collectDepthWallPoints(frame, fy)
            }
        }

        // "Add corner": collect 12 measured readings (~0.4 s) and use the median, which removes
        // most of the jitter of a single reading. Gives up after ~2 s without enough readings.
        if (addRequested) {
            addRequested = false
            samples.clear()
            sampleHeights.clear()
            samplingFrames = 0
            sampling = true
        }
        if (sampling) {
            samplingFrames++
            val m = measured
            if (tracking && m != null) {
                samples.add(Vec2(m.tx().toDouble(), m.tz().toDouble()))
                sampleHeights.add(m.ty())
            }
            if (samples.size >= 12 || samplingFrames > 60) {
                sampling = false
                onAddResult(finishCorner(m ?: pointerPose))
            }
        }
        frameCount++

        // The screen only needs ~30 updates a second; halves the work on the main thread.
        if (frameCount % 2 == 1 && state == lastState && !sampling) return
        lastState = state

        // Corners are drawn exactly where they were captured (on the pointer). The floor plan
        // only uses their x and z, so the height they were captured at doesn't matter.
        val corners = anchors.mapNotNull { a ->
            try { val p = a.pose; Vec3(p.tx(), p.ty(), p.tz()) } catch (e: Exception) { null }
        }
        onFrame(
            ArFrameInfo(
                tracking = tracking,
                trackingMessage = if (tracking) null else trackingMessage(camera.trackingFailureReason),
                floorY = floorY,
                pointerState = state,
                pointer = pointerPose?.let { Vec3(it.tx(), it.ty(), it.tz()) },
                corners = corners,
                viewProj = viewProj,
                rayOrigin = origin,
                rayDir = dir,
                wallPointCount = synchronized(wallPointLock) { wallPoints.size },
                measuring = sampling,
            )
        )
    }

    private val samples = ArrayList<Vec2>()
    private val sampleHeights = ArrayList<Float>()
    private var samplingFrames = 0
    @Volatile private var sampling = false

    private fun finishCorner(lastPose: Pose?): AddResult {
        if (samples.size < 5 || lastPose == null) return AddResult.NO_SURFACE
        val xs = samples.map { it.x }.sorted()
        val zs = samples.map { it.y }.sorted()
        val mx = xs[xs.size / 2]
        val mz = zs[zs.size / 2]
        // Median distance of the readings from the median point: how steady the aim was.
        val spread = samples.map { Math.hypot(it.x - mx, it.y - mz) }.sorted()[samples.size / 2]
        if (spread > 0.04) return AddResult.UNSTEADY
        anchors.lastOrNull()?.let { last ->
            val p = last.pose
            if (Math.hypot(p.tx() - mx, p.tz() - mz) < 0.05) return AddResult.DUPLICATE
        }
        val anchor = try {
            val my = sampleHeights.sorted()[sampleHeights.size / 2]
            session.createAnchor(Pose.makeTranslation(mx.toFloat(), my, mz.toFloat()))
        } catch (e: Exception) { return AddResult.NO_SURFACE }
        anchors.add(anchor)
        return AddResult.OK
    }

    /** The floor is the lowest large upward-facing plane. */
    private fun updateFloor() {
        for (plane in session.getAllTrackables(Plane::class.java)) {
            if (plane.trackingState != TrackingState.TRACKING || plane.subsumedBy != null) continue
            if (plane.type != Plane.Type.HORIZONTAL_UPWARD_FACING) continue
            if (plane.extentX * plane.extentZ < 0.25f) continue
            val y = plane.centerPose.ty()
            val current = floorY
            if (current == null || y < current - 0.05f) floorY = y
        }
    }

    /** Ray through the screen centre, from the inverse of projection × view. */
    private fun centreRay(viewProj: FloatArray): Pair<Vec3, Vec3> {
        val inv = FloatArray(16)
        Matrix.invertM(inv, 0, viewProj, 0)
        val near = FloatArray(4)
        val far = FloatArray(4)
        Matrix.multiplyMV(near, 0, inv, 0, floatArrayOf(0f, 0f, -1f, 1f), 0)
        Matrix.multiplyMV(far, 0, inv, 0, floatArrayOf(0f, 0f, 1f, 1f), 0)
        val o = Vec3(near[0] / near[3], near[1] / near[3], near[2] / near[3])
        val f = Vec3(far[0] / far[3], far[1] / far[3], far[2] / far[3])
        var d = Vec3(f.x - o.x, f.y - o.y, f.z - o.z)
        val len = kotlin.math.sqrt(d.x * d.x + d.y * d.y + d.z * d.z)
        if (len > 0f) d = Vec3(d.x / len, d.y / len, d.z / len)
        return o to d
    }

    /**
     * Plain walls give ARCore few feature points. With the Depth API, a 6 × 8 grid of hit tests
     * returns depth points on any surface; those on walls are added to the PCA wall points.
     */
    private fun collectDepthWallPoints(frame: com.google.ar.core.Frame, fy: Float) {
        val found = ArrayList<Vec2>()
        for (gx in 1..6) for (gy in 1..8) {
            val hits = try { frame.hitTest(width * gx / 7f, height * gy / 9f) } catch (e: Exception) { continue }
            val hit = hits.firstOrNull { it.trackable is DepthPoint } ?: continue
            val p = hit.hitPose
            if (p.ty() > fy + 0.3f && p.ty() < fy + 2.4f && hit.distance < 6f) {
                found.add(Vec2(p.tx().toDouble(), p.tz().toDouble()))
            }
        }
        synchronized(wallPointLock) {
            for (v in found) {
                if (wallPoints.size >= 40_000) break
                val key = ((v.x / 0.03).roundToInt().toLong() shl 32) or ((v.y / 0.03).roundToInt().toLong() and 0xffffffffL)
                if (wallPointKeys.add(key)) wallPoints.add(v)
            }
        }
    }

    /** Keeps confident feature points between knee and ceiling height (i.e. on walls), on a 3 cm grid. */
    private fun collectWallPoints(frame: com.google.ar.core.Frame, fy: Float) {
        val cloud = try { frame.acquirePointCloud() } catch (e: Exception) { return }
        try {
            val buf = cloud.points
            val count = buf.remaining() / 4
            synchronized(wallPointLock) {
                for (i in 0 until count) {
                    val x = buf.get(i * 4)
                    val y = buf.get(i * 4 + 1)
                    val z = buf.get(i * 4 + 2)
                    val conf = buf.get(i * 4 + 3)
                    if (conf < 0.3f || y < fy + 0.3f || y > fy + 2.4f) continue
                    if (wallPoints.size >= 40_000) break
                    val key = ((x / 0.03f).roundToInt().toLong() shl 32) or
                        ((z / 0.03f).roundToInt().toLong() and 0xffffffffL)
                    if (wallPointKeys.add(key)) wallPoints.add(Vec2(x.toDouble(), z.toDouble()))
                }
            }
        } finally {
            cloud.close()
        }
    }

    private fun trackingMessage(reason: TrackingFailureReason) = when (reason) {
        TrackingFailureReason.INSUFFICIENT_LIGHT -> "Too dark – turn on the lights"
        TrackingFailureReason.EXCESSIVE_MOTION -> "Moving too fast – slow down"
        TrackingFailureReason.INSUFFICIENT_FEATURES -> "Point at a textured area (floor edges, furniture)"
        TrackingFailureReason.CAMERA_UNAVAILABLE -> "Camera unavailable"
        else -> "Move the phone slowly to start tracking"
    }

    // ---------------------------------------------------------------- Actions (GL thread)

    @Volatile private var addRequested = false

    /** Called after "Add corner" has been processed (on the GL thread). */
    var onAddResult: (AddResult) -> Unit = {}

    /** Adds a corner at the pointer on the next frame. */
    fun requestAddCorner() { addRequested = true }

    fun removeLastCorner() {
        if (anchors.isNotEmpty()) anchors.removeAt(anchors.size - 1).detach()
    }

    fun clearCorners() {
        anchors.forEach { it.detach() }
        anchors.clear()
    }

    /** Wall points collected so far (thread-safe). */
    fun wallPointsSnapshot(): List<Vec2> = synchronized(wallPointLock) { ArrayList(wallPoints) }
}
