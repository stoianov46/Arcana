package com.arcana.brain.app.design

/**
 * Makes arbitrary text (job logs, prompts, names from the server) safe to draw in a Mosaic cell grid.
 *
 * Mosaic puts one code point in one cell, while a terminal gives emoji and CJK characters two cells and acts on control
 * sequences. Left raw, such text shifts rows, wraps lines and leaves stale lines behind (and escape codes in tool output
 * could even reconfigure the terminal). So we:
 *  - remove ANSI escape sequences and control characters (tabs become spaces),
 *  - drop zero-width characters (combining marks, joiners, variation selectors),
 *  - replace two-cell characters with a one-cell lookalike (a few common emoji) or `▪`.
 */
fun sanitizeForTerminal(text: String): String {
    if (text.isEmpty()) return text
    val noAnsi = if ('\u001B' in text) ANSI.replace(text, "") else text
    val out = StringBuilder(noAnsi.length)
    var i = 0
    while (i < noAnsi.length) {
        val cp = noAnsi.codePointAt(i)
        i += Character.charCount(cp)
        when {
            cp == '\n'.code -> out.append('\n')
            cp == '\t'.code -> out.append("    ")
            cp < 0x20 || cp in 0x7F..0x9F -> Unit
            isZeroWidth(cp) -> Unit
            isWide(cp) -> out.append(lookalike(cp))
            else -> out.appendCodePoint(cp)
        }
    }
    return out.toString()
}

private val ANSI = Regex("\u001B(?:\\[[0-?]*[ -/]*[@-~]|\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)|[@-Z\\\\-_])")

private fun isZeroWidth(cp: Int) =
    cp in 0x0300..0x036F || cp in 0x1AB0..0x1AFF || cp in 0x20D0..0x20FF || cp in 0x200B..0x200F ||
        cp in 0x2028..0x202E || cp in 0x2060..0x206F || cp in 0xFE00..0xFE0F || cp == 0xFEFF

private val wideBmpSymbols = setOf(
    0x231A, 0x231B, 0x23E9, 0x23EA, 0x23EB, 0x23EC, 0x23F0, 0x23F3, 0x25FD, 0x25FE, 0x2614, 0x2615, 0x267F, 0x2693, 0x26A1,
    0x26AA, 0x26AB, 0x26BD, 0x26BE, 0x26C4, 0x26C5, 0x26CE, 0x26D4, 0x26EA, 0x26F2, 0x26F3, 0x26F5, 0x26FA, 0x26FD, 0x2705,
    0x270A, 0x270B, 0x2728, 0x274C, 0x274E, 0x2753, 0x2754, 0x2755, 0x2757, 0x2795, 0x2796, 0x2797, 0x27B0, 0x27BF, 0x2B1B,
    0x2B1C, 0x2B50, 0x2B55,
)

private fun isWide(cp: Int) =
    cp in 0x1100..0x115F || cp in 0x2E80..0x303E || cp in 0x3041..0x33FF || cp in 0x3400..0x4DBF || cp in 0x4E00..0x9FFF ||
        cp in 0xA000..0xA4CF || cp in 0xAC00..0xD7A3 || cp in 0xF900..0xFAFF || cp in 0xFE30..0xFE6F || cp in 0xFF00..0xFF60 ||
        cp in 0xFFE0..0xFFE6 || cp in 0x2648..0x2653 || cp >= 0x1F000 || cp in wideBmpSymbols

private fun lookalike(cp: Int): Char = when (cp) {
    0x2705 -> '✓'
    0x274C, 0x274E -> '✗'
    0x26A1 -> 'ϟ'
    0x1F680 -> '»'
    0x2B50 -> '*'
    else -> '▪'
}
