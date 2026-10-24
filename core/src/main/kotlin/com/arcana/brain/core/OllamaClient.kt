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
    /** For a message of yours: the text files attached to it (their text is sent with the message and kept in the chat). */
    val files: List<FileAttachment> = emptyList(),
    /** For a reply: the tools the model asked to use (its text, if any, is [content]). */
    @SerialName("tool_calls") val toolCalls: List<ToolCall> = emptyList(),
    /** For a `tool` message: the tool whose result [content] is. */
    @SerialName("tool_name") val toolName: String? = null,
) {
    companion object {
        fun system(content: String) = ChatMessage("system", content)
        fun user(content: String, images: List<ImageAttachment> = emptyList(), notes: List<String> = emptyList(), files: List<FileAttachment> = emptyList()) =
            ChatMessage("user", content, images.map { it.base64 }, images.map { it.name }, noteNames = notes, files = files)
        fun assistant(content: String, stats: ReplyStats? = null, sources: List<String> = emptyList()) =
            ChatMessage("assistant", content, stats = stats, sources = sources)
        fun assistantCalls(content: String, calls: List<ToolCall>, stats: ReplyStats? = null) =
            ChatMessage("assistant", content, stats = stats, toolCalls = calls)
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
private data class WireShow(val capabilities: List<String> = emptyList())

@Serializable
private data class WireTags(val models: List<WireModel> = emptyList())

@Serializable
private data class WireModel(@SerialName("name") val name: String)

object OllamaProtocol {
    private val json = Json { ignoreUnknownKeys = true }

    /** [jsonMode] asks Ollama to constrain the reply to valid JSON (used when the app needs to read the answer). */
    fun chatRequest(config: AppConfig, messages: List<ChatMessage>, jsonMode: Boolean = false, tools: JsonArray? = null): JsonObject = buildJsonObject {
        put("model", config.model)
        put("stream", true)
        if (jsonMode) put("format", "json")
        if (tools != null && tools.isNotEmpty()) put("tools", tools)
        put("messages", json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(ChatMessage.serializer()), messages.map { it.withFilesInlined() }))
        put("options", buildJsonObject { put("temperature", config.temperature) })
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

    fun parseModels(body: String): List<String> =
        json.decodeFromString<WireTags>(body).models.map { it.name }.sorted()
}

class OllamaClient(private val config: () -> AppConfig) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    fun chat(messages: List<ChatMessage>, jsonMode: Boolean = false, tools: JsonArray? = null): Flow<ChatChunk> = flow {
        val cfg = config()
        val request = HttpRequest.newBuilder(endpoint(cfg, "/api/chat"))
            .timeout(Duration.ofSeconds(cfg.timeoutSeconds.toLong()))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(OllamaProtocol.chatRequest(cfg, messages, jsonMode, tools).toString()))
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
    suspend fun capabilities(model: String): List<String> = withContext(Dispatchers.IO) {
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
        if (response.statusCode() != 200) return@withContext emptyList()
        OllamaProtocol.parseCapabilities(response.body())
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
