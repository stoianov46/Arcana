package com.arcana.brain.core

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One message of a conversation. [images] are base64-encoded pictures sent to a model that can see; they are kept in
 * memory only. What is saved to disk is [imageNames], so a stored chat stays small and still shows what was attached.
 */
@Serializable
data class ChatMessage(
    val role: String,
    val content: String,
    val images: List<String> = emptyList(),
    val imageNames: List<String> = emptyList(),
    /** For a reply: its token counts and timing. */
    val stats: ReplyStats? = null,
    /** For a message of yours: the vault paths of the notes attached to it. Their text is read from the vault when the message is sent. */
    val noteNames: List<String> = emptyList(),
    /** For a reply: the vault paths of the notes it was answered from. */
    val sources: List<String> = emptyList(),
    /** For a reply: which of [sources] were shown to the model only in part (the note is longer than *Chars per note*). */
    val sourcesCut: List<String> = emptyList(),
    /** For a message of yours: the text files attached to it (their text is sent with the message and kept in the chat). */
    val files: List<FileAttachment> = emptyList(),
    /** For a reply: the tools the model asked to use (its text, if any, is [content]). */
    @SerialName("tool_calls") val toolCalls: List<ToolCall> = emptyList(),
    /** For a `tool` message: the tool whose result [content] is. */
    @SerialName("tool_name") val toolName: String? = null,
    /** For a reply: what a reasoning model thought before it answered. Kept with the chat to be shown; never sent back to the model. */
    val thinking: String = "",
) {
    companion object {
        fun system(content: String) = ChatMessage("system", content)
        fun user(content: String, images: List<ImageAttachment> = emptyList(), notes: List<String> = emptyList(), files: List<FileAttachment> = emptyList()) =
            ChatMessage("user", content, images.map { it.base64 }, images.map { it.name }, noteNames = notes, files = files)
        fun assistant(content: String, stats: ReplyStats? = null, sources: List<String> = emptyList(), thinking: String = "", sourcesCut: List<String> = emptyList()) =
            ChatMessage("assistant", content, stats = stats, sources = sources, sourcesCut = sourcesCut, thinking = thinking)
        fun assistantCalls(content: String, calls: List<ToolCall>, stats: ReplyStats? = null, thinking: String = "") =
            ChatMessage("assistant", content, stats = stats, toolCalls = calls, thinking = thinking)
        fun toolResult(name: String, content: String) = ChatMessage("tool", content, toolName = name)
    }

    /** The same message with its text files written into its text, which is how the model receives them. */
    fun withFilesInlined() = if (files.isEmpty()) this else copy(content = content + files.joinToString("") { "\n\n---\n" + TextFiles.block(it) }, files = emptyList())

    /** The same message without the picture data, as it is stored on disk. */
    fun withoutImageData() = if (images.isEmpty()) this else copy(images = emptyList())
}

/** One streamed piece of a reply. The last one ([done]) also carries Ollama's token counts and timings. */
data class ChatChunk(
    val content: String = "",
    val thinking: String = "",
    val done: Boolean = false,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    /** Wall-clock time of the whole request as Ollama measured it, in milliseconds. */
    val totalMillis: Long = 0,
    /** Time spent generating the reply itself (without loading the model or reading the prompt), in milliseconds. */
    val evalMillis: Long = 0,
    /** Tools the model asked for (usually in the last chunk). */
    val toolCalls: List<ToolCall> = emptyList(),
) {
    /** What to show under a finished reply; null when Ollama reported no token counts. */
    fun toStats(): ReplyStats? =
        if (promptTokens > 0 || completionTokens > 0) ReplyStats(promptTokens, completionTokens, totalMillis, evalMillis) else null
}

/** What one reply cost: tokens in and out, and how long it took. Kept with the message, so a saved chat still shows it. */
@Serializable
data class ReplyStats(val promptTokens: Int, val completionTokens: Int, val totalMillis: Long, val evalMillis: Long = 0) {
    /** Generated tokens per second, from the time spent generating (or the whole time when that is unknown); 0 when unknown. */
    val tokensPerSecond: Double
        get() = (if (evalMillis > 0) evalMillis else totalMillis).let { if (it <= 0) 0.0 else completionTokens * 1000.0 / it }

    val seconds: Double get() = totalMillis / 1000.0
}

/** A model Ollama has loaded, from `/api/ps`: its [sizeBytes] (with the context cache), the part in graphics memory ([vramBytes]) and the context it was loaded with. */
data class LoadedModel(val name: String, val sizeBytes: Long, val vramBytes: Long, val context: Int) {
    /** The share that runs on the CPU, in percent. */
    val cpuPercent: Int get() = if (sizeBytes <= 0) 0 else ((sizeBytes - vramBytes).coerceAtLeast(0) * 100 / sizeBytes).toInt()
}

/** A loaded model that runs partly on the CPU: [cpuPercent] of its [sizeBytes] (the model with its context cache) did not fit in the graphics memory. */
data class Offload(val model: String, val cpuPercent: Int, val sizeBytes: Long)

/** An installed model: what it can do and how big it is, as Ollama reports it. */
data class ModelDetails(val name: String, val sizeBytes: Long, val capabilities: List<String>, val contextLength: Int) {
    /** `19 GB · tools · vision · thinking`, or just the size when Ollama named no capabilities. */
    fun summary(): String {
        val size = if (sizeBytes <= 0) "" else if (sizeBytes >= 1_000_000_000) "%.1f GB".format(sizeBytes / 1e9).replace(".0 GB", " GB") else "${sizeBytes / 1_000_000} MB"
        val can = listOf("tools", "vision", "thinking").filter { it in capabilities }
        return buildList {
            if (size.isNotEmpty()) add(size)
            addAll(can)
            if (capabilities.isNotEmpty() && "tools" !in capabilities) add("no tools")
        }.joinToString(" · ")
    }
}

/** What `/api/show` says about a model: its abilities, its context, its experts, and the values its `think` accepts (`true`, `false`, `low`…; empty when it cannot think or Ollama does not say). */
data class ModelShow(val capabilities: List<String>, val contextLength: Int, val experts: Int, val thinkingValues: List<String>, val thinkingDefault: String = "", val kvBytesPerToken: Long = 0)

class OllamaException(message: String, cause: Throwable? = null) : Exception(message, cause)

@Serializable
private data class WireChunk(
    val message: WireMessage? = null,
    val done: Boolean = false,
    val error: String? = null,
    @SerialName("prompt_eval_count") val promptEvalCount: Int = 0,
    @SerialName("eval_count") val evalCount: Int = 0,
    @SerialName("total_duration") val totalDuration: Long = 0,
    @SerialName("eval_duration") val evalDuration: Long = 0,
)

@Serializable
private data class WireMessage(val content: String = "", val thinking: String = "", @SerialName("tool_calls") val toolCalls: List<ToolCall> = emptyList())

@Serializable
private data class WireShow(val capabilities: List<String> = emptyList(), @SerialName("model_info") val modelInfo: JsonObject = JsonObject(emptyMap()), val thinking: JsonObject? = null)

@Serializable
private data class WireTags(val models: List<WireModel> = emptyList())

@Serializable
private data class WireModel(@SerialName("name") val name: String, val size: Long = 0)

object OllamaProtocol {
    private val json = Json { ignoreUnknownKeys = true }

    /** [jsonMode] asks Ollama to constrain the reply to valid JSON (used when the app needs to read the answer). */
    fun chatRequest(config: AppConfig, messages: List<ChatMessage>, jsonMode: Boolean = false, tools: JsonArray? = null, think: kotlinx.serialization.json.JsonElement? = null, repeatPenalty: Double? = null): JsonObject = buildJsonObject {
        put("model", config.model)
        put("stream", true)
        if (think != null) put("think", think)
        if (jsonMode) put("format", "json")
        if (tools != null && tools.isNotEmpty()) put("tools", tools)
        put("messages", json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(ChatMessage.serializer()), messages.map { it.withFilesInlined().copy(thinking = "") }))
        put("options", buildJsonObject {
            put("temperature", config.temperature)
            if (config.contextTokens > 0) put("num_ctx", config.contextTokens)
            if (repeatPenalty != null) put("repeat_penalty", repeatPenalty)
        })
    }

    fun parseChunk(line: String): ChatChunk {
        val chunk = json.decodeFromString<WireChunk>(line)
        chunk.error?.let { throw OllamaException(it) }
        return ChatChunk(
            content = chunk.message?.content.orEmpty(),
            thinking = chunk.message?.thinking.orEmpty(),
            done = chunk.done,
            promptTokens = chunk.promptEvalCount,
            completionTokens = chunk.evalCount,
            totalMillis = chunk.totalDuration / 1_000_000,
            evalMillis = chunk.evalDuration / 1_000_000,
            toolCalls = chunk.message?.toolCalls.orEmpty(),
        )
    }

    fun parseCapabilities(body: String): List<String> =
        runCatching { json.decodeFromString<WireShow>(body).capabilities }.getOrDefault(emptyList())

    /** The values `think` accepts for the model, from `thinking.values` (`true`, `false`, `low`…) and the default one; empty when the model does not list any. */
    fun parseThinking(body: String): Pair<List<String>, String> = runCatching {
        val t = json.decodeFromString<WireShow>(body).thinking ?: return emptyList<String>() to ""
        val values = (t["values"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }.orEmpty()
        values to ((t["default"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "")
    }.getOrDefault(emptyList<String>() to "")

    /** The `think` value a setting asks for ("off", "on", a level; "default" asks for none), or null. */
    fun thinkValue(setting: String): kotlinx.serialization.json.JsonElement? = when (setting.lowercase()) {
        "", "default" -> null
        "off" -> kotlinx.serialization.json.JsonPrimitive(false)
        "on" -> kotlinx.serialization.json.JsonPrimitive(true)
        else -> kotlinx.serialization.json.JsonPrimitive(setting.lowercase())
    }

    /** How many experts a mixture-of-experts model has, from `model_info` (`<architecture>.expert_count`); 0 for a dense model or when it does not say. */
    fun parseExperts(body: String): Int = runCatching {
        json.decodeFromString<WireShow>(body).modelInfo.entries
            .firstOrNull { it.key.endsWith(".expert_count") }?.value?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() } ?: 0
    }.getOrDefault(0)

    /** The longest context the model supports, from `model_info` (`<architecture>.context_length`); 0 when it does not say. */
    fun parseContextLength(body: String): Int = runCatching {
        json.decodeFromString<WireShow>(body).modelInfo.entries
            .firstOrNull { it.key.endsWith(".context_length") }?.value?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() }?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: 0
    }.getOrDefault(0)

    /**
     * What one token of context costs in memory, from `model_info` (the cache keeps a key and a value per layer, per key/value head, 2 bytes each): 0 when the model does not say.
     * An estimate: a model with some layers that keep no cache (a hybrid) needs less than this says.
     */
    fun parseKvBytesPerToken(body: String): Long = runCatching {
        val info = json.decodeFromString<WireShow>(body).modelInfo
        fun num(key: String) = (info[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull()?.toLong() ?: 0L
        val arch = (info["general.architecture"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return@runCatching 0L
        val layers = num("$arch.block_count")
        val heads = num("$arch.attention.head_count")
        val kvHeads = num("$arch.attention.head_count_kv").takeIf { it > 0 } ?: heads
        val headDim = num("$arch.attention.key_length").takeIf { it > 0 } ?: if (heads > 0) num("$arch.embedding_length") / heads else 0L
        if (layers <= 0 || kvHeads <= 0 || headDim <= 0) 0L else 2L * layers * kvHeads * headDim * 2
    }.getOrDefault(0L)

    /** The context window Ollama has loaded [model] with, from `/api/ps` (`context_length`); 0 when the model is not loaded or Ollama does not say. */
    fun parseLoadedContext(body: String, model: String): Int = runCatching {
        val models = (json.parseToJsonElement(body) as? kotlinx.serialization.json.JsonObject)?.get("models") as? kotlinx.serialization.json.JsonArray ?: return 0
        models.mapNotNull { it as? kotlinx.serialization.json.JsonObject }
            .firstOrNull { m -> listOf("name", "model").any { (m[it] as? kotlinx.serialization.json.JsonPrimitive)?.content == model } }
            ?.get("context_length")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() } ?: 0
    }.getOrDefault(0)

    /**
     * The loaded models that do not fit in the graphics memory, from `/api/ps` (`size` is the whole model with its cache, `size_vram` the part on the GPU): the rest runs on the CPU, which
     * is several times slower. Only models with at least [minPercent] % on the CPU are listed, biggest share first.
     */
    fun parseOffloads(body: String, minPercent: Int = 10): List<Offload> = runCatching {
        val models = (json.parseToJsonElement(body) as? kotlinx.serialization.json.JsonObject)?.get("models") as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        models.mapNotNull { it as? kotlinx.serialization.json.JsonObject }.mapNotNull { m ->
            fun long(key: String) = (m[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() ?: 0L
            val size = long("size")
            val onGpu = long("size_vram")
            val name = listOf("name", "model").firstNotNullOfOrNull { (m[it] as? kotlinx.serialization.json.JsonPrimitive)?.content } ?: return@mapNotNull null
            if (size <= 0) null else Offload(name, ((size - onGpu).coerceAtLeast(0) * 100 / size).toInt(), size)
        }.filter { it.cpuPercent >= minPercent }.sortedByDescending { it.cpuPercent }
    }.getOrDefault(emptyList())

    /** Every model Ollama has loaded, from `/api/ps`; empty when none or when the answer cannot be read. */
    fun parseLoaded(body: String): List<LoadedModel> = runCatching {
        val models = (json.parseToJsonElement(body) as? kotlinx.serialization.json.JsonObject)?.get("models") as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        models.mapNotNull { it as? kotlinx.serialization.json.JsonObject }.mapNotNull { m ->
            fun long(key: String) = (m[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() ?: 0L
            val name = listOf("name", "model").firstNotNullOfOrNull { (m[it] as? kotlinx.serialization.json.JsonPrimitive)?.content } ?: return@mapNotNull null
            LoadedModel(name, long("size"), long("size_vram"), long("context_length").toInt())
        }
    }.getOrDefault(emptyList())

    /** Installed models with their size in bytes. */
    fun parseModelSizes(body: String): List<Pair<String, Long>> =
        runCatching { json.decodeFromString<WireTags>(body).models.map { it.name to it.size } }.getOrDefault(emptyList())

    fun parseModels(body: String): List<String> =
        json.decodeFromString<WireTags>(body).models.map { it.name }.sorted()
}

class OllamaClient(private val config: () -> AppConfig) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    private val thinkingValuesCache get() = thinkingValuesByModel

    /** [repeatPenalty] (Ollama's `repeat_penalty`, 1.0 = none) is sent only for a retry after a loop; normally the model's own default stands. */
    fun chat(messages: List<ChatMessage>, jsonMode: Boolean = false, tools: JsonArray? = null, repeatPenalty: Double? = null): Flow<ChatChunk> = flow {
        val cfg = config()
        val request = HttpRequest.newBuilder(endpoint(cfg, "/api/chat"))
            .timeout(Duration.ofSeconds(cfg.timeoutSeconds.toLong()))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(OllamaProtocol.chatRequest(cfg, messages, jsonMode, tools, thinkFor(cfg), repeatPenalty).toString()))
            .build()
        val future = http.sendAsync(request, HttpResponse.BodyHandlers.ofLines())
        // Cancelling the future closes the connection, which unblocks the line reader below.
        val handle = currentCoroutineContext().job.invokeOnCompletion { future.cancel(true) }
        try {
            val response = try {
                future.get()
            } catch (e: Exception) {
                throw OllamaException("Cannot reach Ollama at ${cfg.ollamaUrl}: ${e.cause?.message ?: e.message}", e)
            }
            if (response.statusCode() != 200) {
                val body = response.body().use { it.toList().joinToString("\n") }
                throw OllamaException("Ollama returned HTTP ${response.statusCode()}: ${errorText(body)}")
            }
            response.body().use { lines ->
                for (line in lines) {
                    currentCoroutineContext().ensureActive()
                    if (line.isBlank()) continue
                    val chunk = OllamaProtocol.parseChunk(line)
                    emit(chunk)
                    if (chunk.done) break
                }
            }
        } finally {
            handle.dispose()
        }
    }.flowOn(Dispatchers.IO)

    /** What the model can do, as Ollama reports it ("completion", "vision", "tools", "thinking"…); empty when it does not say. */
    suspend fun capabilities(model: String): List<String> = show(model).capabilities

    /**
     * The `think` to send: what the setting asks for, but only when the model lists that value (a model that cannot think answers `does not support
     * thinking` to any `think`, and one with only on/off has no "low"). Null leaves it to the model.
     */
    private suspend fun thinkFor(cfg: AppConfig): kotlinx.serialization.json.JsonElement? {
        val wanted = OllamaProtocol.thinkValue(cfg.thinking) ?: return null
        val allowed = thinkingValuesCache[cfg.ollamaUrl + " " + cfg.model] ?: runCatching { show(cfg.model).thinkingValues }.getOrNull()?.also { thinkingValuesCache[cfg.ollamaUrl + " " + cfg.model] = it } ?: return null
        return wanted.takeIf { (it as kotlinx.serialization.json.JsonPrimitive).content in allowed }
    }

    /** What the model can do, how many tokens of context it supports (0 when unknown), how many experts it has (0 = dense, or unknown) and the values its `think` takes. */
    suspend fun show(model: String): ModelShow = withContext(Dispatchers.IO) {
        val cfg = config()
        val body = buildJsonObject { put("model", model) }.toString()
        val request = HttpRequest.newBuilder(endpoint(cfg, "/api/show"))
            .timeout(Duration.ofSeconds(5))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: Exception) {
            throw OllamaException("Cannot reach Ollama at ${cfg.ollamaUrl}: ${e.message}", e)
        }
        if (response.statusCode() != 200) return@withContext ModelShow(emptyList(), 0, 0, emptyList())
        val (values, default) = OllamaProtocol.parseThinking(response.body())
        ModelShow(OllamaProtocol.parseCapabilities(response.body()), OllamaProtocol.parseContextLength(response.body()), OllamaProtocol.parseExperts(response.body()), values, default, OllamaProtocol.parseKvBytesPerToken(response.body()))
    }

    /** The context window the running [model] really has (what Ollama loaded it with), or 0 when it is not loaded or this Ollama version does not say. */
    suspend fun loadedContext(model: String): Int = withContext(Dispatchers.IO) {
        val cfg = config()
        val request = HttpRequest.newBuilder(endpoint(cfg, "/api/ps")).timeout(Duration.ofSeconds(5)).GET().build()
        val response = try { http.send(request, HttpResponse.BodyHandlers.ofString()) } catch (e: Exception) { return@withContext 0 }
        if (response.statusCode() != 200) 0 else OllamaProtocol.parseLoadedContext(response.body(), model)
    }

    /** Every model Ollama has loaded right now (see [OllamaProtocol.parseLoaded]); null when Ollama cannot be asked. */
    suspend fun loadedModels(): List<LoadedModel>? = withContext(Dispatchers.IO) {
        val cfg = config()
        val request = HttpRequest.newBuilder(endpoint(cfg, "/api/ps")).timeout(Duration.ofSeconds(5)).GET().build()
        val response = try { http.send(request, HttpResponse.BodyHandlers.ofString()) } catch (e: Exception) { return@withContext null }
        if (response.statusCode() != 200) null else OllamaProtocol.parseLoaded(response.body())
    }

    /** The loaded models that run partly on the CPU (see [OllamaProtocol.parseOffloads]); empty when none, when nothing is loaded or when this Ollama does not say. */
    suspend fun offloads(): List<Offload> = withContext(Dispatchers.IO) {
        val cfg = config()
        val request = HttpRequest.newBuilder(endpoint(cfg, "/api/ps")).timeout(Duration.ofSeconds(5)).GET().build()
        val response = try { http.send(request, HttpResponse.BodyHandlers.ofString()) } catch (e: Exception) { return@withContext emptyList() }
        if (response.statusCode() != 200) emptyList() else OllamaProtocol.parseOffloads(response.body())
    }

    /** The installed models with what each can do (tools, vision, thinking) and its size, for choosing one; a model whose details cannot be read is still listed. */
    suspend fun modelDetails(): List<ModelDetails> = withContext(Dispatchers.IO) {
        val cfg = config()
        val request = HttpRequest.newBuilder(endpoint(cfg, "/api/tags")).timeout(Duration.ofSeconds(5)).GET().build()
        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: Exception) {
            throw OllamaException("Cannot reach Ollama at ${cfg.ollamaUrl}: ${e.message}", e)
        }
        if (response.statusCode() != 200) throw OllamaException("Ollama returned HTTP ${response.statusCode()}")
        OllamaProtocol.parseModelSizes(response.body()).map { (name, size) ->
            val shown = runCatching { show(name) }.getOrNull()
            ModelDetails(name, size, shown?.capabilities.orEmpty(), shown?.contextLength ?: 0)
        }
    }

    /** The installed models and their sizes in bytes (one cheap request, no per-model details), for the memory advice. */
    suspend fun modelSizes(): Map<String, Long> = withContext(Dispatchers.IO) {
        val cfg = config()
        val request = HttpRequest.newBuilder(endpoint(cfg, "/api/tags")).timeout(Duration.ofSeconds(5)).GET().build()
        val response = try { http.send(request, HttpResponse.BodyHandlers.ofString()) } catch (e: Exception) { return@withContext emptyMap() }
        if (response.statusCode() != 200) emptyMap() else OllamaProtocol.parseModelSizes(response.body()).toMap()
    }

    suspend fun listModels(): List<String> = withContext(Dispatchers.IO) {
        val cfg = config()
        val request = HttpRequest.newBuilder(endpoint(cfg, "/api/tags"))
            .timeout(Duration.ofSeconds(5))
            .GET()
            .build()
        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: Exception) {
            throw OllamaException("Cannot reach Ollama at ${cfg.ollamaUrl}: ${e.message}", e)
        }
        if (response.statusCode() != 200) throw OllamaException("Ollama returned HTTP ${response.statusCode()}")
        OllamaProtocol.parseModels(response.body())
    }

    private fun endpoint(cfg: AppConfig, path: String): URI {
        val base = cfg.ollamaUrl.trim().trimEnd('/')
        return runCatching { URI.create(base + path) }
            .getOrElse { throw OllamaException("Invalid Ollama URL: ${cfg.ollamaUrl}") }
    }

    private fun errorText(body: String): String =
        runCatching { Json.parseToJsonElement(body).let { (it as JsonObject)["error"].toString().trim('"') } }
            .getOrDefault(body.take(200))
}

/** What each model's `think` accepts, by server and model, once asked (a model's values do not change while Arcana runs). */
private val thinkingValuesByModel = java.util.concurrent.ConcurrentHashMap<String, List<String>>()
