package com.cp36.roomscanner.ui.plan

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import com.cp36.roomscanner.domain.geometry.Geometry
import com.cp36.roomscanner.domain.model.LengthUnit
import com.cp36.roomscanner.domain.model.OpeningType
import com.cp36.roomscanner.domain.model.RoomPlan
import com.cp36.roomscanner.domain.model.UnitFormat
import com.cp36.roomscanner.domain.model.Vec2
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min

/** Maps plan metres to screen pixels: screen = offset + world × scale. */
data class PlanTransform(val scale: Float, val offsetX: Float, val offsetY: Float) {
    fun toScreen(v: Vec2) = Vec2(offsetX + v.x * scale, offsetY + v.y * scale)
    fun toWorld(x: Float, y: Float) = Vec2(((x - offsetX) / scale).toDouble(), ((y - offsetY) / scale).toDouble())

    companion object {
        /** Fits the plan into a w × h area with [padding] pixels on each side. */
        fun fit(plan: RoomPlan, w: Float, h: Float, padding: Float): PlanTransform {
            if (plan.corners.isEmpty()) return PlanTransform(100f, w / 2, h / 2)
            val minX = plan.corners.minOf { it.x }; val maxX = plan.corners.maxOf { it.x }
            val minY = plan.corners.minOf { it.y }; val maxY = plan.corners.maxOf { it.y }
            val bw = max(maxX - minX, 0.5); val bh = max(maxY - minY, 0.5)
            val s = min((w - 2 * padding) / bw, (h - 2 * padding) / bh).toFloat().coerceAtLeast(1f)
            val ox = w / 2 - ((minX + maxX) / 2 * s).toFloat()
            val oy = h / 2 - ((minY + maxY) / 2 * s).toFloat()
            return PlanTransform(s, ox, oy)
        }
    }
}

sealed interface Selection {
    data class Corner(val index: Int) : Selection
    data class Wall(val index: Int) : Selection
    data class OpeningSel(val id: Long) : Selection
}

data class PlanStyle(
    val wall: Int = Color.rgb(30, 41, 59),
    val floor: Int = Color.argb(40, 11, 92, 173),
    val text: Int = Color.rgb(30, 41, 59),
    val accent: Int = Color.rgb(11, 92, 173),
    val door: Int = Color.rgb(194, 120, 3),
    val window: Int = Color.rgb(0, 131, 143),
    val density: Float = 1f,
    val showCorners: Boolean = true,
    val showArea: Boolean = true,
)

/** Draws a floor plan on an Android Canvas (used by the editor, PNG and PDF export). */
object PlanDrawer {

    fun draw(
        canvas: Canvas,
        plan: RoomPlan,
        t: PlanTransform,
        unit: LengthUnit,
        style: PlanStyle,
        selection: Selection? = null,
    ) {
        val n = plan.corners.size
        if (n < 2) return
        val d = style.density
        val pts = plan.corners.map { t.toScreen(it) }

        // Floor.
        val floorPath = Path().apply {
            moveTo(pts[0].x.toFloat(), pts[0].y.toFloat())
            for (i in 1 until n) lineTo(pts[i].x.toFloat(), pts[i].y.toFloat())
            close()
        }
        canvas.drawPath(floorPath, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = style.floor; this.style = Paint.Style.FILL })

        val wallPaint = stroke(style.wall, 6f * d)
        val selPaint = stroke(style.accent, 10f * d)
        val doorPaint = stroke(style.door, 2.5f * d)
        val windowPaint = stroke(style.window, 2.5f * d)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = style.text; textSize = 12f * d; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
        }

        for (i in 0 until n) {
            val a = plan.wallStart(i); val b = plan.wallEnd(i)
            val len = (b - a).length()
            if (len < 1e-6) continue
            val u = (b - a) / len
            val inward = Geometry.inwardNormal(plan, i)

            // Wall segments with gaps for the openings.
            val ops = plan.openings.filter { it.wallIndex == i }.sortedBy { it.offset }
            var cursor = 0.0
            val paint = if (selection == Selection.Wall(i)) selPaint else wallPaint
            for (o in ops) {
                if (o.offset > cursor) line(canvas, t, a + u * cursor, a + u * o.offset, paint)
                cursor = max(cursor, o.offset + o.width)
            }
            if (cursor < len) line(canvas, t, a + u * cursor, b, paint)

            for (o in ops) {
                val p1 = a + u * o.offset
                val p2 = a + u * (o.offset + o.width)
                val selected = selection == Selection.OpeningSel(o.id)
                if (o.type == OpeningType.DOOR) {
                    val dp = if (selected) stroke(style.accent, 4f * d) else doorPaint
                    // Door leaf from the hinge (p1) into the room, plus the swing arc.
                    val leafEnd = p1 + inward * o.width
                    line(canvas, t, p1, leafEnd, dp)
                    val hs = t.toScreen(p1)
                    val r = (o.width * t.scale).toFloat()
                    val su = Vec2(u.x, u.y); val sn = inward
                    val a0 = Math.toDegrees(atan2(su.y, su.x)).toFloat()
                    val a1 = Math.toDegrees(atan2(sn.y, sn.x)).toFloat()
                    var sweep = a1 - a0
                    while (sweep > 180) sweep -= 360
                    while (sweep < -180) sweep += 360
                    val oval = RectF(hs.x.toFloat() - r, hs.y.toFloat() - r, hs.x.toFloat() + r, hs.y.toFloat() + r)
                    canvas.drawArc(oval, a0, sweep, false, Paint(dp).apply { pathEffect = DashPathEffect(floatArrayOf(6f * d, 4f * d), 0f) })
                } else {
                    val wp = if (selected) stroke(style.accent, 4f * d) else windowPaint
                    val off = inward * (3.0 * d / t.scale)
                    line(canvas, t, p1 + off, p2 + off, wp)
                    line(canvas, t, p1 - off, p2 - off, wp)
                    line(canvas, t, p1, p2, Paint(wp).apply { strokeWidth = 1.2f * d })
                    line(canvas, t, p1 + off, p1 - off, wp)
                    line(canvas, t, p2 + off, p2 - off, wp)
                }
            }

            // Dimension label outside the wall, kept upright.
            val mid = t.toScreen((a + b) / 2.0)
            val out = inward * -1.0
            val lx = (mid.x + out.x * 16 * d).toFloat()
            val ly = (mid.y + out.y * 16 * d).toFloat()
            var ang = Math.toDegrees(atan2(u.y, u.x)).toFloat()
            if (ang > 90) ang -= 180
            if (ang < -90) ang += 180
            canvas.save()
            canvas.rotate(ang, lx, ly)
            canvas.drawText(UnitFormat.length(len, unit), lx, ly + textPaint.textSize / 3, textPaint)
            canvas.restore()
        }

        if (style.showCorners) {
            val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = style.accent }
            val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
            pts.forEachIndexed { i, p ->
                val r = if (selection == Selection.Corner(i)) 11f * d else 7f * d
                canvas.drawCircle(p.x.toFloat(), p.y.toFloat(), r + 2 * d, white)
                canvas.drawCircle(p.x.toFloat(), p.y.toFloat(), r, dot)
            }
        }

        if (style.showArea && n >= 3) {
            val c = t.toScreen(Geometry.centroid(plan.corners))
            val big = Paint(textPaint).apply { textSize = 15f * d }
            val small = Paint(textPaint).apply { textSize = 12f * d; typeface = Typeface.DEFAULT }
            canvas.drawText(plan.name, c.x.toFloat(), c.y.toFloat() - 4 * d, big)
            canvas.drawText(UnitFormat.area(Geometry.area(plan.corners), unit), c.x.toFloat(), c.y.toFloat() + 14 * d, small)
        }
    }

    private fun stroke(c: Int, w: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = c; style = Paint.Style.STROKE; strokeWidth = w; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }

    private fun line(canvas: Canvas, t: PlanTransform, a: Vec2, b: Vec2, p: Paint) {
        val sa = t.toScreen(a); val sb = t.toScreen(b)
        canvas.drawLine(sa.x.toFloat(), sa.y.toFloat(), sb.x.toFloat(), sb.y.toFloat(), p)
    }
}
