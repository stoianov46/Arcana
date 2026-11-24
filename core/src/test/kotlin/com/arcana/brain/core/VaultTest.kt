package com.arcana.brain.core

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The vault: notes, search, trash, replace and append, daily notes and the Thoughts Index, the index (links, tags, health), tags, rename with link rewriting, the memory file (place, cap, remember, audit), search_notes. */
class VaultTest {
    @Test
    fun vaultCreateSearchAppend() {
        val dir = Files.createTempDirectory("vault")
        Files.createDirectories(dir.resolve(".obsidian"))
        Files.writeString(dir.resolve(".obsidian/hidden.md"), "kotlin secret")
        Files.createDirectories(dir.resolve(".arcana/skills"))
        Files.writeString(dir.resolve(".arcana/skills/s.md"), "skill")
        Files.writeString(dir.resolve("Kotlin.md"), "Kotlin coroutines make async simple. Kotlin rocks.")
        Files.writeString(dir.resolve("Cooking.md"), "Pasta recipe")
        val vault = Vault(dir)
        assertEquals(listOf(".arcana/skills/s.md"), vault.hiddenNotes().map { it.path }, "hidden notes are listed apart, without .obsidian")
        assertTrue(vault.notes().none { it.path.startsWith(".") })

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
    fun appendNeverDoublesTheHeading() {
        val dir = Files.createTempDirectory("vault")
        Files.writeString(dir.resolve("N.md"), "one\n")
        val vault = Vault(dir)
        vault.append("N.md", "## Top items", "## Top items\n\nA")      // argument and content both carry it
        vault.append("N.md", "Chart", "## ## Chart\n\nB")              // doubled marker in the content
        vault.append("N.md", "## Sources", "- link")                     // marks typed into the argument
        vault.append("N.md", "  ", "plain")                              // blank heading = none
        vault.append("N.md", "Method", "### Detail\n\nC")              // a different heading is kept
        assertEquals(
            "one\n\n## Top items\n\nA\n\n## Chart\n\nB\n\n## Sources\n\n- link\n\nplain\n\n## Method\n\n### Detail\n\nC\n",
            vault.read("N.md"),
        )
    }

    @Test
    fun aNewDailyNoteIsListedInTheThoughtsIndex() {
        val dir = Files.createTempDirectory("thoughts")
        val vault = Vault(dir)
        val daily = DailyNotes(AppConfig().dailyFolder, AppConfig().dailyFormat)
        assertEquals("Thoughts", daily.folder)
        val day = java.time.LocalDateTime.of(2026, 11, 2, 9, 0)
        vault.capture("one", daily, day)
        vault.capture("two", daily, day.plusHours(1))
        vault.capture("next day", daily, day.plusDays(1))
        assertTrue(Files.exists(dir.resolve("Thoughts/thought-2026-11-02.md")) && !Files.exists(dir.resolve("thought-2026-11-02.md")), "the day is in the Thoughts folder, not the root")
        val own = vault.captureNote("Thoughts", "an idea of its own", day)
        vault.listThought(own)
        vault.listThought(own)
        val index = Files.readString(dir.resolve("Thoughts/Thoughts Index.md"))
        assertEquals(1, Regex("\\[\\[an idea of its own]]").findAll(index).count(), "a thought saved as a note is listed once")
        assertEquals(1, Regex("\\[\\[thought-2026-11-02]]").findAll(index).count())
        assertTrue("[[thought-2026-11-03]]" in index && index.indexOf("thought-2026-11-02") < index.indexOf("thought-2026-11-03"))
        assertTrue(index.trimEnd().endsWith("Back to [[Home]]"))
    }

    @Test
    fun aNoteWithADotInItsNameIsLinkedButAPictureIsNot() {
        val dir = Files.createTempDirectory("brain-dots")
        fun note(path: String, text: String) { val f = dir.resolve(path); Files.createDirectories(f.parent); Files.writeString(f, text) }
        note("Hub.md", "See [[Qwen3.8 27B vs Qwen3.6 35B-A3B Showdown]] and [[v1.2]] and ![[shot.png]] and [[notes.txt]] and [[Missing.Note here]].")
        note("Qwen3.8 27B vs Qwen3.6 35B-A3B Showdown.md", "text")
        note("v1.2.md", "text")
        val index = VaultIndex.build(Vault(dir))
        val hub = index.note("Hub.md")!!
        assertEquals(setOf("Qwen3.8 27B vs Qwen3.6 35B-A3B Showdown.md", "v1.2.md"), hub.links, "a dot in a note's name is not a file extension")
        assertEquals(setOf("Missing.Note here"), hub.unresolved, "a missing note with a dot is a broken link, a missing picture or file is not")
        assertTrue(index.orphans.isEmpty())
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
        val health = index.health(staleDays = 90)
        assertEquals(5, health.notes)
        assertEquals(2, health.orphans)
        assertEquals(1, health.brokenNotes)
        assertEquals(1, health.stale)
        assertEquals(60, health.connectedPercent, "3 of 5 notes are connected")
        assertEquals(4, health.untagged, "only Hub has tags; Deep has no real frontmatter")
        assertEquals(100, VaultIndex(emptyList()).health(90).connectedPercent, "an empty vault is not unhealthy")
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
        assertEquals("thought-2026-11-02.md", DailyNotes("", AppConfig().dailyFormat).pathFor(java.time.LocalDate.of(2026, 11, 2)))
        val at = java.time.LocalDateTime.of(2026, 10, 25, 9, 5)
        assertEquals("Journal/2026-10-25 Sunday.md", vault.capture("first thought", daily, at))
        vault.capture("second thought", daily, at.plusMinutes(10))
        assertEquals("- 09:05 first thought\n- 09:15 second thought\n", vault.read("Journal/2026-10-25 Sunday.md"))
        assertEquals(DailyNotes("Daily", "YYYY-MM-DD"), DailyNotes.detect(Vault(Files.createTempDirectory("none")), "Daily"), "defaults when the vault has no settings")
    }

    @Test
    fun aThoughtCanBecomeANoteInAFolderAndANoteCanBeRewritten() {
        val root = Files.createTempDirectory("vault")
        Files.createDirectories(root.resolve("Research")); Files.createDirectories(root.resolve("Projects")); Files.createDirectories(root.resolve(".obsidian")); Files.createDirectories(root.resolve("attachments"))
        val vault = Vault(root)
        assertEquals(listOf("Projects", "Research"), vault.rootFolders(), "no hidden folders and no attachments")
        val at = java.time.LocalDateTime.of(2026, 11, 1, 9, 5)
        assertEquals("Research/Ask Anna about flights.md", vault.captureNote("Research", "Ask Anna about flights\nand hotels", at))
        assertEquals("Research/Ask Anna about flights 2.md", vault.captureNote("Research", "Ask Anna about flights", at), "a taken name gets a number")
        assertEquals("---\ncreated: 2026-11-01T09:05\n---\n\nAsk Anna about flights\nand hotels\n", vault.read("Research/Ask Anna about flights.md"))
        assertEquals("New/Idea.md", vault.captureNote("New", "Idea", at), "the folder is made when it is missing")
        vault.write("Research/Ask Anna about flights.md", "edited\n")
        assertEquals("edited\n", vault.read("Research/Ask Anna about flights.md"))
        assertFailsWith<VaultException> { vault.write("Research/Nothing.md", "x") }
        assertFailsWith<VaultException> { vault.captureNote("Research", "  ") }
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
    fun theMemoryFileCanLiveInsideTheVaultOrAnywhereOnTheComputer() {
        val root = Files.createTempDirectory("arcana-memloc")
        val vaultDir = Files.createDirectories(root.resolve("vault"))
        // default: .arcana/ARCANA.md, hidden from the notes; an ARCANA.md that already sits at the vault root is still the one used
        assertEquals(vaultDir.resolve(".arcana/ARCANA.md"), Vault(vaultDir).memoryFile())
        Files.writeString(vaultDir.resolve("ARCANA.md"), "old place")
        assertEquals(vaultDir.resolve("ARCANA.md"), Vault(vaultDir).memoryFile())
        Files.delete(vaultDir.resolve("ARCANA.md"))
        // inside the vault, in a folder that does not exist yet
        val inside = Vault(vaultDir, "Notes/Memory.md")
        assertEquals("Memory.md", inside.memoryName())
        inside.ensureMemory()
        assertTrue(Files.isRegularFile(vaultDir.resolve("Notes/Memory.md")) && "Arcana memory" in inside.memory())
        inside.remember("I like short answers")
        assertTrue("- I like short answers" in inside.memory())
        // anywhere on the computer, by absolute path
        val outside = root.resolve("elsewhere/mem.md")
        val far = Vault(vaultDir, outside.toString())
        far.ensureMemory()
        assertTrue(Files.isRegularFile(outside) && !Files.exists(vaultDir.resolve("ARCANA.md")))
        far.remember("outside the vault")
        assertTrue("outside the vault" in Files.readString(outside) && "outside the vault" in far.memory())
        // a memory file without the "Remembered" section gets one
        Files.writeString(outside, "# My rules\n\nBe brief.\n")
        far.remember("also this")
        assertEquals("# My rules\n\nBe brief.\n\n## Remembered\n\n- also this\n", Files.readString(outside))
        // ~ is the home folder
        assertEquals(java.nio.file.Path.of(System.getProperty("user.home"), "x/ARCANA.md"), Vault(vaultDir, "~/x/ARCANA.md").memoryFile())
        assertEquals("", Vault(vaultDir, "Nope/Missing.md").memory())
    }

    @Test
    fun tagsWrittenAnyWayAreFoundAndAgentTagsStayNextToArcanas() {
        fun tags(front: String) = VaultIndex.frontmatterTags("---\n$front\n---\n\n")
        assertEquals(listOf("project", "ai"), tags("tags: project ai"))
        assertEquals(listOf("project", "ai"), tags("tags: #project #ai"))
        assertEquals(listOf("a", "b"), tags("Tags: [a, b]"))
        assertEquals(listOf("a", "b"), tags("tag: a\ntags:\n  - b"))
        assertEquals(listOf("my-tag"), tags("tags:\n  - my tag"))
        val root = Files.createTempDirectory("arcana-tags")
        val vault = Vault(Files.createDirectories(root.resolve("vault")))
        // a note the agent wrote with a `tag:` line is found in the index (next to Arcana's own `arcana` tag)
        val note = vault.create("", "N", "---\ntag: project ai\n---\nbody", "m")
        assertEquals(setOf("arcana", "project", "ai"), VaultIndex.build(vault).notes.single().tags)
        // addTags merges every tag line into one and keeps what was there
        assertEquals(listOf("arcana", "project", "ai", "extra"), vault.addTags(note.path, listOf("#extra", "ai")))
        assertEquals(1, Regex("(?im)^tags?:").findAll(vault.read(note.path)).count(), "one tags line, not two")
        assertTrue("body" in vault.read(note.path))
        val bare = vault.create("", "Bare", "just text", "m")
        vault.append(bare.path, null, "more")
        val box = ToolBox(vault, emptyList(), root.resolve("b"))
        assertTrue(box.execute(call("add_tags", "path" to bare.path, "tags" to "x, #y z")).endsWith("#arcana, #x, #y, #z"))
        val listed = box.execute(call("list_tags"))
        assertTrue(listed.startsWith("7 tags") && "#ai  (1)" in listed, listed)
        assertTrue(box.execute(call("add_tags", "path" to bare.path, "tags" to " , ")).startsWith("Error:"))
        assertTrue(box.find("list_tags")!!.risk == ToolRisk.Read && box.find("add_tags")!!.risk == ToolRisk.Write)
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
        assertEquals(root.resolve(".arcana/ARCANA.md"), file)
        assertTrue("## Remembered" in Files.readString(file))
        assertTrue("Arcana memory" in vault.memory())
        vault.remember("I write in Ukrainian and English")
        vault.remember("Projects live in ~/IDEA Projects\nand more")
        val text = Files.readString(file)
        assertTrue(text.trimEnd().endsWith("- Projects live in ~/IDEA Projects and more") && "- I write in Ukrainian and English" in text, text)
        Files.writeString(file, "---\ntags: [x]\n---\n" + "z".repeat(9000))
        assertTrue(vault.memory().length < 8200 && "cut" in vault.memory() && "tags" !in vault.memory())
        assertEquals(9000, vault.memoryLength(), "the real length, so the status bar can say how much is cut")
        val system = Prompts.chatMessages(AppConfig(), listOf(ChatMessage.user("hi")), null, emptyMap(), memory = "Always answer in French.").first().content
        assertTrue("ARCANA.md" in system && "Always answer in French." in system)
        assertTrue("ARCANA.md" !in Prompts.chatMessages(AppConfig(), listOf(ChatMessage.user("hi")), null, emptyMap()).first().content)
    }

    @Test
    fun searchNotesSaysWhyItFoundNothingAndStillFindsShortTerms() {
        val root = Files.createTempDirectory("arcana-search")
        val vaultDir = Files.createDirectories(root.resolve("vault"))
        Files.writeString(vaultDir.resolve("Trip Planner.md"), "# Trip Planner\nWe fly to Rome. AI tools help with the plan.")
        val box = ToolBox(Vault(vaultDir), emptyList(), root.resolve("backups"))
        // a normal query finds the note
        assertTrue("Trip Planner.md" in box.execute(call("search_notes", "query" to "rome trip")))
        // only common or short words: used to answer "No notes matched" as if broken, now searched as written
        assertTrue("Trip Planner.md" in box.execute(call("search_notes", "query" to "the")), "a common word is searched literally when nothing else is left")
        assertTrue("Trip Planner.md" in box.execute(call("search_notes", "query" to "AI")))
        // a miss explains itself and says the vault is not empty
        val miss = box.execute(call("search_notes", "query" to "zebra giraffe"))
        assertTrue("\"zebra\" \"giraffe\"" in miss && "not broken" in miss && "1 notes" in miss && "grep_files" in miss, miss)
        assertTrue(box.execute(call("search_notes", "query" to "  ")).startsWith("Error:"))
        // the automatic Ask-your-vault search is unchanged: no words, no notes injected
        assertTrue(Vault(vaultDir).search("the", 5, 100).isEmpty())
    }

    @Test
    fun rememberSkipsWhatIsAlreadyThereAndRefusesDeadNotePaths() {
        val dir = java.nio.file.Files.createTempDirectory("arcana-mem")
        java.nio.file.Files.createDirectories(dir.resolve("Notes"))
        java.nio.file.Files.writeString(dir.resolve("Notes/Plan.md"), "# Plan\n")
        val vault = Vault(dir)
        assertEquals(null, vault.remember("I write in Ukrainian and English"))
        val base = vault.memoryEntries().size - 1 // the template has bullets of its own
        assertEquals("I write in Ukrainian and English", vault.remember("  i write in ukrainian and english. "), "same after normalizing: not added again")
        assertEquals("I write in Ukrainian and English", vault.remember("I write in Ukrainian and in English"), "near-equal (most words shared): not added again")
        assertEquals(null, vault.remember("I like short answers"), "a different fact is added")
        assertEquals(base + 2, vault.memoryEntries().size)
        assertEquals(null, vault.remember("The plan lives in Notes/Plan.md"), "an existing path is fine")
        assertEquals(null, vault.remember("See [[Plan]] for the roadmap"), "an existing [[link]] is fine")
        val error = assertFailsWith<IllegalArgumentException> { vault.remember("The roadmap is in Notes/Roadmap.md") }
        assertTrue("Notes/Roadmap.md" in error.message.orEmpty() && "find_files" in error.message.orEmpty(), "the model is told what to do")
        assertFailsWith<IllegalArgumentException> { vault.remember("See [[Nowhere]]") }
        assertEquals(base + 4, vault.memoryEntries().size, "a refused line is not written")
    }

    @Test
    fun theMemoryAuditListsRepeatsAndDeadPathsAndOnlyCleaningRemovesRepeats() {
        val dir = java.nio.file.Files.createTempDirectory("arcana-mem2")
        val vault = Vault(dir)
        val file = vault.ensureMemory()
        java.nio.file.Files.writeString(file, "# Memory\n\n- I prefer short answers\n- I PREFER short answers!\n- Notes live in Gone/Old.md\n- Something else entirely different here\n")
        val audit = vault.auditMemory()
        assertEquals(listOf("I PREFER short answers!" to "I prefer short answers"), audit.duplicates)
        assertEquals(listOf("Notes live in Gone/Old.md" to "Gone/Old.md"), audit.deadPaths)
        assertTrue(!audit.clean)
        assertEquals(1, vault.cleanMemory())
        assertEquals(listOf("I prefer short answers", "Notes live in Gone/Old.md", "Something else entirely different here"), vault.memoryEntries(), "only the repeat went; the dead path stays for the user")
        assertTrue(java.nio.file.Files.readString(file).startsWith("# Memory"), "the rest of the file is untouched")
        assertEquals(0, vault.cleanMemory())
        assertTrue(Vault(java.nio.file.Files.createTempDirectory("arcana-mem3")).auditMemory().clean, "no memory file: nothing to report")
    }
}
