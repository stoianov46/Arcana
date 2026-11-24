package com.arcana.brain.core

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One tool call the model made: which tool, how long it took, how it ended, and how much text its result put into the conversation. */
@Serializable
data class ToolUseRecord(
    val time: Long,
    val tool: String,
    val millis: Long,
    /**
     * `ok`, `error` (the tool reported "Error: …"), `denied` (the user said no, or Arcana refused it), `unknown` (the model called a tool that does not exist) or `malformed`
     * (it wrote a call Ollama could not read, [MALFORMED] as the tool).
     */
    val outcome: String,
    /** Characters of the result that went back to the model: what the call cost the context window. */
    val resultChars: Int,
    /** The model that made the call (blank in records from before this was kept): how well each model uses the tools. */
    val model: String = "",
) {
    /** About how many tokens the result took in the context (four characters to a token is the usual rule of thumb). */
    val approxTokens: Int get() = (resultChars + 3) / 4
}

/** Tool calls, appended as JSON lines to `tool-usage.jsonl` next to the settings, like [UsageStore] does for requests. */
class ToolUseStore(val path: Path = defaultPath()) {
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): List<ToolUseRecord> {
        if (!Files.exists(path)) return emptyList()
        return runCatching {
            Files.readAllLines(path).mapNotNull { line -> line.takeIf { it.isNotBlank() }?.let { runCatching { json.decodeFromString<ToolUseRecord>(it) }.getOrNull() } }
        }.getOrDefault(emptyList())
    }

    fun append(record: ToolUseRecord) {
        Files.createDirectories(path.parent)
        Files.writeString(path, json.encodeToString(ToolUseRecord.serializer(), record) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }

    fun clear() {
        Files.deleteIfExists(path)
    }

    companion object {
        fun defaultPath(): Path = ConfigStore.defaultPath().resolveSibling("tool-usage.jsonl")
    }
}

/** What one tool did over a period. */
data class ToolTotals(val tool: String, val calls: Int, val errors: Int, val denied: Int, val millis: Long, val resultChars: Long) {
    val averageMillis: Long get() = if (calls == 0) 0 else millis / calls
    val approxTokens: Long get() = (resultChars + 3) / 4
}

/** How one model did with the tools: its calls, how many worked, failed, were denied, named a tool that does not exist, or could not be read at all. */
data class ModelToolTotals(val model: String, val calls: Int, val ok: Int, val errors: Int, val denied: Int, val unknown: Int, val malformed: Int) {
    /** The share of calls that worked, of those the model is responsible for (a denial is the user's choice). */
    val okPercent: Int get() = (calls - denied).let { if (it <= 0) 0 else ok * 100 / it }
}

/** The tool name of a record for a call the model wrote and Ollama could not read. */
const val MALFORMED = "(malformed call)"

object ToolStats {
    /** Totals per model (records with a model only), the one with the most calls first. */
    fun byModel(records: List<ToolUseRecord>): List<ModelToolTotals> =
        records.filter { it.model.isNotBlank() }.groupBy { it.model }.map { (model, list) ->
            ModelToolTotals(model, list.size, list.count { it.outcome == "ok" }, list.count { it.outcome == "error" }, list.count { it.outcome == "denied" }, list.count { it.outcome == "unknown" }, list.count { it.outcome == "malformed" })
        }.sortedWith(compareByDescending<ModelToolTotals> { it.calls }.thenBy { it.model })

    /** Totals per tool, the one that put the most text into the context first. */
    fun by(records: List<ToolUseRecord>): List<ToolTotals> =
        records.groupBy { it.tool }.map { (tool, list) ->
            ToolTotals(tool, list.size, list.count { it.outcome == "error" }, list.count { it.outcome == "denied" }, list.sumOf { it.millis }, list.sumOf { it.resultChars.toLong() })
        }.sortedWith(compareByDescending<ToolTotals> { it.approxTokens }.thenByDescending { it.calls }.thenBy { it.tool })

    fun since(records: List<ToolUseRecord>, fromMillis: Long) = records.filter { it.time >= fromMillis }
}
