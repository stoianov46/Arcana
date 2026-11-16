import com.arcana.brain.app.screens.AUTO_COMPACT_CHOICES
import com.arcana.brain.app.screens.PaneRows
import com.arcana.brain.app.screens.WINDOW_CHOICES
import com.arcana.brain.app.screens.autoCompactLabel
import com.arcana.brain.app.screens.compactionView
import com.arcana.brain.app.screens.effortCells
import com.arcana.brain.app.screens.nextChoice
import com.arcana.brain.app.screens.nextModel
import com.arcana.brain.app.screens.pickRows
import com.arcana.brain.app.screens.windowLabel
import com.arcana.brain.core.Sampling
import com.arcana.brain.app.screens.sliderCells
import com.arcana.brain.app.screens.slimBar
import com.arcana.brain.app.screens.statusPaneStep
import com.arcana.brain.app.screens.contextCells
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The pure parts of the Chat status pane: its width keys, the sampling sliders and the context / compaction state. */
class StatusPaneTest {
    @Test
    fun curlyBracesPointTheWayTheColumnsLeftEdgeMoves() {
        // { widens (and switches the pane on), } narrows (and, at the smallest size, switches it off)
        val on = statusPaneStep(on = false, percent = 30, wider = true)
        assertTrue(on.on); assertEquals(30, on.percent); assertTrue(on.message.startsWith("Status pane on at 30 %"))
        val wider = statusPaneStep(true, 30, wider = true)
        assertEquals(35, wider.percent); assertTrue(wider.on)
        val narrower = statusPaneStep(true, 30, wider = false)
        assertEquals(25, narrower.percent); assertTrue(narrower.on)
        assertEquals(30, statusPaneStep(true, 33, wider = false).percent, "steps land on multiples of 5")
        assertEquals(35, statusPaneStep(true, 33, wider = true).percent)
        val off = statusPaneStep(true, 10, wider = false)
        assertFalse(off.on); assertEquals(10, off.percent)
        val widest = statusPaneStep(true, 90, wider = true)
        assertEquals(90, widest.percent); assertTrue("widest" in widest.message)
        val nothing = statusPaneStep(false, 30, wider = false)
        assertFalse(nothing.on); assertEquals("", nothing.message, "} does nothing while the pane is off")
    }

    @Test
    fun theTwoSamplingModesHaveOneNameEverywhere() {
        assertEquals("Model defaults", Sampling.modeLabel(Sampling.MODEL))
        assertEquals("Custom", Sampling.modeLabel(Sampling.CUSTOM))
        assertEquals("Defaults", Sampling.modeShort(Sampling.MODEL))
        assertEquals("Custom", Sampling.modeShort(Sampling.CUSTOM))
    }

    @Test
    fun aSliderShowsWhereTheValueSitsInItsRange() {
        assertEquals(0, sliderCells(0.0, 0.0, 2.0, 10), "the minimum is empty")
        assertEquals(5, sliderCells(1.0, 0.0, 2.0, 10))
        assertEquals(10, sliderCells(2.0, 0.0, 2.0, 10), "the maximum is full")
        assertEquals(1, sliderCells(0.05, 0.0, 2.0, 10), "a small value is still one cell")
        assertEquals(10, sliderCells(9.0, 0.0, 2.0, 10), "held inside the range")
        assertEquals("▰▰▰" to "▱▱", slimBar(3, 5, false))
        assertEquals("##" to "---", slimBar(2, 5, true))
    }

    @Test
    fun theContextBarIsTenSlimCellsAndTheCompactionStateSaysHowMuchRoomIsLeft() {
        assertEquals("▰▰▰▰▰▰▰" to "▱▱▱", slimBar(7, 10, false))
        // 131,072-token window, auto-compact at 80 % = 104,857 tokens
        val room = compactionView(used = 90_000, estimated = false, window = 131_072, autoPercent = 80)
        assertEquals("auto-compact at 80 %", room.ruleText); assertFalse(room.warn); assertTrue(room.roomText.startsWith("~") && "left before it" in room.roomText, room.roomText)
        val due = compactionView(used = 110_000, estimated = true, window = 131_072, autoPercent = 80)
        assertTrue(due.warn && due.roomText.startsWith("due"), due.roomText); assertTrue(due.usedText.startsWith("~"), "an estimate after a compaction")
        val off = compactionView(used = 5_000, estimated = false, window = 131_072, autoPercent = 0)
        assertEquals("auto-compact off", off.ruleText); assertFalse(off.warn)
        val fresh = compactionView(used = null, estimated = false, window = 8192, autoPercent = 80)
        assertEquals("no reply yet", fresh.usedText); assertEquals("", fresh.roomText)
        assertEquals("window unknown", compactionView(null, false, 0, 80).windowText)
        // the bar has three tones: 70 % filled, room up to the 80 % line, the rest free (10 cells)
        contextCells(0.70, 80, 10).let { assertEquals(Triple(7, 1, 2), Triple(it.used, it.room, it.free)) }
        contextCells(0.85, 80, 10).let { assertEquals(Triple(9, 0, 1), Triple(it.used, it.room, it.free), "past the line there is no room left") }
        contextCells(0.10, 80, 10).let { assertEquals(Triple(1, 7, 2), Triple(it.used, it.room, it.free)) }
        contextCells(0.70, 0, 10).let { assertEquals(Triple(7, 0, 3), Triple(it.used, it.room, it.free), "auto-compact off: no room tone") }
        contextCells(0.0, 80, 10).let { assertEquals(Triple(0, 8, 2), Triple(it.used, it.room, it.free)) }
        contextCells(0.02, 80, 10).let { assertEquals(1, it.used, "anything above zero shows a cell") }
        contextCells(1.0, 80, 10).let { assertEquals(Triple(10, 0, 0), Triple(it.used, it.room, it.free)) }
    }

    @Test
    fun theModelAndTheContextSettingsStepThroughTheSameChoicesAsSettings() {
        val models = listOf("gemma4:latest", "qwen3:30b", "llama3.1:8b")
        assertEquals("qwen3:30b", nextModel(models, "gemma4:latest", 1))
        assertEquals("llama3.1:8b", nextModel(models, "gemma4:latest", -1), "wraps round")
        assertEquals("gemma4:latest", nextModel(models, "llama3.1:8b", 1))
        assertEquals("qwen3:30b", nextModel(models, "not-installed", 1), "a model that is not installed steps from the first")
        assertEquals(null, nextModel(emptyList(), "x", 1))
        assertEquals(8192, nextChoice(WINDOW_CHOICES, 0, 1)); assertEquals(1048576, nextChoice(WINDOW_CHOICES, 0, -1))
        assertEquals(0, nextChoice(WINDOW_CHOICES, 1048576, 1), "the largest window wraps to the model's own")
        assertEquals(90, nextChoice(AUTO_COMPACT_CHOICES, 80, 1)); assertEquals(0, nextChoice(AUTO_COMPACT_CHOICES, 70, -1))
        assertEquals("Model default", windowLabel(0)); assertEquals("8k", windowLabel(8192)); assertEquals("128k", windowLabel(131072)); assertEquals("1M", windowLabel(1048576))
        assertEquals("Off", autoCompactLabel(0)); assertEquals("80 %", autoCompactLabel(80))
        // the rows are in order: model, mode, the six options, the three thinking rows, then window, auto-compact, keep
        assertEquals(listOf(0, 1, 2, 3, 9, 10, 11, 12, 13, 14, 15), listOf(PaneRows.MODEL, PaneRows.DOCK, PaneRows.MODE, PaneRows.FIRST_PARAM, PaneRows.EFFORT, PaneRows.VIEW, PaneRows.PANE, PaneRows.PLACE, PaneRows.WINDOW, PaneRows.AUTO, PaneRows.KEEP))
        assertEquals("full", nextChoice(listOf("summary", "full", "hidden"), "summary", 1)); assertEquals("hidden", nextChoice(listOf("summary", "full", "hidden"), "summary", -1))
    }

    @Test
    fun aShortWindowDrawsTheMostUsefulOptionalRowsThatFit() {
        assertEquals(PaneRows.OPTIONAL.toSet(), pickRows(100), "room for everything")
        assertEquals(emptySet(), pickRows(0))
        // effort first: its row, its bar and the Thinking title make three
        assertEquals(emptySet(), pickRows(2).intersect(setOf(PaneRows.EFFORT)), "two rows are not enough for the effort and its title")
        assertEquals(setOf(PaneRows.EFFORT), pickRows(3))
        assertEquals(setOf(PaneRows.EFFORT, PaneRows.WINDOW, PaneRows.AUTO), pickRows(5))
        assertEquals(setOf(PaneRows.WINDOW), pickRows(1), "one row left: the effort does not fit, the window does")
        assertEquals(PaneRows.OPTIONAL.toSet(), pickRows(9), "seven rows, the effort bar and the one title")
    }

    @Test
    fun theEffortBarFillsOneStepPerLevelTheModelLists() {
        val opts = listOf("default", "low", "medium", "high")
        assertEquals(0, effortCells(opts, "off", "medium", 12), "off is the empty bar")
        assertEquals(4, effortCells(opts, "low", "medium", 12)); assertEquals(8, effortCells(opts, "medium", "medium", 12)); assertEquals(12, effortCells(opts, "high", "medium", 12))
        assertEquals(8, effortCells(opts, "default", "medium", 12), "left to the model: the level the model really uses")
        assertEquals(0, effortCells(opts, "default", "", 12), "the model does not say: nothing to show")
        assertEquals(0, effortCells(opts, "xhigh", "", 12), "a level the model does not list")
        val onOff = listOf("default", "off", "on")
        assertEquals(10, effortCells(onOff, "on", "", 10)); assertEquals(0, effortCells(onOff, "off", "", 10))
        assertEquals(1, effortCells(opts, "low", "", 3), "a level always shows at least one cell")
    }
}
