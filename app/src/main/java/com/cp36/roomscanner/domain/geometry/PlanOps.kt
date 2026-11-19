package com.cp36.roomscanner.domain.geometry

import com.cp36.roomscanner.domain.model.Opening
import com.cp36.roomscanner.domain.model.OpeningDefaults
import com.cp36.roomscanner.domain.model.OpeningType
import com.cp36.roomscanner.domain.model.RoomPlan
import com.cp36.roomscanner.domain.model.Vec2
import kotlin.math.max
import kotlin.math.min

/** Editing operations on a plan. Each returns a new plan (immutable), which makes undo/redo trivial. */
object PlanOps {

    fun rectangle(name: String, width: Double, length: Double, height: Double) = RoomPlan(
        name = name,
        corners = listOf(Vec2(0.0, 0.0), Vec2(width, 0.0), Vec2(width, length), Vec2(0.0, length)),
        ceilingHeight = height,
        source = "MANUAL",
    )

    fun moveCorner(plan: RoomPlan, index: Int, to: Vec2): RoomPlan =
        clampOpenings(plan.copy(corners = plan.corners.toMutableList().also { it[index] = to }))

    /**
     * Sets wall [wall] to [newLength] by sliding its end corner along the wall. The next corner
     * moves with it, so the following wall keeps its direction and length (rectangles stay rectangles).
     */
    fun setWallLength(plan: RoomPlan, wall: Int, newLength: Double): RoomPlan {
        val n = plan.corners.size
        if (n < 3 || newLength <= 0.01) return plan
        val a = plan.wallStart(wall)
        val b = plan.wallEnd(wall)
        val u = (b - a).normalized()
        val delta = u * (newLength - (b - a).length())
        val pts = plan.corners.toMutableList()
        val j = (wall + 1) % n
        pts[j] = pts[j] + delta
        if (n >= 4) {
            val k = (wall + 2) % n
            pts[k] = pts[k] + delta
        }
        return clampOpenings(plan.copy(corners = pts))
    }

    /** Scales the whole plan so that [wall] measures [trueLength] (calibration from a tape measurement). */
    fun calibrate(plan: RoomPlan, wall: Int, trueLength: Double): RoomPlan {
        val current = plan.wallLength(wall)
        if (current < 1e-6 || trueLength <= 0) return plan
        return scale(plan, trueLength / current)
    }

    fun scale(plan: RoomPlan, factor: Double): RoomPlan {
        val c = Geometry.centroid(plan.corners)
        return plan.copy(
            corners = plan.corners.map { c + (it - c) * factor },
            openings = plan.openings.map { it.copy(offset = it.offset * factor, width = it.width * factor) },
        )
    }

    fun square(plan: RoomPlan, toleranceDeg: Double): RoomPlan =
        clampOpenings(plan.copy(corners = Geometry.squareCorners(plan.corners, toleranceDeg)))

    /** Adds a corner in the middle of [wall], splitting it in two. */
    fun splitWall(plan: RoomPlan, wall: Int): RoomPlan {
        val a = plan.wallStart(wall)
        val b = plan.wallEnd(wall)
        val half = (b - a).length() / 2
        val pts = plan.corners.toMutableList().apply { add(wall + 1, (a + b) / 2.0) }
        val openings = plan.openings.map { o ->
            when {
                o.wallIndex > wall -> o.copy(wallIndex = o.wallIndex + 1)
                o.wallIndex == wall && o.offset >= half -> o.copy(wallIndex = wall + 1, offset = o.offset - half)
                else -> o
            }
        }
        return clampOpenings(plan.copy(corners = pts, openings = openings))
    }

    /** Removes a corner (the two walls next to it merge). Openings on those walls are removed. */
    fun deleteCorner(plan: RoomPlan, index: Int): RoomPlan {
        val n = plan.corners.size
        if (n <= 3) return plan
        val prevWall = (index - 1 + n) % n
        val pts = plan.corners.toMutableList().apply { removeAt(index) }
        val openings = plan.openings
            .filter { it.wallIndex != index && it.wallIndex != prevWall }
            .map { if (it.wallIndex > index) it.copy(wallIndex = it.wallIndex - 1) else it }
        return plan.copy(corners = pts, openings = openings)
    }

    fun addOpening(plan: RoomPlan, type: OpeningType, wall: Int, offset: Double? = null, width: Double? = null): RoomPlan {
        val d = if (type == OpeningType.DOOR) OpeningDefaults.DOOR else OpeningDefaults.WINDOW
        val len = plan.wallLength(wall)
        val w = min(width ?: d.width, max(0.2, len - 0.1))
        val off = offset ?: ((len - w) / 2)
        val id = (plan.openings.maxOfOrNull { it.id } ?: 0L) + 1
        val o = Opening(id, type, wall, off, w, d.height, d.sill)
        return clampOpenings(plan.copy(openings = plan.openings + o))
    }

    fun updateOpening(plan: RoomPlan, opening: Opening): RoomPlan =
        clampOpenings(plan.copy(openings = plan.openings.map { if (it.id == opening.id) opening else it }))

    fun removeOpening(plan: RoomPlan, id: Long): RoomPlan =
        plan.copy(openings = plan.openings.filterNot { it.id == id })

    /** Keeps every opening inside its wall after the walls change. */
    fun clampOpenings(plan: RoomPlan): RoomPlan {
        val n = plan.corners.size
        val fixed = plan.openings.mapNotNull { o ->
            if (o.wallIndex !in 0 until n) return@mapNotNull null
            val len = plan.wallLength(o.wallIndex)
            val w = min(o.width, len)
            o.copy(width = w, offset = o.offset.coerceIn(0.0, max(0.0, len - w)))
        }
        return plan.copy(openings = fixed)
    }
}
