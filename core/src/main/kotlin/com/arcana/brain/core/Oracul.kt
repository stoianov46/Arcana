package com.arcana.brain.core

import kotlinx.coroutines.flow.collect

/**
 * The Oracul: a second model that reads what the worker model answered and says what is wrong or missing (a second opinion), or answers the
 * same question itself so that a judge can compare the two (a comparison). It only advises: its text goes into the chat as a block of its
 * own, the user decides what to do with it. Models come from the settings ([AppConfig.oraculModel], [AppConfig.judgeModel]).
 * See docs/oracul.md.
 */
object Oracul {
    /** The first line of an Oracul message in the chat; `[Second opinion · qwen3:30b]` or `[Comparison · qwen3:30b vs gemma4:12b]`. */
    const val MARK = "[Second opinion"
    const val MARK_COMPARE = "[Comparison"

    /** A plan written by the skill's planner model before the worker starts: shown like an Oracul block, but it is advice for the worker, not a review. */
    const val MARK_PLAN = "[Plan"

    /** What the flow's analyst wrote before the planner: the task restated, what is unclear, what a good result looks like. Advice for the later stages, like a plan. */
    const val MARK_ANALYSIS = "[Analysis"

    /** The flow's controller deciding whether the answer is good enough: `Verdict: done` or `Verdict: redo` with what must change. */
    const val MARK_CONTROL = "[Control"

    /** The judge's verdict on a reviewer's opinion (is it right, is it worth acting on); advice, not a review. */
    const val MARK_VERDICT = "[Verdict"

    /** A `/goal` check: the goal is met, or what is still missing. */
    const val MARK_GOAL = "[Goal"

    /** What [AppConfig.oraculAuto] may be. */
    val AUTO_MODES = listOf("off", "problems", "always")

    /** The model that gives second opinions; blank = the Oracul is off. */
    fun model(config: AppConfig): String = if (config.oraculEnabled) config.oraculModel.trim() else ""

    /** [config] as the Oracul and the judge see it: asking [model], on their own server when [AppConfig.oraculUrl] is set. */
    fun serverConfig(config: AppConfig, model: String): AppConfig =
        config.oraculUrl.trim().let { if (it.isEmpty()) config.copy(model = model) else config.copy(model = model, ollamaUrl = it.trimEnd('/')) }

    /** Every model that answers a comparison: the Oracul model, then the extra ones ([AppConfig.oraculExtra], comma-separated), without blanks or repeats. Empty when the Oracul is off. */
    fun models(config: AppConfig): List<String> {
        val first = model(config).ifEmpty { return emptyList() }
        return (listOf(first) + config.oraculExtra.split(',').map { it.trim() }).filter { it.isNotEmpty() }.distinct()
    }

    /** When the Oracul speaks by itself, in one word for the status bars: `auto` (after problems), `always`, or `manual` (only on a command). */
    fun modeWord(config: AppConfig): String = when (config.oraculAuto) { "always" -> "always"; "problems" -> "auto"; else -> "manual" }

    /**
     * What the status bars show while the Oracul is idle: `Ψ qwen3:30b ⚖ gemma4:12b · auto` (the judge only when it is another model than the
     * Oracul; [plain] drops the glyphs). Empty when no Oracul model is set and no flow is on; a flow adds `⇉ careful`.
     */
    fun statusText(config: AppConfig, plain: Boolean = false): String {
        val flow = Flow.of(config)?.let { " ${if (plain) "flow" else "⇉"} ${it.name.lowercase()}" }.orEmpty()
        val model = model(config).ifEmpty { return flow.trim() }
        val judge = judge(config).takeIf { it != model }?.let { " ${if (plain) "judge" else "⚖"} $it" }.orEmpty()
        return "${if (plain) "oracul" else GLYPH} $model$judge · ${modeWord(config)}$flow"
    }

    /** How the Oracul is set, for `chat_stats`: models, server, trigger; empty when it is off. */
    fun setupLine(config: AppConfig): String {
        val model = model(config).ifEmpty { return "" }
        val more = models(config).drop(1)
        return buildString {
            append("model $model")
            if (more.isNotEmpty()) append(", more ${more.joinToString()}")
            judge(config).takeIf { it != model }?.let { append(", judge $it") }
            if (config.oraculUrl.isNotBlank()) append(", server ${config.oraculUrl.trim()}")
            append(", automatic: ${config.oraculAuto}, tools: ${if (config.oraculTools) "on" else "off"}")
        }
    }

    /** The model that judges a comparison: its own setting, else the Oracul model. */
    fun judge(config: AppConfig): String = if (config.oraculEnabled) config.judgeModel.trim().ifEmpty { model(config) } else ""

    /** Why there is no Oracul model: switched off (the master switch), or not chosen yet. */
    fun missingModelMessage(config: AppConfig): String =
        if (!config.oraculEnabled) "Helper models are switched off: turn them on with /helpers (Settings → Helpers → Helper models)." else "Choose an Oracul model first: Settings → Helpers → Model."

    fun isOpinion(message: ChatMessage): Boolean = message.role == "user" && (message.content.startsWith(MARK) || message.content.startsWith(MARK_COMPARE) || message.content.startsWith(MARK_PLAN) || message.content.startsWith(MARK_ANALYSIS) || message.content.startsWith(MARK_CONTROL) || message.content.startsWith(MARK_VERDICT) || message.content.startsWith(MARK_GOAL))

    fun isPlan(message: ChatMessage): Boolean = message.role == "user" && message.content.startsWith(MARK_PLAN)

    fun isAnalysis(message: ChatMessage): Boolean = message.role == "user" && message.content.startsWith(MARK_ANALYSIS)

    fun isControl(message: ChatMessage): Boolean = message.role == "user" && message.content.startsWith(MARK_CONTROL)

    fun isVerdict(message: ChatMessage): Boolean = message.role == "user" && message.content.startsWith(MARK_VERDICT)

    fun isGoal(message: ChatMessage): Boolean = message.role == "user" && message.content.startsWith(MARK_GOAL)

    /** A second opinion or a comparison: an Oracul block that [REVISE_PROMPT] can act on (a plan, an analysis, a control verdict, a judge verdict or a goal check is not one). */
    fun isReview(message: ChatMessage): Boolean = isOpinion(message) && !isPlan(message) && !isAnalysis(message) && !isControl(message) && !isVerdict(message) && !isGoal(message)

    /** What the worker is told about a plan, in the plan block itself. */
    private const val PLAN_INTRO = "A planner (another model) wrote this plan for the task. Follow it where it is right; if a step is wrong, say why in one line and do the right thing."
    private const val ANALYSIS_INTRO = "An analyst (another model) read the user's request before the work began. Use it to see what is really asked; if it is wrong, say why in one line and do the right thing."
    private const val VERDICT_INTRO = "A judge (another model) checked the reviewer's opinion about your last answer:"
    private const val GOAL_INTRO = "A checker (another model) compared your last answer with the goal:"
    private const val CONTROL_INTRO = "A controller (another model) checked your last answer against the request:"
    private const val REVIEW_INTRO = "A reviewer (another model) read your last answer and says:"

    /** Said to the worker after a reviewer's intro: the reviewer saw only part of the conversation, so it can be wrong about what the user meant. */
    private const val CAVEAT = "It was shown only part of the conversation: check what it says against the chat before you agree."

    fun analysisMessage(model: String, analysis: String): ChatMessage = ChatMessage.user("$MARK_ANALYSIS · $model]\n$ANALYSIS_INTRO\n\n${analysis.trim()}")

    const val ANALYSIS_INSTRUCTIONS = "You analyse a request before other assistants plan and do the work. Do not do the work and do not answer it. Write, briefly and in the language " +
        "of the request: 1) what is really asked, in one or two sentences; 2) what is unclear or missing, each with the assumption you would make; 3) constraints and things to " +
        "avoid; 4) what a good result looks like, in a way that can be checked. At most 150 words."

    fun analysisRequest(question: String, background: String = ""): List<ChatMessage> = listOf(
        ChatMessage.system(ANALYSIS_INSTRUCTIONS),
        ChatMessage.user(earlier(background) + "REQUEST:\n${question.trim().take(MAX_PART)}"),
    )

    // ---- the judge's verdict on an opinion

    fun verdictMessage(model: String, text: String): ChatMessage = ChatMessage.user("$MARK_VERDICT · $model]\n$VERDICT_INTRO\n\n${text.trim()}")

    const val VERDICT_INSTRUCTIONS = "You judge a reviewer's opinion about an answer. You are given the request, the answer and the opinion. Decide whether the opinion is right and worth acting on. " +
        "Your first line must be exactly one of: `AGREE` (the opinion is right, the answer should change), `PARTLY` (some of it is right) or `DISAGREE` (the answer was fine or the opinion is wrong). " +
        "Then say in at most 3 short lines why, and name which points are right. Keep the language of the request."

    fun verdictRequest(question: String, answer: String, opinion: String, background: String = ""): List<ChatMessage> = listOf(
        ChatMessage.system(VERDICT_INSTRUCTIONS),
        ChatMessage.user(earlier(background) + "REQUEST:\n${question.trim().take(MAX_PART)}\n\nANSWER:\n${answer.trim().take(MAX_PART)}\n\nREVIEWER'S OPINION:\n${opinion.trim().take(MAX_PART)}"),
    )

    // ---- /goal

    /** What a goal check found: [met], or [notes] saying what is still missing. */
    data class GoalCheck(val met: Boolean, val notes: String)

    fun goalMessage(model: String, check: GoalCheck): ChatMessage =
        ChatMessage.user("$MARK_GOAL · $model]\n$GOAL_INTRO\n\n${if (check.met) "Goal met." else "Goal not met yet."}" + if (check.notes.isBlank()) "" else "\n\n${check.notes.trim()}")

    const val GOAL_INSTRUCTIONS = "You check whether a goal has been reached. You are given the goal and the assistant's latest answer. Be strict about what the goal says and nothing else. " +
        "Your first line must be exactly `VERDICT: MET` or `VERDICT: NOT MET`. After NOT MET, say in at most 4 short lines what is still missing. After MET you may add one line."

    /** The check's verdict from its text: the first `VERDICT: MET|NOT MET` decides. A text without one is **not** met (the text becomes the notes): unlike the controller, a goal is never assumed reached. */
    fun parseGoal(text: String): GoalCheck {
        val match = Regex("VERDICT:\\s*(NOT MET|MET)", RegexOption.IGNORE_CASE).find(text) ?: return GoalCheck(false, text.trim())
        return GoalCheck(!match.groupValues[1].contains("NOT", ignoreCase = true), text.substring(match.range.last + 1).trimStart('.', ':', ' ').trim())
    }

    fun goalRequest(goal: String, answer: String, background: String = ""): List<ChatMessage> = listOf(
        ChatMessage.system(GOAL_INSTRUCTIONS),
        ChatMessage.user(earlier(background) + "GOAL:\n${goal.trim().take(MAX_PART)}\n\nLATEST ANSWER:\n${answer.trim().take(MAX_PART)}"),
    )

    /** The start of what the worker is told when a goal is not met; the check's notes and the goal follow. */
    const val GOAL_PROMPT = "The goal is not met yet (see the checker's notes above)."

    fun goalContinue(goal: String): String = "$GOAL_PROMPT Goal: ${goal.trim()}\nKeep working toward it: do what is missing, use your tools to check, and give the complete result."

    /** What a controller decided: [done] = the answer stands; otherwise [notes] say what must change. */
    data class Control(val done: Boolean, val notes: String)

    fun controlMessage(model: String, control: Control): ChatMessage =
        ChatMessage.user("$MARK_CONTROL · $model]\n$CONTROL_INTRO\n\nVerdict: ${if (control.done) "done" else "redo"}" + if (control.notes.isBlank()) "" else "\n\n${control.notes.trim()}")

    const val CONTROL_INSTRUCTIONS = "You are the controller of a team of assistants. You are given a request, what a good result looks like (when an analyst wrote it), an answer and what a " +
        "reviewer said about it. Decide whether the answer is good enough to give to the user. Answer DONE unless there is a real error, a missing part of the request or a claim " +
        "that was not checked; do not ask for redoing because of style. Your first line must be exactly `VERDICT: DONE` or `VERDICT: REDO`. After REDO, list in at most 5 short lines what " +
        "must change. After DONE you may add one line. Keep the language of the request."

    /** The controller's verdict from its text: the first `VERDICT: DONE|REDO` decides; a text without one counts as done, so a confused model never starts a loop. */
    fun parseControl(text: String): Control {
        val match = Regex("VERDICT:\\s*(DONE|REDO)", RegexOption.IGNORE_CASE).find(text) ?: return Control(true, text.trim())
        val notes = text.substring(match.range.last + 1).trimStart('.', ':', ' ').trim()
        return Control(match.groupValues[1].equals("DONE", ignoreCase = true), notes)
    }

    fun controlRequest(question: String, answer: String, review: String, analysis: String, background: String = "", check: String = ""): List<ChatMessage> = listOf(
        ChatMessage.system(CONTROL_INSTRUCTIONS),
        ChatMessage.user(
            earlier(background) + "REQUEST:\n${question.trim().take(MAX_PART)}" +
                (if (analysis.isBlank()) "" else "\n\nWHAT A GOOD RESULT LOOKS LIKE (analyst):\n${analysis.trim().take(MAX_PART)}") +
                "\n\nANSWER:\n${answer.trim().take(MAX_PART)}" +
                (if (review.isBlank()) "" else "\n\nWHAT THE REVIEWER SAID:\n${review.trim().take(MAX_PART)}") +
                (if (check.isBlank()) "" else "\n\nCHECK COMMAND:\n${check.trim().take(MAX_PART)}"),
        ),
    )

    /** What the worker is told when the controller wants a redo: the verdict is already in its history. */
    /** The choices of the dialog that follows an agreeing verdict (`ChatSession.askWhatNow`). */
    const val CHOICE_REVISE = "Revise the answer now"
    const val CHOICE_LEAVE = "Leave it as it is"
    const val CHOICE_ALWAYS = "Revise automatically from now on"

    /** The start of what the worker is told when it ended its turn with open steps on its own todo list; the open steps follow. */
    const val TODO_PROMPT = "You still have open steps on your todo list"

    fun todoNudge(open: List<String>): String =
        "$TODO_PROMPT: ${open.joinToString("; ")}. Finish them now, or, if one is no longer needed, mark it done or clear the list with the todo tool, and then give the final answer."

    const val REDO_PROMPT = "The controller says your last answer is not good enough yet (see its verdict above). Fix what it names, use your tools to check where you need to, " +
        "and give the complete corrected answer."

    fun planMessage(model: String, plan: String): ChatMessage = ChatMessage.user("$MARK_PLAN · $model]\n$PLAN_INTRO\n\n${plan.trim()}")

    const val PLAN_INSTRUCTIONS = "You plan work for another assistant that has tools. Read the task and the skill's instructions and write a short numbered plan " +
        "(at most 8 steps): what to look at first, what to do in which order, what to check at the end, and where the result goes. Do not do the work and do not answer " +
        "the task yourself. Keep the language of the task."

    fun planRequest(question: String, skillText: String, background: String = "", analysis: String = ""): List<ChatMessage> = listOf(
        ChatMessage.system(PLAN_INSTRUCTIONS),
        ChatMessage.user(
            earlier(background) + "TASK:\n${question.trim().take(MAX_PART)}" +
                (if (analysis.isBlank()) "" else "\n\nANALYSIS OF THE TASK (by an analyst):\n${analysis.trim().take(MAX_PART)}") +
                "\n\nSKILL INSTRUCTIONS:\n${skillText.trim().take(MAX_PART)}",
        ),
    )

    /** The recent conversation as a header for a request, so a short question ("do option C", "yes") makes sense to the model that answers it; empty when there is none. */
    private fun earlier(background: String): String =
        if (background.isBlank()) "" else "EARLIER IN THE CHAT (context only, so the question below makes sense; do not answer it):\n$background\n\n"

    /**
     * What came before the user's last message, for the requests that go to the Oracul, the judge and the planner (they otherwise see only the last question and
     * would not know what "option C" or "that file" means): the last few messages of the user and replies of the assistant, without tool output, each shortened in the
     * middle (the end of a reply, where its options or questions are, is kept), newest last: at most [maxMessages] messages of at most [perMessage] characters each (Settings → Helpers → Chat context), so the size is bounded by their product and nothing else cuts it. Empty when there is nothing before or [maxMessages] is 0.
     */
    fun background(messages: List<ChatMessage>, maxMessages: Int = 0, perMessage: Int = 3000): String {
        val at = messages.indexOfLast { isRealUser(it) }
        if (at <= 0) return ""
        val lines = ArrayList<String>()
        var total = 0
        for (m in messages.subList(0, at).asReversed()) {
            val who = when {
                Compaction.isSummary(m) -> "SUMMARY OF THE EARLIER CHAT"
                isOpinion(m) -> continue
                m.role == "user" -> "USER"
                m.role == "assistant" && m.content.isNotBlank() && m.toolCalls.isEmpty() -> "ASSISTANT"
                else -> continue
            }
            val line = "$who: ${squeeze(m.content.trim(), perMessage.coerceAtLeast(100))}"
            // the newest messages first: stop at the cap, or when the whole is too long for a model that is not the chat's (its window is unknown)
            if (lines.isNotEmpty() && total + line.length > MAX_BACKGROUND) break
            lines += line
            total += line.length
            if (maxMessages > 0 && lines.size >= maxMessages) break
        }
        return lines.asReversed().joinToString("\n\n")
    }

    /** [text] limited to [limit] characters by cutting the middle (its start and its end are what matter). */
    private fun squeeze(text: String, limit: Int): String =
        if (text.length <= limit) text else text.take(limit / 4) + "\n… (shortened) …\n" + text.takeLast(limit - limit / 4)

    /** One part of a comparison: the model's name, whether it is the main model (the answer above), and its text. */
    class Part(val name: String, val main: Boolean, val text: String)

    /** What [comparisonParts] found: the answers in order (the main model's first), the verdict and the judge's name. */
    class Comparison(val answers: List<Part>, val verdict: String, val judge: String)

    /**
     * Splits a comparison block into its answers and verdict, or null when [message] is not a comparison or does not have the expected shape
     * (an older or edited block then stays as plain text). The headings are looked for by the names in the block's title, in order, so a
     * model's own `## headings` inside its answer cannot be taken for a part.
     */
    fun comparisonParts(message: ChatMessage): Comparison? {
        if (!message.content.startsWith(MARK_COMPARE)) return null
        val title = title(message).substringAfter("· ", "")
        val judge = title.substringAfter(", judged by ", "").trim()
        val names = title.substringBefore(", judged by").split(" vs ", limit = 2).let { p -> if (p.size < 2) return null else listOf(p[0].trim()) + p[1].split(", ").map { it.trim() } }
        if (names.size < 2 || judge.isEmpty()) return null
        val body = "\n" + text(message) + "\n"
        val verdictAt = body.lastIndexOf("\n## Verdict\n").takeIf { it >= 0 } ?: return null
        val starts = ArrayList<Pair<Int, Int>>() // heading position, text start
        var from = 0
        for ((i, name) in names.withIndex()) {
            val heading = "\n## $name" + if (i == 0) " (the answer above)\n" else "\n"
            val at = body.indexOf(heading, from).takeIf { it in 0 until verdictAt } ?: return null
            starts += at to at + heading.length
            from = at + heading.length
        }
        val answers = names.mapIndexed { i, name ->
            val end = if (i + 1 < starts.size) starts[i + 1].first else verdictAt
            Part(name, i == 0, body.substring(starts[i].second, end).trim())
        }
        return Comparison(answers, body.substring(verdictAt + "\n## Verdict\n".length).trim(), judge)
    }

    /** An Oracul message as the reader sees it: without the line that is addressed to the worker model. */
    fun body(message: ChatMessage): String = text(message).removePrefix(REVIEW_INTRO).removePrefix(PLAN_INTRO).removePrefix(ANALYSIS_INTRO).removePrefix(CONTROL_INTRO).removePrefix(VERDICT_INTRO).removePrefix(GOAL_INTRO).removePrefix(STUCK_INTRO).trim().removePrefix(CAVEAT).trim()

    /** An Oracul message without its first line. */
    fun text(message: ChatMessage): String = message.content.substringAfter('\n', "").trim()

    /** The title of an Oracul message (the first line without brackets). */
    fun title(message: ChatMessage): String = message.content.substringBefore('\n').trim().removePrefix("[").removeSuffix("]")

    /**
     * An Oracul message is stored as a message of the user, so the worker model sees it in the history and can act on it when the user goes on;
     * the chat shows it as a block of its own.
     */
    fun opinionMessage(model: String, text: String): ChatMessage =
        ChatMessage.user("$MARK · $model]\n$REVIEW_INTRO $CAVEAT\n\n${text.trim()}")

    /** A comparison block: every answer under its model's name (the worker's first), then the judge's verdict. [others] are the Oracul models' answers in order. */
    fun comparisonMessage(worker: String, others: List<Pair<String, String>>, judge: String, workerAnswer: String, verdict: String): ChatMessage =
        ChatMessage.user("$MARK_COMPARE · $worker vs ${others.joinToString(", ") { it.first }}, judged by $judge]\n## $worker (the answer above)\n\n${workerAnswer.trim()}\n\n" +
            others.joinToString("") { "## ${it.first}\n\n${it.second.trim()}\n\n" } + "## Verdict\n\n${verdict.trim()}")

    const val REVIEW_INSTRUCTIONS = "You are a careful reviewer. Another assistant answered a user. Read the question and the answer and say, briefly, what is wrong, " +
        "missing, unsafe or unsupported, and what to do about it. Check numbers, names, file paths and claims; do not praise and do not repeat the answer. " +
        "If the answer is correct and complete say so in one sentence. Keep the language of the question."

    const val JUDGE_INSTRUCTIONS = "You judge answers to the same question (two or more). Say which one is best and why in at most five lines, then give the best final answer " +
        "(one of them, or a merge that keeps what is right in both) under the heading 'Best answer'. Do not invent facts that are in neither. Keep the language of the question."

    /** Added to the instructions when the Oracul has the worker's tools: check with them, and never change anything on its own initiative. */
    const val TOOLS_NOTE = " You have the same tools as the assistant. Use them to CHECK the answer (read the files, search the notes, run a harmless check) instead of guessing. " +
        "Do not change anything unless the user asked for it; if something should be fixed, say what to do. Every change, command or web access you try asks the user first."

    fun reviewRequest(question: String, answer: String, toolsUsed: List<String>, withTools: Boolean = false, background: String = ""): List<ChatMessage> = listOf(
        ChatMessage.system(REVIEW_INSTRUCTIONS + if (withTools) TOOLS_NOTE else ""),
        ChatMessage.user(earlier(background) + "QUESTION:\n${question.trim().take(MAX_PART)}\n\nANSWER:\n${answer.trim().take(MAX_PART)}" +
            if (toolsUsed.isEmpty()) "" else "\n\nTOOLS THE ANSWERING ASSISTANT USED: ${toolsUsed.distinct().joinToString()}"),
    )

    /** What a stage is asked when the main model calls it as a tool: the stage's own instructions and the brief the model wrote (it sees nothing else). */
    fun consultRequest(kind: StageKind, brief: String, background: String = ""): List<ChatMessage> = listOf(
        ChatMessage.system(when (kind) { StageKind.Analyst -> ANALYSIS_INSTRUCTIONS; StageKind.Planner -> PLAN_INSTRUCTIONS; else -> REVIEW_INSTRUCTIONS }),
        ChatMessage.user(earlier(background) + (if (kind == StageKind.Reviewer) "TO REVIEW:\n" else "TASK:\n") + brief.trim().take(MAX_PART)),
    )

    fun answerRequest(question: String, withTools: Boolean = false, background: String = ""): List<ChatMessage> = listOf(
        ChatMessage.system("Answer the user's question carefully and concisely. If you cannot know something, say so." + if (withTools) " You have tools: use them to look things up instead of guessing; do not change anything unless the user asked for it." else ""),
        ChatMessage.user(if (background.isBlank()) question.trim().take(MAX_PART) else earlier(background) + "QUESTION:\n" + question.trim().take(MAX_PART)),
    )

    /** The judge sees the question and every answer, the worker's first; [others] are the Oracul models' answers. */
    fun judgeRequest(question: String, worker: String, workerAnswer: String, others: List<Pair<String, String>>, background: String = ""): List<ChatMessage> = listOf(
        ChatMessage.system(JUDGE_INSTRUCTIONS),
        ChatMessage.user(earlier(background) + "QUESTION:\n${question.trim().take(MAX_PART)}\n\nANSWER 1 ($worker):\n${workerAnswer.trim().take(MAX_PART)}" +
            others.withIndex().joinToString("") { (i, o) -> "\n\nANSWER ${i + 2} (${o.first}):\n${o.second.trim().take(MAX_PART)}" }),
    )

    /** What the worker is told when the user applies a second opinion (`/revise`): the opinion is already in its history. */
    const val REVISE_PROMPT = "Please revise your last answer using what the reviewer said above. Fix what is really wrong (use your tools to check if you need to), " +
        "keep what is right, and say in one line where you disagree with the reviewer."

    /** Whether the chat has a second opinion that has not been answered yet: the last Oracul block comes after the last real message of the worker. */
    fun hasOpenOpinion(messages: List<ChatMessage>): Boolean {
        val at = messages.indexOfLast { isReview(it) }
        return at >= 0 && messages.subList(at + 1, messages.size).none { it.role == "assistant" || (it.role == "user" && !isOpinion(it)) }
    }

    private const val MAX_PART = 12_000

    /** The most characters of earlier chat the Oracul, the judge and the planner are shown (about 8k tokens), whatever the settings say. */
    const val MAX_BACKGROUND = 30_000

    /** What happened in the last turn (from the user's last message to now): the facts [due] decides on. */
    data class TurnFacts(val toolErrors: Int, val skippedSteps: Int, val toolNames: List<String>, val failedTurns: Int = 0)

    /** A tool result that says the call failed: an error, a timeout or a non-zero exit code (a refusal by the user is not a failure of the worker). */
    fun isFailure(message: ChatMessage): Boolean =
        message.role == "tool" && (message.content.startsWith("Error:") || message.content.startsWith("Timed out") || message.content.startsWith("exit code ") && !message.content.startsWith("exit code 0"))

    internal fun isRealUser(message: ChatMessage) = message.role == "user" && !isOpinion(message) && !Compaction.isSummary(message)

    /** The messages from the user's last real message to the end. */
    fun lastTurn(messages: List<ChatMessage>): List<ChatMessage> = messages.subList(messages.indexOfLast { isRealUser(it) }.coerceAtLeast(0), messages.size)

    /** How many turns in a row, counting back from the last one, had at least one failed tool call: the user keeps coming back and the worker keeps failing. */
    fun failedStreak(messages: List<ChatMessage>): Int {
        val starts = messages.indices.filter { isRealUser(messages[it]) }
        var streak = 0
        for ((i, start) in starts.withIndex().reversed()) {
            val end = if (i + 1 < starts.size) starts[i + 1] else messages.size
            if (messages.subList(start, end).none { isFailure(it) }) break
            streak++
        }
        return streak
    }

    fun turnFacts(messages: List<ChatMessage>, skippedSteps: Int): TurnFacts {
        val turn = lastTurn(messages)
        return TurnFacts(
            toolErrors = turn.count { isFailure(it) },
            skippedSteps = skippedSteps,
            toolNames = turn.filter { it.role == "assistant" }.flatMap { m -> m.toolCalls.map { it.function.name } },
            failedTurns = failedStreak(messages),
        )
    }

    /** How many times the Oracul should already have stepped in during this turn: one per [AppConfig.stuckCalls] failed tool calls so far (0 when that setting is 0 = never). */
    fun stuckLevel(facts: TurnFacts, config: AppConfig): Int = if (config.stuckCalls <= 0) 0 else facts.toolErrors / config.stuckCalls

    private const val STUCK_INTRO = "A reviewer (another model) read your attempts so far, which keep failing, and says:"

    /** What the worker is told in the middle of its own tool loop: the Oracul's advice on the attempts so far. A review block, so it is shown and stored like one. */
    fun stuckMessage(model: String, text: String): ChatMessage = ChatMessage.user("$MARK · $model]\n$STUCK_INTRO $CAVEAT\n\n${text.trim()}")

    const val STUCK_INSTRUCTIONS = "An assistant is trying to do what the user asked and keeps failing. Read the attempts below. Say briefly: what is really going wrong (the " +
        "cause, not the symptom), which of the attempts repeat the same mistake or cannot work, and what to do differently next, concretely. If it is stuck in a loop, " +
        "say so and name a different approach. Do not do the task yourself. Keep the language of the task."

    /** The turn's tool calls and what came back, one line each and shortened, newest last, at most about [MAX_PART] characters: what the Oracul reads to see why the worker is stuck. */
    fun attempts(turn: List<ChatMessage>): String {
        val lines = turn.mapNotNull { m ->
            when {
                m.role == "assistant" && m.toolCalls.isNotEmpty() -> m.toolCalls.joinToString("\n") { "CALL ${it.function.name} ${it.function.arguments.toString().take(300)}" }
                m.role == "assistant" && m.content.isNotBlank() -> "SAID ${m.content.trim().take(300)}"
                m.role == "tool" -> "RESULT${if (isFailure(m)) " (FAILED)" else ""}: ${m.content.trim().take(500)}"
                else -> null
            }
        }
        val all = lines.joinToString("\n")
        return if (all.length <= MAX_PART) all else "…\n" + all.takeLast(MAX_PART)
    }

    fun stuckRequest(question: String, turn: List<ChatMessage>, withTools: Boolean = false, background: String = ""): List<ChatMessage> = listOf(
        ChatMessage.system(STUCK_INSTRUCTIONS + if (withTools) TOOLS_NOTE else ""),
        ChatMessage.user(earlier(background) + "TASK:\n${question.trim().take(MAX_PART)}\n\nATTEMPTS SO FAR:\n${attempts(turn)}"),
    )

    /**
     * Whether a finished reply should get a second opinion without being asked. `off`: never; `always`: every reply; `problems`: when the
     * turn had two or more failed tool calls, or a skill had to skip a required step. The Oracul must have a model for any of it.
     */
    fun due(config: AppConfig, facts: TurnFacts): Boolean {
        if (model(config).isEmpty()) return false
        return when (config.oraculAuto) {
            "always" -> true
            "problems" -> facts.toolErrors >= 2 || facts.skippedSteps > 0 || (config.stuckTurns > 0 && facts.failedTurns >= config.stuckTurns)
            else -> false
        }
    }

    /** A closing line for an Oracul message naming the tools it used (`Checked with: read_file ×2, grep_files`); empty when it used none. */
    fun toolsLine(used: List<String>): String =
        if (used.isEmpty()) "" else "\n\n_Checked with: " + used.groupingBy { it }.eachCount().entries.joinToString(", ") { if (it.value > 1) "${it.key} ×${it.value}" else it.key } + "_"

    /** The first line of an answer the Oracul wrote after the worker failed (escalation, `/takeover`). */
    /** The Oracul's glyph: in front of its model in the status bars, its blocks and its dialogs (plain-glyph terminals get words or `*`). */
    const val GLYPH = "Ψ"

    const val TAKEOVER_MARK = "_$GLYPH "

    fun takeoverHeading(model: String): String = "$TAKEOVER_MARK$model took over this turn_\n\n"

    /** The last question and the last answer worth reviewing, or null when there is nothing to review yet. */
    fun lastExchange(messages: List<ChatMessage>): Pair<String, String>? {
        val answerAt = messages.indexOfLast { it.role == "assistant" && it.content.isNotBlank() && it.toolCalls.isEmpty() }
        if (answerAt < 0) return null
        val question = messages.subList(0, answerAt).lastOrNull { it.role == "user" && !isOpinion(it) && !Compaction.isSummary(it) && !it.content.startsWith(REDO_PROMPT) && !it.content.startsWith(TODO_PROMPT) && !it.content.startsWith(GOAL_PROMPT) && !it.content.startsWith(REVISE_PROMPT) } ?: return null
        return question.content to messages[answerAt].content
    }

    /** Sends [request] to [client] (built for the model that should answer) and returns the text; [onUsage] sees the final chunk with the token counts. */
    suspend fun ask(client: OllamaClient, request: List<ChatMessage>, onUsage: (ChatChunk) -> Unit = {}): String {
        val text = StringBuilder()
        client.chat(request).collect { chunk ->
            text.append(chunk.content)
            if (chunk.done) onUsage(chunk)
        }
        return text.toString().trim()
    }
}
