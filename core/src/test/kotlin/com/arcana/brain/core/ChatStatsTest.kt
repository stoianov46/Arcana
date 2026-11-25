package com.arcana.brain.core

import java.nio.file.Files
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Usage and chat statistics: the usage store and summaries, advisors, usage insights, the tool usage store, chat_stats and chat_stats_json, chat time, compaction lines, consulted models, team stats. */
class ChatStatsTest {
    @Test
    fun advisorsAreCountedApartFromTheChat() {
        val records = listOf(
            UsageRecord(1, "Chat", "gemma", 600, 200, 8000),
            UsageRecord(2, "Oracul", "big", 100, 50, 3000),
            UsageRecord(3, "Oracul", "big", 100, 50, 1000),
            UsageRecord(4, "Judge", "judge", 40, 10, 500),
        )
        val advisors = UsageStats.advisors(records).associateBy { it.feature }
        assertEquals(listOf("Oracul", "Analyst", "Planner", "Controller", "Judge"), UsageStats.advisors(records).map { it.feature })
        assertEquals(2, advisors.getValue("Oracul").totals.requests)
        assertEquals(300L, advisors.getValue("Oracul").totals.totalTokens)
        assertEquals(listOf("big"), advisors.getValue("Oracul").models)
        assertEquals(0, advisors.getValue("Planner").totals.requests)
        assertEquals(300.0 / 1150, advisors.getValue("Oracul").share, 0.0001)
    }

    @Test
    fun usageIsStoredAndSummarised() {
        val store = UsageStore(Files.createTempDirectory("usage").resolve("usage.jsonl"))
        assertTrue(store.load().isEmpty())
        val day = java.time.LocalDate.of(2026, 10, 24)
        val zone = java.time.ZoneId.of("UTC")
        fun at(d: java.time.LocalDate) = d.atStartOfDay(zone).toInstant().toEpochMilli() + 1000
        store.append(UsageRecord(at(day), "Chat", "gemma", 100, 400, 4000))
        store.append(UsageRecord(at(day), "Ask", "gemma", 300, 100, 1000))
        store.append(UsageRecord(at(day.minusDays(2)), "Chat", "gemma", 10, 20, 100))
        val all = store.load()
        assertEquals(3, all.size)
        val totals = UsageStats.totals(all)
        assertEquals(930L, totals.totalTokens)
        assertEquals(3, totals.requests)
        assertEquals(listOf("Chat", "Ask"), UsageStats.by(all) { it.feature }.map { it.first })
        val days = UsageStats.perDay(all, 3, today = day, zone = zone)
        assertEquals(listOf(30L, 0L, 900L), days.map { it.second })
        assertEquals("1.2k", UsageStats.compact(1234))
        assertEquals("3M", UsageStats.compact(3_000_000))
        store.clear()
        assertTrue(store.load().isEmpty())
    }

    @Test
    fun usageInsightsDescribeHabitsAndExtremes() {
        val zone = java.time.ZoneId.of("UTC")
        val today = java.time.LocalDate.of(2026, 10, 25)
        fun at(day: java.time.LocalDate, hour: Int) = day.atTime(hour, 5).atZone(zone).toInstant().toEpochMilli()
        fun rec(time: Long, prompt: Int, reply: Int, millis: Long) = UsageRecord(time, "Chat", "gemma", prompt, reply, millis)
        val all = listOf(
            rec(at(today, 23), 1000, 100, 2000), rec(at(today, 23), 3000, 400, 8000),
            rec(at(today.minusDays(1), 9), 500, 50, 1000),
            rec(at(today.minusDays(9), 9), 200, 20, 500),
        )
        val hours = UsageStats.perHour(all, zone)
        assertEquals(24, hours.size)
        assertEquals(4500L, hours[23])
        val i = UsageStats.insights(all, all, today, zone)
        assertEquals(3, i.activeDays)
        assertEquals(2, i.streakDays)
        assertEquals(today, i.busiestDay?.first)
        assertEquals(23, i.peakHour)
        assertEquals(400, i.longestReply?.completionTokens)
        assertEquals(8000L, i.slowestReply?.millis)
        assertEquals(2000L, i.medianMillis)
        assertTrue(i.contextRatio > 7.0)
        assertTrue(i.weekChange!! > 0)
        assertEquals(UsageInsights(0, 0, null, null, null, null, 0, 0, 0.0, null), UsageStats.insights(emptyList(), emptyList(), today, zone))
        val speed = UsageStats.speedPerDay(all, 3, today, zone)
        assertEquals(3, speed.size)
        assertTrue(speed.last().second > 0 && speed.first().second == 0.0)
    }

    @Test
    fun toolUseIsStoredAndTotalledPerTool() {
        val path = Files.createTempDirectory("arcana-tooluse").resolve("tool-usage.jsonl")
        val store = ToolUseStore(path)
        listOf(
            ToolUseRecord(1_000, "read_file", 40, "ok", 4_000), ToolUseRecord(2_000, "read_file", 60, "error", 20),
            ToolUseRecord(3_000, "grep_files", 10, "ok", 400), ToolUseRecord(4_000, "run_command", 900, "denied", 60),
        ).forEach { store.append(it) }
        val loaded = store.load()
        assertEquals(4, loaded.size)
        val totals = ToolStats.by(loaded)
        assertEquals(listOf("read_file", "grep_files", "run_command"), totals.map { it.tool }, "the biggest context cost first")
        val read = totals.first()
        assertEquals(2, read.calls); assertEquals(1, read.errors); assertEquals(0, read.denied); assertEquals(50L, read.averageMillis); assertEquals(1005L, read.approxTokens)
        assertEquals(1, totals.last().denied)
        assertEquals(2, ToolStats.since(loaded, 3_000).size)
        store.clear()
        assertTrue(store.load().isEmpty())
        assertTrue(ToolUseRecord(0, "x", 0, "ok", 5).approxTokens == 2)
    }

    @Test
    fun chatStatsDescribesTheChatSoFar() {
        val calls = listOf(ToolCall(ToolFunction("read_note", kotlinx.serialization.json.JsonObject(emptyMap()))), ToolCall(ToolFunction("read_note", kotlinx.serialization.json.JsonObject(emptyMap()))))
        val messages = listOf(
            ChatMessage.user("hi"),
            ChatMessage.assistantCalls("", calls, ReplyStats(1000, 100, 4000, 2000)),
            ChatMessage.toolResult("read_note", "x"), ChatMessage.toolResult("read_note", "y"),
            ChatMessage.assistant("done", ReplyStats(2000, 300, 6000, 3000)),
        )
        val text = ChatStats.describe(messages, "qwen3:30b", 8000, 3, 15, 90_000)
        assertTrue("Model: qwen3:30b" in text && "Messages: 5 (1 from the user, 2 replies, 2 tool results)" in text, text)
        assertTrue("3k read" in text && "400 generated" in text && "10 s" in text, text)
        assertTrue("Tool calls: 2 (read_note ×2)" in text && "of 8k tokens (29%)" in text && "running for 1 min 30 s" in text, text)
        assertTrue("$0.0150" in text, text)
        assertTrue("free" in ChatStats.describe(messages, "m", 0, 0, 0, 0) && "size unknown" in ChatStats.describe(emptyList(), "m", 0, 0, 0, 0))
        assertTrue(CHAT_STATS_DEFINITION.toString().contains("chat_stats"))
    }

    @Test
    fun chatStatsJsonHasTheSameNumbersInSecondsAndDollars() {
        val calls = listOf(ToolCall(ToolFunction("read_note", kotlinx.serialization.json.JsonObject(emptyMap()))))
        val messages = listOf(
            ChatMessage("user", "hi", at = 1_000_000L),
            ChatMessage.assistantCalls("", calls, ReplyStats(1000, 100, 4000, 2000)).copy(at = 1_010_000L),
            ChatMessage.assistant("done", ReplyStats(2000, 300, 6000, 3000)).copy(at = 1_070_000L),
        )
        val json = kotlinx.serialization.json.Json.parseToJsonElement(ChatStats.describeJson(messages, "qwen3:30b", 8000, 3, 15, 90_000)).jsonObject
        fun num(vararg path: String): Double = path.dropLast(1).fold(json) { o, k -> o.getValue(k).jsonObject }.getValue(path.last()).jsonPrimitive.double
        assertEquals("qwen3:30b", json.getValue("model").jsonPrimitive.content)
        assertEquals(70.0, num("time", "chat_seconds"), 0.001)   // seconds, not milliseconds
        assertEquals(70.0, num("time", "active_seconds"), 0.001)
        assertEquals(10.0, num("time", "generation_seconds"), 0.001)
        assertEquals(40.0, num("time", "tokens_per_second"), 0.001)
        assertEquals(3400.0, num("tokens", "total"), 0.001)
        assertEquals(0.015, num("cost", "estimated_dollars"), 0.0001)
        assertEquals(1.0, num("tools", "by_tool", "read_note"), 0.001)
        assertEquals(28.75, num("context", "percent_used"), 0.001)
        assertEquals(90.0, num("arcana_running_seconds"), 0.001)
        // same figures as the text version
        assertTrue("$0.0150" in ChatStats.describe(messages, "qwen3:30b", 8000, 3, 15, 90_000))
    }

    @Test
    fun chatStatsJsonCopesWithAnEmptyOrOlderChat() {
        val json = kotlinx.serialization.json.Json.parseToJsonElement(ChatStats.describeJson(emptyList(), "m", 0, 0, 0)).jsonObject
        assertTrue(json.getValue("time").jsonObject.getValue("chat_seconds") is kotlinx.serialization.json.JsonNull)   // a chat from before messages had a time
        assertTrue(json.getValue("context").jsonObject.getValue("last_request_used") is kotlinx.serialization.json.JsonNull)
        assertEquals(true, json.getValue("cost").jsonObject.getValue("free").jsonPrimitive.boolean)
        assertTrue(CHAT_STATS_JSON_DEFINITION.toString().contains("chat_stats_json") && CHAT_STATS_JSON != CHAT_STATS)
    }

    @Test
    fun chatTimeRunsFirstToLastAndActiveTimeSkipsLongPauses() {
        val min = 60_000L
        val t0 = 1_000_000_000L
        fun at(m: Long) = ChatMessage("user", "x", at = t0 + m * min)
        // 0, 5, 10 min; a 10-hour night; 610 + 3 min
        val chat = listOf(at(0), at(5), at(10), at(10 * 60 + 10), at(10 * 60 + 13))
        val (total, active) = ChatStats.spans(chat)!!
        assertEquals(613 * min, total)
        assertEquals(13 * min, active)
        assertNull(ChatStats.spans(listOf(ChatMessage("user", "old"), ChatMessage("assistant", "old"))))
        assertNull(ChatStats.spans(listOf(at(0))))
        val text = ChatStats.describe(chat, "m", 0, 0, 0, 0)
        assertTrue("Chat time: 10 h 13 min 0 s" in text && "Active time: 13 min 0 s" in text, text)
        assertTrue("Chat time" !in ChatStats.describe(listOf(ChatMessage("user", "old")), "m", 0, 0, 0, 0))
        assertTrue(ChatMessage.user("a").at > 0 && ChatMessage.assistant("b").at > 0 && ChatMessage.toolResult("t", "r").at > 0)
    }

    @Test
    fun chatStatsSaysHowOftenTheChatWasCompactedAndHowMuchRoomIsLeft() {
        val messages = listOf(ChatMessage.user("a"), Compaction.summaryMessage("x".repeat(400)), ChatMessage.user("b"))
        fun line(state: ChatStats.CompactionState, window: Int = 100_000) = ChatStats.compactionLine(ChatStats.compactionFacts(messages, window, state))
        assertTrue(line(ChatStats.CompactionState(80, 30_000)).let { "compacted 1 time" in it && "~100 tokens" in it && "~80k tokens): 50k tokens left before it" in it }, line(ChatStats.CompactionState(80, 30_000)))
        assertTrue("due, the next message compacts first" in line(ChatStats.CompactionState(80, 90_000)))
        assertTrue("auto-compact off" in line(ChatStats.CompactionState(0, 10)))
        assertTrue("no reply measured yet" in line(ChatStats.CompactionState(80, null)))
        assertTrue("not compacted yet" in ChatStats.compactionLine(ChatStats.compactionFacts(listOf(ChatMessage.user("a")), 1000, ChatStats.CompactionState(80, 10))))
        // the same facts in the JSON, and the line in the text
        val json = kotlinx.serialization.json.Json.parseToJsonElement(ChatStats.describeJson(messages, "m", 100_000, 0, 0, compaction = ChatStats.CompactionState(80, 30_000))).jsonObject["compaction"]!!.jsonObject
        assertEquals("1", json["count"].toString()); assertEquals("50000", json["tokens_until_auto"].toString()); assertEquals("false", json["due"].toString())
        assertTrue("Compaction: compacted 1 time" in ChatStats.describe(messages, "m", 100_000, 0, 0, 0, compaction = ChatStats.CompactionState(80, 30_000)))
    }

    @Test
    fun chatStatsNamesTheOtherModelsThatWereConsulted() {
        val chat = listOf(
            ChatMessage.user("q"), ChatMessage.assistant("a"),
            Oracul.opinionMessage("big", "wrong"), Oracul.planMessage("planner", "1. x"),
            ChatMessage.assistant(Oracul.takeoverHeading("big") + "done"),
        )
        val session = listOf(
            UsageRecord(1, "Chat", "worker", 3_000_000, 100_000, 1000), UsageRecord(2, "Chat", "worker", 1_000_000, 10_000, 1000), UsageRecord(3, "Compact", "worker", 1000, 100, 10),
            UsageRecord(4, "Oracul", "big", 100, 50, 10), UsageRecord(5, "Judge", "judge", 200, 20, 10), UsageRecord(6, "Planner", "planner", 50, 10, 5),
        )
        val earlier = listOf(UsageRecord(0, "Oracul", "big", 1000, 100, 10), UsageRecord(0, "Judge", "judge", 500, 50, 10), UsageRecord(0, "Judge", "judge", 500, 50, 10))
        val text = ChatStats.describe(chat, "worker", 0, 3, 15, 0, Oracul.setupLine(AppConfig(oraculModel = "big", judgeModel = "judge")), session, earlier + session)
        assertTrue("model big, judge judge" in text && "Second opinions in this chat: 1 (big)" in text && "Plans written by a planner model: 1 (planner)" in text, text)
        assertTrue("took over after the main model failed: 1 (big)" in text, text)
        // per model, the most tokens first, with what it was used for, then the total
        val lines = text.lines()
        val at = lines.indexOfFirst { it.startsWith("Token spend by model") }
        assertTrue(at >= 0 && lines[at + 1].startsWith("  worker: 4M read + 110k generated = 4.1M tokens in 3 requests (Chat ×2, Compact ×1), est. $"), text)
        assertTrue(lines.drop(at + 2).take(3).map { it.substringBefore(":") } == listOf("  judge", "  big", "  planner"), text)
        assertTrue(lines.any { it.startsWith("  Total: ") && "in 6 requests" in it && "est. $" in it }, text)
        // the calls are counted over the whole history, with how many of them were this session
        val calls = lines.first { it.startsWith("Calls to the other models, all time") }
        assertTrue("Oracul 2 requests" in calls && "Judge 3 requests" in calls && "1 this session" in calls && "Planner 1 request" in calls, calls)
        // free local models: no cost shown; nothing recorded: no spend section
        assertTrue("est. $" !in ChatStats.describe(chat, "worker", 0, 0, 0, 0, "", session).substringAfter("Token spend"))
        assertTrue("Token spend" !in ChatStats.describe(chat, "worker", 0, 0, 0, 0, "", emptyList()) && "no other model has been consulted" !in text)
        assertTrue("no other model has been consulted" in ChatStats.describe(emptyList(), "m", 0, 0, 0, 0, "", emptyList()))
    }

    @Test
    fun theTeamStatsSplitTheTokensAndCountWhatTheOrchestrationDid() {
        val records = listOf(
            UsageRecord(1, "Chat", "main", 600, 200, 8000), UsageRecord(2, "Oracul", "helper", 100, 50, 3000), UsageRecord(3, "Controller", "helper", 100, 50, 1000),
            UsageRecord(4, "Compact", "main", 400, 100, 1000),
        )
        fun e(t: Long, kind: String, detail: String = "", model: String = "") = StatEvent(t, kind, detail, model)
        val events = listOf(
            e(1, Events.FLOW), e(2, Events.CONTROL, "done"),                                   // accepted the first time
            e(3, Events.FLOW), e(4, Events.CONTROL, "redo"), e(5, Events.REDO), e(6, Events.CONTROL, "done"),  // redone once
            e(7, Events.FLOW), e(8, Events.CHECK, "fail"), e(9, Events.REDO), e(10, Events.CONTROL, "done"),   // a failing check counts as a redo
            e(11, Events.VERDICT, "agree"), e(12, Events.VERDICT, "partly"), e(13, Events.VERDICT, "disagree"), e(14, Events.OPINION, "auto"),
            e(15, Events.GOAL, "set"), e(16, Events.GOAL, "met"), e(17, Events.ROUTE, "code", "coder"), e(18, Events.ROUTE, "code", "coder"),
            e(19, Events.CONSULT, "planner", "helper"), e(20, Events.CANCEL), e(21, Events.COMPACT, "auto"), e(22, Events.MEMORY, "duplicate"), e(23, Events.ARTIFACT, "created"),
        )
        val stats = TeamStatsBuilder.build(records, events, 3, 15)
        assertEquals(800L, stats.chatTokens, "only the Chat feature is the chat model's own (compaction is neither)")
        assertEquals(300L, stats.helperTokens)
        assertEquals(300.0 / 1100, stats.helperShare, 0.0001)
        assertEquals(listOf("Oracul", "Controller"), stats.helpers.map { it.first })
        assertEquals("main", stats.models.first().model, "the model that spent most comes first")
        assertEquals(3, stats.flowRuns)
        assertEquals(1, stats.flowFirstTime, "only the first run was accepted without a redo")
        assertEquals(2, stats.redos)
        assertEquals(1, stats.checksFailed)
        assertEquals(0.5, stats.opinionsConfirmed!!, 0.0001, "agree 1 + partly half, of 3")
        assertEquals(listOf("code → coder" to 2), stats.routes)
        assertEquals(listOf("planner (helper)" to 1), stats.consults)
        assertEquals(1, stats.goalsMet); assertEquals(1, stats.goalsSet)
        assertEquals(1, stats.cancels); assertEquals(1, stats.compacts); assertEquals(1, stats.memoryDuplicates)
        assertTrue(TeamStatsBuilder.build(emptyList(), emptyList(), 3, 15).isEmpty)
        val dir = java.nio.file.Files.createTempDirectory("arcana-events")
        val store = EventStore(dir.resolve("events.jsonl"))
        store.append(e(1, Events.FLOW)); store.append(e(2, Events.ROUTE, "image", "vl"))
        assertEquals(listOf(Events.FLOW, Events.ROUTE), store.load().map { it.kind })
        store.clear(); assertEquals(emptyList(), store.load())
    }
}
