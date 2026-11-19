package com.cp36.roomscanner.ar

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import com.cp36.roomscanner.domain.model.Vec2
import com.google.ar.core.Anchor
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
    /** No floor found under the pointer. */
    NONE,
    /** Pointer is on the floor's height but outside the detected floor area (less accurate). */
    ESTIMATED,
    /** Pointer hits the detected floor plane (most accurate). */
    ON_FLOOR,
}

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

    private val view = FloatArray(16)
    private val proj = FloatArray(16)
    private val wallPointLock = Any()
    private val wallPointKeys = HashSet<Long>()
    private val wallPoints = ArrayList<Vec2>()

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        background.createOnGlThread()
        session.setCameraTextureName(background.textureId)
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        GLES20.glViewport(0, 0, w, h)
        width = w
        height = h
        viewportChanged = true
    }

    override fun onDrawFrame(gl: GL10?) {
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
        val fy = floorY
        if (tracking && fy != null) {
            // 1. Ray cast against detected planes (ARCore hit test).
            for (hit in frame.hitTest(width / 2f, height / 2f)) {
                val plane = hit.trackable as? Plane ?: continue
                if (plane.type == Plane.Type.HORIZONTAL_UPWARD_FACING &&
                    plane.isPoseInPolygon(hit.hitPose) &&
                    abs(hit.hitPose.ty() - fy) < 0.10f
                ) {
                    pointerPose = Pose.makeTranslation(hit.hitPose.tx(), fy, hit.hitPose.tz())
                    state = PointerState.ON_FLOOR
                    break
                }
            }
            // 2. Fallback: intersect the ray with the infinite floor plane (y = floorY).
            if (pointerPose == null && dir.y < -0.05f) {
                val t = (fy - origin.y) / dir.y
                if (t in 0f..12f) {
                    pointerPose = Pose.makeTranslation(origin.x + dir.x * t, fy, origin.z + dir.z * t)
                    state = PointerState.ESTIMATED
                }
            }
            if (frameCount % 6 == 0) collectWallPoints(frame, fy)
        }
        frameCount++

        val corners = anchors.map { val p = it.pose; Vec3(p.tx(), p.ty(), p.tz()) }
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
            )
        )
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

    /** Adds a corner at the pointer. Returns false if there is no valid point or it duplicates the last corner. */
    fun addCorner(): Boolean {
        val pose = pointerPose ?: return false
        anchors.lastOrNull()?.let { last ->
            val p = last.pose
            val dx = p.tx() - pose.tx(); val dz = p.tz() - pose.tz()
            if (dx * dx + dz * dz < 0.05f * 0.05f) return false
        }
        anchors.add(session.createAnchor(pose))
        return true
    }

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
