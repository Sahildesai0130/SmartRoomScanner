package com.cp36.roomscanner.export

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import com.cp36.roomscanner.domain.geometry.Geometry
import com.cp36.roomscanner.domain.model.LengthUnit
import com.cp36.roomscanner.domain.model.OpeningType
import com.cp36.roomscanner.domain.model.RoomPlan
import com.cp36.roomscanner.domain.model.UnitFormat
import com.cp36.roomscanner.domain.model.Vec2
import com.cp36.roomscanner.ui.plan.PlanDrawer
import com.cp36.roomscanner.ui.plan.PlanStyle
import com.cp36.roomscanner.ui.plan.PlanTransform
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.max

enum class ExportFormat(val ext: String, val mime: String, val label: String) {
    DXF("dxf", "application/dxf", "DXF (AutoCAD / LibreCAD)"),
    PNG("png", "image/png", "PNG image"),
    PDF("pdf", "application/pdf", "PDF report"),
    JSON("json", "application/json", "JSON backup"),
}

object Exporters {

    fun export(context: Context, plan: RoomPlan, format: ExportFormat, unit: LengthUnit, json: Json): File {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val safe = plan.name.replace(Regex("[^A-Za-z0-9_-]+"), "_").trim('_').ifEmpty { "room" }
        val file = File(dir, "$safe.${format.ext}")
        when (format) {
            ExportFormat.DXF -> file.writeText(dxf(plan))
            ExportFormat.JSON -> file.writeText(json(plan, json))
            ExportFormat.PNG -> file.outputStream().use { png(plan, unit).compress(Bitmap.CompressFormat.PNG, 100, it) }
            ExportFormat.PDF -> pdf(plan, unit, file)
        }
        return file
    }

    fun share(context: Context, file: File, format: ExportFormat) {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = format.mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Share ${file.name}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // ---------------------------------------------------------------- DXF (R12, millimetres)

    fun dxf(plan: RoomPlan): String {
        val sb = StringBuilder()
        fun g(code: Int, value: Any) { sb.append(code).append('\n').append(value).append('\n') }
        fun num(v: Double) = String.format(Locale.US, "%.2f", v)
        // Plan y points down; CAD y points up, so flip y. Units: mm.
        fun P(v: Vec2) = Vec2(v.x * 1000, -v.y * 1000)
        fun lineE(layer: String, a: Vec2, b: Vec2) {
            g(0, "LINE"); g(8, layer)
            g(10, num(a.x)); g(20, num(a.y)); g(30, "0.0")
            g(11, num(b.x)); g(21, num(b.y)); g(31, "0.0")
        }
        fun textE(layer: String, at: Vec2, h: Double, text: String, rot: Double = 0.0) {
            g(0, "TEXT"); g(8, layer)
            g(10, num(at.x)); g(20, num(at.y)); g(30, "0.0")
            g(40, num(h)); g(1, text); g(50, num(rot))
            g(72, 1); g(11, num(at.x)); g(21, num(at.y)); g(31, "0.0")
        }

        g(0, "SECTION"); g(2, "HEADER")
        g(9, "\$ACADVER"); g(1, "AC1009")
        g(9, "\$INSUNITS"); g(70, 4)
        g(0, "ENDSEC")

        g(0, "SECTION"); g(2, "TABLES")
        g(0, "TABLE"); g(2, "LTYPE"); g(70, 1)
        g(0, "LTYPE"); g(2, "CONTINUOUS"); g(70, 0); g(3, "Solid line"); g(72, 65); g(73, 0); g(40, "0.0")
        g(0, "ENDTAB")
        val layers = listOf("WALLS" to 7, "DOORS" to 30, "WINDOWS" to 4, "DIMENSIONS" to 3, "TEXT" to 7)
        g(0, "TABLE"); g(2, "LAYER"); g(70, layers.size)
        for ((name, color) in layers) {
            g(0, "LAYER"); g(2, name); g(70, 0); g(62, color); g(6, "CONTINUOUS")
        }
        g(0, "ENDTAB")
        g(0, "ENDSEC")

        g(0, "SECTION"); g(2, "ENTITIES")
        val n = plan.corners.size
        for (i in 0 until n) {
            val a = plan.wallStart(i); val b = plan.wallEnd(i)
            val len = (b - a).length()
            if (len < 1e-6) continue
            val u = (b - a) / len
            val inward = Geometry.inwardNormal(plan, i)
            val ops = plan.openings.filter { it.wallIndex == i }.sortedBy { it.offset }
            var cursor = 0.0
            for (o in ops) {
                if (o.offset > cursor) lineE("WALLS", P(a + u * cursor), P(a + u * o.offset))
                cursor = max(cursor, o.offset + o.width)
            }
            if (cursor < len) lineE("WALLS", P(a + u * cursor), P(b))

            for (o in ops) {
                val p1 = a + u * o.offset
                val p2 = a + u * (o.offset + o.width)
                if (o.type == OpeningType.DOOR) {
                    lineE("DOORS", P(p1), P(p1 + inward * o.width))
                    // Arc in CAD coordinates (angles counter-clockwise, degrees).
                    val c = P(p1)
                    val du = P(p1 + u) - c
                    val dn = P(p1 + inward) - c
                    val au = Math.toDegrees(atan2(du.y, du.x))
                    val an = Math.toDegrees(atan2(dn.y, dn.x))
                    val (start, end) = if ((du cross dn) > 0) au to an else an to au
                    g(0, "ARC"); g(8, "DOORS")
                    g(10, num(c.x)); g(20, num(c.y)); g(30, "0.0")
                    g(40, num(o.width * 1000)); g(50, num(norm360(start))); g(51, num(norm360(end)))
                } else {
                    val off = inward * 0.05
                    lineE("WINDOWS", P(p1 + off), P(p2 + off))
                    lineE("WINDOWS", P(p1 - off), P(p2 - off))
                    lineE("WINDOWS", P(p1), P(p2))
                }
            }

            // Wall length text, 250 mm outside the wall.
            val mid = (a + b) / 2.0 - inward * 0.25
            val dd = P(b) - P(a)
            var rot = Math.toDegrees(atan2(dd.y, dd.x))
            if (rot > 90) rot -= 180
            if (rot < -90) rot += 180
            textE("DIMENSIONS", P(mid), 120.0, "${Math.round(len * 1000)}", rot)
        }
        if (n >= 3) {
            val c = P(Geometry.centroid(plan.corners))
            textE("TEXT", c + Vec2(0.0, 150.0), 200.0, plan.name)
            textE("TEXT", c - Vec2(0.0, 200.0), 150.0, String.format(Locale.US, "%.2f m2", Geometry.area(plan.corners)))
        }
        g(0, "ENDSEC")
        g(0, "EOF")
        return sb.toString()
    }

    private fun norm360(a: Double): Double { var x = a % 360.0; if (x < 0) x += 360.0; return x }

    // ---------------------------------------------------------------- JSON

    fun json(plan: RoomPlan, json: Json): String {
        val s = Geometry.stats(plan)
        val obj = buildJsonObject {
            put("app", "Smart Room Scanner")
            put("units", "metres")
            put("exportedAt", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date()))
            put("plan", json.encodeToJsonElement(RoomPlan.serializer(), plan))
            put("stats", buildJsonObject {
                put("area", s.area)
                put("perimeter", s.perimeter)
                put("volume", s.volume)
                put("wallArea", s.wallArea)
                put("wallLengths", kotlinx.serialization.json.JsonArray(s.wallLengths.map { JsonPrimitive(it) }))
                put("interiorAngles", kotlinx.serialization.json.JsonArray(s.interiorAngles.map { JsonPrimitive(it) }))
            })
        }
        return json.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), obj)
    }

    // ---------------------------------------------------------------- PNG

    fun png(plan: RoomPlan, unit: LengthUnit, width: Int = 2000): Bitmap {
        val minX = plan.corners.minOfOrNull { it.x } ?: 0.0
        val maxX = plan.corners.maxOfOrNull { it.x } ?: 1.0
        val minY = plan.corners.minOfOrNull { it.y } ?: 0.0
        val maxY = plan.corners.maxOfOrNull { it.y } ?: 1.0
        val aspect = (max(maxY - minY, 0.5) / max(maxX - minX, 0.5)).coerceIn(0.4, 2.0)
        val header = 160
        val height = (header + (width - 240) * aspect + 240).toInt()
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val density = width / 600f
        drawHeader(c, plan, unit, 60f, 70f, density * 0.9f)
        c.save()
        c.translate(0f, header.toFloat())
        val t = PlanTransform.fit(plan, width.toFloat(), (height - header).toFloat(), 120f)
        PlanDrawer.draw(c, plan, t, unit, PlanStyle(density = density, showCorners = false))
        c.restore()
        return bmp
    }

    private fun drawHeader(c: Canvas, plan: RoomPlan, unit: LengthUnit, x: Float, y: Float, scale: Float) {
        val s = Geometry.stats(plan)
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(15, 23, 42); textSize = 22f * scale; typeface = Typeface.DEFAULT_BOLD }
        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(71, 85, 105); textSize = 13f * scale }
        c.drawText(plan.name, x, y, title)
        c.drawText(
            "Area ${UnitFormat.area(s.area, unit)}   ·   Perimeter ${UnitFormat.length(s.perimeter, unit)}   ·   " +
                "Height ${UnitFormat.length(plan.ceilingHeight, unit)}   ·   Volume ${UnitFormat.volume(s.volume, unit)}",
            x, y + 22f * scale, body,
        )
    }

    // ---------------------------------------------------------------- PDF report

    fun pdf(plan: RoomPlan, unit: LengthUnit, file: File) {
        val doc = PdfDocument()
        val pageW = 595; val pageH = 842 // A4 in points
        var pageNo = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNo).create())
        var c = page.canvas
        val s = Geometry.stats(plan)

        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(15, 23, 42); textSize = 20f; typeface = Typeface.DEFAULT_BOLD }
        val h2 = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(15, 23, 42); textSize = 12f; typeface = Typeface.DEFAULT_BOLD }
        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(51, 65, 85); textSize = 10f }
        val muted = Paint(body).apply { color = Color.rgb(100, 116, 139); textSize = 9f }
        val rule = Paint().apply { color = Color.rgb(203, 213, 225); strokeWidth = 0.8f }

        c.drawText(plan.name, 40f, 56f, title)
        c.drawText(
            "Smart Room Scanner · " + SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date()) +
                " · " + (if (plan.source == "AR") "AR scan" else "Manual drawing"),
            40f, 74f, muted,
        )
        c.drawLine(40f, 86f, pageW - 40f, 86f, rule)

        // Plan drawing.
        c.save()
        c.translate(40f, 96f)
        val t = PlanTransform.fit(plan, pageW - 80f, 400f, 40f)
        PlanDrawer.draw(c, plan, t, unit, PlanStyle(density = 0.8f, showCorners = false))
        c.restore()

        var y = 520f
        c.drawText("Summary", 40f, y, h2); y += 18
        val summary = listOf(
            "Floor area" to UnitFormat.area(s.area, unit),
            "Perimeter" to UnitFormat.length(s.perimeter, unit),
            "Ceiling height" to UnitFormat.length(plan.ceilingHeight, unit),
            "Volume" to UnitFormat.volume(s.volume, unit),
            "Wall area (minus openings)" to UnitFormat.area(s.wallArea, unit),
            "Doors / windows" to "${Geometry.countOf(plan, OpeningType.DOOR)} / ${Geometry.countOf(plan, OpeningType.WINDOW)}",
        )
        for ((k, v) in summary) { c.drawText(k, 40f, y, body); c.drawText(v, 220f, y, body); y += 15 }

        y += 10
        fun ensureSpace() {
            if (y > pageH - 50) {
                doc.finishPage(page)
                pageNo++
                page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNo).create())
                c = page.canvas
                y = 56f
            }
        }
        c.drawText("Walls", 40f, y, h2); y += 18
        c.drawText("Wall", 40f, y, muted); c.drawText("Length", 120f, y, muted); c.drawText("Corner angle", 220f, y, muted); y += 14
        for (i in s.wallLengths.indices) {
            ensureSpace()
            val next = (i + 1) % s.wallLengths.size
            c.drawText("${label(i)}–${label(next)}", 40f, y, body)
            c.drawText(UnitFormat.length(s.wallLengths[i], unit), 120f, y, body)
            c.drawText(String.format(Locale.US, "%.1f° at %s", s.interiorAngles.getOrElse(i) { 0.0 }, label(i)), 220f, y, body)
            y += 14
        }
        if (plan.openings.isNotEmpty()) {
            y += 10; ensureSpace()
            c.drawText("Doors and windows", 40f, y, h2); y += 18
            for (o in plan.openings) {
                ensureSpace()
                val wall = "${label(o.wallIndex)}–${label((o.wallIndex + 1) % plan.corners.size)}"
                c.drawText(
                    (if (o.type == OpeningType.DOOR) "Door" else "Window") + " on wall $wall: width " +
                        UnitFormat.length(o.width, unit) + ", height " + UnitFormat.length(o.height, unit) +
                        ", " + UnitFormat.length(o.offset, unit) + " from " + label(o.wallIndex),
                    40f, y, body,
                )
                y += 14
            }
        }
        doc.finishPage(page)
        file.outputStream().use { doc.writeTo(it) }
        doc.close()
    }

    /** Corner labels A, B, C … Z, A1 … */
    fun label(i: Int): String = ('A' + i % 26).toString() + if (i >= 26) (i / 26).toString() else ""
}
