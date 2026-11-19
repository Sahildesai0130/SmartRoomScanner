package com.cp36.roomscanner

import com.cp36.roomscanner.domain.geometry.Geometry
import com.cp36.roomscanner.domain.geometry.PlanOps
import com.cp36.roomscanner.domain.model.LengthUnit
import com.cp36.roomscanner.domain.model.UnitFormat
import com.cp36.roomscanner.domain.model.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class GeometryTest {

    private val rect = listOf(Vec2(0.0, 0.0), Vec2(4.0, 0.0), Vec2(4.0, 3.0), Vec2(0.0, 3.0))

    @Test fun shoelaceAreaOfRectangle() = assertEquals(12.0, Geometry.area(rect), 1e-9)

    @Test fun shoelaceAreaOfLShape() {
        val l = listOf(Vec2(0.0, 0.0), Vec2(4.0, 0.0), Vec2(4.0, 2.0), Vec2(2.0, 2.0), Vec2(2.0, 4.0), Vec2(0.0, 4.0))
        assertEquals(12.0, Geometry.area(l), 1e-9)
        assertEquals(16.0, Geometry.perimeter(l), 1e-9)
    }

    @Test fun interiorAnglesSumTo540ForPentagon() {
        val p = List(5) { i -> Vec2(cos(i * 2 * Math.PI / 5), sin(i * 2 * Math.PI / 5)) }
        assertEquals(540.0, Geometry.interiorAngles(p).sum(), 1e-6)
        assertEquals(540.0, Geometry.interiorAngles(p.reversed()).sum(), 1e-6)
    }

    @Test fun squareCornersFixesNoisyRotatedRectangle() {
        val rnd = Random(1)
        val angle = Math.toRadians(23.0)
        val noisy = rect.map {
            val x = it.x + rnd.nextDouble(-0.04, 0.04)
            val y = it.y + rnd.nextDouble(-0.04, 0.04)
            Vec2(x * cos(angle) - y * sin(angle), x * sin(angle) + y * cos(angle))
        }
        val fixed = Geometry.squareCorners(noisy, 10.0)
        Geometry.interiorAngles(fixed).forEach { assertEquals(90.0, it, 1e-6) }
        assertEquals(12.0, Geometry.area(fixed), 0.4)
    }

    @Test fun pcaFindsWallLine() {
        val rnd = Random(2)
        val pts = List(200) { Vec2(rnd.nextDouble(0.0, 4.0), 1.5 + rnd.nextDouble(-0.01, 0.01)) }
        val line = assertNotNullAndGet(Geometry.fitLinePca(pts))
        assertEquals(0.0, line.dir.y, 0.01)
        assertEquals(1.5, line.point.y, 0.005)
    }

    @Test fun refineMovesCornersOntoWallPoints() {
        // User tapped corners 5 cm off; wall points sit on the true 4 x 3 rectangle.
        val tapped = listOf(Vec2(0.05, 0.05), Vec2(4.05, 0.0), Vec2(4.0, 3.05), Vec2(-0.05, 3.0))
        val pts = ArrayList<Vec2>()
        for (i in 0..100) {
            val t = i / 100.0
            pts += Vec2(4 * t, 0.0); pts += Vec2(4.0, 3 * t); pts += Vec2(4 * t, 3.0); pts += Vec2(0.0, 3 * t)
        }
        val refined = Geometry.refineWithWallPoints(tapped, pts)
        for (i in rect.indices) assertEquals(0.0, (refined[i] - rect[i]).length(), 0.005)
    }

    @Test fun setWallLengthKeepsRectangle() {
        val plan = PlanOps.rectangle("R", 4.0, 3.0, 2.7)
        val edited = PlanOps.setWallLength(plan, 0, 5.0)
        assertEquals(5.0, edited.wallLength(0), 1e-9)
        assertEquals(5.0, edited.wallLength(2), 1e-9)
        assertEquals(15.0, Geometry.area(edited.corners), 1e-9)
    }

    @Test fun parsesFeetAndInches() {
        assertEquals(3.81, UnitFormat.parse("12' 6", LengthUnit.FT_IN)!!, 1e-3)
        assertEquals(2.5, UnitFormat.parse("250", LengthUnit.CM)!!, 1e-9)
    }

    private fun <T> assertNotNullAndGet(v: T?): T { assertNotNull(v); return v!! }
}
