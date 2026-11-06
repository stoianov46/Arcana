package com.arcana.brain.app.design

import com.arcana.brain.core.DayPoint

/**
 * Small charts drawn with block characters, as plain text, so they are tested without a terminal. The screens colour the text.
 */
object Chart {
    private const val STEPS = "▁▂▃▄▅▆▇█"
    private const val HEAT = "░▒▓█"

    /** One character per value, as tall as the value is against the largest: a zero is a dot, so a quiet week is still visible. */
    fun spark(values: List<Long>): String {
        val max = values.maxOrNull() ?: 0L
        return values.joinToString("") { v ->
            if (v <= 0L || max <= 0L) "·" else STEPS[(((v * 8 + max - 1) / max).toInt() - 1).coerceIn(0, 7)].toString()
        }
    }

    /** A bar [width] cells wide: [value] of [max] filled (`█`), the rest `░`. A value above zero always shows at least one cell. */
    fun bar(value: Int, max: Int, width: Int): String {
        if (width <= 0) return ""
        val filled = if (max <= 0 || value <= 0) 0 else (value.toLong() * width / max).toInt().coerceIn(1, width)
        return "█".repeat(filled) + "░".repeat(width - filled)
    }

    /** The intensity of a day, 0 (nothing) to 4 (the busiest), for [count] against [max]. */
    fun heatLevel(count: Int, max: Int): Int = if (count <= 0 || max <= 0) 0 else (((count * 4 + max - 1) / max)).coerceIn(1, 4)

    /** The glyph of one day: a dot for nothing, then `░▒▓█` by intensity. */
    fun heatGlyph(level: Int): Char = if (level <= 0) '·' else HEAT[(level - 1).coerceIn(0, 3)]

    /**
     * The days as a calendar: seven rows (Monday to Sunday), one column per week, the first column being the week of the first day. A day that is
     * not in [days] (the rest of this week) is a blank. Every cell is [cell] glyphs wide.
     */
    fun heatLevels(days: List<DayPoint>, weeks: Int): List<List<Int?>> {
        if (days.isEmpty()) return List(7) { List(weeks) { null } }
        val max = days.maxOf { it.changed }
        val first = days.first().day
        val byDay = days.associateBy { it.day }
        return (0 until 7).map { row ->
            (0 until weeks).map { col ->
                byDay[first.plusDays((col * 7 + row).toLong())]?.let { heatLevel(it.changed, max) }
            }
        }
    }

    /** The weekday labels of the heat rows. */
    val WEEKDAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
}
