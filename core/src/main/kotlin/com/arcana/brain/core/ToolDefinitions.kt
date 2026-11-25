package com.arcana.brain.core

import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

// The prompts about tools and the definitions of the tools the chat answers itself (ask_user, request_access, turn_on_tools, request_mode); moved out of Tools.kt (proposal 0007).

/** The word to put in the system prompt when tools are on, so the model knows what it can do and how to behave. */
const val TOOLS_PROMPT = " You have tools to search, read and write the user's notes and to read and write files in the folders they allowed. " +
    "To find NOTES about a topic, call search_vault first (it ranks passages by their words AND their meaning; describe what you look for), then read_note the best hits by the path in its answer. Do not guess note names, and do not read a long index or reference note (such as .arcana/reference/…) to look something up: search. To find CODE or DOCUMENTS (PDF, Word) in the user's folders call search_files, then read_file the best hit near the line or page it gives. " +
    "To find where something is used or defined, search inside files with grep_files; read long files in parts with start_line and end_line. This includes source code: you can read a project's .kt, .java, .xml, .gradle and other files and analyse them, not only notes. " +
    "Your tools are NOT limited to the vault: any folder on this computer can be used (except hidden and system ones), and the user is asked to allow a new one. " +
    "So when something is not in the vault, look for it elsewhere (find_files, list_dir with ~/… or absolute paths) instead of saying you can only see the vault. " +
    "When a web service answers \"rate limit\" (HTTP 429) or allows only a few requests a minute, call wait for the time it needs, then retry; do not hammer it. When you need a decision or missing information from the user, call ask_user with a few short options instead of guessing; use it for quizzes too. Never say you cannot access files without first trying the matching tool. " +
    "Deleting (delete_path) and moving (move_path) always ask the user to confirm; a deleted item goes to a trash folder, not into nothing. Use a tool only when it helps. Do not guess a path: list or search first. To analyse a folder, module or project, list it with recursive=true, read every relevant file (not just the first ones), and at the end list the files you really read. Never claim you reviewed files you did not open. Keep working until the task is finished: never stop to ask " +
    "permission to read, list or search, or to continue; the app already asks the user whenever approval is needed. Changes are shown to the user for approval; " +
    "after making one, say briefly what you did."

private val PATH_LIKE = Regex("""(^|\s)(~/|/Users/|/home/|[A-Za-z]:\\)\S""")
private val FILE_WORDS = Regex("""\b(files?|folders?|directory|directories|project|module|repo|repository|codebase|source code)\b""", RegexOption.IGNORE_CASE)

/** True when [message] reads like a job for file tools (a path, or files / folders / a project), so the user can be offered them before the model has to say it cannot. */
fun looksLikeToolTask(message: String): Boolean = PATH_LIKE.containsMatchIn(message) || FILE_WORDS.containsMatchIn(message)

/** The one tool a model has when tools are off or read-only: it asks the user, on the spot, for what it is missing. */
/** The tool that says where the vault and Arcana's files are ([ToolBox.vaultInfo]). */
const val VAULT_INFO = "vault_info"

/** The tool that runs the vault hygiene checks and lists dead links ([ToolBox.vaultHealth]); the obsidian-doctor skill is built on it. */
const val VAULT_HEALTH = "vault_health"

const val ASK_USER = "ask_user"

private val OPTION_LABEL = Regex("""^\s*(\(\s*[A-Za-z0-9]{1,2}\s*\)|[A-Za-z0-9]{1,2}\s*[).:])\s+""")

/** An answer option without the "A)", "(b)", "1." or "C:" the model put in front of it: the panel numbers them itself. */
fun cleanOption(option: String): String =
    option.trim().let { text -> OPTION_LABEL.replace(text, "").trim().trim('`', '*').trim().ifEmpty { text } }

const val ASK_USER_DESCRIPTION = "Ask the user a question with answer options (a quiz or a decision), or an open question without options. Always set question to the full text. " +
    "Use it when you need a decision or missing information instead of guessing, and for quizzes. The answer comes back as the result."

val ASK_USER_PARAMETERS: JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        putJsonObject("question") { put("type", "string"); put("description", "The question, short and clear") }
        putJsonObject("options") {
            put("type", "array"); put("description", "Up to 9 short answers to pick from; leave out for an open question")
            putJsonObject("items") { put("type", "string") }
        }
        putJsonObject("multiple") { put("type", "boolean"); put("description", "true if more than one option may be chosen") }
    }
    putJsonArray("required") { add(JsonPrimitive("question")) }
}

val ASK_USER_DEFINITION: JsonObject = buildJsonObject {
    put("type", "function")
    putJsonObject("function") {
        put("name", ASK_USER)
        put("description", ASK_USER_DESCRIPTION)
        put("parameters", ASK_USER_PARAMETERS)
    }
}

const val REQUEST_ACCESS = "request_access"

val REQUEST_ACCESS_DEFINITION: JsonObject = buildJsonObject {
    put("type", "function")
    putJsonObject("function") {
        put("name", REQUEST_ACCESS)
        put("description", "Ask the user to turn on tools (or to leave read-only plan mode) when the request needs you to read, search, create or change their notes or files. They are asked at once and may agree.")
        putJsonObject("parameters") {
            put("type", "object")
            putJsonObject("properties") { putJsonObject("reason") { put("type", "string"); put("description", "What you need to do and why, in one sentence") } }
            putJsonArray("required") { add(JsonPrimitive("reason")) }
        }
    }
}

/** The tools a switch turns off, and which switch: shown as "switched off" instead of "no such tool" when the model calls one. */
val OFF_TOOLS = mapOf("run_command" to "shell", "web_search" to "web", "fetch_url" to "web", "http_request" to "web")

const val TURN_ON_TOOLS = "turn_on_tools"

/** Offered while shell commands or the web are switched off (instead of their four tools): the model asks the user, who decides in a dialog. */
fun turnOnToolsDefinition(off: List<String>): JsonObject = buildJsonObject {
    put("type", "function")
    putJsonObject("function") {
        put("name", TURN_ON_TOOLS)
        put("description", "Ask the user to switch on shell commands (run_command) or the web (web_search, fetch_url, http_request) when the task needs them. Switched off now: ${off.joinToString(" and ")}.")
        putJsonObject("parameters") {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("what") { put("type", "string"); putJsonArray("enum") { off.forEach { add(JsonPrimitive(it)) } } }
                putJsonObject("reason") { put("type", "string"); put("description", "What you need it for, in one sentence") }
            }
            putJsonArray("required") { add(JsonPrimitive("what")); add(JsonPrimitive("reason")) }
        }
    }
}

const val REQUEST_MODE = "request_mode"

/** The tool modes the model may ask for, in the order `/mode` cycles them. */
val TOOL_MODES = listOf("ask", "auto", "plan")

val REQUEST_MODE_DEFINITION: JsonObject = buildJsonObject {
    put("type", "function")
    putJsonObject("function") {
        put("name", REQUEST_MODE)
        put("description", "Ask the user to switch the tool mode: `auto` (changes run at once, old files backed up) when many changes are coming, `plan` (read-only) to investigate first, `ask` (every change asks) to go back. The user is always asked and may refuse; do nothing until the result says it is done.")
        putJsonObject("parameters") {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("mode") { put("type", "string"); putJsonArray("enum") { TOOL_MODES.forEach { add(JsonPrimitive(it)) } }; put("description", "The mode to switch to") }
                putJsonObject("reason") { put("type", "string"); put("description", "Why, in one sentence") }
            }
            putJsonArray("required") { add(JsonPrimitive("mode")); add(JsonPrimitive("reason")) }
        }
    }
}

/** For a model that can use tools while they are off: [REQUEST_ACCESS] is its way to get them. */
const val REQUEST_ACCESS_PROMPT = " Tools are turned off in this chat. When the user asks you to read, search, create or change their notes or files, do not say you cannot: " +
    "call request_access with the reason. The user is asked right then; if they agree you get the tools and can carry on. To ask the user a question with options (or run a quiz), call ask_user."

/** Said to the model when tools are off, so it explains how to get them instead of claiming it cannot do such things at all. */
const val NO_TOOLS_PROMPT = " In this chat you have no tools turned on, so you cannot read or write the user's notes or files yourself. " +
    "If they ask you to, tell them they can turn tools on with /tools in Chat (or Settings → Tools), after which you can search, read and " +
    "write their notes and files, always asking before changing anything."
