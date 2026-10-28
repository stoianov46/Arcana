package com.arcana.brain.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** The pseudo-tool for a model's own task list; the chat answers it (see [Todos]) and shows the list's progress in the status bar. */
const val TODO = "todo"

val TODO_DEFINITION: JsonObject = buildJsonObject {
    put("type", "function")
    putJsonObject("function") {
        put("name", TODO)
        put(
            "description",
            "Keep a short task list for a job with three or more steps, so you and the user can see the plan and what is done. Call it with the WHOLE list each time " +
                "(it replaces the old one): one item per line, starting with [ ] (to do), [~] (doing now, at most one) or [x] (done). Call it first to plan, then again after each step to " +
                "tick it off, and carry on with the next item. Do not use it for a one-step question. An empty list clears it. It changes nothing in the vault and asks nothing.",
        )
        putJsonObject("parameters") {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("items") {
                    put("type", "string")
                    put("description", "The whole list, one item per line, for example: [x] read the note\n[~] write the summary\n[ ] save it to Reports")
                }
            }
            putJsonArray("required") { add(JsonPrimitive("items")) }
        }
    }
}

/** One line of the list: [state] is ' ' (to do), '~' (doing now) or 'x' (done). */
data class TodoItem(val state: Char, val text: String) {
    val done: Boolean get() = state == 'x'
}

object Todos {
    const val MAX_ITEMS = 20
    private const val MAX_TEXT = 120
    private val line = Regex("""^\s*(?:[-*]|\d+[.)])?\s*(?:\[\s*([^\]]?)\s*\]|\(\s*([^)]?)\s*\))?\s*(.+?)\s*$""")

    /** The list in [text]: a line may start with `[ ]`, `[~]` or `[x]` (a bullet or number before it is fine); a line without a mark is an item still to do. */
    fun parse(text: String): List<TodoItem> = text.lineSequence().filter { it.isNotBlank() }.mapNotNull { raw ->
        val m = line.matchEntire(raw) ?: return@mapNotNull null
        val mark = (m.groupValues[1] + m.groupValues[2]).trim().lowercase()
        val body = m.groupValues[3].take(MAX_TEXT)
        TodoItem(when (mark) { "x", "✓", "✔", "done" -> 'x'; "~", ">", "-", "*", "doing" -> '~'; else -> ' ' }, body)
    }.take(MAX_ITEMS).toList()

    /** The list from the latest `todo` call among [messages] (empty when there was none, or when it cleared the list). */
    fun last(messages: List<ChatMessage>): List<TodoItem> {
        val call = messages.asReversed().firstNotNullOfOrNull { m -> m.toolCalls.lastOrNull { it.name == TODO } } ?: return emptyList()
        return parse((call.arguments["items"] as? JsonPrimitive)?.content.orEmpty())
    }

    fun doneCount(items: List<TodoItem>) = items.count { it.done }

    private fun mark(item: TodoItem) = when (item.state) { 'x' -> "[x]"; '~' -> "[~]"; else -> "[ ]" }

    /** The list as the chat shows it and as the model gets it back. */
    fun render(items: List<TodoItem>): String = items.mapIndexed { i, it -> "${i + 1}. ${mark(it)} ${it.text}" }.joinToString("\n")

    /** The result of a `todo` call: the list, how far it is, and what to do next. */
    fun describe(items: List<TodoItem>): String {
        if (items.isEmpty()) return "Todo list cleared."
        val done = doneCount(items)
        val doing = items.count { it.state == '~' }
        val next = items.firstOrNull { !it.done }
        val advice = when {
            next == null -> "Every item is done: give the final answer now."
            doing > 1 -> "More than one item is marked [~]: only one should be in progress. Carry on with: ${next.text}"
            doing == 0 -> "Nothing is marked [~] yet: start with \"${next.text}\" (call todo again with it marked) or just do it."
            else -> "Carry on with: ${items.first { it.state == '~' }.text}"
        }
        return render(items) + "\n$done of ${items.size} done. $advice"
    }
}
