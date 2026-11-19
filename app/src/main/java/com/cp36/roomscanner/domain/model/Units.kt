package com.cp36.roomscanner.domain.model

import java.util.Locale
import kotlin.math.floor
import kotlin.math.roundToInt

enum class LengthUnit(val label: String) {
    M("m"), CM("cm"), MM("mm"), FT_IN("ft-in");

    /** Short suffix used next to text fields. */
    val inputSuffix: String get() = if (this == FT_IN) "ft" else label
}

object UnitFormat {
    private const val M_PER_FT = 0.3048

    fun length(m: Double, unit: LengthUnit): String = when (unit) {
        LengthUnit.M -> String.format(Locale.US, "%.2f m", m)
        LengthUnit.CM -> String.format(Locale.US, "%.1f cm", m * 100)
        LengthUnit.MM -> "${(m * 1000).roundToInt()} mm"
        LengthUnit.FT_IN -> {
            val totalIn = m / 0.0254
            var ft = floor(totalIn / 12).toInt()
            var inch = (totalIn - ft * 12).roundToInt()
            if (inch == 12) { ft += 1; inch = 0 }
            "$ft' $inch\""
        }
    }

    fun area(m2: Double, unit: LengthUnit): String =
        if (unit == LengthUnit.FT_IN) String.format(Locale.US, "%.1f ft²", m2 / (M_PER_FT * M_PER_FT))
        else String.format(Locale.US, "%.2f m²", m2)

    fun volume(m3: Double, unit: LengthUnit): String =
        if (unit == LengthUnit.FT_IN) String.format(Locale.US, "%.1f ft³", m3 / (M_PER_FT * M_PER_FT * M_PER_FT))
        else String.format(Locale.US, "%.2f m³", m3)

    /** Value to pre-fill an input field (number only, in the field's unit). */
    fun toInput(m: Double, unit: LengthUnit): String = when (unit) {
        LengthUnit.M -> String.format(Locale.US, "%.3f", m)
        LengthUnit.CM -> String.format(Locale.US, "%.1f", m * 100)
        LengthUnit.MM -> (m * 1000).roundToInt().toString()
        LengthUnit.FT_IN -> String.format(Locale.US, "%.2f", m / M_PER_FT)
    }

    /**
     * Parses a typed length into metres. In ft-in mode accepts decimal feet ("12.5"),
     * or feet and inches ("12' 6", "12 6", "12'6\"").
     */
    fun parse(text: String, unit: LengthUnit): Double? {
        val t = text.trim().replace(',', '.')
        if (t.isEmpty()) return null
        if (unit == LengthUnit.FT_IN) {
            val parts = t.replace("\"", "").split('\'', ' ').filter { it.isNotBlank() }
            val ft = parts.getOrNull(0)?.toDoubleOrNull() ?: return null
            val inch = parts.getOrNull(1)?.toDoubleOrNull() ?: 0.0
            return (ft * 12 + inch) * 0.0254
        }
        val v = t.toDoubleOrNull() ?: return null
        return when (unit) {
            LengthUnit.M -> v
            LengthUnit.CM -> v / 100
            LengthUnit.MM -> v / 1000
            LengthUnit.FT_IN -> v * M_PER_FT
        }.takeIf { it > 0 }
    }
}
