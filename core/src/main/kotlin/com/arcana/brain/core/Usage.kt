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
