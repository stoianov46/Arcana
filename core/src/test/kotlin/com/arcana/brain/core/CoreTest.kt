package com.arcana.brain.core

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoreTest {
    @Test
    fun parsesContentChunk() {
        val chunk = OllamaProtocol.parseChunk("""{"model":"g","message":{"role":"assistant","content":"Hi"},"done":false}""")
        assertEquals(ChatChunk(content = "Hi"), chunk)
    }

    @Test
    fun parsesThinkingChunkAndDone() {
        val chunk = OllamaProtocol.parseChunk("""{"message":{"role":"assistant","content":"","thinking":"hmm"},"done":true}""")
        assertEquals(ChatChunk(thinking = "hmm", done = true), chunk)
    }

    @Test
    fun errorChunkThrows() {
        assertFailsWith<OllamaException> { OllamaProtocol.parseChunk("""{"error":"model not found"}""") }
    }

    @Test
    fun parsesModelList() {
        assertEquals(listOf("a:1", "b:2"), OllamaProtocol.parseModels("""{"models":[{"name":"b:2"},{"name":"a:1"}]}"""))
    }

    @Test
    fun configRoundTrips() {
        val dir = Files.createTempDirectory("arcana")
        val store = ConfigStore(dir.resolve("nested/config.json"))
        assertEquals(AppConfig(), store.load())
        val changed = AppConfig(model = "x", vaultPath = "/v", temperature = 0.1)
        store.save(changed)
        assertEquals(changed, store.load())
    }

    @Test
    fun vaultCreateSearchAppend() {
        val dir = Files.createTempDirectory("vault")
        Files.createDirectories(dir.resolve(".obsidian"))
        Files.writeString(dir.resolve(".obsidian/hidden.md"), "kotlin secret")
        Files.writeString(dir.resolve("Kotlin.md"), "Kotlin coroutines make async simple. Kotlin rocks.")
        Files.writeString(dir.resolve("Cooking.md"), "Pasta recipe")
        val vault = Vault(dir)

        assertEquals(setOf("Kotlin", "Cooking"), vault.notes().map { it.title }.toSet())
        val hits = vault.search("what about kotlin coroutines?", limit = 5, excerptChars = 100)
        assertEquals(listOf("Kotlin"), hits.map { it.note.title })

        val created = vault.create("Arcana", "Bad: title/with*chars", "body", "m")
        assertEquals("Arcana/Bad title with chars.md", created.path)
        assertTrue(vault.read(created.path).startsWith("---\ncreated:"))
        assertEquals("Arcana/Bad title with chars 2.md", vault.create("Arcana", "Bad: title/with*chars", "b", "m").path)

        vault.append("Cooking.md", "Summary", "tasty")
        assertTrue(vault.read("Cooking.md").endsWith("## Summary\n\ntasty\n"))
    }

    @Test
    fun vaultRejectsEscapingPaths() {
        val vault = Vault(Files.createTempDirectory("vault"))
        assertFailsWith<VaultException> { vault.read("../../etc/passwd") }
    }

    @Test
    fun trashMovesNoteToObsidianTrash() {
        val dir = Files.createTempDirectory("vault")
        Files.writeString(dir.resolve("Old.md"), "x")
        val vault = Vault(dir)
        assertEquals(".trash/Old.md", vault.trash("Old.md"))
        assertTrue(vault.notes().isEmpty(), "trashed notes are hidden from the note list")
        Files.writeString(dir.resolve("Old.md"), "y")
        assertEquals(".trash/Old 2.md", vault.trash("Old.md"))
        assertFailsWith<VaultException> { vault.trash("Missing.md") }
    }

    @Test
    fun parsesTokenCountsFromTheFinalChunk() {
        val chunk = OllamaProtocol.parseChunk("""{"message":{"role":"assistant","content":""},"done":true,"prompt_eval_count":26,"eval_count":298,"total_duration":5043500000}""")
        assertEquals(ChatChunk(done = true, promptTokens = 26, completionTokens = 298, totalMillis = 5043), chunk)
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
    fun chatsAreSavedListedAndPruned() {
        val store = ChatStore(Files.createTempDirectory("chats").resolve("chats"))
        assertTrue(store.list().isEmpty())
        fun chat(id: String, at: Long, first: String) =
            StoredChat(id, ChatStore.titleOf(listOf(ChatMessage.user(first))), at, "gemma", listOf(ChatMessage.user(first), ChatMessage.assistant("ok")))
        store.save(chat("a", 1, "first chat\nmore"))
        store.save(chat("b", 3, "newest"))
        store.save(chat("c", 2, "middle"))
        assertEquals(listOf("b", "c", "a"), store.list().map { it.id })
        assertEquals("first chat", store.list().last().title)
        assertEquals("b", store.latest()?.id)
        store.prune(2)
        assertEquals(listOf("b", "c"), store.list().map { it.id })
        store.delete("b")
        assertEquals(listOf("c"), store.list().map { it.id })
        assertEquals("New chat", ChatStore.titleOf(emptyList()))
    }

    @Test
    fun replaceKeepsFrontmatterAndBacksUpTheOldText() {
        val dir = Files.createTempDirectory("vault")
        Files.writeString(dir.resolve("Plan.md"), "---\ntags: [a]\n---\n\nold body\n")
        val vault = Vault(dir)
        val backup = vault.replace("Plan.md", "new body")
        assertEquals("---\ntags: [a]\n---\n\nnew body\n", vault.read("Plan.md"))
        assertTrue(backup.startsWith(".trash/Plan (backup "))
        assertEquals("---\ntags: [a]\n---\n\nold body\n", Files.readString(dir.resolve(backup)))
        // a model that hands the frontmatter back does not duplicate it
        vault.replace("Plan.md", "---\ntags: [a]\n---\n\nechoed body")
        assertEquals("---\ntags: [a]\n---\n\nechoed body\n", vault.read("Plan.md"))
        Files.writeString(dir.resolve("Plain.md"), "just text")
        vault.replace("Plain.md", "rewritten")
        assertEquals("rewritten\n", vault.read("Plain.md"))
        assertFailsWith<VaultException> { vault.replace("Missing.md", "x") }
    }

    @Test
    fun appendCanSkipTheHeading() {
        val dir = Files.createTempDirectory("vault")
        Files.writeString(dir.resolve("N.md"), "one\n")
        val vault = Vault(dir)
        vault.append("N.md", null, "two")
        vault.append("N.md", "Summary", "three")
        assertEquals("one\n\ntwo\n\n## Summary\n\nthree\n", vault.read("N.md"))
    }

    @Test
    fun noteTasksCarryTheNoteAndTheInstruction() {
        val config = AppConfig()
        val translate = Prompts.noteTask(config, NoteTask.Translate, "Plan", "Hello", input = "Ukrainian").last().content
        assertTrue("Ukrainian" in translate && "Hello" in translate && "Plan" in translate)
        assertTrue("shorter" in Prompts.noteTask(config, NoteTask.Custom, "T", "body", "make it shorter").last().content)
        assertEquals(config.summaryPrompt, Prompts.noteTask(config, NoteTask.Summarize, "T", "b").last().content.substringBefore("\n\nNote title"))
    }

    @Test
    fun imagesAreValidatedAndEncoded() {
        val dir = Files.createTempDirectory("img")
        val png = dir.resolve("a.PNG").also { Files.write(it, byteArrayOf(1, 2, 3)) }
        val txt = dir.resolve("note.txt").also { Files.writeString(it, "x") }
        assertNull(ImageFiles.validate(listOf(png)))
        assertTrue(ImageFiles.validate(listOf(txt))!!.contains("not a PNG"))
        assertTrue(ImageFiles.validate(listOf(png), already = 5)!!.contains("at most 5"))
        assertTrue(ImageFiles.validate(listOf(dir.resolve("missing.png")))!!.contains("not a file"))
        val loaded = ImageFiles.load(png)
        assertEquals("a.PNG", loaded.name)
        assertEquals("AQID", loaded.base64)
        assertEquals("1.5 MB", ImageFiles.size(1_572_864))
    }

    @Test
    fun imagesTravelInTheRequestButNotOnDisk() {
        val message = ChatMessage.user("what is this?", listOf(ImageAttachment("a.png", "QUJD", 3)))
        val request = OllamaProtocol.chatRequest(AppConfig(), listOf(message)).toString()
        assertTrue("\"images\":[\"QUJD\"]" in request, request)
        assertTrue("images" !in OllamaProtocol.chatRequest(AppConfig(), listOf(ChatMessage.user("hi"))).toString())
        val stored = message.withoutImageData()
        assertTrue(stored.images.isEmpty())
        assertEquals(listOf("a.png"), stored.imageNames)
        assertEquals(listOf("vision", "completion"), OllamaProtocol.parseCapabilities("""{"capabilities":["vision","completion"]}"""))
    }

    @Test
    fun picturesAreCopiedIntoTheVaultWithUniqueNames() {
        val vault = Vault(Files.createTempDirectory("vault"))
        val src = Files.createTempDirectory("src").resolve("shot.png").also { Files.write(it, byteArrayOf(9)) }
        assertEquals("shot.png", vault.copyAttachment("Arcana", src))
        assertEquals("shot 2.png", vault.copyAttachment("Arcana", src))
        assertTrue(Files.exists(vault.root.resolve("Arcana/attachments/shot 2.png")))
        assertEquals("shot.png", vault.copyAttachment("", src))
        assertTrue(Files.exists(vault.root.resolve("attachments/shot.png")), "a blank folder means the vault root")
    }

    @Test
    fun replyStatsComeFromTheFinalChunkAndAreStoredWithTheMessage() {
        val chunk = OllamaProtocol.parseChunk("""{"message":{"role":"assistant","content":""},"done":true,"prompt_eval_count":66,"eval_count":300,"total_duration":6000000000,"eval_duration":4000000000}""")
        val stats = chunk.toStats()!!
        assertEquals(ReplyStats(66, 300, 6000, 4000), stats)
        assertEquals(75.0, stats.tokensPerSecond, "speed is measured over the generating time")
        assertEquals(6.0, stats.seconds)
        assertNull(ChatChunk(content = "hi").toStats())
        assertEquals(50.0, ReplyStats(1, 300, 6000).tokensPerSecond, "without the generating time the whole time is used")
        val dir = Files.createTempDirectory("chats").resolve("chats")
        val store = ChatStore(dir)
        store.save(StoredChat("a", "t", 1, "m", listOf(ChatMessage.user("q"), ChatMessage.assistant("a", stats))))
        assertEquals(stats, store.latest()!!.messages.last().stats)
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
    fun sessionsAreStoredReportedAndSummarised() {
        val store = SessionStore(Files.createTempDirectory("sessions").resolve("sessions"))
        assertTrue(store.list().isEmpty())
        val zone = java.time.ZoneId.of("UTC")
        val t0 = java.time.LocalDateTime.of(2026, 10, 25, 14, 0).atZone(zone).toInstant().toEpochMilli()
        val session = StoredSession(
            "s1", t0, ended = t0 + 4_320_000,
            events = listOf(
                SessionEvent(t0 + 60_000, SessionKind.Asked, "What is RAG?"),
                SessionEvent(t0 + 120_000, SessionKind.Task, "Improve Trip.md", "Trip.md"),
                SessionEvent(t0 + 180_000, SessionKind.Replaced, "Trip.md", "Trip.md"),
                SessionEvent(t0 + 240_000, SessionKind.Created, "Summary", "Arcana/Summary.md"),
                SessionEvent(t0 + 300_000, SessionKind.Attached, "photo.png"),
            ),
        )
        store.save(session)
        store.save(session.copy(id = "s0", started = t0 - 1000, events = emptyList()))
        assertEquals(listOf("s1", "s0"), store.list().map { it.id })
        assertEquals(session, store.list().first())
        val stats = SessionReport.stats(session.events)
        assertEquals(SessionStats(1, 1, 1, 1, 0, 1, 0), stats)
        assertEquals("1 question · 1 AI task · 1 note created · 1 note changed · 1 attachment", stats.describe())
        assertEquals("no activity yet", SessionStats(0, 0, 0, 0, 0, 0).describe())
        assertEquals("3 thoughts captured", SessionStats(0, 0, 0, 0, 0, 0, 3).describe())
        assertEquals(5, stats.total)
        assertEquals("1h 12m", SessionReport.duration(session.durationMillis))
        assertEquals("14:01", SessionReport.clock(t0 + 60_000, zone))
        val note = SessionReport.note(session, "- learned things", zone)
        assertTrue("## Summary" in note && "- learned things" in note && "[[Arcana/Summary]]" in note && "**Replaced**: Trip.md [[Trip]]" in note)
        assertTrue("## Summary" !in SessionReport.note(session, "", zone), "a plain log has no summary section")
        assertEquals("Session 2026-10-25 14-00", SessionReport.title(session, zone))
        val prompt = Prompts.sessionSummary(AppConfig(), "- log", listOf("Trip" to "You: hi")).last().content
        assertTrue("What I did" in prompt && "- log" in prompt && "### Chat: Trip" in prompt)
        store.prune(1)
        assertEquals(listOf("s1"), store.list().map { it.id })
    }

    @Test
    fun theVaultIsIndexedForLinksTagsAndHealth() {
        val dir = Files.createTempDirectory("brain")
        fun note(path: String, text: String, ageDays: Long = 0) {
            val f = dir.resolve(path); Files.createDirectories(f.parent); Files.writeString(f, text)
            Files.setLastModifiedTime(f, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - ageDays * 86_400_000L))
        }
        note("Hub.md", "---\ntags: [project, ai]\n---\nSee [[Idea]] and [[Sub/Deep|the deep one]] and [[Missing note]]. #active\n```\n#notatag [[InCode]]\n```")
        note("Idea.md", "Back to [[Hub]]. Picture ![[shot.png]]. Cost is #5 dollars.")
        note("Sub/Deep.md", "tags:\n- deep\n\nplain text")
        note("Lonely.md", "nothing links here", ageDays = 200)
        note("Inbox/Fresh.md", "new thought")
        val index = VaultIndex.build(Vault(dir))
        assertEquals(5, index.notes.size)
        val hub = index.note("Hub.md")!!
        assertEquals(setOf("Idea.md", "Sub/Deep.md"), hub.links)
        assertEquals(setOf("Missing note"), hub.unresolved)
        assertEquals(listOf("one", "two"), VaultIndex.frontmatterTags("---\ntags:\n- one\n- two\n---\n\n"), "the closing fence is not a tag")
        assertEquals(setOf("project", "ai", "active"), hub.tags, "frontmatter and inline tags; none from inside code")
        assertTrue(index.note("Idea.md")!!.tags.isEmpty(), "#5 is a number, not a tag; a picture embed is not a link")
        assertEquals(setOf("Hub.md"), index.note("Idea.md")!!.links)
        assertEquals(listOf("Hub"), index.backlinksOf("Idea.md").map { it.title })
        assertEquals(listOf("Hub"), index.backlinksOf("Sub/Deep.md").map { it.title })
        assertEquals(setOf("Lonely.md", "Inbox/Fresh.md"), index.orphans.map { it.path }.toSet(), "Sub/Deep is linked from Hub, so it is no island")
        assertEquals(listOf("Lonely.md"), index.stale(90).map { it.path })
        assertEquals(listOf("Inbox/Fresh.md"), index.inFolder("Inbox").map { it.path })
        assertEquals(1, index.deadLinks)
        assertEquals("project", index.tagCounts.first { it.first == "project" }.first)
        val health = index.health("Inbox", staleDays = 90)
        assertEquals(5, health.notes)
        assertEquals(1, health.inbox)
        assertEquals(2, health.orphans)
        assertEquals(1, health.brokenNotes)
        assertEquals(1, health.stale)
        assertEquals(60, health.connectedPercent, "3 of 5 notes are connected")
        assertEquals(4, health.untagged, "only Hub has tags; Deep has no real frontmatter")
        assertEquals(100, VaultIndex(emptyList()).health("Inbox", 90).connectedPercent, "an empty vault is not unhealthy")
        assertEquals(listOf("Deep", "Idea"), index.linksOf("Hub.md").map { it.title })
        assertTrue(index.related("Hub.md").map { it.path }.containsAll(listOf("Idea.md", "Sub/Deep.md")))
        assertTrue("Lonely.md" !in index.related("Hub.md").map { it.path }, "an unconnected note is not related")
    }

    @Test
    fun dailyNotesFollowObsidianSettingsAndCaptureAppends() {
        val dir = Files.createTempDirectory("daily")
        Files.createDirectories(dir.resolve(".obsidian"))
        Files.writeString(dir.resolve(".obsidian/daily-notes.json"), """{"folder":"Journal","format":"YYYY-MM-DD dddd"}""")
        val vault = Vault(dir)
        val daily = DailyNotes.detect(vault)
        assertEquals(DailyNotes("Journal", "YYYY-MM-DD dddd"), daily)
        val day = java.time.LocalDate.of(2026, 10, 25)
        assertEquals("Journal/2026-10-25 Sunday.md", daily.pathFor(day))
        assertEquals("yyyy-MM-dd 'at' HH", DailyNotes.javaPattern("YYYY-MM-DD [at] HH"))
        val at = java.time.LocalDateTime.of(2026, 10, 25, 9, 5)
        assertEquals("Journal/2026-10-25 Sunday.md", vault.capture("first thought", daily, at))
        vault.capture("second thought", daily, at.plusMinutes(10))
        assertEquals("- 09:05 first thought\n- 09:15 second thought\n", vault.read("Journal/2026-10-25 Sunday.md"))
        assertEquals(DailyNotes("Daily", "YYYY-MM-DD"), DailyNotes.detect(Vault(Files.createTempDirectory("none")), "Daily"), "defaults when the vault has no settings")
    }

    @Test
    fun aMessyModelReplyBecomesACheckedPlan() {
        val reply = "Sure! Here you go:\n```json\n{\"title\": \"Call: Anna / flights\", \"summary\": \"Ask Anna about flights.\\nSoon.\", " +
            "\"tags\": [\"#travel\", \"to do\", \"travel\"], \"links\": [\"lisbon trip\", \"[[Ghost note]]\", \"Hub\"], \"folder\": \"projects\", \"extra\": 1}\n```"
        val plan = InboxProcessing.parse(reply, "Fallback", listOf("Lisbon Trip", "Hub"), listOf("Projects", "Areas"))!!
        assertEquals("Call Anna flights", plan.title, "characters that cannot be in a name are cleaned")
        assertEquals("Ask Anna about flights. Soon.", plan.summary)
        assertEquals(listOf("travel", "to-do"), plan.tags, "# removed, spaces become dashes, duplicates dropped")
        assertEquals(listOf("Lisbon Trip", "Hub"), plan.links, "only real notes, with their real spelling")
        assertEquals("Projects", plan.folder, "matched to an existing folder, case-insensitively")
        assertEquals("", InboxProcessing.parse("""{"folder": "Invented"}""", "F", emptyList(), listOf("Projects"))!!.folder, "an invented folder is dropped")
        assertEquals("Fallback", InboxProcessing.parse("""{"summary": "x"}""", "Fallback", emptyList(), emptyList())!!.title)
        assertNull(InboxProcessing.parse("no json here", "F", emptyList(), emptyList()))
        assertNull(InboxProcessing.parse("{ broken", "F", emptyList(), emptyList()))
    }

    @Test
    fun tagsAreMergedIntoFrontmatter() {
        assertEquals("---\ntags: [a, b]\n---\n\n", mergeFrontmatterTags("", listOf("a", "b")))
        assertEquals("", mergeFrontmatterTags("", emptyList()))
        assertEquals("---\ntitle: X\ntags: [old, new]\n---\n\n", mergeFrontmatterTags("---\ntitle: X\ntags: [old]\n---\n\n", listOf("new", "old")))
        assertEquals("---\ntags: [one, two, three]\n---\n\n", mergeFrontmatterTags("---\ntags:\n- one\n- two\n---\n\n", listOf("three")), "a block list is rewritten as one line")
        assertEquals("---\ncreated: 1\ntags: [x]\n---\n\n", mergeFrontmatterTags("---\ncreated: 1\n---\n\n", listOf("x")))
    }

    @Test
    fun anInboxNoteIsFiledWithABackupAndNothingLost() {
        val dir = Files.createTempDirectory("inbox")
        Files.createDirectories(dir.resolve("Inbox"))
        Files.writeString(dir.resolve("Inbox/call anna.md"), "---\ncreated: 5\n---\n\nCall Anna about flights.\n")
        val vault = Vault(dir)
        val plan = InboxPlan("Call Anna", "Projects", listOf("travel"), listOf("Lisbon Trip"), "Ask Anna about flights.")
        val done = vault.processInboxNote("Inbox/call anna.md", plan)
        assertEquals("Projects/Call Anna.md", done.path)
        assertTrue(!Files.exists(dir.resolve("Inbox/call anna.md")), "the note left the inbox")
        val text = vault.read("Projects/Call Anna.md")
        assertTrue(text.startsWith("---\ncreated: 5\ntags: [travel]\n---\n\n> [!summary] Ask Anna about flights.\n\nCall Anna about flights."), text)
        assertTrue(text.trimEnd().endsWith("## Related\n\n- [[Lisbon Trip]]"))
        assertEquals("---\ncreated: 5\n---\n\nCall Anna about flights.\n", Files.readString(dir.resolve(done.backup)), "the original is kept exactly")
        // a name already taken gets a number; keepName leaves the file name alone
        Files.writeString(dir.resolve("Inbox/other.md"), "body")
        assertEquals("Projects/Call Anna 2.md", vault.processInboxNote("Inbox/other.md", plan).path)
        Files.writeString(dir.resolve("Inbox/keep.md"), "body")
        assertEquals("Projects/keep.md", vault.processInboxNote("Inbox/keep.md", plan, keepName = true).path)
        assertFailsWith<VaultException> { vault.processInboxNote("Inbox/missing.md", plan) }
        assertFailsWith<VaultException> { vault.processInboxNote("Projects/keep.md", plan.copy(folder = "../outside")) }
    }

    @Test
    fun textFilesAreSentInsideTheMessage() {
        val dir = Files.createTempDirectory("arcana-files")
        val json = dir.resolve("data.json").also { Files.writeString(it, """{"a": 1}""") }
        val bin = dir.resolve("blob.txt").also { Files.write(it, byteArrayOf(65, 0, 66)) }
        assertTrue(TextFiles.isText(json) && !ImageFiles.isImage(json) && TextFiles.isAttachable(json))
        assertNull(TextFiles.validate(listOf(json)))
        assertFailsWith<IllegalArgumentException> { TextFiles.load(bin) }
        val file = TextFiles.load(json)
        val request = OllamaProtocol.chatRequest(AppConfig(), listOf(ChatMessage.user("what is a?", files = listOf(file)))).toString()
        assertTrue("what is a?" in request && "Attached file" in request && "data.json" in request && "files" !in request)
    }

    @Test
    fun pdfTextIsExtracted() {
        val dir = Files.createTempDirectory("arcana-pdf")
        fun pdf(name: String, text: String?) = dir.resolve(name).also { path ->
            org.apache.pdfbox.pdmodel.PDDocument().use { doc ->
                val page = org.apache.pdfbox.pdmodel.PDPage()
                doc.addPage(page)
                if (text != null) org.apache.pdfbox.pdmodel.PDPageContentStream(doc, page).use { c ->
                    c.beginText()
                    c.setFont(org.apache.pdfbox.pdmodel.font.PDType1Font(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 12f)
                    c.newLineAtOffset(50f, 700f)
                    c.showText(text)
                    c.endText()
                }
                doc.save(path.toFile())
            }
        }
        val good = pdf("report.pdf", "Quarterly revenue grew")
        assertTrue(TextFiles.isPdf(good) && TextFiles.isAttachable(good) && TextFiles.validate(listOf(good)) == null)
        assertTrue("Quarterly revenue grew" in TextFiles.load(good).text)
        val blank = pdf("scan.pdf", null)
        assertTrue(assertFailsWith<IllegalArgumentException> { TextFiles.load(blank) }.message!!.contains("no text layer"))
        val fake = dir.resolve("fake.pdf").also { Files.writeString(it, "not a pdf") }
        assertFailsWith<IllegalArgumentException> { TextFiles.load(fake) }
    }

    private fun call(name: String, vararg args: Pair<String, String>) =
        ToolCall(ToolFunction(name, kotlinx.serialization.json.JsonObject(args.associate { it.first to kotlinx.serialization.json.JsonPrimitive(it.second) })))

    @Test
    fun toolsStayInsideTheAllowedFolders() {
        val root = Files.createTempDirectory("arcana-tools")
        val vaultDir = Files.createDirectories(root.resolve("vault"))
        val other = Files.createDirectories(root.resolve("other"))
        Files.writeString(vaultDir.resolve("Idea.md"), "# Idea\nbody")
        Files.writeString(other.resolve("secret.txt"), "nope")
        val box = ToolBox(Vault(vaultDir), emptyList(), root.resolve("backups"))
        assertEquals("# Idea\nbody", box.execute(call("read_note", "path" to "Idea")))
        assertTrue(box.execute(call("read_file", "path" to other.resolve("secret.txt").toString())).startsWith("Error:"))
        assertTrue(box.execute(call("read_file", "path" to "../other/secret.txt")).startsWith("Error:"))
        Files.createSymbolicLink(vaultDir.resolve("link.txt"), other.resolve("secret.txt"))
        assertTrue(box.execute(call("read_file", "path" to "link.txt")).startsWith("Error:"))
        // an extra folder opens exactly that folder
        val wide = ToolBox(Vault(vaultDir), listOf(other), root.resolve("backups"))
        assertEquals("nope", wide.execute(call("read_file", "path" to other.resolve("secret.txt").toString())))
        assertTrue(box.execute(call("nothing")).startsWith("Error: there is no tool"))
    }

    @Test
    fun writeToolsBackUpAndProtect() {
        val root = Files.createTempDirectory("arcana-write")
        val vaultDir = Files.createDirectories(root.resolve("vault"))
        val box = ToolBox(Vault(vaultDir), emptyList(), root.resolve("backups"), outputFolder = "Arcana")
        assertTrue(box.execute(call("create_note", "title" to "Plan", "content" to "first")).startsWith("Created Arcana/Plan.md"))
        assertTrue(box.execute(call("append_to_note", "path" to "Arcana/Plan", "content" to "second")).startsWith("Added"))
        assertTrue(Files.readString(vaultDir.resolve("Arcana/Plan.md")).let { "first" in it && "second" in it })
        box.execute(call("write_file", "path" to "data.txt", "content" to "v1"))
        assertTrue(box.execute(call("write_file", "path" to "data.txt", "content" to "v2")).contains("backed up"))
        assertEquals(listOf("v1"), Files.list(root.resolve("backups")).use { it.toList() }.map { Files.readString(it) })
        assertTrue(box.execute(call("edit_file", "path" to "data.txt", "old" to "v2", "new" to "v3")).startsWith("Edited"))
        assertEquals(setOf("v1", "v2"), Files.list(root.resolve("backups")).use { it.toList() }.map { Files.readString(it) }.toSet())
        assertEquals("v3", Files.readString(vaultDir.resolve("data.txt")))
        assertTrue(box.execute(call("edit_file", "path" to "data.txt", "old" to "missing", "new" to "x")).startsWith("Error:"))
        Files.createDirectories(vaultDir.resolve(".obsidian"))
        assertTrue(box.execute(call("write_file", "path" to ".obsidian/app.json", "content" to "{}")).contains("protected"))
        assertTrue(box.find("write_file")!!.risk == ToolRisk.Write && box.find("read_note")!!.risk == ToolRisk.Read)
    }

    @Test
    fun toolCallsTravelInTheOllamaFormat() {
        val box = ToolBox(null, listOf(Files.createTempDirectory("arcana-fmt")), Files.createTempDirectory("arcana-bk"))
        val request = OllamaProtocol.chatRequest(
            AppConfig(),
            listOf(ChatMessage.assistantCalls("", listOf(call("read_file", "path" to "a.txt"))), ChatMessage.toolResult("read_file", "hi")),
            tools = box.definitions(),
        ).toString()
        assertTrue("\"tool_calls\"" in request && "\"tool_name\":\"read_file\"" in request && "\"type\":\"function\"" in request)
        val chunk = OllamaProtocol.parseChunk("""{"message":{"role":"assistant","content":"","tool_calls":[{"function":{"name":"search_notes","arguments":{"query":"llm"}}}]},"done":true}""")
        assertEquals("search_notes", chunk.toolCalls.single().name)
        assertEquals("llm", (chunk.toolCalls.single().arguments["query"] as kotlinx.serialization.json.JsonPrimitive).content)
    }

    @Test
    fun theModelIsToldWhetherItHasTools() {
        val history = listOf(ChatMessage.user("hi"))
        assertTrue("/tools" in Prompts.chatMessages(AppConfig(), history, null, emptyMap()).first().content)
        assertTrue("You have tools" in Prompts.chatMessages(AppConfig(), history, null, emptyMap(), tools = true).first().content)
    }

    @Test
    fun grepGlobAndLineRangesWorkLikeClaudeCodes() {
        val root = Files.createTempDirectory("arcana-grep")
        val proj = Files.createDirectories(root.resolve("proj/src/main"))
        Files.writeString(proj.resolve("Paywall.kt"), (1..500).joinToString("\n") { if (it == 321) "class PaywallRepository(val x: Int)" else "// line $it" })
        Files.writeString(proj.resolve("Other.java"), "class Other { PaywallRepository r; }")
        Files.writeString(proj.resolve("notes.txt"), "paywallrepository in text")
        Files.write(proj.resolve("blob.bin"), byteArrayOf(0, 1, 2, 3))
        val box = ToolBox(Vault(Files.createDirectories(root.resolve("vault"))), listOf(root.resolve("proj")), root.resolve("b"))
        val all = box.execute(call("grep_files", "pattern" to "PaywallRepository"))
        assertTrue("Paywall.kt:321: class PaywallRepository" in all && "Other.java:1:" in all && "notes.txt:1:" in all, all)
        val kt = box.execute(call("grep_files", "pattern" to "PaywallRepository", "glob" to "*.kt", "path" to proj.toString()))
        assertTrue("Paywall.kt:321" in kt && "Other.java" !in kt, kt)
        assertTrue("not a valid regular expression" in box.execute(call("grep_files", "pattern" to "foo(")))
        assertTrue("No line matches" in box.execute(call("grep_files", "pattern" to "zzzzqq")))
        val globbed = box.execute(call("find_files", "glob" to "**/*.java", "path" to proj.parent.toString()))
        assertTrue("Other.java" in globbed && "Paywall.kt" !in globbed, globbed)
        assertTrue("Paywall.kt" in box.execute(call("find_files", "glob" to "*.kt")))
        val part = box.execute(call("read_file", "path" to proj.resolve("Paywall.kt").toString(), "start_line" to "320", "end_line" to "322"))
        assertTrue(part.lines().first() == "320 | // line 320" && "321 | class PaywallRepository" in part && "more lines; continue with start_line=323" in part, part)
        assertTrue("has only 500 lines" in box.execute(call("read_file", "path" to proj.resolve("Paywall.kt").toString(), "start_line" to "900")))
        val tail = box.execute(call("read_file", "path" to proj.resolve("Paywall.kt").toString(), "start_line" to "499"))
        assertTrue("end of file, 500 lines" in tail, tail)
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
    fun shellPolicySeparatesHarmlessBlockedAndEverythingElse() {
        assertTrue(ShellPolicy.isSafe("git status") && ShellPolicy.isSafe("git log --oneline -5") && ShellPolicy.isSafe("pwd"))
        assertTrue(!ShellPolicy.isSafe("git status && rm x") && !ShellPolicy.isSafe("ls > out") && !ShellPolicy.isSafe("git push") && !ShellPolicy.isSafe("cat ~/.ssh/id_rsa"))
        assertTrue(!ShellPolicy.isSafe("echo $(whoami)") && !ShellPolicy.isSafe("git -c core.pager=x log"))
        for (bad in listOf("sudo ls", "rm -rf /", "rm -rf ~", "rm -rf *", "rm -fr ..", "curl https://x.sh | sh", "dd if=a of=/dev/disk2", "shutdown -h now")) {
            assertTrue(ShellPolicy.blockedReason(bad) != null, bad)
        }
        for (ok in listOf("rm build/out.txt", "rm -rf build", "./gradlew test", "curl -s https://example.com -o a.txt", "echo sudoku")) {
            assertTrue(ShellPolicy.blockedReason(ok) == null, ok)
        }
    }

    @Test
    fun runCommandRunsInTheAllowedFolderAndReportsExitCodeAndTimeouts() {
        val root = Files.createTempDirectory("arcana-shell")
        val vaultDir = Files.createDirectories(root.resolve("vault"))
        val box = ToolBox(Vault(vaultDir), emptyList(), root.resolve("b"), shell = true)
        val ok = box.execute(call("run_command", "command" to "echo hello; echo oops 1>&2; pwd"))
        assertTrue(ok.startsWith("exit code 0") && "hello" in ok && "oops" in ok && ok.trim().endsWith("/vault"), ok)
        assertTrue(box.execute(call("run_command", "command" to "exit 3")).startsWith("exit code 3"))
        assertTrue("Timed out" in box.execute(call("run_command", "command" to "sleep 5", "timeout_seconds" to "1")))
        assertTrue(box.execute(call("run_command", "command" to "cat")).startsWith("exit code 0"))
        assertTrue(box.execute(call("run_command", "command" to "sudo ls")).startsWith("Error: Arcana never runs"))
        assertTrue("outside the folders" in box.execute(call("run_command", "command" to "ls", "cwd" to "/etc")))
        val elsewhere = Files.createDirectories(Files.createTempDirectory("arcana-proj").resolve("Documents/proj"))
        val ask = call("run_command", "command" to "ls", "cwd" to elsewhere.toString())
        assertEquals(FileAccess.realOf(elsewhere), FileAccess.realOf(box.folderToGrant(ask)!!))
        box.allow(elsewhere)
        assertTrue(box.execute(ask).startsWith("exit code 0"))
        val plan = ToolBox(Vault(vaultDir), emptyList(), root.resolve("b"), readOnly = true)
        assertTrue(plan.find("run_command") == null)
        assertTrue("run_command" in box.describe())
    }

    @Test
    fun gitDiffShowsChangesReadOnlyAndRefusesOddArguments() {
        val root = Files.createTempDirectory("arcana-git")
        val vaultDir = Files.createDirectories(root.resolve("vault"))
        val box = ToolBox(Vault(vaultDir), emptyList(), root.resolve("b"))
        assertTrue(box.find("git_diff")!!.risk == ToolRisk.Read)
        fun git(vararg args: String) = ProcessBuilder("git", "-c", "user.name=t", "-c", "user.email=t@t", *args).directory(vaultDir.toFile()).redirectErrorStream(true).start().also { it.inputStream.readBytes(); it.waitFor() }
        git("init", "-q")
        Files.writeString(vaultDir.resolve("a.md"), "one\n")
        git("add", "."); git("commit", "-q", "-m", "first")
        Files.writeString(vaultDir.resolve("a.md"), "two\n")
        val patch = box.execute(call("git_diff"))
        assertTrue(patch.startsWith("exit code 0") && "-one" in patch && "+two" in patch, patch)
        assertTrue("a.md" in box.execute(call("git_diff", "summary" to "names")))
        assertEquals("exit code 0 (no output)", box.execute(call("git_diff", "staged" to "true")))
        assertTrue(box.execute(call("git_diff", "summary" to "x")).startsWith("Error:"))
        assertTrue(box.execute(call("git_diff", "path" to "a'; rm -rf x; '")).startsWith("Error:"))
    }

    @Test
    fun contextLengthIsReadFromModelInfoAndAskedForWhenSet() {
        val body = """{"capabilities":["tools"],"model_info":{"general.architecture":"gemma4","gemma4.context_length":131072,"gemma4.embedding_length":2560}}"""
        assertEquals(131072, OllamaProtocol.parseContextLength(body))
        assertEquals(0, OllamaProtocol.parseContextLength("""{"capabilities":[]}"""))
        val withCtx = OllamaProtocol.chatRequest(AppConfig(contextTokens = 16384), listOf(ChatMessage.user("hi")))
        assertEquals(16384, ((withCtx["options"] as kotlinx.serialization.json.JsonObject)["num_ctx"] as kotlinx.serialization.json.JsonPrimitive).content.toInt())
        assertTrue("num_ctx" !in (OllamaProtocol.chatRequest(AppConfig(), listOf(ChatMessage.user("hi")))["options"] as kotlinx.serialization.json.JsonObject))
    }

    @Test
    fun webAddressesOnTheOwnMachineOrNetworkAreRefused() {
        for (bad in listOf("ftp://example.com/a", "file:///etc/passwd", "http://localhost:8080/", "http://127.0.0.1/", "http://10.0.0.5/", "http://192.168.1.1/admin",
            "http://172.16.0.9/", "http://169.254.169.254/latest/meta-data", "http://[::1]/", "http://0.0.0.0/", "http://100.64.1.2/", "http://user:pw@example.com/", "not a url", "http:///nohost")) {
            assertTrue(WebPolicy.refusal(bad) != null, bad)
        }
        assertTrue(WebPolicy.refusal("http://127.0.0.1/", allowPrivate = true) == null)
    }

    @Test
    fun htmlBecomesReadableText() {
        val html = """<html><head><title>Docs &amp; more</title><style>p{color:red}</style><script>alert(1)</script></head>
            <body><h1>Intro</h1><p>Hello <b>world</b> &mdash; see <a href="https://x.dev/a">the guide</a>.</p><ul><li>one</li><li>two &#38; three</li></ul><!-- hidden --></body></html>"""
        val text = htmlToText(html)
        assertTrue(text.startsWith("# Docs & more"), text)
        assertTrue("# Intro" in text && "Hello world — see the guide (https://x.dev/a)." in text && "- one" in text && "- two & three" in text, text)
        assertTrue("alert" !in text && "color:red" !in text && "hidden" !in text, text)
    }

    @Test
    fun fetchUrlReadsTextFollowsSafeRedirectsAndReadsLongPagesInParts() {
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0)
        fun reply(ex: com.sun.net.httpserver.HttpExchange, code: Int, type: String, body: ByteArray) {
            ex.responseHeaders.add("Content-Type", type); ex.sendResponseHeaders(code, body.size.toLong()); ex.responseBody.use { it.write(body) }
        }
        server.createContext("/page") { reply(it, 200, "text/html; charset=utf-8", "<html><title>T</title><body><p>Hi there</p></body></html>".toByteArray()) }
        server.createContext("/long") { reply(it, 200, "text/plain", "x".repeat(25_000).toByteArray()) }
        server.createContext("/json") { reply(it, 200, "application/json", """{"a":1}""".toByteArray()) }
        server.createContext("/gone") { reply(it, 404, "text/plain", "no".toByteArray()) }
        server.createContext("/bin") { reply(it, 200, "image/png", byteArrayOf(1, 2, 3)) }
        server.createContext("/go") { it.responseHeaders.add("Location", "/page"); it.sendResponseHeaders(302, -1); it.close() }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            assertTrue("Hi there" in fetchUrl("$base/page", allowPrivate = true))
            assertTrue("Hi there" in fetchUrl("$base/go", allowPrivate = true))
            assertTrue("""{"a":1}""" in fetchUrl("$base/json", allowPrivate = true))
            val long = fetchUrl("$base/long", allowPrivate = true)
            assertTrue("continue with offset=20000" in long, long.takeLast(80))
            assertTrue("continue" !in fetchUrl("$base/long", offset = 20_000, allowPrivate = true))
            assertTrue(runCatching { fetchUrl("$base/gone", allowPrivate = true) }.exceptionOrNull()!!.message!!.contains("HTTP 404"))
            assertTrue(runCatching { fetchUrl("$base/bin", allowPrivate = true) }.exceptionOrNull()!!.message!!.contains("not text"))
            // the real tool never reaches this server: it is on the loopback address
            val vaultRoot = Files.createTempDirectory("arcana-web")
            val box = ToolBox(null, listOf(vaultRoot), vaultRoot.resolve("b"), web = true)
            assertTrue(box.execute(call("fetch_url", "url" to "$base/page")).startsWith("Error:"))
            assertTrue("fetch_url" in box.describe())
            assertTrue(ToolBox(null, listOf(vaultRoot), vaultRoot.resolve("b"), readOnly = true).find("fetch_url") == null)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun deleteGoesToTrashAndMoveNeverOverwritesAndBothAlwaysAsk() {
        val root = Files.createTempDirectory("arcana-del")
        val vaultDir = Files.createDirectories(root.resolve("vault"))
        val other = Files.createDirectories(root.resolve("proj"))
        Files.writeString(vaultDir.resolve("note.md"), "keep me")
        Files.createDirectories(vaultDir.resolve("old/sub")); Files.writeString(vaultDir.resolve("old/sub/a.txt"), "a")
        Files.writeString(other.resolve("x.kt"), "class X")
        Files.createDirectories(vaultDir.resolve(".obsidian"))
        val box = ToolBox(Vault(vaultDir), listOf(other), root.resolve("cfg/backups"))
        for (name in listOf("delete_path", "move_path")) assertTrue(box.find(name)!!.alwaysAsk && box.find(name)!!.confirmHeading != null, name)
        assertTrue(ToolBox(Vault(vaultDir), emptyList(), root.resolve("b"), readOnly = true).find("delete_path") == null)

        val preview = box.find("delete_path")!!.preview(call("delete_path", "path" to "old").arguments)
        assertTrue("folder with 2 items" in preview.first() && ".trash" in preview.last(), preview.toString())
        val gone = box.execute(call("delete_path", "path" to "note.md"))
        assertTrue(gone.startsWith("Moved note.md to the trash") && !Files.exists(vaultDir.resolve("note.md")), gone)
        val trashed = Files.list(vaultDir.resolve(".trash")).use { it.toList() }.single()
        assertEquals("keep me", Files.readString(trashed))
        assertTrue(box.execute(call("delete_path", "path" to "old")).startsWith("Moved"))
        assertTrue(Files.exists(Files.list(vaultDir.resolve(".trash")).use { s -> s.toList() }.first { it.fileName.toString().endsWith("-old") }.resolve("sub/a.txt")))
        val outside = box.execute(call("delete_path", "path" to other.resolve("x.kt").toString()))
        assertTrue(outside.startsWith("Moved") && Files.exists(root.resolve("cfg/trash")) && !Files.exists(other.resolve("x.kt")), outside)

        assertTrue("allowed folder itself" in box.execute(call("delete_path", "path" to ".")))
        assertTrue("allowed folder itself" in box.execute(call("delete_path", "path" to other.toString())))
        assertTrue("protected" in box.execute(call("delete_path", "path" to ".obsidian")))
        assertTrue(box.execute(call("delete_path", "path" to "missing.md")).contains("does not exist"))

        Files.writeString(vaultDir.resolve("a.md"), "A"); Files.writeString(vaultDir.resolve("b.md"), "B")
        assertTrue("already exists" in box.execute(call("move_path", "from" to "a.md", "to" to "b.md")))
        assertEquals("B", Files.readString(vaultDir.resolve("b.md")))
        assertTrue(box.execute(call("move_path", "from" to "a.md", "to" to "Archive/2026/a.md")).startsWith("Moved"))
        assertEquals("A", Files.readString(vaultDir.resolve("Archive/2026/a.md")))
        assertTrue("into itself" in box.execute(call("move_path", "from" to "Archive", "to" to "Archive/2026/x")))
        val elsewhere = Files.createTempDirectory("arcana-elsewhere").resolve("Documents/p")
        val grant = box.folderToGrant(call("move_path", "from" to "b.md", "to" to elsewhere.resolve("b.md").toString()))
        assertEquals(FileAccess.realOf(Files.createDirectories(elsewhere).parent.parent), FileAccess.realOf(grant!!).let { FileAccess.realOf(it) }.let { FileAccess.realOf(elsewhere.parent.parent) }.let { it })
    }

    @Test
    fun webSearchParsesAllThreeBackendsAndAsksBeforeEverySearch() {
        val ddg = """<div class="results"><div class="result results_links"><h2 class="result__title"><a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fa%3Fx%3D1&amp;rut=abc">Example <b>Domain</b> &amp; more</a></h2>
            <a class="result__snippet" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fa">This domain is for use in <b>examples</b>.</a></div>
            <div class="result result--ad"><a class="result__a" href="https://duckduckgo.com/y.js?ad=1">An ad</a><a class="result__snippet">ad text</a></div>
            <div class="result"><a class="result__a" href="https://kotlinlang.org/docs">Kotlin docs</a><a class="result__snippet" href="x">Official <b>documentation</b></a></div></div>"""
        val d = WebSearch.parseDuckDuckGo(ddg, 5)
        assertEquals(listOf("Example Domain & more", "Kotlin docs"), d.map { it.title })
        assertEquals("https://example.com/a?x=1", d[0].url)
        assertEquals("This domain is for use in examples.", d[0].snippet)
        assertEquals(1, WebSearch.parseDuckDuckGo(ddg, 1).size)
        assertTrue(runCatching { WebSearch.parseDuckDuckGo("<html>Unfortunately, bots use DuckDuckGo too. anomaly-modal</html>", 5) }.exceptionOrNull()!!.message!!.contains("human check"))
        val searx = WebSearch.parseSearx("""{"results":[{"title":"A","url":"https://a.dev","content":"about <b>a</b>"},{"url":"https://b.dev"},{"title":"no url"}]}""", 5)
        assertEquals(listOf("A", "https://b.dev"), searx.map { it.title }); assertEquals("about a", searx[0].snippet)
        val brave = WebSearch.parseBrave("""{"web":{"results":[{"title":"Brave <strong>hit</strong>","url":"https://c.dev","description":"desc"}]}}""", 5)
        assertEquals("Brave hit", brave.single().title)
        assertTrue(WebSearch.format("kotlin", "duckduckgo", d).contains("1. Example Domain & more\n   https://example.com/a?x=1\n   This domain"))
        assertTrue(WebSearch.format("zzz", "brave", emptyList()).startsWith("No results"))
        // a SearXNG server on the user's own network is reachable (they configured it); the tool asks before every search
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/search") { ex ->
            val body = """{"results":[{"title":"Hit for ${ex.requestURI.query.substringAfter("q=").substringBefore("&")}","url":"https://hit.dev","content":"snippet"}]}""".toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json"); ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val root = Files.createTempDirectory("arcana-search")
            val box = ToolBox(null, listOf(root), root.resolve("b"), web = true, searchBackend = "searxng", searchUrl = "http://127.0.0.1:${server.address.port}")
            val out = box.execute(call("web_search", "query" to "kotlin flow", "limit" to "3"))
            assertTrue("1. Hit for kotlin+flow" in out && "https://hit.dev" in out, out)
            val spec = box.find("web_search")!!
            assertEquals(ToolRisk.Web, spec.risk)
            assertTrue(spec.preview(call("web_search", "query" to "kotlin flow").arguments).any { "SearXNG" in it })
            assertTrue("No SearXNG address" in ToolBox(null, listOf(root), root.resolve("b"), searchBackend = "searxng").execute(call("web_search", "query" to "x")))
            assertTrue("No Brave Search API key" in ToolBox(null, listOf(root), root.resolve("b"), searchBackend = "brave").execute(call("web_search", "query" to "x")))
            assertTrue("- web_search(query, limit?)" in box.cheatSheet())
            assertTrue(ToolBox(null, listOf(root), root.resolve("b"), readOnly = true).find("web_search") == null)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun skillsAreFoundLoadedAndOfferedToTheModel() {
        val root = Files.createTempDirectory("arcana-skills")
        fun file(path: String, text: String) { root.resolve(path).also { Files.createDirectories(it.parent) }.let { Files.writeString(it, text) } }
        file("Skills/demo/demo.skill.md", "---\nname: demo\ndescription: \"Does the demo thing. Use it for demos.\"\nversion: 2\noutput: Reports/demo/\ntype: skill\n---\n\n# Demo\n\nStep 1: say hello.\n")
        file("Skills/demo/reference/Facts.md", "facts")
        file("Skills/demo/scripts/run.py", "print('hi')")
        file("Skills/demo/.hidden/x.md", "x")
        for (i in 1..12) file("Skills/demo/reports/r$i.md", "r")
        file("Skills/broken/broken.skill.md", "---\nname: other\n---\n\n" + "x".repeat(Skills.MAX_CHARS + 10))
        file("Skills/notaskill/readme.md", "no entry note named like the folder")
        file("Skills/plain/plain.md", "---\ndescription: d\n---\nan old-style entry without .skill is not a skill")
        file("Skills/Bad Name/Bad Name.skill.md", "---\ndescription: d\n---\nbody")
        val vault = Vault(root)

        val found = Skills.discover(vault, "Skills")
        assertEquals(listOf("Bad Name", "broken", "demo"), found.map { it.name })
        val demo = Skills.find(found, "/DEMO")!!
        assertEquals(listOf("reference/Facts.md", "reports/r1.md", "reports/r10.md", "reports/r11.md", "reports/r12.md", "reports/r2.md", "reports/r3.md", "reports/r4.md", "reports/r5.md", "reports/… 4 more (list the folder to see them)", "scripts/run.py"), demo.files)
        assertEquals("2", demo.version)
        assertEquals("Reports/demo", demo.output, "the output folder, without the trailing slash")
        assertEquals("", found.first { it.name == "broken" }.output)
        assertTrue(demo.problems.isEmpty(), demo.problems.toString())
        assertEquals("Skills/demo/demo.skill.md", demo.path)
        val broken = found.first { it.name == "broken" }
        assertTrue(broken.problems.any { "description" in it } && broken.problems.any { "differs" in it } && broken.problems.any { "characters" in it }, broken.problems.toString())
        assertTrue(found.first { it.name == "Bad Name" }.problems.any { "folder name" in it })
        assertTrue(Skills.discover(vault, "Nothing").isEmpty() && Skills.discover(vault, "").isEmpty() && Skills.discover(vault, "../x").isEmpty())

        assertTrue(Skills.isEntry("Skills/demo/demo.skill.md") && !Skills.isEntry("Skills/demo/reference/Facts.md") && !Skills.isEntry("Skills/plain/plain.md"))
        assertEquals("demo", Skills.nameOfEntry("Skills/demo/demo.skill.md"))
        assertEquals("◆ skill demo", Skills.chipLabel("Skills/demo/demo.skill.md"))
        assertNull(Skills.chipLabel("Notes/Idea.md"))
        assertEquals(Skills.load(vault, demo), Skills.loadEntry(vault, "Skills/demo/demo.skill.md"), "an attached entry note loads like use_skill")
        assertNull(Skills.loadEntry(vault, "Skills/demo/reference/Facts.md"))
        assertNull(Skills.loadEntry(vault, "Skills/ghost/ghost.skill.md"))
        val prompt = Prompts.chatMessages(AppConfig(), listOf(ChatMessage.user("run it", notes = listOf("Skills/demo/demo.skill.md", "Skills/demo/reference/Facts.md"))), null,
            mapOf("Skills/demo/demo.skill.md" to Skills.load(vault, demo), "Skills/demo/reference/Facts.md" to "facts"))
        val sent = prompt.last().content
        assertTrue("SKILL “demo”" in sent && "do not call use_skill" in sent && "Step 1: say hello." in sent && "Attached note “Facts”" in sent, sent)

        val plainAsk = listOf(ChatMessage.user("hi"))
        val skillAsk = listOf(ChatMessage.user("old", notes = listOf("Skills/demo/demo.skill.md")), ChatMessage.assistant("ok"), ChatMessage.user("run it", notes = listOf("Skills/demo/demo.skill.md")))
        assertEquals(8, Skills.stepsFor(plainAsk, 8))
        assertEquals(Skills.RUN_STEPS, Skills.stepsFor(skillAsk, 8), "a skill run gets room for its many steps")
        assertEquals(60, Skills.stepsFor(skillAsk, 60), "a larger user setting wins")
        assertEquals(8, Skills.stepsFor(skillAsk + ChatMessage.user("and now something else"), 8), "only the latest message counts")

        val text = Skills.load(vault, demo)
        assertTrue(text.startsWith("SKILL demo v2\nFolder: ") && text.lineSequence().drop(1).first().endsWith("Skills/demo"), text)
        assertTrue("Results go to (outside the skill): Reports/demo\n" in text && text.indexOf("Results go to") < text.indexOf("Files in the skill"), text)
        assertTrue("- Skills/demo/reference/Facts.md" in text && "- Skills/demo/scripts/run.py" in text && "Step 1: say hello." in text && "description:" !in text, text)

        val box = ToolBox(vault, emptyList(), root.resolve("b"), skillsFolder = "Skills")
        assertTrue("- use_skill(name)" in box.cheatSheet())
        assertTrue("- demo: Does the demo thing" in box.describe() && "call use_skill" in box.describe(), box.describe())
        assertTrue("Step 1: say hello." in box.execute(call("use_skill", "name" to "demo")))
        assertTrue(box.execute(call("use_skill", "name" to "nope")).let { it.startsWith("Error: There is no skill called \"nope\"") && "demo" in it })
        assertTrue(ToolBox(vault, emptyList(), root.resolve("b"), readOnly = true, skillsFolder = "Skills").find("use_skill") != null, "reading a skill is allowed in plan mode")
        assertNull(ToolBox(vault, emptyList(), root.resolve("b")).find("use_skill"), "skills off: no tool")
        assertNull(ToolBox(vault, emptyList(), root.resolve("b"), skillsFolder = "Empty").find("use_skill"), "no skills: no tool")
        assertTrue("SKILLS" !in ToolBox(vault, emptyList(), root.resolve("b")).describe())
    }

    @Test
    fun noteToolsCanSetPropertiesWithoutAFileSystemEdit() {
        val root = Files.createTempDirectory("arcana-props")
        val vault = Vault(root)
        // a properties block in the content is merged with Arcana's own: one block, its keys win, nothing doubled
        val given = "---\ntags: [forecast, defi]\nllm: \"qwen3:30b\"\nstatus: running\nrelated:\n  - \"[[a]]\"\n  - \"[[b]]\"\n---\n\n# Report\n\nbody\n"
        val note = vault.create("Out", "Report one", given, "gemma4:latest")
        val text = vault.read(note.path)
        assertEquals(1, Regex("(?m)^---$").findAll(text.substringBefore("# Report")).count() / 2, text)
        assertEquals(2, Regex("(?m)^---$").findAll(text.substringBefore("# Report")).count(), text)
        assertTrue(text.startsWith("---\ncreated: ") && "generator: arcana\nmodel: gemma4:latest\ntags: [forecast, defi]\nllm: \"qwen3:30b\"\nstatus: running\nrelated:\n  - \"[[a]]\"\n  - \"[[b]]\"\n---\n\n# Report" in text, text)
        assertTrue("tags: [arcana]" !in text)
        // without a block it is as before
        assertTrue("tags: [arcana]" in vault.read(vault.create("Out", "Plain", "just text", "m").path))

        // set_properties: replaces a key, adds a new one, keeps the rest (and a list value), leaves the text alone
        val box = ToolBox(vault, emptyList(), root.resolve("b"))
        val out = box.execute(call("set_properties", "path" to note.path, "properties" to "status: done\nfinished: 2026-10-26T18:05:12\ntotal_work_time: 5 min 12 s"))
        assertEquals("Set status, finished, total_work_time in ${note.path}", out)
        val after = vault.read(note.path)
        assertTrue("llm: \"qwen3:30b\"\nstatus: done\nrelated:" in after && "status: running" !in after, after) // replaced in place, order kept
        assertTrue("related:\n  - \"[[a]]\"\n  - \"[[b]]\"" in after && "llm: \"qwen3:30b\"" in after && "total_work_time: 5 min 12 s\n---\n\n# Report\n\nbody" in after, after)
        assertEquals(1, Regex("(?m)^status:").findAll(after).count())
        // a note with no properties gets a block; bad input is an error the model can read
        val bare = root.resolve("Bare.md").also { Files.writeString(it, "# Bare\n\ntext\n") }
        assertEquals("Set a in Bare", box.execute(call("set_properties", "path" to "Bare", "properties" to "a: 1")))
        assertEquals("---\na: 1\n---\n\n# Bare\n\ntext\n", Files.readString(bare))
        assertTrue(box.execute(call("set_properties", "path" to "Bare", "properties" to "no colon here")).startsWith("Error: Give the properties as lines"))
        assertEquals(ToolRisk.Write, box.find("set_properties")?.risk)
        assertTrue("- set_properties(path, properties)" in box.cheatSheet())
        assertTrue(ToolBox(vault, emptyList(), root.resolve("b"), readOnly = true).find("set_properties") == null, "plan mode cannot change properties")
    }

    @Test
    fun theModelCanMeasureElapsedTimeWithoutDoingTheMath() {
        assertEquals("0 s", describeDuration(0))
        assertEquals("45 s", describeDuration(45))
        assertEquals("5 min 12 s", describeDuration(312))
        assertEquals("1 min 0 s", describeDuration(60))
        assertEquals("1 h 2 min 3 s", describeDuration(3723))
        assertEquals("1 h 0 min 0 s", describeDuration(3600))
        assertEquals("2 d 3 h 4 min 5 s", describeDuration(2 * 86400 + 3 * 3600 + 4 * 60 + 5L))
        val kyiv = java.time.ZoneId.of("Europe/Kyiv")
        val start = parseMoment("2026-10-26T18:05:00", kyiv)
        assertEquals(start, parseMoment("2026-10-26 18:05:00", kyiv))
        assertEquals(start, parseMoment("2026-10-26T18:05:00+02:00"))
        assertEquals(start, parseMoment("2026-10-26T16:05:00Z"))
        assertEquals(start, parseMoment("${start.epochSecond}"))
        assertEquals(start, parseMoment("${start.toEpochMilli()}"))
        assertEquals("total_work_time=5 min 12 s\nseconds=312\nfrom=2026-10-26T18:05:00\nto=2026-10-26T18:10:12", describeElapsed(start, start.plusSeconds(312), kyiv))
        assertTrue(runCatching { parseMoment("yesterday") }.exceptionOrNull()?.message!!.contains("is not a time"))
        assertTrue(runCatching { describeElapsed(start.plusSeconds(5), start, kyiv) }.exceptionOrNull()?.message == "The end is before the start.")

        val root = Files.createTempDirectory("arcana-elapsed")
        val box = ToolBox(null, listOf(root), root.resolve("b"))
        val now = java.time.ZonedDateTime.now().minusSeconds(75).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))
        val out = box.execute(call("elapsed_time", "from" to now))
        assertTrue(Regex("""total_work_time=1 min \d+ s\nseconds=7\d\nfrom=$now\nto=\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d""").matches(out), out)
        assertEquals("total_work_time=1 h 0 min 0 s\nseconds=3600\nfrom=2026-10-26T10:00:00\nto=2026-10-26T11:00:00",
            box.execute(call("elapsed_time", "from" to "2026-10-26T10:00:00", "to" to "2026-10-26T11:00:00")))
        assertTrue(box.execute(call("elapsed_time", "from" to "soon")).startsWith("Error: \"soon\" is not a time"))
        assertEquals(ToolRisk.Read, box.find("elapsed_time")?.risk)
        assertTrue("- elapsed_time(from, to?)" in box.cheatSheet(), box.cheatSheet())
    }

    @Test
    fun aSkillCanRequireStepsAndTheRunIsTracked() {
        // the grammar: tool ~ text in the call => text in the result xN | label
        val r = Requirement.parse("run_command ~ check --note => REPORT_COMPLETE=yes | step 11: check")!!
        assertEquals(listOf("run_command", "check --note", "REPORT_COMPLETE=yes", 1, "step 11: check"), listOf(r.tool, r.contains, r.expect, r.count, r.label))
        val a = Requirement.parse("append_to_note x3")!!
        assertEquals(listOf("append_to_note", "", "", 3, ""), listOf(a.tool, a.contains, a.expect, a.count, a.label))
        assertEquals(1, Requirement.parse("create_note | step 6")!!.count)
        assertNull(Requirement.parse("Run Command"), "a tool name is lower-case words with _")
        assertNull(Requirement.parse("run_command ~"), "a ~ needs a text")
        assertNull(Requirement.parse("run_command =>"), "a => needs a text")
        assertEquals("a call of `run_command` whose command contains `snapshot`", Requirement.parse("run_command ~ snapshot")!!.describe())
        assertTrue("3 times" in a.describe())

        // read from a skill's properties (quoted list items, so the YAML stays valid), bad lines reported as problems
        val root = Files.createTempDirectory("arcana-gate")
        val dir = root.resolve("Skills/gated").also { Files.createDirectories(it) }
        Files.writeString(dir.resolve("gated.skill.md"), "---\nname: gated\ndescription: d\nrequires:\n  - \"run_command ~ snapshot | step 4: snapshot\"\n  - \"append_to_note x2 | step 9: parts\"\n  - \"???\"\n  - \"run_command ~ check => REPORT_COMPLETE=yes | step 11: check\"\n---\n\nbody")
        val vault = Vault(root)
        val skill = Skills.discover(vault, "Skills").single()
        assertTrue(skill.problems.single().contains("requires: cannot read the line \"???\""), skill.problems.toString())
        val reqs = Skills.requirementsOf(vault, "Skills/gated/gated.skill.md")
        assertEquals(listOf("run_command", "append_to_note", "run_command"), reqs.map { it.tool })
        assertTrue(Skills.requirementsOf(vault, "Skills/gated/other.md").isEmpty() && Skills.requirementsOf(vault, "Nothing/x.skill.md").isEmpty())

        // a run: only matching calls count; the result can matter; the first missing step is the one named
        val run = SkillRun("gated", reqs)
        assertEquals("0/3".replace("/", " of "), "${run.done} of ${run.total}")
        assertTrue(run.nudge()!!.let { "step 4: snapshot" in it && "STOP" in it && "0 of 3" in it && "snapshot" in it }, "the first missing step comes first")
        run.observe("run_command", "{command=python3 x.py snapshot --network eth}", "exit code 0")
        run.observe("append_to_note", "{path=a}", "Added")
        assertEquals(1, run.done)
        assertTrue("step 9: parts" in run.nudge()!!, "append x2 is not done after one append")
        run.observe("append_to_note", "{path=a}", "Added")
        assertEquals(2, run.done)
        run.observe("run_command", "{command=python3 x.py check --note n}", "exit code 1\nREPORT_COMPLETE=no")
        assertEquals(2, run.done, "a check whose result says no does not count")
        assertTrue(run.nudge()!!.contains("REPORT_COMPLETE=yes"))
        run.observe("run_command", "{command=python3 x.py check --note n}", "exit code 0\nREPORT_COMPLETE=yes")
        assertTrue(run.done == 3 && run.finished && run.nudge() == null && run.summary() == null)

        // a step the model keeps ignoring is given up on after MAX_STRIKES, so a stuck run still ends; the summary says what was left
        val stuck = SkillRun("gated", reqs)
        stuck.observe("run_command", "{command=snapshot}", "ok")
        repeat(SkillRun.MAX_STRIKES) { assertTrue(stuck.nudge()!!.contains("step 9: parts")) }
        assertTrue(stuck.nudge()!!.contains("step 11: check"), "after the strikes the next missing step is asked for")
        repeat(SkillRun.MAX_STRIKES - 1) { assertTrue(stuck.nudge()!!.contains("step 11: check")) }
        assertNull(stuck.nudge())
        assertTrue(stuck.finished && stuck.skippedCount == 2 && stuck.done == 1)
        assertEquals("Skill gated: 1 of 3 required steps done; not done: step 9: parts; step 11: check.", stuck.summary())
    }

    @Test
    fun theModelCanWaitOutARateLimit() {
        val root = Files.createTempDirectory("arcana-wait")
        val box = ToolBox(null, listOf(root), root.resolve("b"))
        val started = System.currentTimeMillis()
        val result = box.execute(call("wait", "seconds" to "1", "reason" to "rate limit"))
        assertTrue(System.currentTimeMillis() - started >= 1000, "it really sleeps")
        assertTrue(result.startsWith("Waited 1 s. ") && "ISO " in result, result)
        assertTrue(box.execute(call("wait")).startsWith("Error: Say how many seconds"))
        assertEquals(ToolRisk.Read, box.find("wait")?.risk, "waiting needs no approval")
        assertTrue("- wait(seconds, reason?)" in box.cheatSheet(), box.cheatSheet())
        assertEquals("30 s - rate limit", box.find("wait")?.summary?.invoke(call("wait", "seconds" to "30", "reason" to "rate limit").arguments))
    }

    @Test
    fun theModelCanAskForTheTimeAndAlwaysKnowsTheDate() {
        val root = Files.createTempDirectory("arcana-time")
        val box = ToolBox(null, listOf(root), root.resolve("b"))
        val now = box.execute(call("current_time"))
        assertTrue(Regex("""\w+ \d+ \w+ \d{4}, \d\d:\d\d:\d\d \(.+, UTC[+-]\d\d:\d\d\); ISO \d{4}-\d\d-\d\dT.+; Unix \d+""").containsMatchIn(now), now)
        val tokyo = box.execute(call("current_time", "timezone" to "Asia/Tokyo"))
        assertTrue("Asia/Tokyo" in tokyo && "UTC+09:00" in tokyo, tokyo)
        assertTrue("not a time zone" in box.execute(call("current_time", "timezone" to "Mars/Base")))
        assertTrue("- current_time(timezone?)" in box.cheatSheet())
        assertEquals("Sunday 25 October 2026, 21:07:45 (Europe/Kyiv, UTC+02:00); ISO 2026-10-25T21:07:45+02:00; Unix 1792955265",
            describeNow(java.time.ZonedDateTime.of(2026, 10, 25, 21, 7, 45, 0, java.time.ZoneId.of("Europe/Kyiv"))))
        val day = java.time.ZonedDateTime.of(2026, 10, 25, 9, 0, 0, 0, java.time.ZoneId.of("UTC"))
        val system = Prompts.chatMessages(AppConfig(), listOf(ChatMessage.user("hi")), null, emptyMap(), today = day).first().content
        assertTrue("Today is Sunday 25 October 2026" in system && "current_time" in system, system)
        assertTrue("Today is" !in Prompts.chatMessages(AppConfig(), listOf(ChatMessage.user("hi")), null, emptyMap(), today = null).first().content)
    }

    @Test
    fun theModelListSaysWhatEachModelCanDo() {
        val tags = """{"models":[{"name":"qwen3:30b","size":19000000000},{"name":"gemma4:latest","size":9500000000},{"name":"tiny","size":400000000}]}"""
        assertEquals(listOf("qwen3:30b" to 19_000_000_000L, "gemma4:latest" to 9_500_000_000L, "tiny" to 400_000_000L), OllamaProtocol.parseModelSizes(tags))
        assertEquals("19 GB · tools · thinking", ModelDetails("qwen3:30b", 19_000_000_000, listOf("completion", "tools", "thinking"), 40960).summary())
        assertEquals("9.5 GB · tools · vision", ModelDetails("g", 9_500_000_000, listOf("tools", "vision"), 0).summary())
        assertEquals("1.2 GB · no tools", ModelDetails("old", 1_200_000_000, listOf("completion"), 0).summary())
        assertEquals("400 MB", ModelDetails("x", 400_000_000, emptyList(), 0).summary())
    }

    @Test
    fun aToolCallWrittenAsTextIsFoundOnlyAtTheEndOfAKnownTool() {
        val tools = setOf("fetch_url", "read_file", "grep_files", "ask_user")
        val screenshot = "I will use the fetch_url tool to read the content of the provided link.\n\nfetch_url(url=\"https://www.youtube.com/watch?v=9UMxZofMNbA\")"
        val (calls, rest) = TextToolCalls.extract(screenshot, tools)
        assertEquals(1, calls.size)
        assertEquals("fetch_url", calls[0].name)
        assertEquals("https://www.youtube.com/watch?v=9UMxZofMNbA", (calls[0].arguments["url"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals("I will use the fetch_url tool to read the content of the provided link.", rest)
        // several calls, single quotes, numbers, booleans and arrays, inside a code fence
        val many = TextToolCalls.extract("Let me look.\n```tool_code\nread_file(path='a (1).txt', start_line=3, end_line=40)\ngrep_files(pattern=\"x\\\"y\")\nask_user(question=\"Pick\", options=[\"a\", \"b\"], multiple=true)\n```", tools)
        assertEquals(listOf("read_file", "grep_files", "ask_user"), many.first.map { it.name })
        assertEquals("a (1).txt", (many.first[0].arguments["path"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals("3", (many.first[0].arguments["start_line"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals("x\"y", (many.first[1].arguments["pattern"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals(2, (many.first[2].arguments["options"] as kotlinx.serialization.json.JsonArray).size)
        assertEquals("Let me look.", many.second)
        // the JSON form
        val json = TextToolCalls.extract("Reading it.\n\n```json\n{\"name\": \"read_file\", \"arguments\": {\"path\": \"x.md\"}}\n```", tools)
        assertEquals("read_file", json.first.single().name)
        assertEquals("Reading it.", json.second)
        assertEquals("read_file", TextToolCalls.extract("""{"name":"read_file","parameters":{"path":"y"}}""", tools).first.single().name)
        // never: an unknown tool, an explanation in the middle of an answer, a call that is not at the end, broken syntax
        assertTrue(TextToolCalls.extract("delete_everything(path=\"/\")", tools).first.isEmpty())
        assertTrue(TextToolCalls.extract("To read a page you can write fetch_url(url=\"https://a.b\") and I will do it.", tools).first.isEmpty())
        assertTrue(TextToolCalls.extract("fetch_url(url=\"https://a.b\")\nand then I explain what it does.", tools).first.isEmpty())
        assertTrue(TextToolCalls.extract("fetch_url(url=\"https://a.b\"", tools).first.isEmpty())
        assertTrue(TextToolCalls.extract("fetch_url(url=https://a.b)", tools).first.isEmpty())
        assertTrue(TextToolCalls.extract("fetch_url(url=\"x\")", emptySet()).first.isEmpty())
    }

    @Test
    fun renamingANoteRewritesTheLinksToItLikeObsidianDoes() {
        val root = Files.createTempDirectory("arcana-rename")
        val vault = Vault(root)
        Files.createDirectories(root.resolve("Notes")); Files.createDirectories(root.resolve("Other"))
        Files.writeString(root.resolve("Notes/Old.md"), "# Old\nsee [[Old]] itself")
        Files.writeString(root.resolve("Other/Same.md"), "x")
        Files.writeString(
            root.resolve("A.md"),
            "[[Old]] and [[Old|the alias]] and [[Old#Heading]] and [[old]] and ![[Old]] and [[Notes/Old]] and [[Notes/Old.md#H|x]] and [[Older]]\n" +
                "`[[Old]]` stays\n```\n[[Old]] in code\n```\nand [[Old]] after",
        )
        Files.writeString(root.resolve("B.md"), "no links here")
        val result = vault.rename("Notes/Old.md", "New: name?")
        assertEquals("Notes/New name.md", result.path)
        assertEquals(1, result.notesChanged)
        assertTrue(Files.exists(root.resolve("Notes/New name.md")) && !Files.exists(root.resolve("Notes/Old.md")))
        val a = Files.readString(root.resolve("A.md"))
        assertEquals(
            "[[New name]] and [[New name|the alias]] and [[New name#Heading]] and [[New name]] and ![[New name]] and [[Notes/New name]] and [[Notes/New name#H|x]] and [[Older]]\n" +
                "`[[Old]]` stays\n```\n[[Old]] in code\n```\nand [[New name]] after",
            a,
        )
        assertEquals(8, result.linksUpdated)
        assertEquals("no links here", Files.readString(root.resolve("B.md")))
        // back again: everything as it was
        val back = vault.rename("Notes/New name.md", "Old")
        assertEquals("Notes/Old.md", back.path)
        assertTrue("[[Old]] and [[Old|the alias]] and [[Old#Heading]]" in Files.readString(root.resolve("A.md")))
        // refusals
        assertTrue(runCatching { vault.rename("Notes/Old.md", "Old") }.exceptionOrNull() is VaultException)
        Files.writeString(root.resolve("Notes/Taken.md"), "t")
        assertTrue("already exists" in runCatching { vault.rename("Notes/Old.md", "Taken") }.exceptionOrNull()!!.message!!)
        assertTrue(runCatching { vault.rename("../x.md", "y") }.exceptionOrNull() is VaultException)
        assertTrue(runCatching { vault.rename("Missing.md", "y") }.exceptionOrNull() is VaultException)
        // a title two notes share is not guessed at: only the full-path link is rewritten
        Files.writeString(root.resolve("Other/Old.md"), "another")
        Files.writeString(root.resolve("C.md"), "[[Old]] [[Notes/Old]]")
        vault.rename("Notes/Old.md", "Renamed")
        assertEquals("[[Old]] [[Notes/Renamed]]", Files.readString(root.resolve("C.md")))
        // only the case changes
        assertEquals("Notes/renamed.md", vault.rename("Notes/Renamed.md", "renamed").path)
        assertTrue(Files.list(root.resolve("Notes")).use { s -> s.toList().any { it.fileName.toString() == "renamed.md" } })
    }

    @Test
    fun theMemoryFileIsReadCappedRememberedAndSentWithEveryChat() {
        val root = Files.createTempDirectory("arcana-memory")
        val vault = Vault(root)
        assertEquals("", vault.memory())
        val file = vault.ensureMemory()
        assertEquals(root.resolve("ARCANA.md"), file)
        assertTrue("## Remembered" in Files.readString(file))
        assertTrue("Arcana memory" in vault.memory())
        vault.remember("I write in Ukrainian and English")
        vault.remember("Projects live in ~/IDEA Projects\nand more")
        val text = Files.readString(file)
        assertTrue(text.trimEnd().endsWith("- Projects live in ~/IDEA Projects and more") && "- I write in Ukrainian and English" in text, text)
        Files.writeString(file, "---\ntags: [x]\n---\n" + "z".repeat(5000))
        assertTrue(vault.memory().length < 4200 && "cut" in vault.memory() && "tags" !in vault.memory())
        val system = Prompts.chatMessages(AppConfig(), listOf(ChatMessage.user("hi")), null, emptyMap(), memory = "Always answer in French.").first().content
        assertTrue("ARCANA.md" in system && "Always answer in French." in system)
        assertTrue("ARCANA.md" !in Prompts.chatMessages(AppConfig(), listOf(ChatMessage.user("hi")), null, emptyMap()).first().content)
    }

    @Test
    fun theToolCheatSheetNamesEveryToolWithItsParameters() {
        val root = Files.createTempDirectory("arcana-sheet")
        val box = ToolBox(Vault(Files.createDirectories(root.resolve("v"))), emptyList(), root.resolve("b"))
        val sheet = box.cheatSheet()
        for (spec in box.specs) assertTrue("- ${spec.name}(" in sheet, spec.name)
        assertTrue("- edit_file(path, old, new)" in sheet && "- read_file(path, start_line?, end_line?)" in sheet && "- request_access(reason)" in sheet, sheet)
        assertTrue("remember(text)" in sheet && "ask_user(question, options?, multiple?)" in sheet)
        assertTrue(box.find("remember")!!.alwaysAsk)
        assertTrue("exactly these names" in box.describe())
        box.execute(call("remember", "text" to "likes tea"))
        assertTrue("- likes tea" in Files.readString(root.resolve("v/ARCANA.md")))
    }

    @Test
    fun answerOptionsLoseTheirOwnLetters() {
        assertEquals("list_dir", cleanOption("A) list_dir"))
        assertEquals("search_notes", cleanOption("  b. search_notes "))
        assertEquals("grep_files", cleanOption("(C) grep_files"))
        assertEquals("find_files", cleanOption("D: find_files"))
        assertEquals("Coroutines", cleanOption("3) Coroutines"))
        assertEquals("Null safety", cleanOption("12. Null safety"))
        assertEquals("Hello, World!", cleanOption("Hello, World!"))
        assertEquals("A", cleanOption("A"))
        assertEquals("A)", cleanOption("A) "), "an option that is only a label is kept as it is")
        assertEquals("Yes, please", cleanOption("Yes, please"))
        assertEquals("search_notes", cleanOption("`search_notes`"))
        assertEquals("list_dir", cleanOption("B) **list_dir**"))
        assertEquals("1999 was the year", cleanOption("1999 was the year"))
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
    fun aFolderOutsideTheSandboxCanBeOfferedAndThenAllowed() {
        val home = Files.createTempDirectory("arcana-home")
        val box = ToolBox(Vault(Files.createDirectories(home.resolve("vault"))), emptyList(), home.resolve("backups"))
        val outside = Files.createDirectories(Files.createTempDirectory("arcana-out").resolve("Desktop/projects"))
        Files.writeString(outside.resolve("a.txt"), "hello")
        val read = call("read_file", "path" to outside.resolve("a.txt").toString())
        assertTrue(box.execute(read).startsWith("Error:"))
        assertEquals(FileAccess.realOf(outside), FileAccess.realOf(box.folderToGrant(read)!!))
        // a note tool never asks for folders, a path that is already allowed does not either
        assertNull(box.folderToGrant(call("search_notes", "query" to "x")))
        assertNull(box.folderToGrant(call("list_dir", "path" to ".")))
        box.allow(box.folderToGrant(read)!!)
        assertEquals("hello", box.execute(read))
        assertNull(box.folderToGrant(read))
        // too broad or hidden folders are not offered
        val userHome = System.getProperty("user.home")
        assertNull(box.folderToGrant(call("list_dir", "path" to userHome)))
        assertNull(box.folderToGrant(call("list_dir", "path" to "/etc")))
        assertNull(box.folderToGrant(call("list_dir", "path" to "$userHome/.ssh")))
    }

    @Test
    fun pathErrorsSayWhatIsWrongAndTheModelKnowsWhereThingsAre() {
        val root = Files.createTempDirectory("arcana-paths")
        val vaultDir = Files.createDirectories(root.resolve("vault"))
        Files.writeString(vaultDir.resolve("a.txt"), "x")
        val box = ToolBox(Vault(vaultDir), emptyList(), root.resolve("backups"))
        val missing = box.execute(call("list_dir", "path" to "IDEA Projects"))
        assertTrue("does not exist" in missing && "absolute path" in missing && "not a folder" !in missing)
        assertTrue("is a file, not a folder" in box.execute(call("list_dir", "path" to "a.txt")))
        assertTrue("is a folder, not a file" in box.execute(call("read_file", "path" to ".")))
        val note = box.describe()
        assertTrue(vaultDir.toString() in note && System.getProperty("user.home") in note && "~/" in note)
        val system = Prompts.chatMessages(AppConfig(), listOf(ChatMessage.user("hi")), null, emptyMap(), tools = true, toolsNote = note).first().content
        assertTrue(System.getProperty("user.home") in system)
    }

    @Test
    fun findFilesSearchesDeepSkipsUnreadableAndHiddenFolders() {
        val root = Files.createTempDirectory("arcana-find")
        val vaultDir = Files.createDirectories(root.resolve("vault"))
        val deep = Files.createDirectories(root.resolve("work/IDEA Projects/Arcana/src"))
        Files.createDirectories(root.resolve("work/.git/Arcana"))
        Files.writeString(Files.createDirectories(root.resolve("work/IDEA Projects/Arcana")).resolve(".gitlab-ci.yml"), "stages: []")
        val locked = Files.createDirectories(root.resolve("work/locked"))
        locked.toFile().setReadable(false)
        try {
            val box = ToolBox(Vault(vaultDir), listOf(root.resolve("work")), root.resolve("backups"))
            val out = box.execute(call("find_files", "name" to "arcana", "path" to root.resolve("work").toString()))
            assertTrue("IDEA Projects/Arcana/" in out, out)
            val tree = box.execute(call("list_dir", "path" to root.resolve("work").toString(), "recursive" to "true"))
            assertTrue("IDEA Projects/Arcana/src/" in tree && ".gitlab-ci.yml" in tree && ".git/Arcana" !in tree, tree)
            Files.writeString(deep.resolve("Api.kt"), "fun api() {}")
            assertTrue("fun api" in box.execute(call("read_file", "path" to "IDEA Projects/Arcana/src/Api.kt")))
            val far = Files.createDirectories(root.resolve("work/Android/app/src/main/java/com/x/y/z/ui")).resolve("MainActivity.kt")
            Files.writeString(far, "class MainActivity")
            assertTrue("MainActivity.kt" in box.execute(call("find_files", "name" to "MainActivity.kt")))
            val lost = box.execute(call("list_dir", "path" to "Arcana/src"))
            assertTrue("Did you mean" in lost && "IDEA Projects/Arcana/src" in lost, lost)
            val old = ChatMessage.toolResult("list_dir", "x".repeat(5000))
            val sent = Prompts.chatMessages(AppConfig(), listOf(old, ChatMessage.assistant("ok"), ChatMessage.user("next"), ChatMessage.toolResult("list_dir", "y".repeat(5000))), null, emptyMap(), tools = true)
            assertTrue(sent[1].content.length < 2000 && sent.last().content.length == 5000)
            assertTrue(".git/" !in out && !out.startsWith("Error"), out)
            assertTrue(".gitlab-ci.yml" in box.execute(call("find_files", "name" to "gitlab-ci")))
            assertTrue(deep.toString() !in out)
            assertTrue(root.resolve("work").toString() in box.describe() && "find_files" in box.describe())
            assertTrue("IDEA Projects/Arcana/" in box.execute(call("find_files", "name" to "arcana")))
        } finally {
            locked.toFile().setReadable(true)
        }
    }

    @Test
    fun planModeOffersOnlyReadingTools() {
        val root = Files.createTempDirectory("arcana-plan")
        val vaultDir = Files.createDirectories(root.resolve("vault"))
        val box = ToolBox(Vault(vaultDir), emptyList(), root.resolve("b"), readOnly = true)
        assertTrue(box.specs.isNotEmpty() && box.specs.all { it.risk == ToolRisk.Read })
        assertTrue("PLAN MODE" in box.describe())
        assertTrue(box.execute(call("write_file", "path" to "x.txt", "content" to "x")).startsWith("Error"))
    }
}
