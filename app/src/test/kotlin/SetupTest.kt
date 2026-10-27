package com.arcana.brain.app

import com.arcana.brain.core.ConfigStore
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `arcana --init`: sets a vault up without a screen, and remembers it. */
class SetupTest {
    private fun run(vararg args: String, store: ConfigStore): Pair<Int?, String> {
        val out = StringBuilder()
        return Cli.run(arrayOf(*args), store) { out.appendLine(it) } to out.toString()
    }

    @Test
    fun noArgumentsStartTheAppAndHelpIsPrinted() {
        val store = ConfigStore(Files.createTempDirectory("arcana-cli").resolve("config.json"))
        assertNull(Cli.run(emptyArray(), store))
        val (code, text) = run("--help", store = store)
        assertEquals(0, code); assertTrue("arcana --init <vault>" in text)
        assertEquals(2, run("--nope", store = store).first)
    }

    @Test
    fun initCreatesWhatIsMissingRemembersTheVaultAndNeverOverwrites() {
        val root = Files.createTempDirectory("arcana-cli")
        val vault = Files.createDirectories(root.resolve("vault"))
        Files.writeString(vault.resolve("ARCANA.md"), "mine")
        val store = ConfigStore(root.resolve("config.json"))
        val (code, text) = run("--init", vault.toString(), store = store)
        assertEquals(0, code, text)
        assertEquals("mine", Files.readString(vault.resolve("ARCANA.md")))
        assertTrue(Files.exists(vault.resolve("Skills/decompose/decompose.skill.md")) && Files.exists(vault.resolve("Skills/Skills Guide.md")), text)
        assertEquals(vault.toRealPath().toString(), java.nio.file.Path.of(store.load().vaultPath).toRealPath().toString())
        assertTrue(store.load().setupDone)
        assertTrue("Nothing to create" in run("--init", vault.toString(), store = store).second)
    }

    @Test
    fun skipLeavesPartsOutAndBadInputIsRefused() {
        val root = Files.createTempDirectory("arcana-cli")
        val vault = Files.createDirectories(root.resolve("vault"))
        val store = ConfigStore(root.resolve("config.json"))
        assertEquals(0, run("--init", vault.toString(), "--skip", "skills", store = store).first)
        assertTrue(Files.exists(vault.resolve("ARCANA.md")) && !Files.exists(vault.resolve("Skills")))
        assertEquals(2, run("--init", store = store).first, "no vault given")
        assertEquals(2, run("--init", vault.toString(), "--skip", "nonsense", store = store).first)
        assertEquals(1, run("--init", root.resolve("missing").toString(), store = store).first)
    }
}
