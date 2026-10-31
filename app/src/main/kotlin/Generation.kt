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
import com.arcana.brain.core.LoopGuard
import com.arcana.brain.core.ReplyStats
import com.arcana.brain.core.TextToolCalls
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
    /** Reports a change in the reply tokens streaming right now: positive as pieces arrive (about one token each), negative to take them back when the exact count arrives or the request ends. */
    private val onLive: (Int) -> Unit = {},
    /** Called with the finished reply and its stats, right after it ends (not for a cancelled or failed one). */
    private val onComplete: (String, ReplyStats?) -> Unit = { _, _ -> },
    /**
     * Called when the model asks for tools: with the text it wrote first, the calls and the stats of that round. It runs
     * them and returns the messages to add to the conversation (the model's request, then one result per call); the
     * model is then asked again.
     */
    private val onToolRound: (suspend (String, List<ToolCall>, ReplyStats?) -> List<ChatMessage>)? = null,
    /** Called when the step limit is reached while the model was still using tools; returns how many more steps the user allows (0 = none). */
    private val onStepLimit: (suspend (Int) -> Int)? = null,
    /** "auto", "ask" or "off": what to do with a tool call the model wrote as text (see [TextToolCalls]). */
    private val textCalls: () -> String = { "auto" },
    /** Asked in "ask" mode before such a call runs; false leaves the reply as plain text. */
    private val confirmTextCalls: (suspend (List<ToolCall>) -> Boolean)? = null,
    /**
     * Asked when the model is about to finish with a plain reply (no tool call): returns a message that sends it back to work (a skill still has
     * a required step open), or null to let it finish. The message is hidden, like the "continue now" nudge; the owner limits how often.
     */
    private val gate: (() -> String?)? = null,
    /**
     * Asked after every round of tool use, before the model is asked again: returns the request to send from now on (the chat was compacted
     * because the window is nearly full, so the request is built anew), or null to go on with the one in hand.
     */
    private val refresh: (suspend () -> List<ChatMessage>?)? = null,
    /** How often the same stretch of text may repeat inside one round before the round is stopped (see [LoopGuard]); 0 = never checked. */
    private val loopRepeats: () -> Int = { 0 },
    /** Asked before each round: true when the tools must be taken away now (the model keeps repeating calls), so it has to answer. */
    private val toolsHalted: () -> Boolean = { false },
    /** Told when a round was stopped for repeating itself (`text`), once each time. */
    private val onLoop: (String) -> Unit = {},
) {
    var state by mutableStateOf(GenState.Idle)
        private set
    var text by mutableStateOf("")
        private set
    var thinking by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** The thoughts of the round being streamed, following the stream (updated every 100 ms at most): what the Chat's thinking pane shows live. Cleared when a new reply starts. */
    var liveThinking by mutableStateOf("")
        private set
    private var liveThinkingAt = 0L

    /** What the model thought during the round that just ended; [takeThinking] hands it to whoever records the round, once. */
    private var roundThinking = ""

    /** The thoughts of the round that just ended (kept apart from the answer), cleared by reading them. */
    fun takeThinking(): String = roundThinking.also { roundThinking = "" }

    /** Tokens and timing of the finished reply (null until it ends, or when nothing was reported). */
    var stats by mutableStateOf<ReplyStats?>(null)
        private set

    /** When the current request started, and how many pieces of it (about one token each) have streamed in so far. */
    var startedAt = 0L
        private set
    var streamed by mutableIntStateOf(0)
        private set
    /** The same for the current round of a tool loop: a new round starts with no pieces while the model reads the whole conversation again (minutes on a big context). */
    var roundStartedAt = 0L
        private set
    var roundPieces by mutableIntStateOf(0)
        private set
    private var job: Job? = null
    /** The tools offered each round; a tool round may replace them (the user just turned tools on). */
    private var activeTools: JsonArray? = null

    fun useTools(tools: JsonArray?) { activeTools = tools }

    /**
     * [prepare] runs off the UI thread first, so it can read files or search the vault. With [tools] the model may ask for
     * them: each round of tool use goes through [onToolRound], up to [maxSteps] rounds, after which it must answer.
     */
    fun start(json: Boolean = false, tools: JsonArray? = null, maxSteps: Int = 0, prepare: suspend () -> List<ChatMessage>) {
        job?.cancel()
        text = ""
        liveThinking = ""
        error = null
        stats = null
        streamed = 0
        roundPieces = 0
        roundStartedAt = System.currentTimeMillis()
        startedAt = System.currentTimeMillis()
        thinking = false
        transition(GenState.Running)
        activeTools = tools
        job = scope.launch {
            try {
                var messages = withContext(Dispatchers.IO) { prepare() }
                var step = 0
                var limit = maxSteps
                var nudges = 0
                var emptyRetries = 0
                var loopRetries = 0
                while (true) {
                    val buffer = StringBuilder()
                    val thoughts = StringBuilder()
                    val calls = ArrayList<ToolCall>()
                    if (onToolRound != null && onStepLimit != null && activeTools != null && step > 0 && step >= limit) {
                        limit += onStepLimit.invoke(step)
                    }
                    val offered = if (onToolRound != null && step < limit && !toolsHalted()) activeTools else null
                    var pieces = 0
                    var liveAdded = 0
                    roundPieces = 0
                    roundStartedAt = System.currentTimeMillis()
                    var looped = false
                    var sinceCheck = 0
                    // the last stretch of each kind of text, apart from the capped copy of the thoughts: a loop must be seen however long the round already is
                    val thinkTail = StringBuilder()
                    val textTail = StringBuilder()
                    try {
                        client.chat(messages, json, offered, repeatPenalty = if (loopRetries > 0) LOOP_RETRY_PENALTY else null).collect { chunk ->
                            if (chunk.done && (chunk.promptTokens > 0 || chunk.completionTokens > 0)) {
                                onUsage(chunk)
                                // the exact figures are in now: take back the estimate that stood in for them
                                if (liveAdded != 0) { onLive(-liveAdded); liveAdded = 0 }
                            }
                            if (chunk.content.isNotEmpty() || chunk.thinking.isNotEmpty()) {
                                streamed++
                                pieces++
                                roundPieces = pieces
                                if (pieces - liveAdded >= LIVE_BATCH) { onLive(pieces - liveAdded); liveAdded = pieces }
                            }
                            calls += chunk.toolCalls
                            if (chunk.done) {
                                // Ollama's own counts when it sent them; otherwise what we measured ourselves
                                stats = chunk.toStats() ?: ReplyStats(0, streamed, System.currentTimeMillis() - startedAt)
                            }
                            if (chunk.content.isNotEmpty()) {
                                buffer.append(chunk.content)
                                text = buffer.toString()
                                thinking = false
                            }
                            if (chunk.thinking.isNotEmpty()) {
                                if (thoughts.length < MAX_THINKING_CHARS) thoughts.append(chunk.thinking)
                                if (chunk.content.isEmpty()) thinking = true
                                val now = System.currentTimeMillis()
                                if (now - liveThinkingAt >= 100) { liveThinking = thoughts.toString(); liveThinkingAt = now }
                            }
                            // the same stretch of text again and again (in the thoughts or in the answer): stop the round instead of waiting for the token limit
                            sinceCheck += chunk.content.length + chunk.thinking.length
                            thinkTail.append(chunk.thinking).trimTo(LoopGuard.WINDOW)
                            textTail.append(chunk.content).trimTo(LoopGuard.WINDOW)
                            if (sinceCheck >= LoopGuard.CHECK_EVERY && !chunk.done) {
                                sinceCheck = 0
                                val repeats = loopRepeats()
                                if (repeats > 1 && (LoopGuard.repeating(thinkTail.toString(), repeats) || LoopGuard.repeating(textTail.toString(), repeats))) { looped = true; throw LoopStop() }
                            }
                        }
                    } catch (e: LoopStop) {
                        // handled below: the stream is closed, what it wrote so far is still in the buffers
                    } finally {
                        // a cancelled or failed request never gets exact figures: do not leave its estimate in the counters
                        if (liveAdded != 0) onLive(-liveAdded)
                    }
                    if (looped) {
                        onLoop("text")
                        // it chanted its answer: keep the first round of it. It only thought in circles (or chanted again after the nudge): ask once more, out of
                        // sight, with a stronger repeat penalty; a second time the reply is an error that says so rather than a wall of repeated text
                        val answered = LoopGuard.withoutLoop(buffer.toString())
                        if (loopRetries < MAX_LOOP_RETRIES) {
                            loopRetries++
                            roundThinking = ""
                            messages = messages + (if (answered.isBlank()) emptyList() else listOf(ChatMessage.assistant(answered))) + ChatMessage.user(LoopGuard.TEXT_NUDGE)
                            text = ""
                            liveThinking = ""
                            stats = null
                            continue
                        }
                        if (answered.isBlank()) {
                            error = "The model kept repeating itself and was stopped. Ask again, more narrowly, or try another model (Ctrl+O)."
                            transition(GenState.Failed)
                        } else {
                            text = answered
                            transition(GenState.Done)
                            onComplete(answered, stats)
                        }
                        break
                    }
                    roundThinking = thoughts.toString().trim()
                    if (roundThinking.isNotEmpty()) liveThinking = roundThinking // the last chunks, which the throttle may have skipped
                    // a call the model wrote as text instead of making it: run it as the real thing
                    var roundText = buffer.toString().trim()
                    if (calls.isEmpty() && onToolRound != null && offered != null && textCalls() != "off") {
                        val names = offered.mapNotNull { ((it as? kotlinx.serialization.json.JsonObject)?.get("function") as? kotlinx.serialization.json.JsonObject)?.get("name")?.let { n -> (n as? kotlinx.serialization.json.JsonPrimitive)?.content } }.toSet()
                        val (found, rest) = TextToolCalls.extract(buffer.toString(), names)
                        if (found.isNotEmpty() && (textCalls() != "ask" || confirmTextCalls?.invoke(found) == true)) { calls += found; roundText = rest.trim() }
                    }
                    if (calls.isNotEmpty() && onToolRound != null && offered != null) {
                        step++
                        nudges = 0 // the limit is for announcements in a row: a long runbook narrates before many of its steps
                        val round = stats
                        messages = messages + onToolRound.invoke(roundText, calls.toList(), round)
                        refresh?.let { r -> withContext(Dispatchers.IO) { r() } }?.let { messages = it }
                        // the next round starts with a clean slate: what the model said before its tools is now in the chat
                        text = ""
                        stats = null
                        continue
                    }
                    // the model said what it is about to do instead of doing it: the loop would end and wait for the user, so push it on
                    if (calls.isEmpty() && onToolRound != null && offered != null && nudges < MAX_NUDGES && announcesAction(buffer.toString())) {
                        nudges++
                        messages = messages + onToolRound.invoke(buffer.toString().trim(), emptyList(), stats) + ChatMessage.user(NUDGE)
                        text = ""
                        stats = null
                        continue
                    }
                    // a skill run with a required step still open: the reply is not accepted as the end, the model is told which step is missing
                    if (calls.isEmpty() && onToolRound != null && offered != null && gate != null) {
                        val push = gate.invoke()
                        if (push != null) {
                            val said = buffer.toString().trim()
                            messages = messages + (if (said.isEmpty()) emptyList() else onToolRound.invoke(said, emptyList(), stats)) + ChatMessage.user(push)
                            text = ""
                            stats = null
                            continue
                        }
                    }
                    // some models end a round, or a finished skill, with nothing at all: ask once more, out of sight, before calling it a failure
                    if (buffer.isBlank() && calls.isEmpty() && emptyRetries < MAX_EMPTY_RETRIES) {
                        emptyRetries++
                        messages = messages + ChatMessage.user(EMPTY_NUDGE)
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
            onLive = { delta -> app.addLiveTokens(delta) },
        )
    }
    // Leaving the screen cancels the reply, so the logo must not keep animating for it.
    DisposableEffect(generation) { onDispose { generation.cancel() } }
    return generation
}

/** How many announcements in a row (without a tool call in between) are pushed on before the reply is accepted as it is. */
private const val MAX_NUDGES = 2

/** How often a round that repeated itself is asked again (with [LOOP_RETRY_PENALTY]) before the reply ends. */
private const val MAX_LOOP_RETRIES = 1

/** Ollama's `repeat_penalty` for the retry after a loop (the usual default is about 1.1). */
private const val LOOP_RETRY_PENALTY = 1.2

/** Drops the oldest characters once the builder is twice [keep] long, so it stays at about that size. */
private fun StringBuilder.trimTo(keep: Int) { if (length > keep * 2) delete(0, length - keep) }

/** Thrown inside the stream to close it when [LoopGuard] sees the text go in circles. */
private class LoopStop : RuntimeException()

/** The most thinking text kept for one reply; a reasoning model can write far more than anyone reads. */
private const val MAX_THINKING_CHARS = 20_000

/** How often an empty reply is answered with [EMPTY_NUDGE] before it counts as an error. */
private const val MAX_EMPTY_RETRIES = 1

/** The live token counters move in steps of this many streamed pieces (one recomposition per piece would be wasteful). */
private const val LIVE_BATCH = 4
private const val EMPTY_NUDGE = "Your last reply was empty. Reply now with a short answer: what you did and the result, or what you need from me."
private const val NUDGE = "Continue now: call the tool you just described. Do not describe it again, and do not ask me to confirm."

/** True when [reply] ends by announcing a next step ("Next, I will read …") rather than giving an answer or asking the user something. */
internal fun announcesAction(reply: String): Boolean {
    val last = reply.trim().takeLast(500)
    // a real question ends a sentence with "?"; the "?" of a web address (watch?v=…) is not one
    if (last.isEmpty() || Regex("""\?(\s|$)""").containsMatchIn(last)) return false
    // "I will read…", "Let me search…": a plan to act. "Let me know…", "I will be happy to…" are only manners and must not count.
    return ANNOUNCEMENT.containsMatchIn(last)
}

private val ANNOUNCEMENT = Regex(
    """(?i)\b(i will|i'll|will help me|i am going to|i'm going to|let me|next,? i|now,? i|first,? i)\s+(now\s+|first\s+|then\s+|also\s+|start\s+by\s+|begin\s+by\s+)?""" +
        """(read|search|look|check|use|call|fetch|list|open|run|find|locate|analy[sz]e|examine|start|begin|try|grep|get|inspect|scan|review|execute|investigate|explore|retrieve|query)\b""",
)
