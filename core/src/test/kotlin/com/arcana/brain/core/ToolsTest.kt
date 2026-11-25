package com.arcana.brain.core

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The ToolBox and file tools: sandbox and folder grants, backups and protected folders, grep/glob/line ranges, find_files, cat, diff and change previews, delete/move, note properties, time and wait, the cheat sheet, ask_user options, request_mode, plan mode, wrong-name hints, path errors, projects, vault_info, vault_health. */
class ToolsTest {
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
    fun vaultHealthListsHygieneProblemsAndDeadLinksWithTheirFix() {
        val root = Files.createTempDirectory("arcana-health")
        val vault = Vault(Files.createDirectories(root.resolve("vault")))
        vault.create("Research", "Typed", "---\ntype: reference\n---\nSee [[Nowhere]] and [[Untyped]].", "m")
        vault.create("Research", "Untyped", "plain text", "m")
        val box = ToolBox(vault, emptyList(), root.resolve("b"))
        assertEquals(ToolRisk.Read, box.find(VAULT_HEALTH)!!.risk)
        val all = box.execute(call(VAULT_HEALTH))
        assertTrue(all.startsWith("Vault health: 2 notes"), all)
        assertTrue("(kind: missingtype)" in all && "Research/Untyped.md" in all, all)
        assertTrue("✕ 1 dead link (kind: dead_links)" in all && "Research/Typed.md links to [[Nowhere]]" in all, all)
        // one kind alone, and a wrong kind is an error that lists the right ones
        val dead = box.execute(call(VAULT_HEALTH, "kind" to "dead_links"))
        assertTrue("dead_links" in dead && "missingtype" !in dead, dead)
        assertTrue(box.execute(call(VAULT_HEALTH, "kind" to "nope")).startsWith("Error: Unknown kind"))
    }

    @Test
    fun aProjectIsAllowedStartsRelativePathsThereAndPassesOnItsInstructions() {
        val root = Files.createTempDirectory("arcana-project")
        val vaultDir = Files.createDirectories(root.resolve("vault"))
        val project = Files.createDirectories(root.resolve("shop"))
        Files.writeString(project.resolve("index.html"), "<h1>hi</h1>\n")
        Files.createDirectories(project.resolve("src")); Files.createDirectories(project.resolve("node_modules")); Files.createDirectories(project.resolve(".git"))
        Files.writeString(project.resolve("CLAUDE.md"), "Use tabs. Run npm test before you finish.")
        Files.writeString(vaultDir.resolve("note.md"), "vault note")
        val box = ToolBox(Vault(vaultDir), emptyList(), root.resolve("b"), project = project)
        // relative paths mean the project; the vault is still reachable by its absolute path
        assertTrue("<h1>hi</h1>" in box.execute(call("read_file", "path" to "index.html")))
        assertTrue("vault note" in box.execute(call("read_file", "path" to vaultDir.resolve("note.md").toString())))
        assertTrue(box.folderToGrant(call("list_dir", "path" to project.toString())) == null, "the project is allowed without asking")
        val describe = box.describe()
        assertTrue("PROJECT:" in describe && project.toString() in describe && "index.html" in describe && "src/" in describe, describe)
        assertTrue("node_modules" !in describe && ".git" !in describe, "noise and hidden entries are left out of the listing")
        assertTrue("Use tabs. Run npm test" in describe && "(CLAUDE.md)" in describe, describe)
        assertTrue("relative to the project folder" in box.cheatSheet() || "project folder" in box.find("read_file")!!.parameters.toString())
        // ARCANA.md outranks CLAUDE.md; no project means no note and relative paths mean the vault
        Files.writeString(project.resolve("ARCANA.md"), "Arcana rules")
        assertTrue("Arcana rules" in box.describe() && "Use tabs" !in box.describe())
        val plain = ToolBox(Vault(vaultDir), emptyList(), root.resolve("b"))
        assertTrue("PROJECT:" !in plain.describe() && "vault note" in plain.execute(call("read_file", "path" to "note.md")))
    }

    @Test
    fun vaultInfoGivesAbsolutePathsAndSaysWhatIsNotCreatedYet() {
        val root = Files.createTempDirectory("arcana-info")
        val vault = Vault(Files.createDirectories(root.resolve("vault")))
        vault.create("Research", "One", "text", "m")
        val project = Files.createDirectories(root.resolve("proj"))
        val box = ToolBox(vault, emptyList(), root.resolve("b"), outputFolder = "Research", project = project)
        val spec = box.find(VAULT_INFO)!!
        assertEquals(ToolRisk.Read, spec.risk)
        val info = box.execute(call(VAULT_INFO))
        assertTrue(info.startsWith("Vault: ${vault.root} (1 notes)"), info)
        assertTrue("New notes go to: ${vault.root.resolve("Research")}\n" in info, "the output folder exists, so no note after it: $info")
        assertTrue("Research backlog: ${vault.root.resolve(ResearchBacklog.PATH)} (not created yet)" in info, info)
        assertTrue("Todos note: ${vault.root.resolve("Tasks/Todos.md")} (not created yet)" in info, info)
        assertTrue("Project folder: $project" in info, info)
        // plan mode keeps it: it only reads
        assertTrue(ToolBox(vault, emptyList(), root.resolve("b"), readOnly = true).find(VAULT_INFO) != null)
    }

    @Test
    fun aToolThatDoesNotExistGetsAHintAtTheRightOne() {
        val root = Files.createTempDirectory("arcana-hint")
        val box = ToolBox(Vault(Files.createDirectories(root.resolve("vault"))), emptyList(), root.resolve("b"))
        val http = box.execute(call("web_get", "url" to "https://example.com"))
        assertTrue(http.startsWith("Error: there is no tool called web_get") && "fetch_url" in http && "http_request" in http && "refused in run_command" in http, http)
        assertTrue("grep_files" in box.execute(call("grep")))
        assertTrue(box.execute(call("something_else")).endsWith("."))
    }

    @Test
    fun catPrintsAFileWithLineNumbersLikeReadFile() {
        val root = Files.createTempDirectory("arcana-cat")
        val vaultDir = Files.createDirectories(root.resolve("vault"))
        Files.writeString(vaultDir.resolve("a.txt"), "alpha\nbeta\n")
        val box = ToolBox(Vault(vaultDir), emptyList(), root.resolve("b"))
        assertTrue(box.find("cat")!!.risk == ToolRisk.Read)
        val out = box.execute(call("cat", "path" to "a.txt"))
        assertTrue("1 | alpha" in out && "2 | beta" in out && "end of file" in out, out)
        assertTrue(box.execute(call("cat", "path" to "missing.txt")).startsWith("Error:"))
        assertTrue(box.execute(call("cat", "path" to ".")).startsWith("Error:"))
        assertTrue("outside the folders" in box.execute(call("cat", "path" to "/etc/hosts")))
        assertTrue(box.folderToGrant(call("git_diff", "cwd" to Files.createTempDirectory("arcana-elsewhere").toString())) != null)
    }

    @Test
    fun requestModeIsOfferedAndExplainedToTheModel() {
        val definition = REQUEST_MODE_DEFINITION.toString()
        assertTrue(REQUEST_MODE == "request_mode" && TOOL_MODES == listOf("ask", "auto", "plan"))
        assertTrue(TOOL_MODES.all { "\"$it\"" in definition }, definition)
        val root = Files.createTempDirectory("arcana-mode")
        val box = ToolBox(Vault(Files.createDirectories(root.resolve("vault"))), emptyList(), root.resolve("b"))
        assertTrue("request_mode" in box.describe())
        assertTrue(box.find("request_mode") == null, "a chat tool: the chat answers it, the toolbox has no spec")
    }

    @Test
    fun diffShowsHunksWithContextAndLineNumbers() {
        assertTrue(Diff.unified("a\nb", "a\nb").isEmpty())
        val old = (1..10).joinToString("\n") { "line $it" }
        val new = old.replace("line 5", "line five")
        assertEquals(listOf("@@ -3,5 +3,5 @@", "  line 3", "  line 4", "- line 5", "+ line five", "  line 6", "  line 7"), Diff.unified(old, new))
        val two = Diff.unified(old, old.replace("line 1\n", "first\n").replace("line 10", "last"))
        assertEquals(2, two.count { it.startsWith("@@") }, two.toString())
        assertEquals(listOf("@@ -1,1 +1,2 @@", "- ", "+ x", "+ y"), Diff.unified("", "x\ny"))
    }

    @Test
    fun changePreviewsShowTheRealDiffAgainstTheFileOnDisk() {
        val root = Files.createTempDirectory("arcana-diff")
        val vaultDir = Files.createDirectories(root.resolve("vault"))
        Files.writeString(vaultDir.resolve("n.txt"), "one\ntwo\nthree\nfour\nfive\n")
        val box = ToolBox(Vault(vaultDir), emptyList(), root.resolve("b"))
        val edit = box.find("edit_file")!!.preview(call("edit_file", "path" to "n.txt", "old" to "three", "new" to "3").function.arguments)
        assertTrue("- three" in edit && "+ 3" in edit && "  two" in edit && edit.any { it.startsWith("@@") }, edit.toString())
        val write = box.find("write_file")!!.preview(call("write_file", "path" to "n.txt", "content" to "one\ntwo\nthree\nfour\nsix\n").function.arguments)
        assertTrue("- five" in write && "+ six" in write, write.toString())
        val same = box.find("write_file")!!.preview(call("write_file", "path" to "n.txt", "content" to "one\ntwo\nthree\nfour\nfive\n").function.arguments)
        assertTrue(same.any { "nothing changes" in it }, same.toString())
        val fresh = box.find("write_file")!!.preview(call("write_file", "path" to "new.txt", "content" to "a").function.arguments)
        assertEquals(listOf("Create new.txt (1 characters)", "+ a"), fresh)
        val missing = box.find("edit_file")!!.preview(call("edit_file", "path" to "n.txt", "old" to "nope", "new" to "x").function.arguments)
        assertEquals(listOf("Edit n.txt", "- nope", "+ x"), missing)
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
    fun theToolCheatSheetNamesEveryToolWithItsParameters() {
        val root = Files.createTempDirectory("arcana-sheet")
        val box = ToolBox(Vault(Files.createDirectories(root.resolve("v"))), emptyList(), root.resolve("b"))
        val sheet = box.cheatSheet()
        for (spec in box.specs) assertTrue("- ${spec.name}(" in sheet, spec.name)
        assertTrue("- edit_file(path, old, new)" in sheet && "- read_file(path, start_line?, end_line?)" in sheet && "- request_access(reason)" in sheet, sheet)
        assertTrue("remember(text)" in sheet && "ask_user(question, options?, multiple?)" in sheet)
        assertTrue(box.find("remember")!!.alwaysAsk)
        assertTrue("exactly these names" !in box.describe() && "- edit_file(" !in box.describe(), "the tools are not listed a second time in the system prompt")
        box.execute(call("remember", "text" to "likes tea"))
        assertTrue("- likes tea" in Files.readString(root.resolve("v/.arcana/ARCANA.md")))
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
    fun aNoteOrFilePathWithAFolderLeftOutSaysWhereTheNoteReallyIs() {
        val root = Files.createTempDirectory("arcana-lost")
        val vaultDir = Files.createDirectories(root.resolve("vault"))
        Files.writeString(Files.createDirectories(vaultDir.resolve("Research/Arcana")).resolve("Architecture-Improvements.md"), "# Plan")
        val box = ToolBox(Vault(vaultDir), emptyList(), root.resolve("backups"))
        val wrong = "Research/Architecture-Improvements.md"
        val file = box.execute(call("read_file", "path" to wrong))
        assertTrue("does not exist" in file && "Did you mean" in file && "Research/Arcana/Architecture-Improvements.md" in file, "read_file: $file")
        // a note is found by its name: the missing folder, a [[wikilink]], an absolute path and a leading emoji do not matter
        for (ref in listOf(wrong, "[[Architecture-Improvements]]", "$vaultDir/Research/Arcana/Architecture-Improvements.md", "📌 Architecture-Improvements")) {
            val out = box.execute(call("read_note", "path" to ref))
            assertTrue("# Plan" in out && "Research/Arcana/Architecture-Improvements.md" in out, "read_note $ref: $out")
        }
        assertTrue("# Plan" in box.execute(call("read_note", "path" to "Research/Arcana/Architecture-Improvements")))
        assertTrue("find_files" in box.execute(call("read_note", "path" to "Nowhere/Nothing.md")), "no match: the next step is named")
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
            val history = listOf(old, ChatMessage.assistant("ok"), ChatMessage.user("next"), ChatMessage.toolResult("list_dir", "y".repeat(5000)))
            val sent = Prompts.chatMessages(AppConfig(oldToolOutput = "shorten"), history, null, emptyMap(), tools = true)
            assertTrue(sent[1].content.length < 2000 && sent.last().content.length == 5000)
            assertEquals(5000, Prompts.chatMessages(AppConfig(), history, null, emptyMap(), tools = true)[1].content.length, "by default an old result is sent whole")
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
