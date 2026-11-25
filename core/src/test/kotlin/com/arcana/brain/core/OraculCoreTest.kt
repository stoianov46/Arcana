package com.arcana.brain.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Oracul and flows in core: system-prompt predicates, when it is due, comparisons, status text, a failing worker, its chat context, opinion and analysis blocks, the careful flow, controller and goal verdicts, system sources, consultable stages, the master switch, the router, check commands. */
class OraculCoreTest {
    @Test
    fun arcanasOwnPromptsAreSystemMessages() {
        assertTrue(Oracul.isSystemPrompt(ChatMessage.user(Oracul.todoNudge(listOf("a")))))
        assertTrue(!Oracul.isSystemPrompt(ChatMessage.user("hello")))
    }

    @Test
    fun theOraculIsDueOnlyWhenTheSettingsAndTheTurnSayso() {
        val call = ToolCall(ToolFunction("read_file", kotlinx.serialization.json.JsonObject(emptyMap())))
        val chat = listOf(
            ChatMessage.user("old question"), ChatMessage.assistant("old answer"),
            ChatMessage.user("check the files"), ChatMessage.assistantCalls("", listOf(call, call)),
            ChatMessage.toolResult("read_file", "Error: nope"), ChatMessage.toolResult("read_file", "Error: nope again"),
            ChatMessage.assistant("I could not read them."),
        )
        val facts = Oracul.turnFacts(chat, skippedSteps = 0)
        assertEquals(2, facts.toolErrors, "only the last turn counts"); assertEquals(listOf("read_file", "read_file"), facts.toolNames)
        val on = AppConfig(oraculModel = "big", oraculAuto = "problems")
        assertTrue(Oracul.due(on, facts))
        assertTrue(!Oracul.due(on, Oracul.TurnFacts(1, 0, emptyList())), "one failed call is not a problem")
        assertTrue(Oracul.due(on, Oracul.TurnFacts(0, 1, emptyList())), "a skipped skill step is")
        assertTrue(!Oracul.due(on.copy(oraculAuto = "off"), facts) && !Oracul.due(on.copy(oraculModel = ""), facts), "off, or no model: never")
        assertTrue(Oracul.due(on.copy(oraculAuto = "always"), Oracul.TurnFacts(0, 0, emptyList())))
        assertEquals("big", Oracul.model(on)); assertEquals("big", Oracul.judge(on)); assertEquals("judge", Oracul.judge(on.copy(judgeModel = " judge ")))
        // the exchange to review, and the messages that are the Oracul's own
        assertEquals("check the files" to "I could not read them.", Oracul.lastExchange(chat))
        val opinion = Oracul.opinionMessage("big", "You missed the folder.")
        assertTrue(Oracul.isOpinion(opinion) && Oracul.title(opinion) == "Second opinion · big" && "missed the folder" in Oracul.text(opinion))
        assertEquals("check the files" to "I could not read them.", Oracul.lastExchange(chat + opinion), "an opinion is not a question")
        assertEquals(null, Oracul.lastExchange(listOf(ChatMessage.user("hi"))))
        assertTrue(Oracul.isOpinion(Oracul.comparisonMessage("a", listOf("b" to "y"), "c", "x", "z")))
        // an opinion is "open" until the worker or the user has said something after it
        assertTrue(Oracul.hasOpenOpinion(chat + opinion) && !Oracul.hasOpenOpinion(chat))
        assertTrue(!Oracul.hasOpenOpinion(chat + opinion + ChatMessage.user("thanks")) && !Oracul.hasOpenOpinion(chat + opinion + ChatMessage.assistant("fixed")))
        // an opinion is no turn of the user: compaction keeps counting real turns
        assertEquals(null, Compaction.cutPoint(chat.take(2) + opinion + opinion + ChatMessage.user("q")))
        val request = Oracul.reviewRequest("Q?", "A.", listOf("grep_files", "grep_files"))
        assertEquals("system", request[0].role); assertTrue("QUESTION:\nQ?" in request[1].content && "ANSWER:\nA." in request[1].content && "grep_files" in request[1].content)
    }

    @Test
    fun aComparisonIsSplitIntoItsAnswersByTheNamesInItsTitle() {
        val message = Oracul.comparisonMessage("worker", listOf("big" to "Answer B\n\n## A heading inside the answer\nmore", "other" to "Answer C"), "judge", "Answer A", "Best is B.")
        val parts = Oracul.comparisonParts(message)!!
        assertEquals(listOf("worker", "big", "other"), parts.answers.map { it.name })
        assertEquals(listOf(true, false, false), parts.answers.map { it.main })
        assertEquals("Answer A", parts.answers[0].text)
        assertTrue("## A heading inside the answer" in parts.answers[1].text && parts.answers[1].text.endsWith("more"), "a heading inside an answer is not a new part")
        assertEquals("Answer C", parts.answers[2].text)
        assertEquals("Best is B." to "judge", parts.verdict to parts.judge)
        assertEquals(null, Oracul.comparisonParts(Oracul.opinionMessage("big", "x")), "an opinion is not a comparison")
        assertEquals(null, Oracul.comparisonParts(ChatMessage.user("${Oracul.MARK_COMPARE} · a vs b, judged by c]\nno headings at all")), "a block of another shape stays plain text")
    }

    @Test
    fun theStatusBarsShowTheOraculSetupWhileItIsIdle() {
        assertEquals("", Oracul.statusText(AppConfig()), "no Oracul model: nothing to show")
        assertEquals("Ψ big · auto", Oracul.statusText(AppConfig(oraculModel = "big")))
        assertEquals("Ψ big ⚖ judge · always", Oracul.statusText(AppConfig(oraculModel = "big", judgeModel = "judge", oraculAuto = "always")))
        assertEquals("Ψ big · manual", Oracul.statusText(AppConfig(oraculModel = "big", judgeModel = "big", oraculAuto = "off")), "a judge that is the Oracul model is not repeated")
        assertEquals("oracul big judge j · manual", Oracul.statusText(AppConfig(oraculModel = "big", judgeModel = "j", oraculAuto = "off"), plain = true))
    }

    @Test
    fun aWorkerThatKeepsFailingIsRecognisedWithinATurnAndAcrossTurns() {
        fun fail(n: Int) = (1..n).flatMap { listOf(ChatMessage.assistantCalls("", listOf(call("run_command", "command" to "make"))), ChatMessage.toolResult("run_command", "exit code 2\nerror")) }
        val ok = listOf(ChatMessage.assistantCalls("", listOf(call("run_command", "command" to "ls"))), ChatMessage.toolResult("run_command", "exit code 0"))
        val turn1 = listOf(ChatMessage.user("fix it")) + fail(1) + ChatMessage.assistant("tried")
        val turn2 = listOf(ChatMessage.user("still broken")) + fail(1) + ChatMessage.assistant("tried again")
        val turn3 = listOf(ChatMessage.user("again")) + fail(1)
        assertEquals(1, Oracul.failedStreak(turn1))
        assertEquals(3, Oracul.failedStreak(turn1 + turn2 + turn3))
        assertEquals(0, Oracul.failedStreak(turn1 + turn2 + listOf(ChatMessage.user("next")) + ok), "a turn without a failure ends the streak")
        assertEquals(1, Oracul.failedStreak(turn1 + listOf(ChatMessage.user("next")) + ok + turn3), "an older failure does not count past a good turn")
        val on = AppConfig(oraculModel = "big")
        assertTrue(Oracul.due(on, Oracul.turnFacts(turn1 + turn2 + turn3, 0)), "three turns in a row with a failure make the review due, though each had only one")
        assertTrue(!Oracul.due(on, Oracul.turnFacts(turn1 + turn2, 0)))
        // inside one turn: every fourth failure is a step in
        assertEquals(0, Oracul.stuckLevel(Oracul.turnFacts(listOf(ChatMessage.user("q")) + fail(3), 0), on))
        assertEquals(1, Oracul.stuckLevel(Oracul.turnFacts(listOf(ChatMessage.user("q")) + fail(4), 0), on))
        assertEquals(2, Oracul.stuckLevel(Oracul.turnFacts(listOf(ChatMessage.user("q")) + fail(9), 0), on))
        // the thresholds are settings: 0 means never, other numbers move the line
        assertEquals(0, Oracul.stuckLevel(Oracul.turnFacts(listOf(ChatMessage.user("q")) + fail(20), 0), on.copy(stuckCalls = 0)))
        assertEquals(2, Oracul.stuckLevel(Oracul.turnFacts(listOf(ChatMessage.user("q")) + fail(6), 0), on.copy(stuckCalls = 3)))
        assertTrue(!Oracul.due(on.copy(stuckTurns = 0), Oracul.turnFacts(turn1 + turn2 + turn3, 0)), "0 turns = never")
        assertTrue(Oracul.due(on.copy(stuckTurns = 2), Oracul.turnFacts(turn1 + turn2, 0)))
        // the Oracul is shown the attempts, and its advice is an ordinary review block for the worker
        val attempts = Oracul.attempts(listOf(ChatMessage.user("q")) + fail(1))
        assertTrue("CALL run_command" in attempts && "RESULT (FAILED): exit code 2" in attempts, attempts)
        val advice = Oracul.stuckMessage("big", "Try another way.")
        assertTrue(Oracul.isReview(advice) && Oracul.body(advice) == "Try another way.")
        assertEquals(listOf("big"), Oracul.lastTurn(listOf(ChatMessage.user("old"), ChatMessage.assistant("a"), ChatMessage.user("big"))).map { it.content })
    }

    @Test
    fun `the Oracul sees what came before a short follow-up`() {
        val chat = listOf(
            ChatMessage.user("Which pools are good?"),
            ChatMessage.assistantCalls("", listOf(ToolCall(ToolFunction("web_search", kotlinx.serialization.json.buildJsonObject { }))), null),
            ChatMessage.toolResult("web_search", "a very long tool result ".repeat(50)),
            ChatMessage.assistant("Pool table...\n" + "x".repeat(3000) + "\n**Option A:** one\n**Option B:** two\n**Option C:** check DefiLlama"),
            Oracul.opinionMessage("big", "an old opinion"),
            ChatMessage.user("do option C and that option B"),
        )
        val background = Oracul.background(chat)
        assertTrue("Which pools are good?" in background && "Option C" in background, "the options at the END of a long reply are kept: $background")
        assertTrue("shortened" in background, "a long reply is cut in the middle")
        assertTrue("tool result" !in background && "old opinion" !in background && "do option C" !in background, "no tool output, no Oracul blocks, not the last question itself")
        assertEquals("", Oracul.background(listOf(ChatMessage.user("first question"))), "nothing before the first question")
        val request = Oracul.stuckRequest("do option C and that option B", chat.takeLast(1), background = background)
        assertTrue(request.last().content.startsWith("EARLIER IN THE CHAT") && "TASK:\ndo option C" in request.last().content)
        assertTrue("EARLIER" !in Oracul.reviewRequest("Q?", "A.", emptyList()).last().content, "without a background the request is as before")
        val long = (1..40).flatMap { listOf(ChatMessage.user("question $it " + "y".repeat(1400)), ChatMessage.assistant("answer $it " + "z".repeat(1400))) } + ChatMessage.user("now")
        assertTrue(Oracul.background(long).length <= Oracul.MAX_BACKGROUND + 100, "capped in total, whatever the settings say")
        assertTrue(Oracul.background(long).split("\n\n").size > 6, "all messages that fit by default, not six")
        assertEquals(6, Oracul.background(long, maxMessages = 6).split("\n\n").size, "a number caps it")
        assertEquals(20, Oracul.background(long, maxMessages = 20).split("\n\n").size, "as many as the setting says while they fit")
        assertTrue(Oracul.background(long, maxMessages = 30).split("\n\n").size < 30, "the total cap wins over a larger number")
        assertTrue("shortened" in Oracul.background(long, maxMessages = 2, perMessage = 300), "the characters per message are a setting too")
    }

    @Test
    fun `an Oracul block tells the worker the reviewer saw only part of the chat`() {
        val message = Oracul.opinionMessage("big", "Looks wrong.")
        assertTrue("check what it says against the chat" in message.content)
        assertEquals("Looks wrong.", Oracul.body(message), "the reader does not see the line meant for the worker")
        assertEquals("Looks wrong.", Oracul.body(Oracul.stuckMessage("big", "Looks wrong.")))
        val old = ChatMessage.user("[Second opinion · big]\nA reviewer (another model) read your last answer and says:\n\nLooks wrong.")
        assertEquals("Looks wrong.", Oracul.body(old), "chats saved before still show cleanly")
    }

    @Test
    fun theCarefulFlowTakesItsModelsFromItsSettingsThenTheRoleTableThenTheOracul() {
        assertEquals(null, Flow.of(AppConfig()))
        val flow = Flow.of(AppConfig(flow = "careful", oraculModel = "o", flowAnalyst = "a", rolePlanner = "p", roleWorker = "", roleReviewer = ""))!!
        assertEquals(listOf(StageKind.Analyst, StageKind.Planner, StageKind.Worker, StageKind.Reviewer, StageKind.Controller), flow.stages.map { it.kind })
        assertEquals("a", flow.model(StageKind.Analyst))
        assertEquals("p", flow.model(StageKind.Planner))
        assertEquals("", flow.model(StageKind.Worker), "blank worker = the chat model")
        assertEquals("o", flow.model(StageKind.Reviewer), "an unset stage takes the Oracul model")
        assertEquals(SkillRoles(worker = "", reviewer = "o", planner = "p"), flow.roles())
        val config = AppConfig(flow = "careful", oraculModel = "o", rolePlanner = "p")
        assertEquals(SkillRoles("w", "o", "p"), SkillRoles(worker = "w").withDefaults(config), "a skill's own role still wins over the flow")
    }

    @Test
    fun anAnalysisBlockIsAdviceNotAReview() {
        val analysis = Oracul.analysisMessage("m", "Asked: X")
        assertTrue(Oracul.isAnalysis(analysis) && Oracul.isOpinion(analysis) && !Oracul.isReview(analysis) && !Oracul.isPlan(analysis))
        assertEquals("Asked: X", Oracul.body(analysis))
        assertTrue("ANALYSIS OF THE TASK" in Oracul.planRequest("q", "", analysis = "Asked: X").last().content)
        assertTrue("ANALYSIS OF THE TASK" !in Oracul.planRequest("q", "").last().content)
    }

    @Test
    fun theControllersVerdictIsReadFromItsFirstVerdictLineAndAnythingElseCountsAsDone() {
        assertEquals(Oracul.Control(true, ""), Oracul.parseControl("VERDICT: DONE"))
        assertEquals(Oracul.Control(false, "- add the edge case\n- check the path"), Oracul.parseControl("verdict: redo\n- add the edge case\n- check the path"))
        assertEquals(Oracul.Control(false, "fix X"), Oracul.parseControl("Looking at it...\nVERDICT: REDO: fix X"))
        assertTrue(Oracul.parseControl("I think it is fine").done, "no verdict line: done, so a confused model never starts a loop")
        val message = Oracul.controlMessage("m", Oracul.Control(false, "fix X"))
        assertTrue(Oracul.isControl(message) && Oracul.isOpinion(message) && !Oracul.isReview(message) && Oracul.body(message).startsWith("Verdict: redo"))
        assertEquals(4, Flow.of(AppConfig(flow = "careful", oraculModel = "o"))!!.stages.count { it.model == "o" }, "analyst, planner, reviewer and controller default to the Oracul model")
        assertEquals(5, Flow.of(AppConfig(flow = "careful", flowRedos = 99))!!.redos, "the redo limit is capped")
        val consultConfig = AppConfig(oraculModel = "o", rolePlanner = "p")
        assertEquals("p", Flow.consultModel(consultConfig, StageKind.Planner), "consult works with Flow off")
        assertEquals("Skill planner", Flow.consultSource(consultConfig, StageKind.Planner))
        assertEquals("o", Flow.consultModel(consultConfig, StageKind.Reviewer), "an unset stage falls back to the Oracul model")
        assertEquals("the Oracul model", Flow.consultSource(consultConfig, StageKind.Reviewer))
    }

    @Test
    fun aFlowShowsInTheStatusText() {
        assertEquals("⇉ careful", Oracul.statusText(AppConfig(flow = "careful")))
        assertEquals("Ψ big · auto ⇉ careful", Oracul.statusText(AppConfig(oraculModel = "big", flow = "careful")))
        assertEquals("oracul big · auto flow careful", Oracul.statusText(AppConfig(oraculModel = "big", flow = "careful"), plain = true))
    }

    @Test
    fun aGoalCheckIsMetOnlyWhenItSaysSo() {
        assertEquals(Oracul.GoalCheck(true, ""), Oracul.parseGoal("VERDICT: MET"))
        assertEquals(Oracul.GoalCheck(false, "- tags missing"), Oracul.parseGoal("verdict: not met\n- tags missing"))
        assertTrue(!Oracul.parseGoal("Looks fine to me").met, "no verdict line: a goal is never assumed reached")
        val m = Oracul.goalMessage("m", Oracul.GoalCheck(false, "- tags missing"))
        assertTrue(Oracul.isGoal(m) && Oracul.isOpinion(m) && !Oracul.isReview(m) && Oracul.body(m).startsWith("Goal not met yet"))
        val v = Oracul.verdictMessage("j", "AGREE\nit is right")
        assertTrue(Oracul.isVerdict(v) && !Oracul.isReview(v) && Oracul.body(v).startsWith("AGREE"))
    }

    @Test
    fun aSystemMessageNamesThePartOfArcanaThatSentIt() {
        fun src(text: String) = Oracul.systemSource(ChatMessage("user", text))
        assertEquals("Controller · redo", src(Oracul.REDO_PROMPT))
        assertEquals("Todo · open steps", src(Oracul.TODO_PROMPT + ": a; b."))
        assertEquals("Goal checker · goal not met", src(Oracul.goalContinue("ship it")))
        assertEquals("Reviewer · revise", src(Oracul.REVISE_PROMPT))
        assertEquals(null, src("hello"), "a message the user wrote has no source")
    }

    @Test
    fun theRouterReadsTheKindOfAMessageFromRulesAlone() {
        assertEquals(Route.Vision, Router.kindOf("what is in this?", hasImages = true))
        assertEquals(Route.Vision, Router.kindOf("fix ```x```", hasImages = true), "an image wins over code")
        assertEquals(Route.Code, Router.kindOf("why does this fail?\n```kotlin\nval x = 1\n```", hasImages = false))
        assertEquals(Route.Code, Router.kindOf("open src/main/App.kt and rename it", hasImages = false))
        assertEquals(null, Router.kindOf("explain how sorting works", hasImages = false))
        assertEquals(null, Router.kindOf("my notes.md are messy", hasImages = false), "a note is not code")
        val config = AppConfig(routeVision = " v ", routeCode = "c")
        assertEquals("v", Router.modelFor(config, Route.Vision))
        assertEquals("c", Router.modelFor(config, Route.Code))
        assertEquals("", Router.modelFor(AppConfig(), Route.Code))
    }

    @Test
    fun aCheckCommandReportsItsExitCodeAndOutput() {
        val dir = java.nio.file.Files.createTempDirectory("arcana-cmd")
        assertEquals(CheckRun(0, "hi"), CheckCommand.run("echo hi", dir))
        val failed = CheckCommand.run("echo broken; exit 3", dir)
        assertEquals(3, failed.code)
        assertTrue(!failed.passed && "broken" in failed.output)
        assertEquals(null, CheckCommand.run("sleep 5", dir, seconds = 1).code, "a timeout has no exit code")
        assertTrue(CheckCommand.refusal("sudo rm x") != null && CheckCommand.refusal("./gradlew test") == null)
    }

    @Test
    fun onlyTheAdvisingStagesCanBeConsulted() {
        assertEquals(StageKind.Analyst, Flow.consultKind(" Analyst "))
        assertEquals(StageKind.Planner, Flow.consultKind("planner"))
        assertEquals(StageKind.Reviewer, Flow.consultKind("REVIEWER"))
        assertEquals(null, Flow.consultKind("controller"), "the controller and the worker are not specialists to call")
        assertEquals(null, Flow.consultKind("boss"))
        assertTrue("analyst" in CONSULT_DEFINITION.toString() && CONSULT == "consult")
        assertTrue("TASK:\nx" in Oracul.consultRequest(StageKind.Planner, "x").last().content && "TO REVIEW:\nx" in Oracul.consultRequest(StageKind.Reviewer, "x").last().content)
    }

    @Test
    fun theMasterSwitchTurnsEveryHelperModelOffAndKeepsTheirSettings() {
        val on = AppConfig(oraculModel = "o", judgeModel = "j", flow = "careful")
        assertEquals("o", Oracul.model(on)); assertEquals("j", Oracul.judge(on)); assertTrue(Flow.of(on) != null)
        val off = on.copy(oraculEnabled = false)
        assertEquals("", Oracul.model(off)); assertEquals("", Oracul.judge(off)); assertEquals(null, Flow.of(off))
        assertEquals("", Oracul.statusText(off), "no badge when the helpers are off")
        assertEquals(emptyList(), Oracul.models(off))
        assertTrue("switched off" in Oracul.missingModelMessage(off) && "Choose an Oracul model" in Oracul.missingModelMessage(on.copy(oraculModel = "")))
        assertEquals("o", off.oraculModel, "the models stay set")
        assertTrue(AppConfig().oraculEnabled && AppConfig().skillRolesEnabled)
    }
}
