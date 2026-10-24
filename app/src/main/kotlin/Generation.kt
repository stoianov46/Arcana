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

    /** [prepare] runs off the UI thread first, so it can read files or search the vault. */
    fun start(json: Boolean = false, prepare: suspend () -> List<ChatMessage>) {
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
                val messages = withContext(Dispatchers.IO) { prepare() }
                val buffer = StringBuilder()
                client.chat(messages, json).collect { chunk ->
                    if (chunk.done && (chunk.promptTokens > 0 || chunk.completionTokens > 0)) onUsage(chunk)
                    if (chunk.content.isNotEmpty() || chunk.thinking.isNotEmpty()) streamed++
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
                if (buffer.isBlank()) {
                    error = "The model returned an empty response."
                    transition(GenState.Failed)
                } else {
                    transition(GenState.Done)
                    onComplete(buffer.toString(), stats)
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
