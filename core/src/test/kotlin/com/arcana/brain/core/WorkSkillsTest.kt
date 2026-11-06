package com.arcana.brain.core

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WorkSkillsTest {
    private fun skill(name: String, vararg kinds: WorkKind) = Skill(name, "d", "1", "", "Skills/$name/$name.skill.md", "Skills/$name", emptyList(), 10, emptyList(), handles = kinds.toSet())
    private val dev = skill("create-dev-research-report", WorkKind.Research, WorkKind.Question)
    private val compare = skill("create-llm-compare-report", WorkKind.Research)
    private val legacy = skill("create-old-research-report")
    private val plainSkill = skill("ascii-bar-chart")
    private val all = listOf(plainSkill, legacy, dev, compare)

    @Test
    fun handlesIsReadFromTheSkillsPropertiesAndOnlyRealKindsCount() {
        assertEquals(setOf(WorkKind.Research, WorkKind.Question), Skills.handlesOf(mapOf("handles" to "[research, Question, nonsense]")))
        assertEquals(setOf(WorkKind.Todo), Skills.handlesOf(mapOf("handles" to "todo")))
        assertEquals(emptySet(), Skills.handlesOf(emptyMap()))
    }

    @Test
    fun aSkillOnDiskCarriesItsHandles() {
        val dir = Files.createTempDirectory("arcana-skills")
        val vault = Vault(dir)
        val folder = Files.createDirectories(dir.resolve("Skills/make-plan"))
        Files.writeString(folder.resolve("make-plan.skill.md"), "---\nname: make-plan\ndescription: \"plans a todo\"\nversion: 1\nhandles: [todo, question]\n---\n\n# Plan\n")
        val found = Skills.discoverAll(vault, "Skills", global = null).single()
        assertEquals(setOf(WorkKind.Todo, WorkKind.Question), found.handles)
    }

    @Test
    fun onlySkillsThatOptInAreOffered() {
        assertEquals(listOf(dev, compare), WorkSkills.offered(all, WorkKind.Research))
        assertEquals(listOf(dev), WorkSkills.offered(all, WorkKind.Question))
        assertEquals(emptyList(), WorkSkills.offered(all, WorkKind.Todo))
    }

    @Test
    fun theChoiceIsThePinThenTheKindsDefaultThenAutomatic() {
        val cfg = AppConfig(workSkillResearch = "create-llm-compare-report")
        assertEquals(Pair(dev, "pinned to this item"), WorkSkills.resolve(WorkKind.Research, dev.name, all, cfg).let { it.skill to it.source })
        assertEquals(Pair(compare, "the default for research"), WorkSkills.resolve(WorkKind.Research, "", all, cfg).let { it.skill to it.source })
        assertEquals(Pair(dev, "automatic"), WorkSkills.resolve(WorkKind.Research, "", all, AppConfig()).let { it.skill to it.source })
    }

    @Test
    fun automaticFallsBackToAnOldResearchReportSkillAndOtherKindsGetAPlainChat() {
        val onlyLegacy = listOf(plainSkill, legacy)
        assertEquals(legacy, WorkSkills.resolve(WorkKind.Research, "", onlyLegacy, AppConfig()).skill)
        assertNull(WorkSkills.resolve(WorkKind.Todo, "", all, AppConfig()).skill)
        assertNull(WorkSkills.resolve(WorkKind.Question, "", all, AppConfig()).skill, "a question offers dev research but does not run it unless you choose it")
    }

    @Test
    fun noneMeansAPlainChatEvenWhereASkillWouldBeAutomatic() {
        assertNull(WorkSkills.resolve(WorkKind.Research, WorkSkills.NONE, all, AppConfig()).skill)
        assertNull(WorkSkills.resolve(WorkKind.Research, "", all, AppConfig(workSkillResearch = WorkSkills.NONE)).skill)
    }

    @Test
    fun aPinOrDefaultThatNamesAMissingSkillFallsThrough() {
        val cfg = AppConfig(workSkillResearch = "gone-too")
        assertEquals(dev, WorkSkills.resolve(WorkKind.Research, "gone", all, cfg).skill, "both are missing: automatic")
        assertEquals(compare, WorkSkills.resolve(WorkKind.Research, "gone", all, AppConfig(workSkillResearch = compare.name)).skill, "the pin is missing: the default")
    }

    @Test
    fun theDefaultOfAKindIsReadAndWrittenInTheConfig() {
        val cfg = WorkSkills.withDefault(AppConfig(), WorkKind.Todo, " make-plan ")
        assertEquals("make-plan", WorkSkills.defaultFor(cfg, WorkKind.Todo))
        assertEquals("", WorkSkills.defaultFor(cfg, WorkKind.Question))
        assertEquals(cfg, WorkSkills.withDefault(cfg, WorkKind.Thought, "x"), "a thought has no skill")
    }

    @Test
    fun theListIsEverySkillWhileNoneHasOptedInAndOnlyTheOptedInOnesAfter() {
        val (all1, fallback1) = WorkSkills.pickable(listOf(plainSkill, legacy), WorkKind.Research)
        assertEquals(listOf(plainSkill, legacy), all1)
        assertEquals(true, fallback1)
        val (some, fallback2) = WorkSkills.pickable(all, WorkKind.Research)
        assertEquals(listOf(dev, compare), some)
        assertEquals(false, fallback2)
        assertEquals(Pair(emptyList<Skill>(), false), WorkSkills.pickable(emptyList(), WorkKind.Todo), "no skills at all: nothing to pick")
        assertEquals(true, WorkSkills.pickable(all, WorkKind.Todo).second, "a kind nobody opted in for lists everything")
    }
}
