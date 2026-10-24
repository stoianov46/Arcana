package com.arcana.brain.app

import com.arcana.brain.app.design.Line
import com.arcana.brain.app.design.OutputState
import com.arcana.brain.app.design.Palette
import com.arcana.brain.app.design.PickerState
import com.arcana.brain.app.design.markdownLines
import com.arcana.brain.app.design.parseMarkdown
import com.arcana.brain.app.design.codeAndTables
import com.arcana.brain.app.design.scrollThumb
import com.arcana.brain.app.design.replyFooter
import com.arcana.brain.app.design.unmath
import com.arcana.brain.core.ReplyStats
import com.arcana.brain.app.design.wrap
import com.jakewharton.mosaic.ui.TextStyle
import com.arcana.brain.app.input.Key
import com.arcana.brain.app.navigation.Screen
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun t(c: Char) = Key.Text(c)

class DesignTest {
    private val items = listOf("alpha.md", "beta.md", "gamma.md")

    @Test
    fun pickerMovesWithinBounds() {
        val picker = PickerState()
        picker.handle(Key.Up, items) { it }
        assertEquals(0, picker.index)
        repeat(5) { picker.handle(Key.Down, items) { it } }
        assertEquals(2, picker.index)
        assertEquals("gamma.md", picker.selected(items) { it })
        picker.handle(t('k'), items) { it }
        assertEquals(1, picker.index, "k moves up when not filtering")
    }

    @Test
    fun pickerFiltersOnlyAfterSlash() {
        val picker = PickerState()
        assertFalse(picker.handle(t('b'), items) { it }, "letters are not consumed before / is pressed")
        assertTrue(picker.handle(t('/'), items) { it })
        assertTrue(picker.filtering)
        picker.handle(t('b'), items) { it }
        assertEquals(listOf("beta.md"), picker.visible(items) { it })
        picker.handle(Key.Escape, items) { it }
        assertFalse(picker.filtering)
        assertEquals(items, picker.visible(items) { it })
    }

    @Test
    fun pickerFilterIsARealTextField() {
        val picker = PickerState()
        picker.handle(t('/'), items) { it }
        "gam ma".forEach { picker.handle(t(it), items) { x -> x } }
        picker.handle(Key.DeleteWord, items) { it }
        assertEquals("gam ", picker.filter)
        picker.handle(Key.DeleteToLineStart, items) { it }
        assertEquals("", picker.filter)
    }

    @Test
    fun pickerNeverConsumesEnter() {
        assertFalse(PickerState().handle(Key.Enter, items) { it })
    }

    @Test
    fun pickerSelectionIsNullWhenNothingMatches() {
        val picker = PickerState()
        picker.handle(t('/'), items) { it }
        picker.handle(t('z'), items) { it }
        assertNull(picker.selected(items) { it })
    }

    @Test
    fun scrollThumbCoversAFractionOfTheTrack() {
        assertNull(scrollThumb(height = 10, scroll = 0, total = 5, visible = 10))
        assertEquals(9, scrollThumb(height = 10, scroll = 10, total = 20, visible = 10)?.last)
    }

    @Test
    fun wrapBreaksAtWordsAndKeepsBlankLines() {
        assertEquals(listOf("hello", "world", "", "x"), wrap("hello world\n\nx", 6))
    }

    @Test
    fun markdownRendersListsAndHeadings() {
        val lines = markdownLines("# Title\n- **bold** item\n1. first", 40).map { it.text }.filter { it.isNotEmpty() }
        assertEquals("█ Title", lines[0])
        assertTrue(lines[1].startsWith("━"))
        assertEquals(listOf("● bold item", "1. first"), lines.drop(2))
    }

    @Test
    fun tablesAreBoxedAlignedAndFitTheWidth() {
        val md = "| Coin | Value |\n|---|---:|\n| WETH | $1,226 |\n| ARB | $49 |"
        val lines = markdownLines(md, 40).map { it.text }.filter { it.isNotEmpty() }
        assertEquals(6, lines.size)
        assertEquals(1, lines.map { it.length }.distinct().size)
        val head = markdownLines(md, 40).first { it.text.contains("Coin") }
        assertTrue(head.fill == com.jakewharton.mosaic.ui.Color.Unspecified && head.runs!!.all { it.background != com.jakewharton.mosaic.ui.Color.Unspecified })
        assertTrue(lines.first().startsWith("╭") && lines.last().startsWith("╰") && lines[2].startsWith("├"))
        assertTrue(lines[3].endsWith(" $1,226 │") && lines[4].endsWith("   $49 │"))
        val narrow = markdownLines("| a very long heading | another long heading |\n|---|---|\n| x | y |", 24).map { it.text }.filter { it.isNotEmpty() }
        assertTrue(narrow.all { it.length <= 24 })
    }

    @Test
    fun figuresAndCodeAreHighlighted() {
        val runs = markdownLines("Loss of -$7,204 and gain +$12, in 2026-10-25 item 2.", 80).first().runs!!
        val lit = runs.filter { it.style != TextStyle.Unspecified && it.text.any(Char::isDigit) }.map { it.text.trim() }
        assertEquals(listOf("-$7,204", "+$12,"), lit)
        assertEquals(Palette.ConsoleErr, runs.first { it.text == "-$7,204" }.color)
        val code = markdownLines("```kotlin\nval x = 42 // answer\n```", 40)
        assertTrue(code.first().text.startsWith("╭─ kotlin ─"))
        val body = code[1].runs!!
        assertEquals(Palette.Purple, body.first { it.text == "val" }.color)
        assertEquals(Palette.Orange, body.first { it.text == "42" }.color)
        assertEquals(Palette.ConsoleDim, body.first { it.text.startsWith("//") }.color)
    }

    @Test
    fun markdownCanBeShownAsWrittenOrWithPlainGlyphs() {
        assertEquals("- **bold** item", markdownLines("- **bold** item", 40, rich = false).first().text)
        assertEquals("- x", markdownLines("- x", 40, plainGlyphs = true).first().text)
        assertTrue(markdownLines("| a |\n|---|\n| b |", 20, plainGlyphs = true).all { l -> l.text.all { it.code < 128 } })
    }

    @Test
    fun markdownBlocksBecomeEntriesWithTheirSource() {
        val blocks = parseMarkdown("# Head\n\npara one\nstill one\n\n- a\n- b\n\n```kotlin\nval x = 1\n```")
        assertEquals(listOf("# Head", "para one\nstill one", "- a", "- b", "```kotlin\nval x = 1\n```"), blocks.map { it.raw })
        val entries = markdownLines("# Head\n\n- a\n- b", 40).map { it.entry }.filter { it >= 0 }.distinct()
        assertEquals(listOf(0, 1, 2), entries)
    }

    @Test
    fun codeOnlyKeepsFencesAndTables() {
        assertEquals("```\nx\n```\n\n| a | b |\n|---|---|", codeAndTables("text\n```\nx\n```\nmore\n| a | b |\n|---|---|"))
        assertNull(codeAndTables("just words"))
    }

    @Test
    fun scrollingUsesArrowsSpaceAndLetters() {
        val out = OutputState().apply { maxOffset = 25; page = 10 }
        out.handle(Key.Up)
        out.handle(Key.Up)
        assertEquals(2, out.offset)
        out.handle(Key.Down)
        assertEquals(1, out.offset)
        out.handle(Key.PageUp)
        assertEquals(11, out.offset)
        out.handle(t(' '))
        out.handle(t(' '))
        assertEquals(0, out.offset, "scrolling down stops at the latest text")
        repeat(5) { out.handle(Key.PageUp) }
        assertEquals(25, out.offset, "scrolling up stops at the first line")
        out.handle(t('G'))
        assertEquals(0, out.offset)
        out.handle(t('g'))
        assertEquals(25, out.offset)
        out.handle(t('j'))
        assertEquals(24, out.offset)
    }

    @Test
    fun lettersAndSpaceAreLeftAloneWhileTyping() {
        val out = OutputState().apply { maxOffset = 25 }
        assertFalse(out.handle(t(' '), letters = false))
        assertFalse(out.handle(t('j'), letters = false))
        assertFalse(out.handle(t('s'), listOf("x"), letters = false))
        assertTrue(out.handle(Key.Up, letters = false))
    }

    @Test
    fun selectModeWalksTicksAndLeaves() {
        val entries = listOf("a", "b", "c")
        val lines = entries.flatMapIndexed { i, s -> listOf(Line(s, entry = i), Line("", entry = i)) }
        val out = OutputState().apply { total = lines.size; visible = 6; maxOffset = 0; ranges = (0..2).map { (it * 2)..(it * 2 + 1) } }
        assertTrue(out.handle(t('s'), entries))
        assertTrue(out.selecting)
        assertEquals(0, out.cursor)
        out.handle(t(' '), entries)
        out.handle(Key.Down, entries)
        assertEquals(setOf(0), out.marked)
        assertEquals(2, out.cursor)
        out.handle(t('a'), entries)
        assertEquals(setOf(0, 1, 2), out.marked)
        out.handle(t('a'), entries)
        assertEquals(emptySet(), out.marked)
        assertTrue(out.handle(Key.Escape, entries))
        assertFalse(out.selecting)
    }

    @Test
    fun parsesSttyOutput() {
        assertEquals(CellSize(120, 40), TerminalSize.parseStty("40 120\n"))
        assertNull(TerminalSize.parseStty("garbage"))
    }

    @Test
    fun latexMathBecomesSymbolsButPricesAreKept() {
        assertEquals("User → CLI", unmath("User \$\\rightarrow\$ CLI"))
        assertEquals("retrieves N relevant notes", unmath("retrieves \$N\$ relevant notes"))
        assertEquals("x ≤ y", unmath("x \\(\\le\\) y"))
        assertEquals("costs \$5 and \$10", unmath("costs \$5 and \$10"))
    }

    @Test
    fun inlineStylesBecomeRunsNotRawMarkers() {
        val line = markdownLines("Use **bold**, *italic* and `code` here", 80).first()
        assertEquals("Use bold, italic and  code  here", line.text)
        val styles = line.runs!!.associate { it.text.trim() to it.style }
        assertEquals(TextStyle.Bold, styles["bold"])
        assertEquals(TextStyle.Italic, styles["italic"])
        assertTrue(line.runs!!.any { it.text == " code " && it.background != com.jakewharton.mosaic.ui.Color.Unspecified }, "inline code is a tinted chip")
    }

    @Test
    fun codeBlocksAreFilledRowsWithoutTheFences() {
        val lines = markdownLines("```kotlin\nval x = 1\n```", 40)
        assertTrue(lines.filter { it.text.isNotBlank() }.any { it.text.contains("val x = 1") })
        assertTrue(lines.filter { it.text.isNotBlank() }.all { it.fill != com.jakewharton.mosaic.ui.Color.Unspecified })
        assertFalse(lines.any { it.text.contains("```") })
    }

    @Test
    fun longLinesWrapInsideTheWidth() {
        val lines = markdownLines("1. " + "word ".repeat(30), 40)
        assertTrue(lines.all { it.text.length <= 40 }, "every row fits the width")
        assertTrue(lines.count { it.text.isNotBlank() } > 1)
    }

    @Test
    fun wideCharactersNeverReachTheScreen() {
        val lines = markdownLines("🔄 **Agent Loop** 日本語 ✅ done", 60)
        val drawn = lines.flatMap { it.runs.orEmpty().map { r -> r.text } }.joinToString("")
        assertTrue(drawn.none { it.code >= 0x1F000 || it.code in 0x4E00..0x9FFF }, "emoji and CJK become one-cell lookalikes: $drawn")
        assertTrue(drawn.contains("Agent Loop") && drawn.contains("done"))
        assertEquals(drawn.length, lines.first().text.length)
    }

    @Test
    fun savedTabOrderIsAppliedWithinSectionsAndRepairsItself() {
        assertEquals(Screen.entries.toList(), Screen.ordered(emptyList()))
        val moved = Screen.ordered(listOf("Chat", "Home", "Settings", "Usage"))
        assertEquals(listOf(Screen.Chat, Screen.Home), moved.take(2))
        assertEquals(listOf(Screen.Settings, Screen.Usage, Screen.Guide), moved.filter { it.group == Screen.Settings.group }, "the Account section keeps its own order")
        assertEquals(Screen.entries.toSet(), moved.toSet(), "screens the list leaves out are slotted back in")
        // a name from another version, or a repeated one, is ignored
        assertEquals(Screen.entries.size, Screen.ordered(listOf("Gone", "Chat", "Chat")).size)
        // a section never splits, even from a hand-edited file: sections follow the order of their first tab
        val crossed = Screen.ordered(listOf("Settings", "Home", "Usage"))
        assertEquals(listOf(Screen.Settings, Screen.Usage, Screen.Guide), crossed.take(3))
        assertEquals(Screen.Home, crossed[3])
    }

    @Test
    fun selectModeScrollsInsideAnEntryTallerThanTheWindow() {
        val entries = listOf("huge")
        val out = OutputState().apply { total = 100; visible = 10; maxOffset = 90; page = 9; ranges = listOf(0..99); offset = 90 }
        assertTrue(out.handle(t('s'), entries))
        assertEquals(0, out.cursor)
        // the window shows lines 0..9 of the entry (offset 90 = scrolled all the way up); Down must scroll, not stay put
        out.handle(Key.Down, entries)
        assertEquals(89, out.offset, "one line down inside the entry")
        repeat(10) { out.handle(Key.Down, entries) }
        assertEquals(79, out.offset)
        assertEquals(0, out.cursor, "the cursor stays on the entry while its end is out of view")
        out.handle(Key.Up, entries)
        assertEquals(80, out.offset)
        out.handle(Key.PageDown, entries)
        assertEquals(71, out.offset, "a page scrolls a page")
    }

    @Test
    fun cursorMovesOnOnceTheEdgeOfItsEntryIsVisible() {
        val entries = listOf("a", "b")
        // entry a = lines 0..3, entry b = lines 4..7; the window shows lines 0..7
        val out = OutputState().apply { total = 8; visible = 8; maxOffset = 0; page = 7; ranges = listOf(0..3, 4..7) }
        out.handle(t('s'), entries)
        out.handle(Key.Down, entries)
        assertEquals(1, out.cursor)
        out.handle(Key.Up, entries)
        assertEquals(0, out.cursor)
    }

    @Test
    fun pressingDownAtTheEndOfAHugeEntryDoesNotJumpToItsTop() {
        val entries = listOf("huge")
        // the window shows the last 10 lines of a 100-line entry
        val out = OutputState().apply { total = 100; visible = 10; maxOffset = 90; page = 9; ranges = listOf(0..99); offset = 0 }
        out.handle(t('s'), entries)
        out.handle(Key.Down, entries)
        assertEquals(0, out.offset, "Down at the end changes nothing")
        out.handle(Key.Up, entries)
        assertEquals(1, out.offset, "Up scrolls up from where the view is")
        out.handle(Key.Home, entries)
        assertEquals(90, out.offset, "Home jumps to the top of the entry")
        out.handle(Key.End, entries)
        assertEquals(0, out.offset, "End jumps to the end of the last entry")
    }

    @Test
    fun finishedRepliesEndWithADoneLine() {
        val stats = ReplyStats(promptTokens = 66, completionTokens = 1234, totalMillis = 6000, evalMillis = 4000)
        assertEquals("✓ Done · 6.0s · ↑ 66  ↓ 1.2k tokens · 309 tok/s", replyFooter(stats, plain = false).text)
        assertEquals("Done · 6.0s · 66 prompt + 1.2k reply tokens · 309 tok/s", replyFooter(stats, plain = true).text)
        // when Ollama sent no counts only what was measured is shown
        assertEquals("✓ Done · 2.0s · ↓ 40 tokens · 20 tok/s", replyFooter(ReplyStats(0, 40, 2000), plain = false).text)
        assertEquals(-1, replyFooter(stats, plain = false).entry, "the line belongs to no select-mode entry")
    }

    @Test
    fun filteringMatchesWordsInAnyOrder() {
        val picker = PickerState()
        val commands = listOf("Theme: Darcula", "Theme: Dark", "Go to Chat", "Capture a thought")
        picker.handle(t('/'), commands) { it }
        "dar theme".forEach { picker.handle(t(it), commands) { x -> x } }
        assertEquals(listOf("Theme: Darcula", "Theme: Dark"), picker.visible(commands) { it })
        "x".forEach { picker.handle(t(it), commands) { x -> x } }
        assertEquals(emptyList(), picker.visible(commands) { it })
    }
}
