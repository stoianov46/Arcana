package com.arcana.brain.core

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Setup and skills: old config migrations, Bootstrap and the starter skills, Layout (the hidden folder, migrate, global skills and memory), skill discovery, usage hints, required steps, skill roles. */
class SetupAndSkillsTest {
    @Test
    fun anOldDailyFormatInASavedConfigMovesToTheThoughtScheme() {
        val file = Files.createTempDirectory("cfg").resolve("config.json")
        Files.writeString(file, """{"dailyFormat": "YYYY-MM-DD"}""")
        assertEquals("[thought-]YYYY-MM-DD", ConfigStore(file).load().dailyFormat)
        Files.writeString(file, """{"dailyFormat": "DD.MM.YYYY"}""")
        assertEquals("DD.MM.YYYY", ConfigStore(file).load().dailyFormat)
    }

    @Test
    fun anEmptyDailyFolderInASavedConfigMovesToThoughts() {
        val file = Files.createTempDirectory("cfg").resolve("config.json")
        Files.writeString(file, """{"dailyFolder": ""}""")
        assertEquals("Thoughts", ConfigStore(file).load().dailyFolder)
        Files.writeString(file, """{"dailyFolder": "Journal"}""")
        assertEquals("Journal", ConfigStore(file).load().dailyFolder)
    }

    @Test
    fun aSkillSaysWhatToTypeAfterItsName() {
        assertEquals("<pair> [horizon]", Skills.usageOf(mapOf("usage" to "<pair> [horizon]", "inputs" to "[a, b]")))
        assertEquals("<pair> <horizon>", Skills.usageOf(mapOf("inputs" to "[pair, horizon]")))
        assertEquals("", Skills.usageOf(emptyMap()))
    }

    @Test
    fun theSetupCreatesWhatIsMissingAndNeverTouchesWhatIsThere() {
        val root = Files.createTempDirectory("arcana-setup")
        val vaultDir = Files.createDirectories(root.resolve("vault"))
        val vault = Vault(vaultDir)
        val config = AppConfig(outputFolder = "Arcana")
        assertTrue(Bootstrap.items(vault, config).none { it.done }, "an empty vault has none of it")
        // the user's own memory file stays exactly as it is
        Files.writeString(vaultDir.resolve("ARCANA.md"), "my own rules")
        val made = Bootstrap.create(vault, config, Bootstrap.items(vault, config).map { it.id }.toSet())
        assertEquals("my own rules", Files.readString(vaultDir.resolve("ARCANA.md")))
        assertEquals(listOf(".arcana/skills/", ".arcana/skills/Skills Guide.md", ".arcana/skills/obsidian-doctor/obsidian-doctor.skill.md", ".arcana/skills/decompose/decompose.skill.md", "Arcana/"), made)
        assertTrue(Bootstrap.items(vault, config).all { it.done })
        assertEquals(emptyList(), Bootstrap.create(vault, config, Bootstrap.items(vault, config).map { it.id }.toSet()), "a second run creates nothing")
        // only what was asked for
        val other = Vault(Files.createDirectories(root.resolve("v2")))
        assertEquals(listOf("ARCANA.md"), Bootstrap.create(other, AppConfig(), setOf(Bootstrap.MEMORY)))
        assertTrue("Arcana memory" in Files.readString(root.resolve("v2/.arcana/ARCANA.md")) && !Files.exists(root.resolve("v2/Skills")) && !Files.exists(root.resolve("v2/ARCANA.md")))
        // a custom skills folder is honoured
        val third = Vault(Files.createDirectories(root.resolve("v3")))
        Bootstrap.create(third, AppConfig(skillsFolder = "Runbooks"), setOf(Bootstrap.STARTER_SKILLS))
        assertTrue(Files.exists(root.resolve("v3/Runbooks/decompose/decompose.skill.md")))
    }

    private fun skillNote(dir: java.nio.file.Path, name: String, description: String = "Does $name.") {
        Files.createDirectories(dir.resolve(name))
        Files.writeString(dir.resolve("$name/$name.skill.md"), "---\nname: $name\ndescription: \"$description\"\n---\n# $name\n")
    }

    @Test
    fun theInternalFolderIsFoundAutomaticallyAndAnOldLayoutKeepsWorking() {
        val root = Files.createTempDirectory("arcana-layout")
        // nothing yet: a new vault gets the hidden folder
        assertEquals(".arcana/skills", Layout.skillsFolder(root, ""))
        // an existing Skills/ is still used
        Files.createDirectories(root.resolve("Skills"))
        assertEquals("Skills", Layout.skillsFolder(root, ""))
        // the hidden folder wins as soon as it exists; a named folder wins over both
        Files.createDirectories(root.resolve(".arcana/skills"))
        assertEquals(".arcana/skills", Layout.skillsFolder(root, "/"))
        assertEquals("Runbooks", Layout.skillsFolder(root, "Runbooks/"))
        // the memory file follows the same rule
        Files.writeString(root.resolve("ARCANA.md"), "old")
        assertEquals(root.resolve("ARCANA.md"), Layout.defaultMemory(root).takeIf { !Files.exists(root.resolve(".arcana/ARCANA.md")) })
        Files.writeString(root.resolve(".arcana/ARCANA.md"), "new")
        assertEquals(root.resolve(".arcana/ARCANA.md"), Layout.defaultMemory(root))
    }

    @Test
    fun migrateMovesSkillsAndMemoryIntoTheHiddenFolderAndTouchesNothingElse() {
        val root = Files.createTempDirectory("arcana-migrate")
        skillNote(root.resolve("Skills"), "alpha")
        Files.writeString(root.resolve("Skills/alpha/alpha.skill.md"), Files.readString(root.resolve("Skills/alpha/alpha.skill.md")) + "run python3 $root/Skills/alpha/scripts/a.py\n")
        Files.writeString(root.resolve("ARCANA.md"), "my rules")
        Files.createDirectories(root.resolve("Reports")); Files.writeString(root.resolve("Reports/r.md"), "report")
        val result = Layout.migrate(root, AppConfig(skillsFolder = "Skills", memoryPath = "ARCANA.md"))
        assertTrue(result.lines.any { it.startsWith("Moved Skills/") } && result.lines.any { it.startsWith("Moved ARCANA.md") }, result.lines.toString())
        assertTrue(Files.exists(root.resolve(".arcana/skills/alpha/alpha.skill.md")) && !Files.exists(root.resolve("Skills")))
        assertEquals("my rules", Files.readString(root.resolve(".arcana/ARCANA.md")))
        assertTrue("python3 $root/.arcana/skills/alpha/scripts/a.py" in Files.readString(root.resolve(".arcana/skills/alpha/alpha.skill.md")), "the skill's own path follows it")
        assertTrue(result.lines.any { it.startsWith("Rewrote the old path") })
        assertEquals("report", Files.readString(root.resolve("Reports/r.md")), "generated content stays where it is")
        assertEquals("", result.config.skillsFolder + result.config.memoryPath, "the settings go back to automatic")
        // a second run has nothing to do, and a target that exists is never overwritten
        assertEquals(listOf("Nothing to move."), Layout.migrate(root, result.config).lines)
        Files.writeString(root.resolve("ARCANA.md"), "another"); skillNote(root.resolve("Skills"), "beta")
        val again = Layout.migrate(root, result.config)
        assertTrue(again.lines.all { it.startsWith("Left") } && "my rules" == Files.readString(root.resolve(".arcana/ARCANA.md")), again.lines.toString())
        // a skills folder the user named is left alone
        assertTrue(Layout.migrate(root, AppConfig(skillsFolder = "Runbooks")).lines.any { "Runbooks" in it })
    }

    @Test
    fun yourOwnMemoryFileComesBeforeTheVaultsAndCountsInItsLength() {
        val home = Files.createTempDirectory("arcana-home")
        val old = System.getProperty("user.home"); val oldEnv = System.getenv("ARCANA_CONFIG")
        System.setProperty("user.home", home.toString())
        try {
            if (oldEnv != null) return // the config location is pinned from outside: this check is about the default one
            val vaultDir = Files.createTempDirectory("arcana-gmem")
            val vault = Vault(vaultDir)
            Files.createDirectories(Layout.globalDir()); Files.writeString(Layout.globalMemory(), "Answer in Ukrainian.")
            assertEquals("Answer in Ukrainian.", vault.memory())
            vault.ensureMemory()
            assertTrue(vault.memory().startsWith("Answer in Ukrainian.\n\n") && "Arcana memory" in vault.memory())
            assertEquals(vault.memory().length, vault.memoryLength())
        } finally { System.setProperty("user.home", old) }
    }

    @Test
    fun yourOwnSkillsAndMemoryApplyToEveryVaultAndTheVaultWins() {
        val root = Files.createTempDirectory("arcana-global")
        val vaultDir = Files.createDirectories(root.resolve("vault"))
        val vault = Vault(vaultDir)
        skillNote(vaultDir.resolve(".arcana/skills"), "shared", "The vault's.")
        skillNote(vaultDir.resolve(".arcana/skills"), "only-here")
        val global = Files.createDirectories(root.resolve("home/skills"))
        skillNote(global, "shared", "The global one.")
        skillNote(global, "everywhere")
        val found = Skills.discoverAll(vault, ".arcana/skills", global).associateBy { it.name }
        assertEquals(setOf("shared", "only-here", "everywhere"), found.keys)
        assertEquals("The vault's.", found.getValue("shared").description, "a vault skill of the same name wins")
        val everywhere = found.getValue("everywhere")
        assertTrue(java.nio.file.Path.of(everywhere.path).isAbsolute, "a global skill is addressed by its absolute path")
        assertTrue("# everywhere" in Skills.load(vault, everywhere) && "# everywhere" in Skills.loadEntry(vault, everywhere.path).orEmpty())
        assertEquals(listOf("only-here", "shared"), Skills.discoverAll(vault, ".arcana/skills", null).map { it.name })
    }

    @Test
    fun theStarterSkillsAreRealSkillsWithoutProblems() {
        val root = Files.createTempDirectory("arcana-starter")
        val vault = Vault(Files.createDirectories(root.resolve("vault")))
        Bootstrap.create(vault, AppConfig(), setOf(Bootstrap.SKILLS_GUIDE, Bootstrap.STARTER_SKILLS))
        val found = Skills.discover(vault, ".arcana/skills").sortedBy { it.name }
        assertEquals(listOf("decompose", "obsidian-doctor"), found.map { it.name }, "the guide is a note, not a skill")
        found.forEach { skill -> assertTrue(skill.description.isNotBlank() && skill.problems.isEmpty(), "${skill.name}: ${skill.problems}") }
        assertEquals(listOf("ask_user", "create_note"), Skills.requirementsOf(vault, found[0].path).map { it.tool })
        assertEquals(listOf("vault_health", "ask_user", "create_note", "vault_health"), Skills.requirementsOf(vault, found[1].path).map { it.tool })
        found.forEach { assertTrue(Files.readString(vault.root.resolve(it.path)).length < 18_000) }
        // the tools each skill names are real ones
        val box = ToolBox(vault, emptyList(), root.resolve("b"))
        val real = box.specs.map { it.name } + listOf("ask_user")
        StarterSkills.all.forEach { starter ->
            val needs = starter.text.substringAfter("needs: [").substringBefore("]").split(",").map { it.trim() }
            assertTrue(needs.all { it in real }, "${starter.name} needs a tool that does not exist: ${needs - real.toSet()}")
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
        // a failed, denied, timed-out or not-run call is not the step done
        run.observe("append_to_note", "{path=a}", "Error: no such note a")
        run.observe("append_to_note", "{path=a}", "The user denied this action. Do not try it again; ask what they want instead.")
        run.observe("append_to_note", "{path=a}", "Added", ran = false)
        run.observe("append_to_note", "{path=a}", "Timed out after 60 s")
        assertEquals(1, run.done, "only the snapshot counts so far")
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
    fun aSkillNamesItsModelsInItsProperties() {
        val roles = Skills.roles("---\nname: demo\nworker: \"qwen3:30b\"\nreviewer: gemma4:12b\n---\nbody worker: no")
        assertEquals(SkillRoles("qwen3:30b", "gemma4:12b"), roles)
        assertTrue(roles.any)
        assertEquals(SkillRoles(), Skills.roles("---\nname: demo\n---\nbody"))
        assertTrue(!Skills.roles("no properties").any)
    }

    @Test
    fun theRoleTableFillsInWhatASkillDoesNotName() {
        val table = AppConfig(rolePlanner = "p", roleWorker = "w", roleReviewer = "r")
        assertEquals(SkillRoles("w", "r", "p"), SkillRoles().withDefaults(table))
        assertEquals(SkillRoles("own", "r", "p"), SkillRoles(worker = "own").withDefaults(table))
        assertEquals("pl", Skills.roles("---\nplanner: pl\n---\n").planner)
        assertTrue(!SkillRoles().withDefaults(AppConfig()).any)
        val plan = Oracul.planMessage("big", " 1. read\n2. write ")
        assertTrue(Oracul.isPlan(plan) && Oracul.isOpinion(plan) && !Oracul.isReview(plan) && Oracul.body(plan) == "1. read\n2. write")
    }
}
