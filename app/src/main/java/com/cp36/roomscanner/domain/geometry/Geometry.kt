package com.cp36.roomscanner.domain.geometry

import com.cp36.roomscanner.domain.model.OpeningType
import com.cp36.roomscanner.domain.model.RoomPlan
import com.cp36.roomscanner.domain.model.RoomStats
import com.cp36.roomscanner.domain.model.Vec2
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** An infinite 2D line through [point] with unit direction [dir]. */
data class Line2(val point: Vec2, val dir: Vec2, val rms: Double = 0.0) {
    fun distanceTo(p: Vec2): Double = abs(dir cross (p - point))
}

/** Result of projecting a point onto the nearest wall of a polygon. */
data class WallProjection(val wallIndex: Int, val offset: Double, val distance: Double)

/**
 * The geometry engine. Every function is pure Kotlin (no Android types), so it is unit-tested
 * in app/src/test.
 */
object Geometry {

    // ---------------------------------------------------------------- Shoelace formula

    /** Signed area: positive when the corners run counter-clockwise in x-right / y-up axes. */
    fun signedArea(pts: List<Vec2>): Double {
        if (pts.size < 3) return 0.0
        var s = 0.0
        for (i in pts.indices) {
            val a = pts[i]
            val b = pts[(i + 1) % pts.size]
            s += a.x * b.y - b.x * a.y
        }
        return s / 2.0
    }

    /** Floor area with the Shoelace formula. Exact for any simple polygon. */
    fun area(pts: List<Vec2>): Double = abs(signedArea(pts))

    fun perimeter(pts: List<Vec2>): Double {
        if (pts.size < 2) return 0.0
        var p = 0.0
        for (i in pts.indices) p += (pts[(i + 1) % pts.size] - pts[i]).length()
        return p
    }

    /** Interior angle at each corner, in degrees. */
    fun interiorAngles(pts: List<Vec2>): List<Double> {
        val n = pts.size
        if (n < 3) return emptyList()
        val orient = if (signedArea(pts) >= 0) 1.0 else -1.0
        return List(n) { i ->
            val prev = pts[(i - 1 + n) % n]
            val cur = pts[i]
            val next = pts[(i + 1) % n]
            val e1 = cur - prev
            val e2 = next - cur
            val turn = Math.toDegrees(atan2(e1 cross e2, e1 dot e2))
            180.0 - turn * orient
        }
    }

    fun stats(plan: RoomPlan): RoomStats {
        val pts = plan.corners
        val a = area(pts)
        val p = perimeter(pts)
        val openingArea = plan.openings.sumOf { it.width * it.height }
        return RoomStats(
            area = a,
            perimeter = p,
            volume = a * plan.ceilingHeight,
            wallLengths = pts.indices.map { plan.wallLength(it) },
            interiorAngles = interiorAngles(pts),
            wallArea = max(0.0, p * plan.ceilingHeight - openingArea),
        )
    }

    // ---------------------------------------------------------------- PCA line fitting

    /**
     * Fits a line through points with Principal Component Analysis: the line passes through
     * the mean, along the eigenvector of the covariance matrix with the largest eigenvalue.
     */
    fun fitLinePca(points: List<Vec2>): Line2? {
        if (points.size < 2) return null
        val n = points.size.toDouble()
        val mx = points.sumOf { it.x } / n
        val my = points.sumOf { it.y } / n
        var sxx = 0.0; var sxy = 0.0; var syy = 0.0
        for (p in points) {
            val dx = p.x - mx; val dy = p.y - my
            sxx += dx * dx; sxy += dx * dy; syy += dy * dy
        }
        sxx /= n; sxy /= n; syy /= n
        // Principal axis angle of a 2x2 symmetric matrix.
        val theta = 0.5 * atan2(2 * sxy, sxx - syy)
        val dir = Vec2(cos(theta), sin(theta))
        val mean = Vec2(mx, my)
        // Smallest eigenvalue = mean squared perpendicular distance.
        val tr = sxx + syy
        val det = sxx * syy - sxy * sxy
        val lambdaMin = tr / 2 - sqrt(max(0.0, tr * tr / 4 - det))
        return Line2(mean, dir, sqrt(max(0.0, lambdaMin)))
    }

    /** PCA fit, then drop outliers (e.g. furniture) beyond 2·RMS and fit again. */
    fun fitLineRobust(points: List<Vec2>, minPoints: Int = 12): Line2? {
        if (points.size < minPoints) return null
        val first = fitLinePca(points) ?: return null
        val cut = max(2.0 * first.rms, 0.01)
        val inliers = points.filter { first.distanceTo(it) <= cut }
        if (inliers.size < minPoints) return first
        return fitLinePca(inliers)
    }

    /** Intersection of two lines, or null if they are close to parallel (< 15°). */
    fun intersect(a: Line2, b: Line2): Vec2? {
        val denom = a.dir cross b.dir
        if (abs(denom) < sin(Math.toRadians(15.0))) return null
        val t = ((b.point - a.point) cross b.dir) / denom
        return a.point + a.dir * t
    }

    /**
     * Refines corners with wall points collected during the scan (ARCore feature points,
     * projected to the floor). For each wall, nearby points are fitted with PCA; adjacent
     * fitted walls are intersected to give the refined corner. Walls without enough
     * points keep the line through the user's corners.
     */
    fun refineWithWallPoints(
        corners: List<Vec2>,
        wallPoints: List<Vec2>,
        band: Double = 0.08,
        maxCornerShift: Double = 0.15,
    ): List<Vec2> {
        val n = corners.size
        if (n < 3 || wallPoints.isEmpty()) return corners
        val lines = List(n) { i ->
            val a = corners[i]
            val b = corners[(i + 1) % n]
            val len = (b - a).length()
            val u = (b - a).normalized()
            val original = Line2(a, u)
            if (len < 0.3) return@List original
            // Ignore the 15% next to each corner so points of the neighbouring wall don't leak in.
            val near = wallPoints.filter { p ->
                val t = (p - a) dot u
                t > 0.15 * len && t < 0.85 * len && original.distanceTo(p) < band
            }
            val fit = fitLineRobust(near) ?: return@List original
            val angleDiff = Math.toDegrees(abs(asinClamped(fit.dir cross u)))
            if (angleDiff > 10.0 || original.distanceTo(fit.point) > band) original
            else Line2(fit.point, if ((fit.dir dot u) < 0) fit.dir * -1.0 else fit.dir, fit.rms)
        }
        return List(n) { i ->
            val prev = lines[(i - 1 + n) % n]
            val cur = lines[i]
            val p = intersect(prev, cur)
            if (p == null || (p - corners[i]).length() > maxCornerShift) corners[i] else p
        }
    }

    private fun asinClamped(v: Double) = kotlin.math.asin(v.coerceIn(-1.0, 1.0))

    // ---------------------------------------------------------------- Orthogonal snapping

    /** Dominant wall orientation (radians, in [-45°, 45°)), weighted by wall length. */
    fun dominantOrientation(pts: List<Vec2>): Double {
        var s = 0.0; var c = 0.0
        for (i in pts.indices) {
            val d = pts[(i + 1) % pts.size] - pts[i]
            val len = d.length()
            val theta = atan2(d.y, d.x)
            s += len * sin(4 * theta)
            c += len * cos(4 * theta)
        }
        return atan2(s, c) / 4.0
    }

    /**
     * Least-squares orthogonal snapping.
     *
     * 1. Find the room's dominant direction and rotate the plan so it lines up with the axes.
     * 2. Each wall within [toleranceDeg] of horizontal gets the constraint y_i = y_(i+1);
     *    within tolerance of vertical, x_i = x_(i+1). Other walls (real diagonals) are left free.
     * 3. Corners linked by these constraints form groups. Setting each group's coordinate to the
     *    group mean is the exact least-squares solution: it satisfies every constraint while
     *    moving the corners as little as possible (minimum sum of squared movement).
     * 4. Rotate back.
     */
    fun squareCorners(pts: List<Vec2>, toleranceDeg: Double = 10.0): List<Vec2> {
        val n = pts.size
        if (n < 3) return pts
        val phi = dominantOrientation(pts)
        val c = cos(-phi); val s = sin(-phi)
        val rot = pts.map { Vec2(it.x * c - it.y * s, it.x * s + it.y * c) }

        val ux = UnionFind(n)
        val uy = UnionFind(n)
        val tol = Math.toRadians(toleranceDeg)
        for (i in 0 until n) {
            val j = (i + 1) % n
            val d = rot[j] - rot[i]
            if (d.length() < 1e-6) continue
            val a = atan2(d.y, d.x)
            val toHorizontal = abs(wrapPi(a - PI * kotlin.math.round(a / PI)))
            val toVertical = abs(wrapPi(a - PI / 2 - PI * kotlin.math.round((a - PI / 2) / PI)))
            when {
                toHorizontal <= tol -> uy.union(i, j)
                toVertical <= tol -> ux.union(i, j)
            }
        }
        val xs = groupMeans(rot.map { it.x }, ux)
        val ys = groupMeans(rot.map { it.y }, uy)
        val c2 = cos(phi); val s2 = sin(phi)
        return List(n) { i -> Vec2(xs[i] * c2 - ys[i] * s2, xs[i] * s2 + ys[i] * c2) }
    }

    private fun wrapPi(a: Double): Double {
        var x = a
        while (x > PI) x -= 2 * PI
        while (x < -PI) x += 2 * PI
        return x
    }

    private fun groupMeans(values: List<Double>, uf: UnionFind): List<Double> {
        val sum = HashMap<Int, Double>()
        val count = HashMap<Int, Int>()
        values.forEachIndexed { i, v ->
            val r = uf.find(i)
            sum[r] = (sum[r] ?: 0.0) + v
            count[r] = (count[r] ?: 0) + 1
        }
        return values.indices.map { i -> val r = uf.find(i); sum[r]!! / count[r]!! }
    }

    private class UnionFind(n: Int) {
        private val parent = IntArray(n) { it }
        fun find(x: Int): Int {
            var r = x
            while (parent[r] != r) r = parent[r]
            var y = x
            while (parent[y] != r) { val next = parent[y]; parent[y] = r; y = next }
            return r
        }
        fun union(a: Int, b: Int) { parent[find(a)] = find(b) }
    }

    // ---------------------------------------------------------------- Helpers

    /** Removes corners closer than [minDist] to the previous one. */
    fun removeDuplicates(pts: List<Vec2>, minDist: Double = 0.02): List<Vec2> {
        val out = ArrayList<Vec2>()
        for (p in pts) if (out.isEmpty() || (p - out.last()).length() >= minDist) out.add(p)
        while (out.size > 1 && (out.first() - out.last()).length() < minDist) out.removeAt(out.size - 1)
        return out
    }

    fun projectOntoWalls(p: Vec2, corners: List<Vec2>): WallProjection? {
        val n = corners.size
        if (n < 2) return null
        var best: WallProjection? = null
        for (i in 0 until n) {
            val a = corners[i]
            val b = corners[(i + 1) % n]
            val len = (b - a).length()
            if (len < 1e-6) continue
            val u = (b - a) / len
            val t = ((p - a) dot u).coerceIn(0.0, len)
            val d = (a + u * t - p).length()
            if (best == null || d < best.distance) best = WallProjection(i, t, d)
        }
        return best
    }

    fun pointInPolygon(p: Vec2, pts: List<Vec2>): Boolean {
        var inside = false
        var j = pts.size - 1
        for (i in pts.indices) {
            val a = pts[i]; val b = pts[j]
            if ((a.y > p.y) != (b.y > p.y) &&
                p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x
            ) inside = !inside
            j = i
        }
        return inside
    }

    /** Unit normal of wall i that points into the room. */
    fun inwardNormal(plan: RoomPlan, i: Int): Vec2 {
        val a = plan.wallStart(i); val b = plan.wallEnd(i)
        val u = (b - a).normalized()
        val nrm = u.perp()
        val mid = (a + b) / 2.0
        return if (pointInPolygon(mid + nrm * 0.05, plan.corners)) nrm else nrm * -1.0
    }

    fun centroid(pts: List<Vec2>): Vec2 =
        if (pts.isEmpty()) Vec2.ZERO else Vec2(pts.sumOf { it.x } / pts.size, pts.sumOf { it.y } / pts.size)

    fun countOf(plan: RoomPlan, type: OpeningType) = plan.openings.count { it.type == type }
}
