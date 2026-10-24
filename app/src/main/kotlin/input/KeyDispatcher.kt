package com.arcana.brain.app.input

/**
 * Bridge from the single root key listener to whichever screen is currently on display.
 *
 * It also repairs pastes. A paste reaches the app as a burst of ordinary key presses, and Mosaic keeps only the last 64
 * events of a burst (it drops the oldest), so a pasted link or prompt longer than that would lose its beginning. When a
 * burst starts, the system clipboard is read: if it contains what just arrived, the whole clipboard text is typed in
 * order and the partial events that keep arriving are swallowed.
 */
class KeyDispatcher(
    /** Shortcuts that work on every screen once the screen itself has declined a key. */
    private val globalHandler: (Key) -> Boolean,
    /** An open overlay (the key map) sees every key before the screen does. */
    private val overlay: (Key) -> Boolean = { false },
    private val clipboard: ClipboardReader = ClipboardReader { null },
) {
    @Volatile
    var screenHandler: ((Key) -> Boolean)? = null

    private var lastKeyAt = 0L
    private val burstKeys = ArrayList<Char>()

    /** Every key typed since the keys last paused for [CHAIN_GAP_NANOS]: what a restored paste has to take back, even when the paste arrived in several bursts. */
    private val chain = ArrayList<Char>()
    private var repairTried = false

    /** The pasted text being typed in (as tokens) and how far the real events have got through it. */
    private var pasted: List<Char>? = null
    private var pastedAt = 0

    private fun token(key: Key): Char? = when (key) {
        is Key.Text -> key.value
        Key.Enter, Key.PastedNewline -> NEWLINE
        else -> null
    }

    private fun keyFor(token: Char): Key = if (token == NEWLINE) Key.PastedNewline else Key.Text(token)

    /** The clipboard as one token per key event a terminal would have sent for it. */
    private fun clipboardTokens(): List<Char> {
        val text = runCatching { clipboard.read() }.getOrNull().orEmpty()
        val out = ArrayList<Char>(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\r' && text.getOrNull(i + 1) == '\n' -> { out += NEWLINE; i++ }
                c == '\r' || c == '\n' -> out += NEWLINE
                c == '\t' -> out += ' '
                c.code >= 0x20 && c.code != 0x7f && !Character.isSurrogate(c) -> out += c
            }
            i++
        }
        return out
    }

    /**
     * Pasted text is a stream of ordinary key presses, newlines and tabs included. Nobody types two keys within a few
     * milliseconds, so an Enter right behind another key is a pasted line break (a prompt keeps it), and a Tab is a
     * pasted indent (a space); neither may press a button or move between fields.
     */
    private fun fromPaste(key: Key, burst: Boolean): Key = when {
        burst && key == Key.Enter -> Key.PastedNewline
        burst && key == Key.Tab -> Key.Text(' ')
        else -> key
    }

    /** Order: the screen first (so text fields get their keys), then the global shortcuts. */
    private val meta = MetaSequence()

    fun dispatch(typed: Key): Boolean {
        val now = System.nanoTime()
        when (val seq = meta.feed(typed, now)) {
            MetaSequence.Result.Swallow -> return true
            is MetaSequence.Result.Emit -> return deliver(seq.key)
            MetaSequence.Result.Pass -> Unit
        }
        val gap = now - lastKeyAt
        val burst = gap < PASTE_GAP_NANOS
        lastKeyAt = now
        if (gap > PASTE_SESSION_NANOS) pasted = null
        if (gap > CHAIN_GAP_NANOS) chain.clear()
        if (!burst) { burstKeys.clear(); repairTried = false }

        // Inside a repaired paste: the text is already typed in, so events that continue it are swallowed.
        pasted?.let { text ->
            val t = token(fromPaste(typed, burst = true))
            val at = if (t == null) -1 else (pastedAt until text.size).firstOrNull { text[it] == t } ?: -1
            if (at >= 0) {
                pastedAt = at + 1
                return true
            }
            pasted = null // something else arrived: the paste is over
        }

        val key = fromPaste(typed, burst)
        token(key)?.let { t ->
            if (burstKeys.size < REPAIR_WINDOW) burstKeys += t
            chain += t
            if (chain.size > CHAIN_MAX) chain.removeAt(0)
        }
        // Tried once per burst (reading the clipboard is slow), when a few keys more than the match needs have arrived, so a
        // stray key or two at the start of the paste does not stop the keys after it from being recognised.
        if (burst && burstKeys.size == REPAIR_WINDOW && !repairTried && pasted == null) {
            repairTried = true
            if (repairPaste()) return true
        }
        return deliver(key)
    }

    /**
     * Called once a burst has delivered [REPAIR_WINDOW] keys. Mosaic keeps the LAST 64 events of a burst, so if the
     * clipboard holds a long text whose end matches those keys exactly, the beginning was lost: take back what was
     * typed, type the whole clipboard in order, and swallow the events that keep arriving.
     */
    private fun repairPaste(): Boolean {
        val text = clipboardTokens()
        if (text.size <= LOST_AFTER) return false // short enough to have arrived whole
        val first = (text.size - 64).coerceAtLeast(0)
        // The first keys of the burst may be strays (the head of the paste, which arrived on its own): skip up to a few.
        for (skip in 0..REPAIR_WINDOW - REPAIR_AFTER) {
            val probe = burstKeys.subList(skip, skip + REPAIR_AFTER)
            val start = (first..text.size - REPAIR_AFTER).firstOrNull { text.subList(it, it + REPAIR_AFTER) == probe } ?: continue
            repeat(chain.size - 1) { deliver(Key.Backspace) } // everything of this paste typed so far; the current key is not delivered yet
            chain.clear()
            text.forEach { deliver(keyFor(it)) }
            pasted = text
            pastedAt = start + (burstKeys.size - skip)
            return true
        }
        return false
    }

    private fun deliver(key: Key): Boolean {
        val latin = (key as? Key.Text)?.value?.let(KeyboardLayout::latinFor)?.let(Key::Text)
        // The screen gets the Cyrillic letter first (text fields want it); shortcuts are retried with the Latin one.
        return dispatchOnce(key) || (latin != null && dispatchOnce(latin))
    }

    private companion object {
        const val PASTE_GAP_NANOS = 20_000_000L // 20 ms: a pause this short between two keys is a paste, not a person
        const val CHAIN_GAP_NANOS = 100_000_000L // keys closer than this belong to the same paste when it has to be taken back
        const val PASTE_SESSION_NANOS = 250_000_000L // a paste is over once keys stop for this long
        const val LOST_AFTER = 60 // Mosaic's queue holds 64 events; longer pastes may have lost their beginning
        const val REPAIR_AFTER = 8 // how many keys of a burst must match the clipboard's end before it is trusted
        const val REPAIR_WINDOW = 11 // keys of a burst collected before trying, so up to 3 strays at its start can be skipped
        const val CHAIN_MAX = 256
        const val NEWLINE = '\u0001'
    }

    private fun dispatchOnce(key: Key): Boolean = dispatchToHandlers(key) || (key is Key.Select && dispatchToHandlers(key.move))

    /** Shift+arrow only selects text; on a screen that has no text field to select in, it is the plain arrow again. */
    private fun dispatchToHandlers(key: Key): Boolean =
        overlay(key) || screenHandler?.invoke(key) == true || globalHandler(key)
}
