package com.arcana.brain.app.design

import com.idea.ink.ui.*
import com.idea.ink.input.*
import com.idea.ink.theme.*

import com.arcana.brain.core.DayPoint
import com.idea.ink.chart.Chart

/** The heat calendar of [days] (Ink's [Chart.heatCalendar]): seven rows, one column per week, null for a day that is not in [days]. */
fun Chart.heatLevels(days: List<DayPoint>, weeks: Int): List<List<Int?>> = heatCalendar(days.map { it.day to it.changed }, weeks)
