import com.arcana.brain.app.screens.Docks
import com.arcana.brain.app.screens.PaneId
import com.arcana.brain.app.screens.statusPaneStep
import com.arcana.brain.core.AppConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DocksTest {
    private val both = AppConfig(thinkingPane = true, thinkingPanePlacement = "right", statusPane = true, statusPanePlacement = "right")

    @Test
    fun aSideHoldsOnePanelUntilTheFirstIsSwitchedOff() {
        val thinkingFirst = AppConfig(thinkingPane = true, thinkingPanePlacement = "right")
        assertNotNull(Docks.refusal(thinkingFirst, PaneId.Status, "right"))
        assertTrue(Docks.refusal(thinkingFirst, PaneId.Status, "right")!!.contains("thinking pane"))
        assertNull(Docks.refusal(thinkingFirst, PaneId.Status, "left"), "the other side is free")
        assertNull(Docks.refusal(thinkingFirst.copy(thinkingPane = false), PaneId.Status, "right"), "off means the side is free")
        assertNull(Docks.refusal(AppConfig(thinkingPane = true, thinkingPanePlacement = "bottom"), PaneId.Status, "right"), "a strip takes no side")
    }

    @Test
    fun theFirstPanelKeepsTheSideWhenAConfigHasTwo() {
        assertEquals("right", Docks.sideOf(both, PaneId.Thinking))
        assertNull(Docks.sideOf(both, PaneId.Status))
        assertEquals("left", Docks.sideOf(both.copy(statusPanePlacement = "left"), PaneId.Status))
        assertNull(Docks.sideOf(both.copy(thinkingPanePlacement = "top"), PaneId.Thinking), "a strip is not a side")
    }

    @Test
    fun onTheLeftTheStatusPaneMessagesNameTheSwappedKeys() {
        assertTrue(statusPaneStep(true, 30, wider = true, left = true).message.contains("} wider, { narrower"))
        assertTrue(statusPaneStep(true, 30, wider = true).message.contains("{ wider, } narrower"))
    }
}
