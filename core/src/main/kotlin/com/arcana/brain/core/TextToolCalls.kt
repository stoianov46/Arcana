package com.arcana.brain.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json

/**
 * Small models sometimes write a tool call as text (`fetch_url(url="https://…")`, or a JSON object with "name" and "arguments")
 * instead of making a real tool call, and then the loop would stop with nothing done. This finds such calls so they can be run.
 *
 * Only a call to a known tool counts, and only at the end of the reply (nothing but whitespace or a closing code fence after it),
 * so a reply that merely explains how a tool is used is never executed.
 */
object TextToolCalls {
    /** The calls found at the end of [text] and the text without them; no calls when there is none. */
    fun extract(text: String, tools: Set<String>): Pair<List<ToolCall>, String> {
        if (tools.isEmpty()) return emptyList<ToolCall>() to text
        json(text, tools)?.let { return it }
        return functionStyle(text, tools) ?: (emptyList<ToolCall>() to text)
    }

    /** ```json {"name": "read_file", "arguments": {...}} ``` or the bare object, as the whole reply or its end. */
    private fun json(text: String, tools: Set<String>): Pair<List<ToolCall>, String>? {
        val trimmed = text.trimEnd()
        val fence = Regex("""```(?:json|tool_code|tool)?\s*(\{.*})\s*```$""", RegexOption.DOT_MATCHES_ALL).find(trimmed)
        val body = fence?.groupValues?.get(1) ?: trimmed.substringAfterLast("\n\n").trim().takeIf { it.startsWith("{") && it.endsWith("}") } ?: return null
        val obj = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        val name = (obj["name"] ?: obj["tool"] ?: obj["function"]?.let { (it as? JsonObject)?.get("name") ?: it })?.let { (it as? JsonPrimitive)?.content } ?: return null
        if (name !in tools) return null
        val args = ((obj["arguments"] ?: obj["parameters"] ?: obj["args"]) as? JsonObject) ?: JsonObject(emptyMap())
        val rest = if (fence != null) trimmed.substring(0, fence.range.first) else trimmed.removeSuffix(body)
        return listOf(ToolCall(ToolFunction(name, args))) to rest.trimEnd()
    }

    /** `tool(arg="value", other=3)` on lines of their own at the end, optionally inside a code fence. */
    private fun functionStyle(text: String, tools: Set<String>): Pair<List<ToolCall>, String>? {
        var body = text.trimEnd()
        // a closing code fence after the calls belongs to them
        val closing = Regex("""\s*```$""").find(body)
        if (closing != null) body = body.substring(0, closing.range.first).trimEnd()
        val names = tools.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
        val start = Regex("""(?m)^[ \t]*($names)\(""")
        val calls = ArrayList<ToolCall>()
        var firstStart = -1
        var at = 0
        while (true) {
            val m = start.find(body, at) ?: break
            val parsed = parseArguments(body, m.range.last + 1) ?: return null
            if (firstStart < 0) firstStart = m.range.first
            calls += ToolCall(ToolFunction(m.groupValues[1], parsed.first))
            at = parsed.second
            // between calls only whitespace; after the last call nothing
            val gap = body.substring(at).let { rest -> rest.takeWhile { it.isWhitespace() }.length }
            at += gap
            if (at >= body.length) break
        }
        if (calls.isEmpty() || at < body.length) return null
        var head = body.substring(0, firstStart).trimEnd()
        // an opening fence line right before the first call goes with it
        head = head.replace(Regex("""```[a-z_]*$"""), "").trimEnd()
        return calls to head
    }

    /** The arguments after the opening parenthesis at [from] − 1, and the index after the closing one; null when it is not a well-formed call. */
    private fun parseArguments(s: String, from: Int): Pair<JsonObject, Int>? {
        val args = LinkedHashMap<String, JsonElement>()
        var i = from
        fun skip() { while (i < s.length && s[i].isWhitespace()) i++ }
        skip()
        if (i < s.length && s[i] == ')') return JsonObject(args) to i + 1
        while (i < s.length) {
            skip()
            val keyStart = i
            while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_')) i++
            if (i == keyStart) return null
            val key = s.substring(keyStart, i)
            skip()
            if (i >= s.length || (s[i] != '=' && s[i] != ':')) return null
            i++
            skip()
            val (value, next) = parseValue(s, i) ?: return null
            args[key] = value
            i = next
            skip()
            if (i >= s.length) return null
            when (s[i]) {
                ',' -> i++
                ')' -> return JsonObject(args) to i + 1
                else -> return null
            }
        }
        return null
    }

    private fun parseValue(s: String, from: Int): Pair<JsonElement, Int>? {
        if (from >= s.length) return null
        val c = s[from]
        if (c == '"' || c == '\'') return parseString(s, from)
        if (c == '[') {
            val items = ArrayList<JsonElement>()
            var i = from + 1
            while (i < s.length) {
                while (i < s.length && s[i].isWhitespace()) i++
                if (i < s.length && s[i] == ']') return JsonArray(items) to i + 1
                val (item, next) = parseValue(s, i) ?: return null
                items += item
                i = next
                while (i < s.length && s[i].isWhitespace()) i++
                if (i < s.length && s[i] == ',') i++
            }
            return null
        }
        val end = (from until s.length).firstOrNull { s[it] == ',' || s[it] == ')' || s[it].isWhitespace() } ?: s.length
        val word = s.substring(from, end)
        val value: JsonElement = when {
            word == "true" || word == "True" -> JsonPrimitive(true)
            word == "false" || word == "False" -> JsonPrimitive(false)
            word == "null" || word == "None" -> JsonNull
            word.toLongOrNull() != null -> JsonPrimitive(word.toLong())
            word.toDoubleOrNull() != null -> JsonPrimitive(word.toDouble())
            else -> return null
        }
        return value to end
    }

    private fun parseString(s: String, from: Int): Pair<JsonElement, Int>? {
        val quote = s[from]
        val out = StringBuilder()
        var i = from + 1
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\\' && i + 1 < s.length -> {
                    out.append(when (val n = s[i + 1]) { 'n' -> '\n'; 't' -> '\t'; 'r' -> '\r'; else -> n })
                    i += 2
                }
                c == quote -> return JsonPrimitive(out.toString()) to i + 1
                else -> { out.append(c); i++ }
            }
        }
        return null
    }
}
