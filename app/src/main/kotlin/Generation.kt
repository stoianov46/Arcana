package com.arcana.brain.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.arcana.brain.core.ChatChunk
import com.arcana.brain.core.ChatMessage
import com.arcana.brain.core.ReplyStats
import com.arcana.brain.core.ToolCall
import kotlinx.serialization.json.JsonArray
import com.arcana.brain.core.OllamaClient
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class GenState { Idle, Running, Done, Failed, Cancelled }

/** Streams one model reply into observable state. */
class Generation(
    private val scope: CoroutineScope,
    private val client: OllamaClient,
    private val onBusy: (Boolean) -> Unit = {},
    private val onUsage: (ChatChunk) -> Unit = {},
    /** Called with the finished reply and its stats, right after it ends (not for a cancelled or failed one). */
    private val onComplete: (String, ReplyStats?) -> Unit = { _, _ -> },
    /**
     * Called when the model asks for tools: with the text it wrote first, the calls and the stats of that round. It runs
     * them and returns the messages to add to the conversation (the model's request, then one result per call); the
     * model is then asked again.
     */
    private val onToolRound: (suspend (String, List<ToolCall>, ReplyStats?) -> List<ChatMessage>)? = null,
) {
    var state by mutableStateOf(GenState.Idle)
        private set
    var text by mutableStateOf("")
        private set
    var thinking by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** Tokens and timing of the finished reply (null until it ends, or when nothing was reported). */
    var stats by mutableStateOf<ReplyStats?>(null)
        private set

    /** When the current request started, and how many pieces of it (about one token each) have streamed in so far. */
    var startedAt = 0L
        private set
    var streamed by mutableIntStateOf(0)
        private set
    private var job: Job? = null

    /**
     * [prepare] runs off the UI thread first, so it can read files or search the vault. With [tools] the model may ask for
     * them: each round of tool use goes through [onToolRound], up to [maxSteps] rounds, after which it must answer.
     */
    fun start(json: Boolean = false, tools: JsonArray? = null, maxSteps: Int = 0, prepare: suspend () -> List<ChatMessage>) {
        job?.cancel()
        text = ""
        error = null
        stats = null
        streamed = 0
        startedAt = System.currentTimeMillis()
        thinking = false
        transition(GenState.Running)
        job = scope.launch {
            try {
                var messages = withContext(Dispatchers.IO) { prepare() }
                var step = 0
                while (true) {
                    val buffer = StringBuilder()
                    val calls = ArrayList<ToolCall>()
                    val offered = if (onToolRound != null && step < maxSteps) tools else null
                    client.chat(messages, json, offered).collect { chunk ->
                        if (chunk.done && (chunk.promptTokens > 0 || chunk.completionTokens > 0)) onUsage(chunk)
                        if (chunk.content.isNotEmpty() || chunk.thinking.isNotEmpty()) streamed++
                        calls += chunk.toolCalls
                        if (chunk.done) {
                            // Ollama's own counts when it sent them; otherwise what we measured ourselves
                            stats = chunk.toStats() ?: ReplyStats(0, streamed, System.currentTimeMillis() - startedAt)
                        }
                        if (chunk.content.isNotEmpty()) {
                            buffer.append(chunk.content)
                            text = buffer.toString()
                            thinking = false
                        } else if (chunk.thinking.isNotEmpty()) {
                            thinking = true
                        }
                    }
                    if (calls.isNotEmpty() && onToolRound != null && offered != null) {
                        step++
                        val round = stats
                        messages = messages + onToolRound.invoke(buffer.toString().trim(), calls.toList(), round)
                        // the next round starts with a clean slate: what the model said before its tools is now in the chat
                        text = ""
                        stats = null
                        continue
                    }
                    if (buffer.isBlank()) {
                        error = "The model returned an empty response."
                        transition(GenState.Failed)
                    } else {
                        transition(GenState.Done)
                        onComplete(buffer.toString(), stats)
                    }
                    break
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: e.toString()
                transition(GenState.Failed)
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        if (state == GenState.Running) transition(GenState.Cancelled)
    }

    /** Every state change goes through here, so [onBusy] fires exactly when a reply starts or stops. */
    private fun transition(to: GenState) {
        if (state != GenState.Running && to == GenState.Running) onBusy(true)
        if (state == GenState.Running && to != GenState.Running) onBusy(false)
        state = to
    }

    /** The reply has been taken (stored elsewhere): back to idle without cancelling anything. */
    fun consume() {
        text = ""
        stats = null
        transition(GenState.Idle)
    }

    fun reset() {
        cancel()
        transition(GenState.Idle)
        text = ""
        error = null
    }
}

@Composable
fun rememberGeneration(app: AppContext, feature: String): Generation {
    val scope = rememberCoroutineScope()
    val generation = remember {
        Generation(
            scope, app.client,
            onBusy = { busy -> app.busy = (app.busy + if (busy) 1 else -1).coerceAtLeast(0) },
            onUsage = { chunk -> app.recordUsage(feature, chunk) },
        )
    }
    // Leaving the screen cancels the reply, so the logo must not keep animating for it.
    DisposableEffect(generation) { onDispose { generation.cancel() } }
    return generation
}
