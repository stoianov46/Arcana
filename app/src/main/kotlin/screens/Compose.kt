package com.arcana.brain.app.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.arcana.brain.app.AppContext
import com.arcana.brain.app.GenState
import com.arcana.brain.app.design.*
import com.arcana.brain.app.input.Key
import com.arcana.brain.app.input.KeyHandler
import com.arcana.brain.app.input.TextFieldState
import com.arcana.brain.app.input.char
import com.arcana.brain.app.navigation.FocusMode
import com.arcana.brain.app.navigation.Hints
import com.arcana.brain.app.navigation.LocalNavigator
import com.arcana.brain.app.navigation.Screen
import com.arcana.brain.app.rememberGeneration
import com.arcana.brain.core.ChatMessage
import com.arcana.brain.core.ImageAttachment
import com.arcana.brain.core.ImageFiles
import com.arcana.brain.core.Note
import com.arcana.brain.core.NoteTask
import com.arcana.brain.core.Prompts
import com.arcana.brain.core.SessionKind
import com.arcana.brain.core.Vault
import java.nio.file.Path

private const val MaxNoteChars = 60_000

/** What the user can ask AI to do. Most tasks start from an existing note; "write" starts from a prompt. */
private enum class Task(val label: String, val icon: String, val detail: String, val note: NoteTask?, val inputLabel: String? = null) {
    Write("Write a new note", "✎", "from a prompt you type, with pictures if you like", null, "What should the note be about?"),
    Pictures("Turn pictures into a note", "▣", "describe, transcribe or summarize images", null, "What should the note say about them? (optional)"),
    Summarize("Summarize a note", "≡", "TL;DR, key points, action items", NoteTask.Summarize),
    Improve("Improve a note", "✦", "fix spelling, clarity and structure", NoteTask.Improve),
    Continue("Continue a note", "→", "keep writing where it stops", NoteTask.Continue),
    Translate("Translate a note", "⇄", "into a language you choose", NoteTask.Translate, "Translate into which language?"),
    ActionItems("Extract action items", "☑", "tasks as a checklist", NoteTask.ActionItems),
    Connect("Suggest tags and links", "≈", "connect it to the rest of your vault", NoteTask.Connect),
    Custom("Do something else with a note", "…", "your own instruction", NoteTask.Custom, "What should AI do with the note?"),
    ;

    val needsNote get() = note != null

    /** The task cannot start without at least one picture. */
    val needsPictures get() = this == Pictures

    /** Whether replacing the note with the result makes sense: the result is the whole new text, not a part or a summary. */
    val replaceable get() = this == Improve || this == Translate || this == Custom

    /** The heading the result goes under when it is added to a note; null = added as plain text (a continuation). */
    fun heading(input: String): String? = when (this) {
        Summarize -> "Summary"
        ActionItems -> "Action items"
        Connect -> "Suggested connections"
        Continue -> null
        Translate -> "Translation (${input.trim().ifEmpty { "English" }})"
        Improve -> "Improved version"
        Custom -> "AI result"
        Write -> "Generated"
        Pictures -> "Pictures"
    }
}

private enum class Mode { Start, PickSource, Input, Result, Follow, Title, AppendPick, ConfirmReplace, ConfirmDiscard }

/** What a follow-up prompt does to the draft: rewrite all of it, or write one more section after it. */
private enum class FollowKind(val verb: String) { Refine("Refine"), More("Add more") }

@Composable
fun NoteComposerScreen(app: AppContext) = WithVault(app, "AI notes") { vault -> NoteComposerContent(app, vault) }

@Composable
private fun NoteComposerContent(app: AppContext, vault: Vault) {
    val gen = rememberGeneration(app, "Generate")
    val navigator = LocalNavigator.current
    val output = remember { OutputState() }
    val taskPicker = remember { PickerState() }
    val notePicker = remember { PickerState() }
    var notes by remember(vault) { mutableStateOf(vault.notes()) }
    // A note handed over by the Library ("AI on this note"): the tasks then apply to it, without asking which note.
    val handedOver = remember { navigator.noteRequest.also { navigator.noteRequest = null } }
    var presetNote by remember { mutableStateOf(handedOver?.let { p -> notes.firstOrNull { it.path == p } }) }
    var mode by remember { mutableStateOf(Mode.Start) }
    var task by remember { mutableStateOf(Task.Write) }
    var source by remember { mutableStateOf<Note?>(null) }
    var typing by remember { mutableStateOf(true) }
    val input = remember { TextFieldState(multiline = true) }
    val followUp = remember { TextFieldState(multiline = true) }
    val title = remember { TextFieldState() }
    var notice by remember { mutableStateOf<Notice?>(null) }
    // Where the note picker leads: "append to another note" instead of choosing the source.
    var pickingTarget by remember { mutableStateOf(false) }
    var discardTarget by remember { mutableStateOf<Screen?>(null) }
    // Pictures, PDFs and text files chosen for the next request (the first one, or a follow-up).
    val attachments = remember { app.attachments() }
    // Every picture the draft was made from, so a saved note can embed them.
    var usedPictures by remember { mutableStateOf<List<Path>>(emptyList()) }
    var copyPictures by remember { mutableStateOf(true) }

    // The conversation behind the draft: system prompt, the first request, then every follow-up and the reply to it.
    val conversation = remember { mutableStateListOf<ChatMessage>() }
    // Every accepted version of the draft, oldest first, so a follow-up can be undone.
    val drafts = remember { mutableStateListOf<String>() }
    var prefix by remember { mutableStateOf("") }
    var pendingUser by remember { mutableStateOf<ChatMessage?>(null) }
    var followKind by remember { mutableStateOf(FollowKind.Refine) }
    var lastFollow by remember { mutableStateOf<Triple<FollowKind, String, SentAttachments>?>(null) }
    // The current draft has been written to the vault (saved, appended or replaced), so leaving loses nothing.
    var applied by remember { mutableStateOf(false) }

    val tasks = if (presetNote != null) Task.entries.filter { it.needsNote } else Task.entries

    val draft = drafts.lastOrNull().orEmpty()
    // While a reply streams (and right after it ends) the draft is what it will become; otherwise the last good version.
    val shown = when (gen.state) {
        GenState.Running, GenState.Done -> prefix + gen.text
        else -> draft
    }
    val entries = rememberEntries(shown)
    val running = gen.state == GenState.Running
    val canUse = shown.isNotBlank() && !running
    val needsConfirm = drafts.isNotEmpty() && !applied

    LaunchedEffect(gen.state) {
        if (gen.state == GenState.Done) {
            pendingUser?.let { conversation += it }
            conversation += ChatMessage.assistant(gen.text)
            drafts += prefix + gen.text
            pendingUser = null
            applied = false
        }
    }

    // Leaving for another screen with a draft that was never written to the vault asks first.
    val latestNeedsConfirm by rememberUpdatedState(needsConfirm && mode != Mode.Start)
    DisposableEffect(navigator) {
        navigator.leaveGuard = { target ->
            if (latestNeedsConfirm && target != Screen.Compose) {
                discardTarget = target
                mode = Mode.ConfirmDiscard
                true
            } else {
                false
            }
        }
        onDispose { navigator.leaveGuard = null }
    }

    fun subject(): String = when (task) {
        Task.Write -> input.text.trim().ifEmpty { "Note from pictures" }
        Task.Pictures -> "Pictures: " + (usedPictures.ifEmpty { attachments.picturePaths.toList() }).joinToString(", ") { it.fileName.toString() }
        else -> "${task.label}: ${source?.path.orEmpty()}"
    }

    fun openAttach() {
        notice = null
        attachments.open { text, error ->
            notice = Notice(text, isError = error)
            typing = true
        }
    }

    /** The last message of [messages] with what was attached ([sent]) carried by it. */
    fun withAttachments(messages: List<ChatMessage>, sent: SentAttachments): List<ChatMessage> =
        if (sent.isEmpty) messages else messages.dropLast(1) + messages.last().copy(
            images = sent.pictures.map { it.base64 }, imageNames = sent.pictures.map { it.name }, files = sent.files,
        )

    fun resetToStart() {
        gen.reset()
        output.reset()
        conversation.clear()
        drafts.clear()
        prefix = ""
        pendingUser = null
        lastFollow = null
        applied = false
        source = null
        input.clear()
        attachments.clear()
        usedPictures = emptyList()
        notes = vault.notes()
        mode = Mode.Start
    }

    fun begin(messages: List<ChatMessage>, sent: SentAttachments) {
        conversation.clear()
        drafts.clear()
        prefix = ""
        pendingUser = null
        lastFollow = null
        applied = false
        usedPictures = sent.picturePaths
        attachments.clear()
        app.session.log(SessionKind.Task, subject(), source?.path)
        conversation += messages
        mode = Mode.Result
        notice = null
        output.reset()
        gen.start { messages }
    }

    /** The tags in use and the notes worth linking to, from the vault index: the lists the model must choose from. */
    fun connectContext(path: String): String {
        val index = app.brain.index ?: return "(the vault index is not ready, so use only your judgement)"
        val tags = index.tagCounts.take(40).joinToString(" ") { "#${it.first}" }.ifEmpty { "(none yet)" }
        val related = index.related(path, 12).map { it.title }
        val recent = index.notes.filter { it.path != path }.sortedByDescending { it.modified }.take(60).map { it.title }
        return "Existing tags: $tags\nCandidate notes to link to: ${(related + recent).distinct().take(60).joinToString("; ")}"
    }

    fun generate() {
        val request = input.text.trim()
        val noteTask = task.note
        val sent = attachments.peek()
        val pics = sent.pictures
        if (noteTask == null) {
            if (task.needsPictures && pics.isEmpty()) {
                notice = Notice("Attach at least one picture first: press Ctrl+O.", isError = true)
                return
            }
            if (request.isEmpty() && sent.isEmpty) return
            val text = request.ifEmpty {
                if (task == Task.Pictures) {
                    "Describe the attached picture${if (pics.size == 1) "" else "s"} as a well-structured note: transcribe any visible text, list the key elements, and summarize what it shows."
                } else if (pics.isEmpty()) {
                    "Write a clear, well-structured note based on the attached file${if (sent.files.size == 1) "" else "s"}."
                } else {
                    "Write a clear, well-structured note based on the attached picture${if (pics.size == 1) "" else "s"}."
                }
            }
            begin(withAttachments(Prompts.compose(app.config, text), sent), sent)
            return
        }
        val note = source ?: return
        // The model gets the note's text without its frontmatter (tags, dates), which is not part of what it should work on.
        val text = runCatching { Vault.splitFrontmatter(vault.read(note.path)).second.take(MaxNoteChars) }.getOrElse {
            notice = saveFailure(it)
            return
        }
        // "Connect" works from what the vault already contains: its tags, and the notes this one could link to.
        val extra = if (noteTask == NoteTask.Connect) connectContext(note.path) else request
        begin(withAttachments(Prompts.noteTask(app.config, noteTask, note.title, text, extra), sent), sent)
    }

    fun runFollow(kind: FollowKind, instruction: String, sent: SentAttachments = attachments.peek()) {
        val userText = when (kind) {
            FollowKind.Refine -> "Revise the note above as follows: $instruction\n\nReturn the complete revised note in Markdown, nothing else."
            FollowKind.More -> "Write an additional section for the note above: $instruction\n\nReturn only the new section in Markdown, nothing else."
        }
        prefix = if (kind == FollowKind.More) draft + "\n\n" else ""
        val user = ChatMessage.user(userText, sent.pictures, files = sent.files)
        pendingUser = user
        lastFollow = Triple(kind, instruction, sent)
        usedPictures = (usedPictures + sent.picturePaths).distinct()
        attachments.clear()
        val messages = conversation.toList() + user
        mode = Mode.Result
        notice = null
        output.reset()
        gen.start { messages }
    }

    /** Takes the last accepted version back (and the exchange that produced it). */
    fun dropLastVersion() {
        if (drafts.size > 1) {
            drafts.removeAt(drafts.lastIndex)
            repeat(2) { if (conversation.size > 2) conversation.removeAt(conversation.lastIndex) }
        }
    }

    fun undo() {
        if (drafts.size <= 1 || running) return
        dropLastVersion()
        gen.reset()
        output.reset()
        notice = Notice("Back to version ${drafts.size}")
    }

    fun redo() {
        val follow = lastFollow
        if (follow == null || drafts.size <= 1) {
            generate()
            return
        }
        // A finished follow-up is replaced by a new try; one that failed or was cancelled was never kept.
        if (pendingUser == null) dropLastVersion()
        runFollow(follow.first, follow.second, follow.third)
    }

    fun defaultTitle(): String = Vault.sanitizeTitle(
        when (task) {
            Task.Write -> input.text.trim().take(60).ifEmpty { "Note from pictures" }
            Task.Pictures -> usedPictures.firstOrNull()?.fileName?.toString()?.substringBeforeLast('.')?.let { "Pictures - $it" } ?: "Note from pictures"
            Task.Summarize -> "${source?.title} - Summary"
            Task.Improve -> "${source?.title} - Improved"
            Task.Continue -> "${source?.title} - Continued"
            Task.Translate -> "${source?.title} - ${input.text.trim().ifEmpty { "Translation" }}"
            Task.ActionItems -> "${source?.title} - Action items"
            Task.Connect -> "${source?.title} - Connections"
            Task.Custom -> "${source?.title} - AI"
        },
    )

    fun saveNew() {
        notice = runCatching {
            val body = if (copyPictures && usedPictures.isNotEmpty()) {
                val names = usedPictures.map { vault.copyAttachment(app.config.outputFolder, it) }
                shown.trimEnd() + "\n\n## Pictures\n\n" + names.joinToString("\n") { "![[$it]]" }
            } else {
                shown
            }
            val saved = vault.create(app.config.outputFolder, title.text, body, app.config.model)
            app.session.log(SessionKind.Created, saved.title, saved.path)
            app.brain.changed()
            applied = true
            notes = vault.notes()
            Notice("Saved as ${saved.path}")
        }.getOrElse(::saveFailure)
        mode = Mode.Result
    }

    fun appendTo(target: Note) {
        notice = runCatching {
            vault.append(target.path, task.heading(input.text), shown)
            app.session.log(SessionKind.Appended, "${task.label} → ${target.title}", target.path)
            app.brain.changed()
            applied = true
            Notice("Added to ${target.path}")
        }.getOrElse(::saveFailure)
        mode = Mode.Result
    }

    fun replaceSource() {
        val note = source ?: return
        notice = runCatching {
            val backup = vault.replace(note.path, shown)
            app.session.log(SessionKind.Replaced, note.title, note.path)
            app.brain.changed()
            applied = true
            Notice("Replaced ${note.path}. The old text is kept in $backup")
        }.getOrElse(::saveFailure)
        mode = Mode.Result
    }

    FocusMode(
        if (attachments.browsing) attachments.filtering else
        (mode == Mode.Input && typing) || mode == Mode.Follow || mode == Mode.Title || mode == Mode.ConfirmReplace || mode == Mode.ConfirmDiscard ||
            (mode == Mode.Start && taskPicker.filtering) || ((mode == Mode.PickSource || mode == Mode.AppendPick) && notePicker.filtering),
    )
    if (attachments.browsing) {
        Hints(*attachBrowserHints(attachments).toTypedArray())
    } else when (mode) {
        Mode.Start -> Hints("↑↓  choose", "Enter  select", "/  filter")
        Mode.PickSource -> Hints("↑↓  move", "Enter  choose note", "/  filter", "Esc  back")
        Mode.Input -> if (typing) Hints("Enter  go", "Ctrl+O  attach", "Ctrl+U  clear line", "Esc  stop typing") else Hints("Enter  type", "i  attach", "Esc  back")
        Mode.Follow -> Hints("Enter  ${followKind.verb.lowercase()}", "Ctrl+O  attach", "Ctrl+U  clear line", "Esc  back")
        Mode.Title -> Hints("Enter  save", *(if (usedPictures.isNotEmpty()) arrayOf("Tab  copy pictures on/off") else emptyArray()), "Esc  back")
        Mode.AppendPick -> Hints("↑↓  move", "Enter  add here", "/  filter", "Esc  back")
        Mode.ConfirmReplace -> Hints("y  replace", "any other key  keep the note")
        Mode.ConfirmDiscard -> Hints("y  discard draft", "any other key  keep it")
        Mode.Result -> if (output.selecting) {
            Hints("↑↓  move", "Space  tick", "c  copy", "b  code only", "Esc  leave")
        } else if (running) {
            Hints("Esc  cancel")
        } else {
            Hints("f  refine", "m  add more", "u  undo", "n  new note", "a  add to note", "r  redo", "s  select", "Esc  start over")
        }
    }

    KeyHandler { key ->
        if (attachments.browsing) return@KeyHandler attachments.handle(key)
        if (key == Key.Browse && (mode == Mode.Input || mode == Mode.Follow)) {
            openAttach()
            return@KeyHandler true
        }
        when (mode) {
            Mode.Start -> when {
                taskPicker.handle(key, tasks) { it.label } -> true
                key == Key.Escape && presetNote != null -> { presetNote = null; taskPicker.reset(); true }
                key == Key.Enter -> {
                    taskPicker.selected(tasks) { it.label }?.let {
                        task = it
                        input.clear()
                        source = presetNote
                        typing = true
                        notice = null
                        notes = vault.notes()
                        notePicker.reset()
                        attachments.clear()
                        usedPictures = emptyList()
                        mode = if (it.needsNote && presetNote == null) Mode.PickSource else Mode.Input
                        // a note handed over by the Library skips choosing one, and runs at once when the task needs no more input
                        if (presetNote != null && it.inputLabel == null) generate()
                        // a note made from pictures starts with choosing them
                        if (it.needsPictures) openAttach()
                    }
                    true
                }
                else -> false
            }
            Mode.PickSource -> when {
                notePicker.handle(key, notes) { it.path } -> true
                key == Key.Enter -> {
                    notePicker.selected(notes) { it.path }?.let {
                        source = it
                        if (task.inputLabel != null) { typing = true; mode = Mode.Input } else generate()
                    }
                    true
                }
                key == Key.Escape -> { mode = Mode.Start; true }
                else -> false
            }
            Mode.Input ->
                if (typing) {
                    when {
                        key == Key.Escape -> { typing = false; true }
                        key == Key.Enter -> { generate(); true }
                        // Backspace on an empty prompt takes the last attached picture back.
                        key == Key.Backspace && input.text.isEmpty() && attachments.removeLast() -> true
                        else -> input.onKey(key)
                    }
                } else when {
                    key == Key.Enter -> { typing = true; true }
                    key.char == 'i' -> { openAttach(); true }
                    key == Key.Escape -> { mode = if (task.needsNote) Mode.PickSource else Mode.Start; true }
                    else -> false
                }
            Mode.Follow -> when {
                key == Key.Escape -> { mode = Mode.Result; true }
                key == Key.Enter -> {
                    val instruction = followUp.text.trim()
                    if (instruction.isNotEmpty()) {
                        followUp.clear()
                        runFollow(followKind, instruction)
                    }
                    true
                }
                key == Key.Backspace && followUp.text.isEmpty() && attachments.removeLast() -> true
                else -> followUp.onKey(key)
            }
            Mode.Title -> when {
                key == Key.Tab -> { if (usedPictures.isNotEmpty()) copyPictures = !copyPictures; true }
                key == Key.Escape -> { mode = Mode.Result; true }
                key == Key.Enter -> { saveNew(); true }
                else -> title.onKey(key)
            }
            Mode.AppendPick -> when {
                notePicker.handle(key, notes) { it.path } -> true
                key == Key.Enter -> { notePicker.selected(notes) { it.path }?.let { appendTo(it) }; true }
                key == Key.Escape -> { mode = Mode.Result; true }
                else -> false
            }
            Mode.ConfirmReplace -> {
                if (key.char == 'y' || key.char == 'Y') replaceSource() else mode = Mode.Result
                true
            }
            Mode.ConfirmDiscard -> {
                if (key.char == 'y' || key.char == 'Y') {
                    val target = discardTarget
                    discardTarget = null
                    applied = true
                    if (target != null) navigator.navigate(target, force = true) else resetToStart()
                } else {
                    discardTarget = null
                    mode = Mode.Result
                }
                true
            }
            Mode.Result -> if (output.handle(key, entries, onNotice = { notice = it })) {
                true
            } else {
                when {
                    key == Key.Escape -> {
                        if (running) { gen.cancel(); true }
                        else if (needsConfirm) { discardTarget = null; mode = Mode.ConfirmDiscard; true }
                        else { resetToStart(); true }
                    }
                    key.char == 'r' && !running -> { redo(); true }
                    key.char == 'f' && canUse -> { followKind = FollowKind.Refine; mode = Mode.Follow; true }
                    key.char == 'm' && canUse -> { followKind = FollowKind.More; mode = Mode.Follow; true }
                    key.char == 'u' -> { undo(); true }
                    key.char == 'n' && canUse -> { title.set(defaultTitle()); mode = Mode.Title; true }
                    key.char == 'a' && canUse -> {
                        if (source != null) {
                            appendTo(source!!)
                        } else {
                            pickingTarget = true
                            notePicker.reset()
                            mode = Mode.AppendPick
                        }
                        true
                    }
                    key.char == 'o' && canUse && source != null -> { pickingTarget = true; notePicker.reset(); mode = Mode.AppendPick; true }
                    key.char == 'w' && canUse && source != null && task.replaceable -> { mode = Mode.ConfirmReplace; true }
                    else -> false
                }
            }
        }
    }

    Stack {
        if (attachments.browsing) {
            AttachBrowser(attachments, "the next prompt")        } else when (mode) {
            Mode.Start -> {
                ScreenHeader("AI notes", presetNote?.let { "What should AI do with ${it.path}?" } ?: "Pick what AI should do. It can start from a prompt or from a note you already have")
                VGap()
                val startHint = "Every result is a draft: refine it with follow-up prompts, then replace the note, add to it, or save a new one."
                // the hint wraps on a narrow window: the list leaves it exactly the rows it needs
                val hintRows = wrap(startHint, (panelWidth() - 2).coerceAtLeast(10)).size
                Panel(presetNote?.let { it.title } ?: "What should AI do?") {
                    Picker(
                        tasks, taskPicker,
                        label = { it.label },
                        rows = bodyRows(extra = 4 + hintRows),
                        icon = { it.icon },
                        iconColor = { Palette.Accent },
                        meta = { it.detail },
                        placeholder = "Press / to filter",
                    )
                    VGap()
                    Hint(startHint)
                }
            }
            Mode.PickSource -> {
                ScreenHeader("AI notes › ${task.label}", "Which note?")
                VGap()
                Panel("Notes") {
                    Picker(notes, notePicker, label = { it.path }, rows = bodyRows(extra = 4), icon = { "▤" }, iconColor = { Palette.Accent }, meta = { relativeTime(it.modified) }, emptyText = "The vault has no Markdown notes yet.")
                }
            }
            Mode.Input -> {
                ScreenHeader(
                    "AI notes › ${task.label}",
                    source?.path ?: if (task == Task.Pictures) "Pick the pictures with Ctrl+O; telling the model what to write is optional" else "Describe the note to write",
                )
                VGap()
                Panel(task.inputLabel ?: "Prompt") {
                    val placeholder = when (task) {
                        Task.Write -> "e.g. A checklist for a weekly review"
                        Task.Pictures -> "e.g. Turn this whiteboard into a task list (optional)"
                        Task.Translate -> "e.g. Ukrainian"
                        else -> "e.g. Turn this into a short email"
                    }
                    TextInput(task.inputLabel ?: "Prompt", input, typing, placeholder, maxRows = 4, attachments = attachments)
                    VGap()
                    Hint(
                        if (task == Task.Write || task == Task.Pictures) {
                            if (task == Task.Pictures) {
                                "Gemma looks at the pictures (a whiteboard photo, a screenshot, a scan…) and writes a note about them. Nothing is saved until you apply the result."
                            } else {
                                "The result is plain Markdown. Press Ctrl+O to attach pictures, PDFs or text files; refine the draft with follow-up prompts before saving."
                            }
                        } else {
                            "Gemma reads “${source?.title}” and does this to it. Nothing in the vault changes until you apply the result."
                        },
                    )
                }
            }
            Mode.Result -> {
                val follows = (drafts.size - 1).coerceAtLeast(0)
                val actions = buildList {
                    add(ActionItem("f", "Refine", canUse))
                    add(ActionItem("m", "Add more", canUse))
                    add(ActionItem("u", "Undo", drafts.size > 1 && !running))
                    if (source != null && task.replaceable) add(ActionItem("w", "Replace note", canUse, Palette.Orange))
                    if (source != null) {
                        add(ActionItem("a", "Add to note", canUse))
                        add(ActionItem("o", "Add to other note", canUse))
                    } else {
                        add(ActionItem("a", "Add to note", canUse))
                    }
                    add(ActionItem("n", "Save as new note", canUse))
                    add(ActionItem("r", "Redo", !running))
                    add(ActionItem("s", if (output.selecting) "Leave select" else "Select & copy", entries.isNotEmpty()))
                    add(ActionItem("Esc", if (running) "Cancel" else "Start over", accent = Palette.Cta))
                }
                ScreenHeader("AI notes", subject() + if (follows > 0) "   +$follows follow-up${if (follows == 1) "" else "s"}" else "")
                VGap()
                Panel((if (drafts.isEmpty()) "Draft" else "Draft · version ${drafts.size}") + if (applied) " · written to vault" else "") {
                    GenerationView(app, gen, output, outputRows(fixed = 5, notice = notice, actions = actions), text = shown)
                    NoticeLine(notice)
                }
                ActionBar(actions)
            }
            Mode.Follow -> {
                ScreenHeader("AI notes › ${followKind.verb}", subject())
                VGap()
                Panel(if (followKind == FollowKind.Refine) "Refine the draft" else "Add a section") {
                    TextInput(if (followKind == FollowKind.Refine) "What should change?" else "What should the new section cover?", followUp, true, "e.g. make it shorter, add a code example, translate to Ukrainian", maxRows = 4, attachments = attachments)
                    VGap()
                    Hint(
                        if (followKind == FollowKind.Refine) {
                            "Gemma sees the whole conversation and the current draft, and returns the whole note rewritten."
                        } else {
                            "Gemma writes only the new section; it is added after the current draft."
                        },
                    )
                }
            }
            Mode.Title -> {
                ScreenHeader("AI notes", "Save as a new note in “${app.config.outputFolder.ifEmpty { "the vault root" }}”")
                VGap()
                Panel("Title") {
                    TextInput("Title", title, true)
                    if (usedPictures.isNotEmpty()) {
                        VGap()
                        Label(
                            "${if (copyPictures) "☑" else "☐"}  Copy ${usedPictures.size} picture${if (usedPictures.size == 1) "" else "s"} into the vault and embed ${if (usedPictures.size == 1) "it" else "them"}  (Tab)",
                            color = if (copyPictures) Palette.Green else Palette.Muted,
                        )
                    }
                }
            }
            Mode.AppendPick -> {
                ScreenHeader("AI notes", "Add the draft to which note?")
                VGap()
                Panel("Notes") {
                    Picker(notes, notePicker, label = { it.path }, rows = bodyRows(extra = 4), icon = { "▤" }, iconColor = { Palette.Accent }, meta = { relativeTime(it.modified) })
                }
            }
            Mode.ConfirmReplace -> {
                ScreenHeader("AI notes", "Replace “${source?.path}”")
                VGap()
                Panel("Replace this note with the draft?", accent = Palette.Orange) {
                    Warning("The text of “${source?.title}” is replaced by the draft. Its frontmatter is kept, and the old text is saved first in the vault's .trash folder, so nothing is lost.")
                }
                ActionBar(listOf(ActionItem("y", "Replace note", accent = Palette.Orange), ActionItem("any other key", "Keep the note", accent = Palette.Cta)), style = ActionStyle.Button)
            }
            Mode.ConfirmDiscard -> {
                ScreenHeader("AI notes", subject())
                VGap()
                Panel("Discard this draft?", accent = Palette.Warn) {
                    Warning("The draft (${drafts.size} version${if (drafts.size == 1) "" else "s"}) has not been written to your vault. Save it, add it to a note or replace one first, or discard it.")
                }
                ActionBar(listOf(ActionItem("y", "Discard draft", accent = Palette.Red), ActionItem("any other key", "Keep working", accent = Palette.Cta)), style = ActionStyle.Button)
            }
        }
    }
}
