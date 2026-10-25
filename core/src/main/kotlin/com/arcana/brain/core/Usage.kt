package com.arcana.brain.core

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One finished request to the model: what it cost in tokens and how long it took. */
@Serializable
data class UsageRecord(
    val time: Long,
    /** The part of Arcana that made the request: Summarize, Chat, Ask or Generate. */
    val feature: String,
    val model: String,
    val promptTokens: Int,
    val completionTokens: Int,
    val millis: Long,
) {
    val totalTokens: Int get() = promptTokens + completionTokens
}

/** Every request is appended as one JSON line to `usage.jsonl` next to the settings, so the history survives restarts. */
class UsageStore(val path: Path = defaultPath()) {
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): List<UsageRecord> {
        if (!Files.exists(path)) return emptyList()
        return runCatching {
            Files.readAllLines(path).mapNotNull { line ->
                line.takeIf { it.isNotBlank() }?.let { runCatching { json.decodeFromString<UsageRecord>(it) }.getOrNull() }
            }
        }.getOrDefault(emptyList())
    }

    fun append(record: UsageRecord) {
        Files.createDirectories(path.parent)
        Files.writeString(
            path, json.encodeToString(UsageRecord.serializer(), record) + "\n",
            StandardOpenOption.CREATE, StandardOpenOption.APPEND,
        )
    }

    fun clear() {
        Files.deleteIfExists(path)
    }

    companion object {
        fun defaultPath(): Path = ConfigStore.defaultPath().resolveSibling("usage.jsonl")
    }
}

data class UsageTotals(val requests: Int, val promptTokens: Long, val completionTokens: Long, val millis: Long) {
    val totalTokens: Long get() = promptTokens + completionTokens

    /** Generated tokens per second over the time spent; 0 when nothing was timed. */
    val tokensPerSecond: Double get() = if (millis <= 0) 0.0 else completionTokens * 1000.0 / millis
    val averageTokens: Long get() = if (requests == 0) 0 else totalTokens / requests
}

/** What [UsageStats.insights] found; every field is empty or zero when there is nothing to find. */
data class UsageInsights(
    val activeDays: Int,
    val streakDays: Int,
    val busiestDay: Pair<LocalDate, Long>?,
    val peakHour: Int?,
    val longestReply: UsageRecord?,
    val slowestReply: UsageRecord?,
    val medianMillis: Long,
    val averageMillis: Long,
    /** Prompt tokens per reply token: high means long contexts (attachments, tool output, history). */
    val contextRatio: Double,
    /** The last 7 days against the 7 before, as a fraction (0.5 = 50% more); null when the earlier week was empty. */
    val weekChange: Double?,
)

object UsageStats {
    fun totals(records: List<UsageRecord>) = UsageTotals(
        records.size, records.sumOf { it.promptTokens.toLong() }, records.sumOf { it.completionTokens.toLong() }, records.sumOf { it.millis },
    )

    /** Totals per [key], the most expensive first. */
    fun by(records: List<UsageRecord>, key: (UsageRecord) -> String): List<Pair<String, UsageTotals>> =
        records.groupBy(key).map { (k, v) -> k to totals(v) }.sortedByDescending { it.second.totalTokens }

    /** Total tokens for each of the last [days] days ending [today], oldest first; days without requests count 0. */
    fun perDay(records: List<UsageRecord>, days: Int, today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): List<Pair<LocalDate, Long>> {
        val byDate = records.groupBy { Instant.ofEpochMilli(it.time).atZone(zone).toLocalDate() }
        return (days - 1 downTo 0).map { back ->
            val date = today.minusDays(back.toLong())
            date to (byDate[date]?.sumOf { it.totalTokens.toLong() } ?: 0L)
        }
    }

    /** Total tokens for each hour of the day (index 0-23), over all [records]. */
    fun perHour(records: List<UsageRecord>, zone: ZoneId = ZoneId.systemDefault()): List<Long> {
        val out = LongArray(24)
        records.forEach { out[Instant.ofEpochMilli(it.time).atZone(zone).hour] += it.totalTokens.toLong() }
        return out.toList()
    }

    /** Generated tokens per second for each of the last [days] days (0.0 for a day without timed replies), oldest first. */
    fun speedPerDay(records: List<UsageRecord>, days: Int, today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): List<Pair<LocalDate, Double>> {
        val byDate = records.groupBy { Instant.ofEpochMilli(it.time).atZone(zone).toLocalDate() }
        return (days - 1 downTo 0).map { back ->
            val date = today.minusDays(back.toLong())
            date to (byDate[date]?.let { totals(it).tokensPerSecond } ?: 0.0)
        }
    }

    /**
     * Facts worth a glance about [records] (the chosen period); streaks and week-on-week change look at [all] because they
     * are about habit, not about one period.
     */
    fun insights(records: List<UsageRecord>, all: List<UsageRecord>, today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): UsageInsights {
        fun day(r: UsageRecord) = Instant.ofEpochMilli(r.time).atZone(zone).toLocalDate()
        val byDay = records.groupBy(::day).mapValues { (_, v) -> v.sumOf { it.totalTokens.toLong() } }
        val hours = perHour(records, zone)
        val allDays = all.map(::day).toSet()
        // the streak counts today, or yesterday when nothing was asked yet today
        var cursor = if (today in allDays) today else today.minusDays(1)
        var streak = 0
        while (cursor in allDays) { streak++; cursor = cursor.minusDays(1) }
        val tokensOf = { from: LocalDate, to: LocalDate -> all.filter { day(it) in from..to }.sumOf { it.totalTokens.toLong() } }
        val thisWeek = tokensOf(today.minusDays(6), today)
        val lastWeek = tokensOf(today.minusDays(13), today.minusDays(7))
        val timed = records.filter { it.millis > 0 }.map { it.millis }.sorted()
        val totals = totals(records)
        return UsageInsights(
            activeDays = byDay.size,
            streakDays = streak,
            busiestDay = byDay.maxByOrNull { it.value }?.toPair(),
            peakHour = hours.withIndex().maxByOrNull { it.value }?.takeIf { it.value > 0 }?.index,
            longestReply = records.maxByOrNull { it.completionTokens },
            slowestReply = records.maxByOrNull { it.millis },
            medianMillis = if (timed.isEmpty()) 0 else timed[timed.size / 2],
            averageMillis = if (records.isEmpty()) 0 else totals.millis / records.size,
            contextRatio = if (totals.completionTokens == 0L) 0.0 else totals.promptTokens.toDouble() / totals.completionTokens,
            weekChange = if (lastWeek == 0L) null else (thisWeek - lastWeek).toDouble() / lastWeek,
        )
    }

    /** What [records] would cost at [priceIn] / [priceOut] dollars per million prompt / reply tokens. */
    fun estimatedCost(records: List<UsageRecord>, priceIn: Int, priceOut: Int): Double =
        records.sumOf { it.promptTokens.toLong() } * priceIn / 1_000_000.0 + records.sumOf { it.completionTokens.toLong() } * priceOut / 1_000_000.0

    fun since(records: List<UsageRecord>, fromMillis: Long) = records.filter { it.time >= fromMillis }

    fun startOfToday(zone: ZoneId = ZoneId.systemDefault(), today: LocalDate = LocalDate.now()): Long =
        today.atStartOfDay(zone).toInstant().toEpochMilli()

    /** "1.2k", "3.4M": a token count short enough for a status bar. */
    fun compact(n: Long): String = when {
        n < 1_000 -> n.toString()
        n < 100_000 -> "%.1fk".format(n / 1000.0).replace(".0k", "k")
        n < 1_000_000 -> "${n / 1000}k"
        else -> "%.1fM".format(n / 1_000_000.0).replace(".0M", "M")
    }
}
