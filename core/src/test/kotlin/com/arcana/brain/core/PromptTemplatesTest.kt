package com.arcana.brain.core

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Prompt templates: notes in the vault's Prompts folder with `{{blanks}}`. */
class PromptTemplatesTest {
    private fun vault() = Vault(Files.createTempDirectory("prompts"))

    @Test
    fun blanksAreFoundOnceInOrderAndFilledWhereGiven() {
        val body = "Research {{ topic }} for {{audience}}. Again {{topic}}. Code: {\"a\": 1}"
        assertEquals(listOf("topic", "audience"), PromptTemplates.placeholders(body))
        assertEquals("Research DeFi for me. Again DeFi. Code: {\"a\": 1}", PromptTemplates.fill(body, mapOf("topic" to "DeFi", "audience" to "me")))
        assertEquals("Hi {{x}}", PromptTemplates.fill("Hi {{x}}", emptyMap()), "a blank without a value stays")
        assertTrue(PromptTemplates.placeholders("no blanks {single} here").isEmpty())
    }

    @Test
    fun aTemplateIsANoteInThePromptsFolder() {
        val v = vault()
        val path = PromptTemplates.create(v, "Weekly review", "Summarise {{week}} in 5 bullets.", "A weekly summary")
        assertEquals("Prompts/Weekly review.md", path)
        val second = PromptTemplates.create(v, "Weekly review", "Other")
        assertEquals("Prompts/Weekly review 2.md", second, "a taken name gets a number")
        Files.writeString(v.root.resolve("elsewhere.md"), "not a template")
        val list = PromptTemplates.list(v)
        assertEquals(listOf("Weekly review", "Weekly review 2"), list.map { it.name })
        val first = list.first()
        assertEquals("A weekly summary", first.description)
        assertEquals("Summarise {{week}} in 5 bullets.", first.body)
        assertEquals(listOf("week"), first.placeholders)
        assertEquals("Other", list[1].description, "no description property: the first line of the prompt")
        assertTrue(v.read(path).startsWith("---\ntype: prompt\n"))
        assertFailsWith<IllegalArgumentException> { PromptTemplates.create(v, "  ") }
    }

    @Test
    fun aTemplateCanNameASkillThatIsAttachedWhenItRuns() {
        val v = vault()
        val path = PromptTemplates.create(v, "Deep dive", "Research {{topic}}.", "A deep dive", skill = "/research")
        assertEquals("research", PromptTemplates.list(v).single().skill, "the leading slash is dropped")
        assertTrue("skill: research" in v.read(path))
        PromptTemplates.setSkill(v, path, "analyse")
        val changed = PromptTemplates.list(v).single()
        assertEquals("analyse", changed.skill)
        assertEquals("Research {{topic}}.", changed.body, "the prompt and the description stay")
        assertEquals("A deep dive", changed.description)
        PromptTemplates.setSkill(v, path, "")
        assertEquals("", PromptTemplates.list(v).single().skill, "a blank name removes the skill")
        assertEquals("", PromptTemplates.create(v, "Plain", "Hello").let { p -> PromptTemplates.parse(p, v.read(p)).skill })
    }
}
