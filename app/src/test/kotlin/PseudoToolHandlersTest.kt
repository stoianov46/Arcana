import com.arcana.brain.app.AppContext
import com.arcana.brain.core.AppConfig
import com.arcana.brain.core.ChatStore
import com.arcana.brain.core.ConfigStore
import com.arcana.brain.core.PseudoTools
import com.arcana.brain.core.ToolUseStore
import com.arcana.brain.core.UsageStore
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/** Every tool the chat answers itself has a handler in the session, and nothing else does: a new pseudo-tool cannot be offered without being answered. */
class PseudoToolHandlersTest {
    @Test
    fun everyPseudoToolHasAHandler() {
        val dir = Files.createTempDirectory("arcana-pseudo")
        val config = ConfigStore(dir.resolve("config.json")).also { it.save(AppConfig(keepChats = false)) }
        val app = AppContext(config, UsageStore(dir.resolve("usage.jsonl")), ToolUseStore(dir.resolve("tool-usage.jsonl")), ChatStore(dir.resolve("chats")), com.arcana.brain.core.EventStore(dir.resolve("events.jsonl")))
        assertEquals(PseudoTools.names, app.chat.pseudoHandlers.keys)
    }
}
