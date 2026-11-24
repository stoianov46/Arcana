import com.arcana.brain.app.AppContext
import com.arcana.brain.core.AppConfig
import com.arcana.brain.core.ChatStore
import com.arcana.brain.core.ConfigStore
import com.arcana.brain.core.ChatTools
import com.arcana.brain.core.ToolUseStore
import com.arcana.brain.core.UsageStore
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/** Every tool the chat answers itself has a handler in the session, and nothing else does: a new chat tool cannot be offered without being answered. */
class ChatToolHandlersTest {
    @Test
    fun everyChatToolHasAHandler() {
        val dir = Files.createTempDirectory("arcana-chat-tools")
        val config = ConfigStore(dir.resolve("config.json")).also { it.save(AppConfig(keepChats = false)) }
        val app = AppContext(config, UsageStore(dir.resolve("usage.jsonl")), ToolUseStore(dir.resolve("tool-usage.jsonl")), ChatStore(dir.resolve("chats")), com.arcana.brain.core.EventStore(dir.resolve("events.jsonl")))
        assertEquals(ChatTools.names, app.chat.chatToolHandlers.keys)
    }
}
