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

    /** How a bar is drawn: the glyph of a filled cell and of an empty one. Each chart takes the style that suits what it shows. */
    enum class BarStyle(val on: Char, val off: Char) {
        /** The solid block: full height, the heaviest look (the default, kept for any bar that does not choose). */
        Block('█', '░'),

        /** A thin line, for a gauge of a percentage (how much of something is in order). */
        Line('━', '─'),

        /** Slim slanted blocks, for progress (how far work has got). */
        Slim('▰', '▱'),

        /** Half the height with a thin baseline, for comparing sizes side by side. */
        Half('▄', '▁'),

        /** One dot per step, for counting things (how many links). */
        Dots('●', '○'),

        /** A textured track of braille dots, for a series over time. */
        Braille('⣿', '⣀'),
    }

    /** How many of [width] cells are filled for [value] of [max]: none for zero, at least one for any value above zero. */
    fun filledCells(value: Int, max: Int, width: Int): Int = if (width <= 0 || max <= 0 || value <= 0) 0 else (value.toLong() * width / max).toInt().coerceIn(1, width)

    /** A bar [width] cells wide: [value] of [max] filled, the rest empty, in [style]. A value above zero always shows at least one cell. */
    fun bar(value: Int, max: Int, width: Int, style: BarStyle = BarStyle.Block): String = barParts(value, max, width, style).let { it.first + it.second }

    /** [bar] as its two parts, the filled cells and the empty ones, so a screen can colour them apart (the empty part quietly). */
    fun barParts(value: Int, max: Int, width: Int, style: BarStyle = BarStyle.Block): Pair<String, String> {
        if (width <= 0) return "" to ""
        val filled = filledCells(value, max, width)
        return style.on.toString().repeat(filled) to style.off.toString().repeat(width - filled)
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
