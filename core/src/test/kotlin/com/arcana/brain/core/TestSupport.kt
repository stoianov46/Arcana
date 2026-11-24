package com.arcana.brain.core

/** Helpers shared by the core test classes. */

/** A tool call named [name] with string arguments, as the model would send it. */
internal fun call(name: String, vararg args: Pair<String, String>) =
    ToolCall(ToolFunction(name, kotlinx.serialization.json.JsonObject(args.associate { it.first to kotlinx.serialization.json.JsonPrimitive(it.second) })))
