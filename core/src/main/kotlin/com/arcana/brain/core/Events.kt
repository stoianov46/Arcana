package com.arcana.brain.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** Something that happened in the orchestration that a token count cannot show: a redo, a verdict, a routed message, a goal met. One line of `events.jsonl`. */
@Serializable
data class StatEvent(val time: Long, val kind: String, val detail: String = "", val model: String = "")

/** The kinds of [StatEvent] and what their `detail` holds. */
object Events {
    /** A message went through a flow (counted once; a redo is not a new run). */
    const val FLOW = "flow"
    /** The controller's verdict: detail `done` or `redo`. */
    const val CONTROL = "control"
    /** The worker was sent back (by the controller or a failing check). */
    const val REDO = "redo"
    /** The controller still wanted a redo but the limit was used up. */
    const val REDO_LIMIT = "redo-limit"
    /** The flow's check command: detail `pass` or `fail`. */
    const val CHECK = "check"
    /** `/goal`: detail `set`, `met` or `stopped` (turn limit). */
    const val GOAL = "goal"
    /** The router sent a message to another model: detail `image` or `code`, model = where it went. */
    const val ROUTE = "route"
    /** The chat model consulted a specialist: detail = the stage, model = who answered. */
    const val CONSULT = "consult"
    /** The judge's verdict on an opinion: detail `agree`, `partly` or `disagree`. */
    const val VERDICT = "verdict"
    /** A second opinion was given: detail `auto`, `flow` or `manual`. */
    const val OPINION = "opinion"
    /** The user cancelled a reply or a helper. */
    const val CANCEL = "cancel"
    /** The chat was compacted: detail `auto` or `manual`. */
    const val COMPACT = "compact"
    /** The memory tool: detail `duplicate` (not added) or `refused` (dead note path). */
    const val MEMORY = "memory"
    /** A file or note the tools wrote: detail = what happened (`created`, `edited`, …). */
    const val ARTIFACT = "artifact"
}

/** Events appended as JSON lines to `events.jsonl` next to the settings, like [UsageStore] does for requests. */
class EventStore(val path: Path = defaultPath()) {
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): List<StatEvent> {
        if (!Files.exists(path)) return emptyList()
        return runCatching {
            Files.readAllLines(path).mapNotNull { line -> line.takeIf { it.isNotBlank() }?.let { runCatching { json.decodeFromString<StatEvent>(it) }.getOrNull() } }
        }.getOrDefault(emptyList())
    }

    fun append(event: StatEvent) {
        Files.createDirectories(path.parent)
        Files.writeString(path, json.encodeToString(event) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }

    fun clear() {
        Files.deleteIfExists(path)
    }

    companion object {
        fun defaultPath(): Path = ConfigStore.defaultPath().resolveSibling("events.jsonl")
    }
}

/** One model's row on the Team page. */
data class ModelRow(val model: String, val totals: UsageTotals)

/** Everything the Usage screen's Team page shows, worked out from the usage records and the events of one period. */
data class TeamStats(
    val chatTokens: Long, val helperTokens: Long, val chatCost: Double, val helperCost: Double,
    /** Tokens per helper feature (Oracul, Analyst, Planner, Controller, Judge), only those used. */
    val helpers: List<Pair<String, UsageTotals>>,
    val models: List<ModelRow>,
    val flowRuns: Int, val flowFirstTime: Int, val redos: Int, val redoLimits: Int, val checksPassed: Int, val checksFailed: Int,
    val opinions: Map<String, Int>, val verdicts: Map<String, Int>,
    val goalsSet: Int, val goalsMet: Int, val goalsStopped: Int,
    val routes: List<Pair<String, Int>>, val consults: List<Pair<String, Int>>,
    val cancels: Int, val compacts: Int, val memoryDuplicates: Int, val memoryRefused: Int, val artifacts: List<Pair<String, Int>>,
) {
    val helperShare: Double get() = (chatTokens + helperTokens).let { if (it == 0L) 0.0 else helperTokens.toDouble() / it }
    val helperCostShare: Double get() = (chatCost + helperCost).let { if (it <= 0.0) 0.0 else helperCost / it }

    /** Of the verdicts on opinions, how many said the opinion was right (agree counts 1, partly a half). */
    val opinionsConfirmed: Double? get() = verdicts.values.sum().takeIf { it > 0 }?.let { ((verdicts["agree"] ?: 0) + 0.5 * (verdicts["partly"] ?: 0)) / it }
    val flowFirstTimeShare: Double? get() = if (flowRuns == 0) null else flowFirstTime.toDouble() / flowRuns
    val isEmpty: Boolean get() = chatTokens + helperTokens == 0L && flowRuns == 0 && goalsSet == 0 && routes.isEmpty() && consults.isEmpty() && verdicts.isEmpty() && cancels == 0 && compacts == 0 && artifacts.isEmpty()
}

object TeamStatsBuilder {
    /** The features that run on a model other than the chat's, to compare with what the chat model itself spent. */
    val HELPERS = listOf("Oracul", "Analyst", "Planner", "Controller", "Judge")

    fun build(records: List<UsageRecord>, events: List<StatEvent>, priceIn: Int, priceOut: Int): TeamStats {
        val chat = records.filter { it.feature == "Chat" }
        val helpers = records.filter { it.feature in HELPERS }
        val byFeature = UsageStats.by(helpers) { it.feature }.toMap()
        fun count(kind: String, detail: String? = null) = events.count { it.kind == kind && (detail == null || it.detail == detail) }
        fun counts(kind: String, key: (StatEvent) -> String) = events.filter { it.kind == kind }.groupingBy(key).eachCount().entries.sortedByDescending { it.value }.map { it.key to it.value }
        // a run is a FLOW event and what follows it up to the next one: accepted the first time when the first verdict is "done" and nothing was redone before it
        var runs = 0; var first = 0
        var open = false; var decided = false; var redone = false
        for (e in events.sortedBy { it.time }) {
            when (e.kind) {
                Events.FLOW -> { runs++; open = true; decided = false; redone = false }
                Events.REDO -> if (open) redone = true
                Events.CONTROL -> if (open && !decided) { decided = true; if (e.detail == "done" && !redone) first++ }
                Events.CHECK -> if (open && !decided && e.detail == "fail") redone = true
            }
        }
        return TeamStats(
            chatTokens = chat.sumOf { it.totalTokens.toLong() }, helperTokens = helpers.sumOf { it.totalTokens.toLong() },
            chatCost = UsageStats.estimatedCost(chat, priceIn, priceOut), helperCost = UsageStats.estimatedCost(helpers, priceIn, priceOut),
            helpers = HELPERS.mapNotNull { name -> byFeature[name]?.let { name to it } },
            models = UsageStats.by(records) { it.model }.map { ModelRow(it.first, it.second) }.sortedByDescending { it.totals.totalTokens },
            flowRuns = runs, flowFirstTime = first, redos = count(Events.REDO), redoLimits = count(Events.REDO_LIMIT),
            checksPassed = count(Events.CHECK, "pass"), checksFailed = count(Events.CHECK, "fail"),
            opinions = events.filter { it.kind == Events.OPINION }.groupingBy { it.detail }.eachCount(),
            verdicts = events.filter { it.kind == Events.VERDICT }.groupingBy { it.detail }.eachCount(),
            goalsSet = count(Events.GOAL, "set"), goalsMet = count(Events.GOAL, "met"), goalsStopped = count(Events.GOAL, "stopped"),
            routes = counts(Events.ROUTE) { "${it.detail} → ${it.model}" }, consults = counts(Events.CONSULT) { "${it.detail} (${it.model})" },
            cancels = count(Events.CANCEL), compacts = count(Events.COMPACT),
            memoryDuplicates = count(Events.MEMORY, "duplicate"), memoryRefused = count(Events.MEMORY, "refused"),
            artifacts = counts(Events.ARTIFACT) { it.detail },
        )
    }

    fun since(events: List<StatEvent>, fromMillis: Long) = events.filter { it.time >= fromMillis }
}
