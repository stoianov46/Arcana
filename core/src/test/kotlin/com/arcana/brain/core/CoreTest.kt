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
}
