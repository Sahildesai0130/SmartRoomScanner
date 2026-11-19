package com.cp36.roomscanner.domain.model

import kotlinx.serialization.Serializable
import kotlin.math.sqrt

/** A 2D point on the floor plan, in metres. x = right, y = down on screen (ARCore world X / Z). */
@Serializable
data class Vec2(val x: Double, val y: Double) {
    operator fun plus(o: Vec2) = Vec2(x + o.x, y + o.y)
    operator fun minus(o: Vec2) = Vec2(x - o.x, y - o.y)
    operator fun times(s: Double) = Vec2(x * s, y * s)
    operator fun div(s: Double) = Vec2(x / s, y / s)
    infix fun dot(o: Vec2) = x * o.x + y * o.y
    infix fun cross(o: Vec2) = x * o.y - y * o.x
    fun length() = sqrt(x * x + y * y)
    fun normalized(): Vec2 { val l = length(); return if (l < 1e-12) Vec2(0.0, 0.0) else this / l }
    /** Perpendicular (rotated +90°). */
    fun perp() = Vec2(-y, x)

    companion object { val ZERO = Vec2(0.0, 0.0) }
}

@Serializable
enum class OpeningType { DOOR, WINDOW }

/**
 * A door or window placed on a wall.
 * Wall i runs from corners[i] to corners[(i + 1) % n]; [offset] is measured from corners[i] along that wall.
 */
@Serializable
data class Opening(
    val id: Long,
    val type: OpeningType,
    val wallIndex: Int,
    val offset: Double,
    val width: Double,
    val height: Double,
    val sillHeight: Double = 0.0,
)

/** One room: a closed polygon of corners plus ceiling height and openings. */
@Serializable
data class RoomPlan(
    val name: String = "Room",
    val corners: List<Vec2> = emptyList(),
    val ceilingHeight: Double = 2.7,
    val openings: List<Opening> = emptyList(),
    /** "AR" when scanned with the camera, "MANUAL" when drawn by hand. */
    val source: String = "AR",
) {
    val wallCount: Int get() = corners.size

    fun wallStart(i: Int): Vec2 = corners[i]
    fun wallEnd(i: Int): Vec2 = corners[(i + 1) % corners.size]
    fun wallLength(i: Int): Double = (wallEnd(i) - wallStart(i)).length()
}

/** Calculated values shown in the app and written to the exports. */
data class RoomStats(
    val area: Double,
    val perimeter: Double,
    val volume: Double,
    val wallLengths: List<Double>,
    val interiorAngles: List<Double>,
    val wallArea: Double,
)

enum class OpeningDefaults(val width: Double, val height: Double, val sill: Double) {
    DOOR(0.9, 2.1, 0.0),
    WINDOW(1.2, 1.2, 0.9),
}
