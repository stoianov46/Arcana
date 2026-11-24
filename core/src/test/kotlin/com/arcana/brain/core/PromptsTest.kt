package com.arcana.brain.core

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What the model is sent: Prompts (note tasks, chat messages with notes and excerpts, tools on or off, asking for access, the goal, the history window, cut notes, shortened tool output), ToolCuts, file-job detection. */
class PromptsTest {
    @Test
    fun noteTasksCarryTheNoteAndTheInstruction() {
        val config = AppConfig()
        val translate = Prompts.noteTask(config, NoteTask.Translate, "Plan", "Hello", input = "Ukrainian").last().content
        assertTrue("Ukrainian" in translate && "Hello" in translate && "Plan" in translate)
        assertTrue("shorter" in Prompts.noteTask(config, NoteTask.Custom, "T", "body", "make it shorter").last().content)
        assertEquals(config.summaryPrompt, Prompts.noteTask(config, NoteTask.Summarize, "T", "b").last().content.substringBefore("\n\nNote title"))
    }

    @Test
    fun chatMessagesCarryAttachedNotesAndVaultExcerpts() {
        val config = AppConfig(chatHistory = 3)
        val note = Note("Trips/Lisbon.md", 0)
        val history = listOf(
            ChatMessage.user("old question"), ChatMessage.assistant("old answer"),
            ChatMessage.user("compare with this", notes = listOf("Trips/Lisbon.md")),
        )
        val plain = Prompts.chatMessages(config, history, hits = null, noteTexts = mapOf("Trips/Lisbon.md" to "Book flights"))
        assertEquals("system", plain.first().role)
        assertTrue("[[" !in plain.first().content, "no citing instruction without the vault")
        val last = plain.last().content
        assertTrue("compare with this" in last && "Attached note “Lisbon”" in last && "Book flights" in last)
        assertTrue(plain.last().noteNames.isEmpty(), "the names are not sent, their text is")

        val withVault = Prompts.chatMessages(config, history, listOf(SearchHit(note, 3, "excerpt text")), emptyMap())
        assertTrue("[[Note title]]" in withVault.first().content)
        assertTrue(withVault.last().content.startsWith("Vault excerpts:") && "### [[Lisbon]]\nexcerpt text" in withVault.last().content)
        assertTrue("(this note could not be read)" in withVault.last().content, "a note that cannot be read is said so")
        assertTrue("Vault excerpts" !in withVault[1].content, "only the latest question gets the excerpts")
        assertTrue("No matching notes" in Prompts.chatMessages(config, history, emptyList(), emptyMap()).last().content)
        assertEquals(4, withVault.size, "system plus the last chatHistory (3) messages")
    }

    @Test
    fun theModelIsToldWhetherItHasTools() {
        val history = listOf(ChatMessage.user("hi"))
        assertTrue("/tools" in Prompts.chatMessages(AppConfig(), history, null, emptyMap()).first().content)
        assertTrue("You have tools" in Prompts.chatMessages(AppConfig(), history, null, emptyMap(), tools = true).first().content)
    }

    @Test
    fun oldToolOutputIsKeptWholeUnlessTheUserChoosesToShortenIt() {
        val big = "x".repeat(5000)
        val history = listOf(ChatMessage.user("read it"), ChatMessage.toolResult("read_file", big), ChatMessage.assistant("ok"), ChatMessage.user("and again?"))
        fun sent(config: AppConfig) = Prompts.chatMessages(config, history, null, emptyMap()).first { it.role == "tool" }.content
        assertEquals(big, sent(AppConfig()), "the default cuts nothing")
        val shortened = sent(AppConfig(oldToolOutput = "shorten"))
        assertTrue(shortened.length < 1600 && "shortened: the first 1200 of 5000 characters" in shortened && "call the tool again" in shortened, shortened.takeLast(120))
    }

    @Test
    fun aCutInAToolResultIsFoundSoTheChatCanSayIt() {
        assertEquals("… (cut after 20000 characters; the file has more. Read the rest with start_line / end_line)", ToolCuts.marker("line\nline\n… (cut after 20000 characters; the file has more. Read the rest with start_line / end_line)"))
        assertEquals("… and 40 more (name a folder to narrow it down)", ToolCuts.marker("a.md\nb.md\n… and 40 more (name a folder to narrow it down)"))
        assertEquals("… 120 characters left out …", ToolCuts.marker("start\n… 120 characters left out …\nend"))
        assertEquals(null, ToolCuts.marker("a normal result\nwith … an ellipsis in the middle\nand nothing left out"))
    }

    @Test
    fun fileJobsAreRecognisedBeforeTheModelAnswers() {
        assertTrue(looksLikeToolTask("Read ~/Desktop/plan.txt and summarise it"))
        assertTrue(looksLikeToolTask("analyse all files in the paywall module"))
        assertTrue(looksLikeToolTask("what is in /Users/me/Documents"))
        assertTrue(!looksLikeToolTask("write me a poem about autumn"))
        assertTrue(!looksLikeToolTask("explain what a monad is"))
    }

    @Test
    fun aModelWithToolsOffCanAskForAccess() {
        val history = listOf(ChatMessage.user("hi"))
        val system = Prompts.chatMessages(AppConfig(), history, null, emptyMap(), canRequestTools = true).first().content
        assertTrue(REQUEST_ACCESS in system && "turned off" in system)
        assertEquals(REQUEST_ACCESS, (REQUEST_ACCESS_DEFINITION["function"] as kotlinx.serialization.json.JsonObject)["name"]?.let { (it as kotlinx.serialization.json.JsonPrimitive).content })
        val box = ToolBox(null, emptyList(), Files.createTempDirectory("arcana-ra"), readOnly = true)
        assertTrue(REQUEST_ACCESS in box.describe())
    }

    @Test
    fun `a note cut for the model says so`() {
        val short = Prompts.cutNote("abc", 10)
        assertEquals("abc", short, "a note that fits is untouched")
        val cut = Prompts.cutNote("x".repeat(30), 10)
        assertTrue(cut.startsWith("x".repeat(10)) && "only 10 of 30 characters" in cut && "read_note" in cut)
        val dir = Files.createTempDirectory("cut")
        Files.writeString(dir.resolve("Long.md"), "alpha ".repeat(500))
        val hit = Vault(dir).search("alpha", 3, 100).single()
        assertEquals(3000, hit.total)
        assertEquals(2900, hit.missing)
        assertTrue("only 100 of 3000" in Prompts.hitText(hit) && "read_note" !in Prompts.hitText(hit))
        assertTrue("read_note" in Prompts.hitText(hit, tools = true))
        val whole = Vault(dir).search("alpha", 3, 5000).single()
        assertEquals(0, whole.missing)
        assertTrue("cut" !in Prompts.hitText(whole), "a note shown whole has no marker")
    }

    @Test
    fun `a long tool run never pushes the users request out of what the model sees`() {
        val calls = (1..40).flatMap { listOf(ChatMessage.assistant("step $it"), ChatMessage.toolResult("run_command", "result $it")) }
        val history = listOf(ChatMessage.user("old question"), ChatMessage.assistant("old answer"), ChatMessage.user("the real task")) + calls
        val window = Prompts.window(10, history)
        assertEquals("the real task", window.first().content, "the whole current turn is kept")
        assertEquals(history.size - 2, window.size)
        val short = listOf(ChatMessage.user("a"), ChatMessage.assistant("b"), ChatMessage.user("c"), ChatMessage.assistant("d"))
        assertEquals(2, Prompts.window(2, short).size, "an ordinary chat is still cut to the limit")
        assertEquals(4, Prompts.window(30, short).size)
    }

    @Test
    fun aHistoryLimitOfZeroSendsTheWholeChatAndTheOraculSeesAllEarlierMessages() {
        val chat = (1..30).flatMap { listOf(ChatMessage.user("question $it"), ChatMessage.assistant("answer $it")) }
        assertEquals(60, Prompts.window(0, chat).size, "0 = everything")
        assertEquals(10, Prompts.window(10, chat).size, "a number still cuts")
        assertEquals(0, AppConfig().chatHistory)
        val background = Oracul.background(chat + ChatMessage.user("do option C"))
        assertTrue("question 1" in background && "answer 30" in background, "all earlier messages, not the last 6")
        assertEquals(6, Oracul.background(chat + ChatMessage.user("do option C"), maxMessages = 6).split("\n\n").size, "a number caps it")
        val long = (1..40).flatMap { listOf(ChatMessage.user("q$it"), ChatMessage.assistant("x".repeat(2900))) }
        assertTrue(Oracul.background(long + ChatMessage.user("next")).length <= Oracul.MAX_BACKGROUND + 100, "the total is capped")
        assertTrue("x" in Oracul.background(long + ChatMessage.user("next")) && "q40" in Oracul.background(long + ChatMessage.user("next")), "newest first")
    }

    @Test
    fun theGoalOfAChatIsInTheSystemPromptOfEveryRequest() {
        val history = listOf(ChatMessage.user("go on"))
        val with = Prompts.chatMessages(AppConfig(), history, null, emptyMap(), goal = "every note is tagged")
        assertTrue("THE GOAL OF THIS CHAT" in with.first().content && "every note is tagged" in with.first().content)
        assertTrue("THE GOAL OF THIS CHAT" !in Prompts.chatMessages(AppConfig(), history, null, emptyMap()).first().content, "no goal, no line")
    }
}
