package com.arcana.brain.app

import com.arcana.brain.app.design.ActionItem
import com.arcana.brain.app.navigation.LibraryFilter
import com.arcana.brain.app.screens.SlashCommands
import com.arcana.brain.app.screens.defaultLibraryFilter
import com.arcana.brain.core.AppConfig
import com.arcana.brain.app.screens.splitSlash
import com.arcana.brain.app.screens.columnCount
import com.arcana.brain.app.screens.sideBySide
import com.arcana.brain.app.screens.toolUsage
import com.arcana.brain.app.screens.unknownCommand
import com.arcana.brain.app.design.ActionStyle
import com.arcana.brain.app.design.breakIntoLines
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
import com.arcana.brain.core.ToolRisk
import com.arcana.brain.app.design.SlashKind
import com.arcana.brain.app.screens.SlashItem
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
    @Test
    fun theThinkingPaneWrapsAtWordsAndCutsOnlyWordsThatAreTooLong() {
        assertEquals(listOf("weighing the", "options and", "fees"), com.arcana.brain.app.screens.wrapWords("weighing the options and fees", 12))
        assertEquals(listOf("first line", "second"), com.arcana.brain.app.screens.wrapWords("first line\nsecond", 20))
        assertEquals(listOf("abcdefgh", "ijkl"), com.arcana.brain.app.screens.wrapWords("abcdefghijkl", 8))
        assertEquals(listOf(""), com.arcana.brain.app.screens.wrapWords("", 10))
    }

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
        assertEquals(listOf(Screen.Settings, Screen.Usage, Screen.Guide, Screen.Changelog), moved.filter { it.group == Screen.Settings.group }, "the Account section keeps its own order")
        assertEquals(Screen.entries.toSet(), moved.toSet(), "screens the list leaves out are slotted back in")
        // a name from another version, or a repeated one, is ignored
        assertEquals(Screen.entries.size, Screen.ordered(listOf("Gone", "Chat", "Chat")).size)
        // a section never splits, even from a hand-edited file: sections follow the order of their first tab
        val crossed = Screen.ordered(listOf("Settings", "Home", "Usage"))
        assertEquals(listOf(Screen.Settings, Screen.Usage, Screen.Guide, Screen.Changelog), crossed.take(4))
        assertEquals(Screen.Home, crossed[4])
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

    @Test
    fun announcedStepsAreRecognisedButAnswersAreNot() {
        assertTrue(announcesAction("I found the module.\n\nI will start with the documentation."))
        assertTrue(announcesAction("Next, I will read the files."))
        assertTrue(!announcesAction("The module has three layers. Here is the analysis."))
        assertTrue(!announcesAction("Which folder should I read? I will wait."))
        assertTrue(!announcesAction("I will read it if you confirm?"))
        assertTrue(!announcesAction("All tools work in allowed folders.\n\nLet me know if you want to use a specific tool!"), "manners are not a plan")
        assertTrue(!announcesAction("I will be happy to help. I'll let you know when it is done."))
        assertTrue(announcesAction("The list is empty.\n\nThis will help me locate the definition."))
        assertTrue(announcesAction("Now I will search the entire project structure."))
        assertTrue(announcesAction("Let me check the folder first."))
        assertTrue(announcesAction("I will use the fetch_url tool on https://www.youtube.com/watch?v=9UMxZofMNbA"), "the ? of a web address is not a question")
    }

    @Test
    fun chartAxisLabelsAreWholeAndDoNotCollide() {
        val axis: (List<String>, Int) -> String = { labels, width -> com.arcana.brain.app.screens.axisLabels(labels, width) }
        assertEquals("0 1 2 3 ", axis((0..3).map { it.toString() }, 2))
        // two-digit labels in two-cell columns: every other label, each whole
        assertEquals("10  12  ", axis(listOf("10", "11", "12", "13"), 2))
        assertEquals("Mon Tue Wed ", axis(listOf("Mon", "Tue", "Wed"), 4))
    }

    @Test
    fun contextMeterShowsHowFullTheWindowWas() {
        val stats = com.arcana.brain.core.ReplyStats(promptTokens = 13_000, completionTokens = 600, totalMillis = 1000)
        val messages = listOf(com.arcana.brain.core.ChatMessage.user("hi"), com.arcana.brain.core.ChatMessage.assistant("ok", stats))
        val meter = com.arcana.brain.app.screens.contextMeter(messages, 131_072)
        assertTrue("ctx ▰▱▱▱▱▱▱▱ 10% 13.6k/128k" in meter, meter)
        assertEquals("", com.arcana.brain.app.screens.contextMeter(messages, 0))
        assertEquals("", com.arcana.brain.app.screens.contextMeter(listOf(com.arcana.brain.core.ChatMessage.user("hi")), 8192))
        assertTrue("nearly full" in com.arcana.brain.app.screens.contextMeter(messages, 16_000))
    }

    @Test
    fun theContextWindowIsTheOneOllamaRunsWithAndIsWrittenTheSameEverywhere() {
        val effective = { asked: Int, max: Int, loaded: Int -> com.arcana.brain.app.screens.effectiveContext(asked, max, loaded) }
        assertEquals(32_768, effective(32_768, 262_144, 4_096), "an explicit window is what is sent, whatever is loaded")
        assertEquals(65_536, effective(131_072, 65_536, 0), "never more than the model supports")
        assertEquals(4_096, effective(0, 262_144, 4_096), "Model default: the window Ollama really loaded, not the model's maximum")
        assertEquals(262_144, effective(0, 262_144, 0), "nothing loaded yet: the best known figure")
        val size = { n: Long -> com.arcana.brain.core.UsageStats.contextSize(n) }
        assertEquals("256k", size(262_144)); assertEquals("32k", size(32_768)); assertEquals("128k", size(131_072)); assertEquals("1M", size(1_048_576))
        assertEquals("4k", size(4_096)); assertEquals("1.5k", size(1_500))
    }

    @Test
    fun statusBarsNeverDropASegmentTheyWrapAndFormatValues() {
        fun seg(text: String, priority: Int) = com.arcana.brain.app.design.statusSegment("", text, com.arcana.brain.app.design.Palette.Text, priority)
        fun text(s: com.arcana.brain.app.design.StatusSegment) = s.parts.joinToString("") { p -> p.text }
        val all = listOf(seg("model", 9), seg("title", 3), seg("ctx", 10), seg("time", 1), seg("price", 7))
        val wide = com.arcana.brain.app.design.wrapSegments(all, all.sumOf { it.width } + 3 * 4)
        assertEquals(1, wide.size, "everything fits on one row")
        val narrow = com.arcana.brain.app.design.wrapSegments(all, 5 + 3 + 5)
        assertEquals(listOf("model", "title", "ctx", "time", "price"), narrow.flatten().map { text(it) }, "nothing is dropped, whatever the width, and the order is kept")
        assertTrue(narrow.size > 1 && narrow.all { row -> row.sumOf { it.width } + 3 * (row.size - 1) <= 13 }, "no row is wider than the bar")
        assertEquals(narrow.size, com.arcana.brain.app.design.statusRowCount(all, 13))
        val tiny = com.arcana.brain.app.design.wrapSegments(listOf(seg("a-very-long-segment-indeed", 5)), 10)
        assertEquals(1, tiny.size)
        assertTrue(text(tiny[0][0]).length <= 10 && text(tiny[0][0]).endsWith("…"), "one segment wider than the bar is cut with an ellipsis")
        val gauge = com.arcana.brain.app.design.gaugeSegment("ctx", 0.11, "15k/131k")
        assertEquals("ctx █░░░░░░░░░ 11% 15k/131k", gauge.parts.joinToString("") { it.text })
        assertEquals("ctx ##-------- 25% x", com.arcana.brain.app.design.gaugeSegment("ctx", 0.25, "x", plain = true).parts.joinToString("") { it.text })
        assertEquals("1h 02m", com.arcana.brain.app.design.spanText(3_720_000))
        assertEquals("14m", com.arcana.brain.app.design.spanText(14 * 60_000L))
        assertEquals("<$0.01", com.arcana.brain.app.design.priceText(0.004))
        assertEquals("$12.40", com.arcana.brain.app.design.priceText(12.4))
        assertEquals(3.0 + 15.0 * 0.5, com.arcana.brain.core.UsageStats.estimatedCost(listOf(com.arcana.brain.core.UsageRecord(0, "Chat", "m", 1_000_000, 500_000, 1)), 3, 15), 1e-9)
    }

    @Test
    fun codePanesAreClosedBoxesOfTheSameWidthOnEverySide() {
        val code = "```json\n{\"tool_name\": \"search_notes\", \"a\": [1, 2, 3], \"long\": \"" + "x".repeat(70) + "\"}\n```"
        for (plain in listOf(false, true)) {
            val lines = com.arcana.brain.app.design.markdownLines(code, 48, plainGlyphs = plain).map { it.text }.dropLastWhile { it.isEmpty() }
            assertTrue(lines.size >= 4, lines.toString())
            assertTrue(lines.all { it.length == 48 }, "every row is 48 wide: " + lines.map { it.length })
            val (tl, tr, bl, br, v) = if (plain) listOf('+', '+', '+', '+', '|') else listOf('╭', '╮', '╰', '╯', '│')
            assertEquals(tl, lines.first().first()); assertEquals(tr, lines.first().last())
            assertEquals(bl, lines.last().first()); assertEquals(br, lines.last().last())
            assertTrue(lines.drop(1).dropLast(1).all { it.last() == v }, "right edge on every code row")
        }
    }

    @Test
    fun theContextGaugeNeverCountsAgainstMoreThanTheModelSupports() {
        assertEquals(262_144, com.arcana.brain.app.screens.effectiveContext(0, 262_144))
        assertEquals(32_768, com.arcana.brain.app.screens.effectiveContext(32_768, 262_144))
        assertEquals(131_072, com.arcana.brain.app.screens.effectiveContext(1_048_576, 131_072))
        assertEquals(65_536, com.arcana.brain.app.screens.effectiveContext(65_536, 0))
        assertEquals(0, com.arcana.brain.app.screens.effectiveContext(0, 0))
    }

    @Test
    fun theSlashMenuFiltersCommandsAndToolsByWhatIsTyped() {
        val items = com.arcana.brain.app.screens.SlashCommands + listOf(
            com.arcana.brain.app.screens.SlashItem("search_notes", "Search the notes", true),
            com.arcana.brain.app.screens.SlashItem("read_file", "Read a file", true),
        )
        val all = com.arcana.brain.app.screens.slashMatches(items, "")
        assertEquals(items.size, all.size)
        assertEquals(listOf("/new"), com.arcana.brain.app.screens.slashMatches(items, "ne").map { it.name })
        assertEquals(listOf("search_notes"), com.arcana.brain.app.screens.slashMatches(items, "search").map { it.name })
        // names that start with the text come before names that only contain it
        assertEquals(listOf("/note", "search_notes"), com.arcana.brain.app.screens.slashMatches(items, "not").map { it.name })
        assertEquals(emptyList(), com.arcana.brain.app.screens.slashMatches(items, "zzz"))
        assertEquals(listOf("read_file"), com.arcana.brain.app.screens.slashMatches(items, "READ").map { it.name })
    }

    @Test
    fun theLibraryTreeShowsFoldersFirstAndOnlyOpenFoldersShowTheirNotes() {
        fun note(path: String, modified: Long = 0) = com.arcana.brain.core.NoteInfo(path, modified, 10, emptySet(), emptySet(), emptySet())
        val notes = listOf(note("Root.md", 9), note("Skills/a/one.md", 5), note("Skills/a/two.md", 4), note("Skills/Guide.md", 8), note("Projects/x.md", 7), note("inbox/n.md", 6))
        fun describe(rows: List<com.arcana.brain.app.screens.LibraryRow>) = rows.map { r ->
            when (r) {
                is com.arcana.brain.app.screens.LibraryRow.Folder -> "${"  ".repeat(r.depth)}${if (r.expanded) "v" else ">"} ${r.name}/ (${r.notes})"
                is com.arcana.brain.app.screens.LibraryRow.Note -> "${"  ".repeat(r.depth)}${r.info.title}"
            }
        }
        // everything collapsed: folders A to Z (case-insensitive), then the notes of the root, with the number of notes below each folder
        assertEquals(listOf("> inbox/ (1)", "> Projects/ (1)", "> Skills/ (3)", "Root"), describe(com.arcana.brain.app.screens.libraryRows(notes, emptySet())))
        // opening a folder shows its subfolders first and then its notes in the view's own order; the others stay closed
        assertEquals(
            listOf("> inbox/ (1)", "> Projects/ (1)", "v Skills/ (3)", "  > a/ (2)", "  Guide", "Root"),
            describe(com.arcana.brain.app.screens.libraryRows(notes, setOf("Skills"))),
        )
        assertEquals(
            listOf("> inbox/ (1)", "> Projects/ (1)", "v Skills/ (3)", "  v a/ (2)", "    one", "    two", "  Guide", "Root"),
            describe(com.arcana.brain.app.screens.libraryRows(notes, setOf("Skills", "Skills/a"))),
        )
        // a nested folder is hidden while its parent is closed, even if it is marked open
        assertEquals(listOf("> inbox/ (1)", "> Projects/ (1)", "> Skills/ (3)", "Root"), describe(com.arcana.brain.app.screens.libraryRows(notes, setOf("Skills/a"))))
        // the notes keep the order they came in (newest first here), not the alphabet
        val newestFirst = listOf(note("d/old.md", 1), note("d/new.md", 5))
        assertEquals(listOf("v d/ (2)", "  old", "  new"), describe(com.arcana.brain.app.screens.libraryRows(newestFirst, setOf("d"))))
        // helpers: what must be open to see a note, and what "expand all" opens
        assertEquals(listOf("Skills", "Skills/a"), com.arcana.brain.app.screens.ancestorsOf("Skills/a/one.md"))
        assertEquals(emptyList(), com.arcana.brain.app.screens.ancestorsOf("Root.md"))
        assertEquals(setOf("inbox", "Projects", "Skills", "Skills/a"), com.arcana.brain.app.screens.allFolders(notes))
        assertEquals(emptyList(), com.arcana.brain.app.screens.libraryRows(emptyList(), emptySet()))
        // folder rows know their subfolders
        val skills = com.arcana.brain.app.screens.libraryRows(notes, emptySet()).filterIsInstance<com.arcana.brain.app.screens.LibraryRow.Folder>().first { it.name == "Skills" }
        assertEquals(1, skills.folders)
    }

    @Test
    fun theSlashMenuSeparatesCommandsSkillsAndToolsByWhatTheyDo() {
        assertEquals(SlashKind.Read, SlashKind.forTool("search_notes", ToolRisk.Read))
        assertEquals(SlashKind.Read, SlashKind.forTool("current_time", ToolRisk.Read))
        assertEquals(SlashKind.Read, SlashKind.forTool("use_skill", ToolRisk.Read))
        assertEquals(SlashKind.Write, SlashKind.forTool("create_note", ToolRisk.Write))
        assertEquals(SlashKind.Write, SlashKind.forTool("set_properties", ToolRisk.Write))
        assertEquals(SlashKind.Write, SlashKind.forTool("remember", ToolRisk.Write), "remember changes a file but is not a delete")
        assertEquals(SlashKind.Danger, SlashKind.forTool("delete_path", ToolRisk.Write))
        assertEquals(SlashKind.Danger, SlashKind.forTool("move_path", ToolRisk.Write))
        assertEquals(SlashKind.Shell, SlashKind.forTool("run_command", ToolRisk.Shell))
        assertEquals(SlashKind.Web, SlashKind.forTool("fetch_url", ToolRisk.Web))
        assertEquals(SlashKind.Web, SlashKind.forTool("web_search", ToolRisk.Web))
        assertEquals(SlashKind.Ask, SlashKind.forTool("ask_user", ToolRisk.Read))
        // the defaults of a menu entry: a command, a skill, a plain tool
        assertEquals(SlashKind.Command, SlashItem("/new", "", false).kind)
        assertEquals(SlashKind.Skill, SlashItem("/demo", "", false, skill = true).kind)
        assertEquals(SlashKind.Read, SlashItem("read_file", "", true).kind)
        // every kind except the plain command says what it is in words (the badge), and the glyph differs per kind, so colour is never the only signal
        val withBadge = SlashKind.values().filter { it != SlashKind.Command }
        assertTrue(withBadge.all { it.badge != null && it.glyph.isNotBlank() })
        assertEquals(withBadge.size, withBadge.map { it.glyph }.toSet().size)
        assertEquals(withBadge.size, withBadge.map { it.badge }.toSet().size)
        // the group of every entry, named on its row: command, skill or tool
        assertEquals(com.arcana.brain.app.design.SlashCategory.Command, SlashKind.Command.category)
        assertEquals(com.arcana.brain.app.design.SlashCategory.Skill, SlashKind.Skill.category)
        assertEquals(setOf(com.arcana.brain.app.design.SlashCategory.Tool), listOf(SlashKind.Read, SlashKind.Write, SlashKind.Shell, SlashKind.Danger, SlashKind.Web, SlashKind.Ask).map { it.category }.toSet())
        // the three badges are the same width, so the column lines up, and differ in colour
        val cats = com.arcana.brain.app.design.SlashCategory.entries
        assertEquals(1, cats.map { it.badge.length }.toSet().size)
        assertEquals(cats.size, cats.map { it.color }.toSet().size)
        assertEquals(listOf("COMMAND", "SKILL", "TOOL"), cats.map { it.label })
        // and the colours: one hue per meaning (shell and delete share red on purpose: both are high risk)
        val hues = SlashKind.values().map { it.color }
        assertEquals(SlashKind.values().size - 1, hues.toSet().size)
    }

    @Test
    fun theSlashMenuGroupsCommandsThenSkillsThenTools() {
        val items = com.arcana.brain.app.screens.SlashCommands + listOf(
            SlashItem("create_note", "Create a note", true, kind = SlashKind.Write),
            SlashItem("/coingecko-forecast", "Pool analysis", false, skill = true),
            SlashItem("current_time", "The time", true),
        )
        fun names(q: String) = com.arcana.brain.app.screens.slashMatches(items, q).map { it.name }
        // "c": commands first (those starting with c, then those containing it), then the skill, then the tools, whatever matched best
        val c = names("c")
        assertEquals(listOf("command", "command"), c.take(2).map { if (it.startsWith("/")) "command" else "other" }.take(2))
        assertTrue(c.indexOfLast { it.startsWith("/") && it != "/coingecko-forecast" } < c.indexOf("/coingecko-forecast"), "every command is listed before the skill: $c")
        assertTrue(c.indexOf("/coingecko-forecast") < c.indexOf("create_note") && c.indexOf("create_note") < c.indexOf("current_time"), "the skill comes before the tools: $c")
        // inside a group the names that start with the text still come first
        assertEquals(listOf("/clear", "/replace"), names("c").filter { it in setOf("/clear", "/replace") })
        // nothing typed: the declared order, which is already commands, skills, tools
        val all = names("")
        assertEquals(items.size, all.size)
        assertEquals(all.sortedBy { n -> items.first { it.name == n }.kind.category.ordinal }, all)
    }

    @Test
    fun theSlashMenuShowsWhetherASettingIsOnRightNow() {
        val on = com.arcana.brain.core.AppConfig(chatUseVault = true, toolsEnabled = true, toolMode = "auto", memoryEnabled = true)
        val off = com.arcana.brain.core.AppConfig(chatUseVault = false, toolsEnabled = false, memoryEnabled = false)
        fun state(name: String, c: com.arcana.brain.core.AppConfig, supported: Boolean? = null) = com.arcana.brain.app.screens.slashState(name, c, supported)?.first
        assertEquals("ON", state("/vault", on)); assertEquals("OFF", state("/vault", off))
        assertEquals("ON", state("/tools", on)); assertEquals("OFF", state("/tools", off)); assertEquals("NO SUPPORT", state("/tools", on, supported = false))
        assertEquals("AUTO", state("/mode", on)); assertEquals("TOOLS OFF", state("/mode", off))
        assertEquals("SHELL OFF", state("/autorun", on))
        assertEquals("ON", state("/autorun", on.copy(toolShell = true, toolAutoShell = true))); assertEquals("OFF", state("/autorun", on.copy(toolShell = true)))
        assertEquals("ON", state("/memory", on)); assertEquals("OFF", state("/memory", off))
        assertEquals(null, state("/new", on)); assertEquals(null, state("/history", off))
    }

    @Test
    fun modelIconsFollowTheFamilyAndKeepTheRealName() {
        assertEquals("◈ qwen3:30b", com.arcana.brain.app.design.ModelIcon.label("qwen3:30b"))
        assertEquals("◆ gemma4:12b", com.arcana.brain.app.design.ModelIcon.label("gemma4:12b"))
        assertEquals("▲ hf.co/x/Llama3:8b", com.arcana.brain.app.design.ModelIcon.label("hf.co/x/Llama3:8b"))
        assertEquals("◆ something-new", com.arcana.brain.app.design.ModelIcon.label("something-new"))
        assertEquals("* qwen3:30b", com.arcana.brain.app.design.ModelIcon.label("qwen3:30b", plain = true))
        assertEquals("●", com.arcana.brain.app.design.ModelIcon.user())
    }

    @Test
    fun thinkingLinesFollowTheViewAndStayOutOfSelection() {
        val text = "first second\nthird"
        assertTrue(com.arcana.brain.app.screens.thinkingLines(text, "hidden", false, 40).isEmpty())
        assertTrue(com.arcana.brain.app.screens.thinkingLines("  ", "full", false, 40).isEmpty())
        assertEquals(listOf("◌ thinking · 3 words"), com.arcana.brain.app.screens.thinkingLines(text, "summary", false, 40).map { it.text })
        val full = com.arcana.brain.app.screens.thinkingLines(text, "full", false, 40)
        assertTrue(full.size > 2 && full.all { it.entry < 0 })
    }

    @Test
    fun quietActionBarsAreNarrowerThanButtonsAndBreakAtTheirOwnWidth() {
        val items = listOf(ActionItem("Enter", "Send"), ActionItem("@", "Note"), ActionItem("Ctrl+O", "Attach"))
        // button: " Enter " + " Send " = 13, " @ "+" Note " = 9, " Ctrl+O "+" Attach " = 16, gaps of 2; quiet: 10, 6, 13, gaps of 3
        assertEquals(1, breakIntoLines(items, 13 + 2 + 9 + 2 + 16, ActionStyle.Button).size)
        assertEquals(2, breakIntoLines(items, 13 + 2 + 9 + 2 + 15, ActionStyle.Button).size)
        assertEquals(1, breakIntoLines(items, 10 + 3 + 6 + 3 + 13, ActionStyle.Quiet).size)
        assertEquals(2, breakIntoLines(items, 10 + 3 + 6 + 3 + 12, ActionStyle.Quiet).size)
        assertEquals(ActionStyle.Button, ActionStyle.entries.first { it.name == "Button" })
    }

    @Test
    fun aTypedSlashLineIsSplitIntoTheCommandAndWhatFollowsIt() {
        assertEquals("/revise" to "", splitSlash("/revise"))
        assertEquals("/revise" to "focus on the typo", splitSlash("  /revise   focus on the typo "))
        assertEquals("/revise" to "the main model", splitSlash("/revise: the main model"), "the colon of the hint it was copied from is dropped")
        assertEquals("/oracul" to "qwen3:30b", splitSlash("/oracul qwen3:30b"))
        assertEquals("/revise" to "a\nb", splitSlash("/revise\na\nb"))
        val message = unknownCommand("/reviise", SlashCommands)
        assertTrue("Unknown command /reviise." in message && message.endsWith("Type / to see the commands and tools."), message)
        assertTrue("/revise" in unknownCommand("/revis", SlashCommands), "a near miss names the command")
        assertEquals("Unknown command /zzz. Type / to see the commands and tools.", unknownCommand("/zzz", SlashCommands))
    }

    @Test
    fun theSlashMenuShowsAnExampleForEntriesThatTakeText() {
        assertEquals("e.g. /read_note Trip Planner", toolUsage("read_note", listOf("path")))
        assertEquals("usage: /some_new_tool <path> <content>", toolUsage("some_new_tool", listOf("path", "content")), "a tool without a curated example shows its required arguments")
        assertEquals("needs no text: just /another", toolUsage("another", emptyList()))
        val withText = SlashCommands.filter { it.usage.isNotEmpty() }.map { it.name }
        assertEquals(listOf("/oracul", "/revise", "/compare", "/memory", "/flow", "/goal"), withText, "exactly the commands that accept text carry an example")
        assertTrue(SlashCommands.filter { it.usage.isEmpty() }.none { it.name in setOf("/oracul", "/revise", "/compare", "/flow", "/goal", "/memory") })
    }

    @Test
    fun aComparisonIsDrawnInColumnsThatFitTheWidth() {
        assertEquals(1, columnCount(2, 60), "two columns of 38 do not fit in 60 cells")
        assertEquals(2, columnCount(2, 80))
        assertEquals(2, columnCount(5, 90))
        assertEquals(3, columnCount(5, 125))
        assertEquals(2, columnCount(2, 125), "never more columns than answers")
        val message = com.arcana.brain.core.Oracul.comparisonMessage("worker", listOf("big" to "Right side text", "other" to "Third"), "judge", "Left side text", "Big is best.")
        val parts = com.arcana.brain.core.Oracul.comparisonParts(message)!!
        val titleRuns = com.arcana.brain.app.screens.comparisonTitleRuns("Ψ Comparison · worker vs big, other, judged by judge", parts)
        assertEquals("Ψ Comparison · worker vs big, other, judged by judge", titleRuns.joinToString("") { it.text })
        assertEquals(listOf("worker", "big", "other", "judge"), titleRuns.filter { it.color != com.arcana.brain.app.design.Palette.Muted }.map { it.text }.drop(1), "each model name has its own colour")
        assertEquals(com.arcana.brain.app.design.Palette.Orange, titleRuns.last().color)
        val lines = sideBySide(parts, 7, com.arcana.brain.core.AppConfig(), 90)
        val texts = lines.map { it.text }
        assertTrue(texts.any { "worker · main" in it } && texts.any { "big · oracul" in it && "worker" !in it || "big · oracul" in it }, texts.toString())
        val both = texts.first { "Left side text" in it }
        assertTrue("Right side text" in both && both.indexOf("Left") < both.indexOf("Right"), "the two answers share a row, left then right: $both")
        assertTrue(texts.first { "Third" in it }.let { "Right" !in it }, "the third answer starts a new row of columns")
        assertTrue(texts.any { "Verdict · judge" in it } && texts.any { "Big is best." in it })
        assertTrue(lines.all { it.text.length <= 90 && it.entry == 7 }, "no row is wider than the window and every row belongs to the block")
    }

    @Test
    fun `the memory pill says when the end of the file is cut`() {
        assertEquals("⚠ memory 8k of 11k: end cut", com.arcana.brain.app.screens.memoryCutText(11_000, false))
        assertEquals("! memory 8k of 11k: end cut", com.arcana.brain.app.screens.memoryCutText(11_000, true))
    }

    @Test
    fun `the models segment puts the model that works first`() {
        val config = com.arcana.brain.core.AppConfig(oraculModel = "big", judgeModel = "jdg")
        val idle = com.arcana.brain.app.screens.modelsSegment("◆ small", null, config, false, 120)
        assertEquals(" ◆ small ", idle.parts[0].text)
        assertTrue(idle.parts[1].text.contains("Ψ big"))
        val asking = com.arcana.brain.app.screens.modelsSegment("◆ small", "big", config, false, 120)
        assertEquals(" Ψ big… ", asking.parts[0].text)
        assertTrue(asking.parts[1].text.contains("small waiting"))
        assertTrue(com.arcana.brain.app.screens.modelsSegment("◆ small", "jdg", config, false, 120).parts[0].text.contains("⚖ jdg"))
        assertEquals(2, com.arcana.brain.app.screens.modelsSegment("◆ small", "big", config, false, 20).parts.size, "narrow: the quiet part stays too, the bar wraps")
    }

    @Test
    fun theLibraryOpensOnTheViewOfItsSettingAndAllWhenItNamesNone() {
        assertEquals(LibraryFilter.All, defaultLibraryFilter(AppConfig()))
        assertEquals(LibraryFilter.Tags, defaultLibraryFilter(AppConfig(libraryTab = "tags")))
        assertEquals(LibraryFilter.Orphans, defaultLibraryFilter(AppConfig(libraryTab = "ORPHANS")))
        assertEquals(LibraryFilter.All, defaultLibraryFilter(AppConfig(libraryTab = "nonsense")))
    }
}
