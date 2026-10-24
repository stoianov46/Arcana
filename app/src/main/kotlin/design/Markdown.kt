package com.arcana.brain.app.design

import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.TextStyle

private val Heading = Regex("""^(#{1,6})\s+(.*)$""")
private val Bullet = Regex("""^(\s*)[-*+]\s+(.*)$""")
private val Numbered = Regex("""^(\s*)(\d+)[.)]\s+(.*)$""")
private val Emphasis = Regex("""(\*\*|__|`)""")

/** Strips inline Markdown markers: Label draws one style per line, so bold and code can only be dropped, not shown. */
private fun plain(text: String) = text.replace(Emphasis, "")

enum class BlockKind { Heading, Paragraph, Item, Code, Quote }

/** One block of a Markdown text: the source it came from (what select mode copies) and what kind of block it is. */
data class MdBlock(val raw: String, val kind: BlockKind)

private fun isFence(line: String) = line.trimStart().startsWith("```")

private fun startsBlock(line: String) =
    line.isBlank() || isFence(line) || Heading.matches(line) || Bullet.matches(line) || Numbered.matches(line) || line.startsWith("> ")

/** Splits [text] into headings, paragraphs, list items (with their continuation lines), code blocks and quotes. */
fun parseMarkdown(text: String): List<MdBlock> {
    val lines = text.replace("\t", "    ").lines().map { it.trimEnd() }
    val out = ArrayList<MdBlock>()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        when {
            line.isBlank() -> i++
            isFence(line) -> {
                var j = i + 1
                while (j < lines.size && !isFence(lines[j])) j++
                out += MdBlock(lines.subList(i, minOf(j + 1, lines.size)).joinToString("\n"), BlockKind.Code)
                i = j + 1
            }
            Heading.matches(line) -> {
                out += MdBlock(line, BlockKind.Heading)
                i++
            }
            Bullet.matches(line) || Numbered.matches(line) -> {
                var j = i + 1
                while (j < lines.size && lines[j].isNotBlank() && lines[j].startsWith(" ") && !Bullet.matches(lines[j]) && !Numbered.matches(lines[j])) j++
                out += MdBlock(lines.subList(i, j).joinToString("\n"), BlockKind.Item)
                i = j
            }
            line.startsWith("> ") -> {
                var j = i
                while (j < lines.size && lines[j].startsWith("> ")) j++
                out += MdBlock(lines.subList(i, j).joinToString("\n"), BlockKind.Quote)
                i = j
            }
            else -> {
                var j = i + 1
                while (j < lines.size && !startsBlock(lines[j])) j++
                out += MdBlock(lines.subList(i, j).joinToString("\n"), BlockKind.Paragraph)
                i = j
            }
        }
    }
    return out
}

/** A stretch of text drawn in one style. */
class Run(
    val text: String,
    val color: Color,
    val background: Color = Color.Unspecified,
    val style: TextStyle = TextStyle.Unspecified,
)

/** One drawn row: styled runs, optionally on a full-width [fill] background (code blocks). */
class MdLine(val runs: List<Run>, val fill: Color = Color.Unspecified)

private data class Look(val color: Color, val style: TextStyle = TextStyle.Unspecified)

private val MD_HEADING = Regex("""^(#{1,6})\s+(.*)$""")
private val MD_RULE = Regex("""^\s*([-*_])(\s*\1){2,}\s*$""")
private val MD_BULLET = Regex("""^(\s*)[-*+]\s+(.*)$""")
private val MD_NUMBERED = Regex("""^(\s*)(\d+[.)])\s+(.*)$""")
private val MD_QUOTE = Regex("""^\s*>\s?(.*)$""")
private val MD_TABLE_ROW = Regex("""^\s*\|.*\|\s*$""")
private val MD_TABLE_SEPARATOR = Regex("""^\s*\|?[\s:|-]*-[\s:|-]*\|?\s*$""")
private val MD_LINK = Regex("""^\[([^\]\n]+)]\(([^)\s]+)\)""")

/**
 * Renders the Markdown that Claude writes (headings, lists, quotes, tables, **bold**, *italic*, `code`, fenced code
 * blocks, links) as styled rows no wider than [width], so the formatting characters are not shown as raw text.
 * [base] is the colour of ordinary text.
 */
internal fun renderStyled(text: String, width: Int, base: Color): List<MdLine> {
    val w = width.coerceAtLeast(8)
    val out = ArrayList<MdLine>()
    val lines = text.split('\n').map { it.trimEnd('\r') }
    var fence: String? = null
    var code = ArrayList<String>()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trimStart()
        if (trimmed.startsWith("```")) {
            if (fence == null) {
                fence = trimmed.removePrefix("```").trim()
                code = ArrayList()
            } else {
                out += renderCode(fence, code, w)
                fence = null
            }
            i++
            continue
        }
        if (fence != null) {
            code += line
            i++
            continue
        }
        if (startsTable(lines, i)) {
            var j = i
            while (j < lines.size && lines[j].isNotBlank() && '|' in lines[j]) j++
            out += renderTable(lines.subList(i, j), w, base)
            i = j
            continue
        }
        out += renderBlock(line, w, base)
        i++
    }
    if (fence != null) out += renderCode(fence, code, w) // unterminated fence
    return out
}

private fun startsTable(lines: List<String>, i: Int): Boolean {
    val line = lines[i]
    if ('|' !in line) return false
    val next = lines.getOrNull(i + 1)
    // a header row is always followed by the separator row; a bordered row alone is still a table
    return (next != null && '|' in next && MD_TABLE_SEPARATOR.matches(next) && !MD_RULE.matches(next)) || MD_TABLE_ROW.matches(line)
}

private fun cellsOf(line: String) = line.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }

private enum class Align { Left, Right, Center }

private fun runsWidth(runs: List<Run>) = runs.sumOf { it.text.length }

/** A table drawn as a box: aligned columns, a lit header, numbers right-aligned and coloured, shrunk to [w]. */
private fun renderTable(rows: List<String>, w: Int, base: Color): List<MdLine> {
    val sepAt = rows.indexOfFirst { MD_TABLE_SEPARATOR.matches(it) && !MD_RULE.matches(it) }.takeIf { it in 1..1 }
    val header = if (sepAt != null) cellsOf(rows[0]) else null
    val body = rows.filterIndexed { idx, _ -> idx != sepAt && !(sepAt == null && MD_TABLE_SEPARATOR.matches(rows[idx])) && !(idx == 0 && sepAt != null) }.map(::cellsOf)
    val all = listOfNotNull(header) + body
    val cols = all.maxOf { it.size }
    val aligns = List(cols) { c ->
        val spec = sepAt?.let { cellsOf(rows[it]).getOrNull(c) }.orEmpty()
        when {
            spec.startsWith(":") && spec.endsWith(":") -> Align.Center
            spec.endsWith(":") -> Align.Right
            spec.startsWith(":") -> Align.Left
            else -> {
                // numbers read best flush right
                val values = body.mapNotNull { it.getOrNull(c) }.filter { it.isNotBlank() }
                if (values.isNotEmpty() && values.all { Regex("""^[-+−(]?[$€£₴]?\d[\d,. ]*[%kKmMbB)]?$""").matches(it) }) Align.Right else Align.Left
            }
        }
    }
    fun parse(cell: String, head: Boolean) = parseInline(cell, if (head) Look(Palette.ConsoleUser, TextStyle.Bold) else Look(base))
    val parsed = all.mapIndexed { r, row -> List(cols) { c -> parse(row.getOrNull(c).orEmpty(), header != null && r == 0) } }
    val natural = IntArray(cols) { c -> parsed.maxOf { runsWidth(it[c]) }.coerceAtLeast(1) }
    val frame = 3 * cols + 1
    val room = (w - frame).coerceAtLeast(cols * 3)
    val widths = natural.copyOf()
    while (widths.sum() > room) {
        val widest = widths.indices.maxByOrNull { widths[it] }!!
        if (widths[widest] <= 3) break
        widths[widest]--
    }
    val dim = Palette.ConsoleDim
    fun rule(left: String, mid: String, right: String) =
        MdLine(listOf(Run(left + widths.joinToString(mid) { "─".repeat(it + 2) } + right, dim)))
    val out = ArrayList<MdLine>()
    out += rule("╭", "┬", "╮")
    parsed.forEachIndexed { r, row ->
        val isHead = header != null && r == 0
        val cells = row.mapIndexed { c, runs -> wrapRuns(runs, widths[c], emptyList(), 0).map { it.runs } }
        val height = cells.maxOf { it.size }
        for (k in 0 until height) {
            val runs = ArrayList<Run>()
            runs += Run("│", dim)
            for (c in 0 until cols) {
                val cell = cells[c].getOrNull(k).orEmpty()
                val gap = (widths[c] - runsWidth(cell)).coerceAtLeast(0)
                val (left, right) = when (aligns[c]) {
                    Align.Right -> gap to 0
                    Align.Center -> gap / 2 to gap - gap / 2
                    Align.Left -> 0 to gap
                }
                runs += Run(" ".repeat(left + 1), base)
                runs += cell
                runs += Run(" ".repeat(right + 1), base)
                runs += Run("│", dim)
            }
            // the header's tint is painted on the runs themselves, so it stops at the table's edge
            out += MdLine(if (isHead) runs.map { Run(it.text, it.color, Palette.ConsoleCodeBg, it.style) } else runs)
        }
        if (isHead) out += rule("├", "┼", "┤")
    }
    out += rule("╰", "┴", "╯")
    return out
}

private val CodeKeywords = setOf(
    "fun", "val", "var", "class", "object", "interface", "enum", "data", "sealed", "override", "private", "public", "protected", "internal",
    "if", "else", "when", "for", "while", "do", "return", "break", "continue", "try", "catch", "finally", "throw", "import", "package",
    "function", "const", "let", "def", "lambda", "from", "as", "in", "is", "not", "and", "or", "with", "async", "await", "yield",
    "new", "this", "self", "super", "static", "final", "void", "int", "string", "bool", "boolean", "true", "false", "null", "nil", "None",
    "True", "False", "select", "from", "where", "join", "group", "order", "by", "insert", "update", "delete", "create", "table", "echo",
    "export", "struct", "impl", "trait", "pub", "use", "mod", "match", "type", "extends", "implements", "suspend", "inline", "init",
)
private val CodeToken = Regex(
    """(?<comment>//.*|/\*.*?\*/|(?<![\w&$])#(?!\[).*|--\s.*)|(?<string>"(?:\\.|[^"\\])*"?|'(?:\\.|[^'\\])*'?|`[^`]*`?)""" +
        """|(?<number>\b\d+(?:\.\d+)?\b)|(?<word>[A-Za-z_][A-Za-z0-9_]*)""",
)

/** One line of code as coloured runs: comments, strings, numbers, keywords, calls, types and JSON keys. */
private fun highlightCode(line: String, lang: String): List<Run> {
    val plain = Palette.ConsoleCodeFg
    val bg = Palette.ConsoleCodeBg
    if (lang == "diff" || lang == "patch") {
        val color = when { line.startsWith("+") -> Palette.Green; line.startsWith("-") -> Palette.Red; line.startsWith("@@") -> Palette.Cta; else -> plain }
        return listOf(Run(line, color, bg))
    }
    val json = lang == "json" || lang == "jsonc"
    val out = ArrayList<Run>()
    var last = 0
    for (m in CodeToken.findAll(line)) {
        if (m.range.first > last) out += Run(line.substring(last, m.range.first), plain, bg)
        val text = m.value
        val run = when {
            m.groups["comment"] != null -> Run(text, Palette.ConsoleDim, bg, TextStyle.Italic)
            m.groups["string"] != null -> {
                val isKey = json && line.substring(m.range.last + 1).trimStart().startsWith(":")
                Run(text, if (isKey) Palette.Blue else Palette.Green, bg)
            }
            m.groups["number"] != null -> Run(text, Palette.Orange, bg)
            else -> {
                val after = line.getOrNull(m.range.last + 1)
                when {
                    !json && text in CodeKeywords -> Run(text, Palette.Purple, bg, TextStyle.Bold)
                    json && text in setOf("true", "false", "null") -> Run(text, Palette.Purple, bg, TextStyle.Bold)
                    after == '(' -> Run(text, Palette.Blue, bg)
                    text.first().isUpperCase() && text.any { it.isLowerCase() } -> Run(text, Palette.Cta, bg)
                    else -> Run(text, plain, bg)
                }
            }
        }
        out += run
        last = m.range.last + 1
    }
    if (last < line.length) out += Run(line.substring(last), plain, bg)
    return out
}

/** A fenced code block as an IDE editor pane: a title bar with the language, line numbers, highlighted code, a bottom edge. */
private fun renderCode(lang: String, lines: List<String>, w: Int): List<MdLine> {
    val bg = Palette.ConsoleCodeBg
    val language = lang.lowercase().ifEmpty { "text" }
    val out = ArrayList<MdLine>()
    val title = " $language "
    out += MdLine(listOf(Run("╭─", Palette.ConsoleDim, bg), Run(title, Palette.Cta, bg, TextStyle.Bold), Run("─".repeat((w - title.length - 2).coerceAtLeast(0)), Palette.ConsoleDim, bg)), bg)
    val digits = lines.size.toString().length.coerceAtLeast(2)
    val room = (w - digits - 4).coerceAtLeast(4)
    lines.forEachIndexed { n, raw ->
        val expanded = raw.replace("\t", "    ")
        val chunks = expanded.chunked(room).ifEmpty { listOf("") }
        chunks.forEachIndexed { k, chunk ->
            val gutter = if (k == 0) (n + 1).toString().padStart(digits) else " ".repeat(digits)
            out += MdLine(listOf(Run(" $gutter ", Palette.ConsoleDim, bg), Run("│ ", Palette.ConsoleDim, bg)) + highlightCode(chunk, language), bg)
        }
    }
    out += MdLine(listOf(Run("╰" + "─".repeat((w - 1).coerceAtLeast(0)), Palette.ConsoleDim, bg)), bg)
    return out
}

private fun renderBlock(line: String, w: Int, base: Color): List<MdLine> {
    if (line.isBlank()) return listOf(MdLine(emptyList()))
    MD_HEADING.matchEntire(line)?.let { m ->
        val level = m.groupValues[1].length
        val (mark, markColor, textColor) = when (level) {
            1 -> Triple("█ ", Palette.Accent, Palette.ConsoleUser)
            2 -> Triple("▌ ", Palette.Accent, Palette.ConsoleUser)
            3 -> Triple("▸ ", Palette.ConsoleTool, Palette.ConsoleText)
            else -> Triple("· ", Palette.ConsoleDim, Palette.ConsoleText)
        }
        val title = wrapRuns(parseInline(m.groupValues[2], Look(textColor, TextStyle.Bold)), w, listOf(Run(mark, markColor)), 2)
        // the first two levels sit on a rule, like the title of an editor pane
        return if (level <= 2) title + MdLine(listOf(Run((if (level == 1) "━" else "─").repeat(w), Palette.ConsoleDim))) else title
    }
    if (MD_RULE.matches(line)) {
        val side = ((w - 3) / 2).coerceAtLeast(1)
        return listOf(MdLine(listOf(Run("─".repeat(side) + " ◆ " + "─".repeat(side), Palette.ConsoleDim))))
    }
    MD_BULLET.matchEntire(line)?.let { m ->
        val depth = (m.groupValues[1].length / 2).coerceAtMost(3)
        val pad = " ".repeat(depth * 2)
        var body = m.groupValues[2]
        val task = Regex("""^\[([ xX])]\s+(.*)$""").matchEntire(body)
        val marker = if (task != null) {
            body = task.groupValues[2]
            if (task.groupValues[1] == " ") Run("$pad☐ ", Palette.ConsoleMuted) else Run("$pad☑ ", Palette.ConsoleOk)
        } else {
            Run("$pad${listOf("●", "◦", "▪", "▫")[depth]} ", Palette.ConsoleTool)
        }
        return wrapRuns(parseInline(body, Look(base)), w, listOf(marker), pad.length + 2)
    }
    MD_NUMBERED.matchEntire(line)?.let { m ->
        val pad = " ".repeat(m.groupValues[1].length.coerceAtMost(8))
        val marker = "$pad${m.groupValues[2]} "
        return wrapRuns(parseInline(m.groupValues[3], Look(base)), w, listOf(Run(marker, Palette.ConsoleTool, style = TextStyle.Bold)), marker.length)
    }
    MD_QUOTE.matchEntire(line)?.let { m ->
        return wrapRuns(parseInline(m.groupValues[1], Look(Palette.ConsoleMuted, TextStyle.Italic)), w, listOf(Run("▎ ", Palette.Accent)), 2)
    }
    return wrapRuns(parseInline(line, Look(base)), w, emptyList(), 0)
}

private val NumberToken = Regex("""(?<![\w./-])[(]?[-+−]?[$€£₴]?\d[\d,]*(?:\.\d+)?(?:%|[kKmMbB]\b|x\b)?[).,;:]*(?![\w/-])""")

/** Plain text with its figures lit up: money, percentages and long or decimal numbers; losses red, gains green. */
private fun highlightNumbers(text: String, look: Look): List<Run> {
    val out = ArrayList<Run>()
    var last = 0
    for (m in NumberToken.findAll(text)) {
        val core = m.value.trimEnd(')', '.', ',', ';', ':').trimStart('(')
        val digits = core.count { it.isDigit() }
        val notable = core.any { it in "$€£₴%" } || '.' in core || ',' in core || digits >= 3 || core.firstOrNull() in setOf('+', '-', '−')
        if (!notable) continue
        if (m.range.first > last) out += Run(text.substring(last, m.range.first), look.color, style = look.style)
        val color = when {
            core.startsWith("-") || core.startsWith("−") -> Palette.ConsoleErr
            core.startsWith("+") -> Palette.ConsoleOk
            else -> Palette.Cta
        }
        out += Run(m.value, color, style = look.style + TextStyle.Bold)
        last = m.range.last + 1
    }
    if (last < text.length) out += Run(text.substring(last), look.color, style = look.style)
    return out
}

/** Inline syntax: `code`, **bold**, *italic* / _italic_, ~~strike~~, [text](url). Spans nest (bold around code, ...). */
private fun parseInline(s: String, look: Look): List<Run> {
    val out = ArrayList<Run>()
    val plain = StringBuilder()
    fun flush() {
        if (plain.isNotEmpty()) out += highlightNumbers(plain.toString(), look)
        plain.clear()
    }
    var i = 0
    while (i < s.length) {
        val c = s[i]
        when {
            c == '`' -> {
                val end = s.indexOf('`', i + 1)
                if (end > i + 1) {
                    flush()
                    out += Run(" ${s.substring(i + 1, end)} ", Palette.ConsoleCodeFg, Palette.ConsoleCodeBg, look.style)
                    i = end + 1
                    continue
                }
            }
            s.startsWith("**", i) || s.startsWith("__", i) -> {
                val mark = s.substring(i, i + 2)
                val end = s.indexOf(mark, i + 2)
                if (end > i + 2) {
                    flush()
                    out += parseInline(s.substring(i + 2, end), look.copy(style = look.style + TextStyle.Bold))
                    i = end + 2
                    continue
                }
            }
            s.startsWith("~~", i) -> {
                val end = s.indexOf("~~", i + 2)
                if (end > i + 2) {
                    flush()
                    out += parseInline(s.substring(i + 2, end), look.copy(style = look.style + TextStyle.Strikethrough))
                    i = end + 2
                    continue
                }
            }
            (c == '*' || c == '_') && i + 1 < s.length && !s[i + 1].isWhitespace() && s[i + 1] != c -> {
                // `snake_case_names` and `2*3*4` are not emphasis: the opener must start a word and the closer end one.
                val opensWord = i == 0 || !s[i - 1].isLetterOrDigit()
                var end = s.indexOf(c, i + 1)
                while (end > 0 && (s[end - 1].isWhitespace() || (end + 1 < s.length && s[end + 1].isLetterOrDigit()))) end = s.indexOf(c, end + 1)
                if (opensWord && end > i + 1) {
                    flush()
                    out += parseInline(s.substring(i + 1, end), look.copy(style = look.style + TextStyle.Italic))
                    i = end + 1
                    continue
                }
            }
            c == '[' -> {
                val m = MD_LINK.find(s.substring(i))
                if (m != null) {
                    flush()
                    out += parseInline(m.groupValues[1], Look(Palette.ConsoleUser, look.style))
                    out += Run(" (${m.groupValues[2]})", Palette.ConsoleDim)
                    i += m.value.length
                    continue
                }
            }
        }
        plain.append(c)
        i++
    }
    flush()
    return out
}

/** Greedy word wrap of styled runs. Code chips (runs with a background) are never split across rows unless too long. */
internal fun wrapRuns(runs: List<Run>, width: Int, first: List<Run>, restIndent: Int): List<MdLine> {
    val firstLen = first.sumOf { it.text.length }
    val room = (width - maxOf(firstLen, restIndent)).coerceAtLeast(1)
    val lines = ArrayList<MdLine>()
    var cur = ArrayList<Run>()
    var col = 0

    fun endLine() {
        val lead = if (lines.isEmpty()) first else if (restIndent > 0) listOf(Run(" ".repeat(restIndent), Palette.ConsoleText)) else emptyList()
        lines += MdLine(lead + cur)
        cur = ArrayList()
        col = 0
    }

    fun place(text: String, run: Run) {
        cur += Run(text, run.color, run.background, run.style)
        col += text.length
    }

    for (run in runs) {
        val atomic = run.background != Color.Unspecified
        val pieces = if (atomic) listOf(run.text) else Regex("""\s+|\S+""").findAll(run.text).map { it.value }.toList()
        for (piece in pieces) {
            if (!atomic && piece.isBlank()) {
                if (col > 0) place(" ", run)
                continue
            }
            if (col > 0 && col + piece.length > room) {
                if (cur.lastOrNull()?.text == " ") cur.removeAt(cur.lastIndex)
                endLine()
            }
            if (piece.length > room) {
                piece.chunked(room).forEach { chunk ->
                    if (col > 0) endLine()
                    place(chunk, run)
                }
            } else {
                place(piece, run)
            }
        }
    }
    if (cur.isNotEmpty() || lines.isEmpty()) {
        if (cur.lastOrNull()?.text == " ") cur.removeAt(cur.lastIndex)
        endLine()
    }
    return lines
}


private val LatexSymbols = mapOf(
    "\\rightarrow" to "→", "\\to" to "→", "\\leftarrow" to "←", "\\Rightarrow" to "⇒", "\\Leftarrow" to "⇐",
    "\\leftrightarrow" to "↔", "\\Leftrightarrow" to "⇔", "\\uparrow" to "↑", "\\downarrow" to "↓", "\\times" to "×",
    "\\cdot" to "·", "\\approx" to "≈", "\\neq" to "≠", "\\ne" to "≠", "\\leq" to "≤", "\\le" to "≤", "\\geq" to "≥",
    "\\ge" to "≥", "\\pm" to "±", "\\infty" to "∞", "\\ldots" to "…", "\\dots" to "…", "\\in" to "∈", "\\sum" to "Σ",
    "\\alpha" to "α", "\\beta" to "β", "\\gamma" to "γ", "\\delta" to "δ", "\\lambda" to "λ", "\\mu" to "μ",
    "\\pi" to "π", "\\sigma" to "σ", "\\theta" to "θ", "\\omega" to "ω", "\\checkmark" to "✓",
)
private val InlineMath = Regex("""\$([^$\n]{1,80})\$""")
private val LatexCommand = Regex("""\\[A-Za-z]+""")

/**
 * Models write maths as LaTeX even in plain notes ("$\rightarrow$", "$N$"). A terminal cannot typeset that, so short inline
 * maths is turned into the symbols it stands for. Only spans that really look like maths are touched (a backslash command,
 * a subscript, or one short identifier), so prices like "$5 and $10" are left alone.
 */
fun unmath(text: String): String {
    if ('$' !in text && "\\(" !in text) return text
    val converted = InlineMath.replace(text) { m ->
        val inner = m.groupValues[1]
        val mathLike = '\\' in inner || '_' in inner || '^' in inner || Regex("""^[A-Za-z]\w{0,3}$""").matches(inner.trim())
        if (!mathLike || inner.startsWith(" ") || inner.endsWith(" ")) {
            m.value
        } else {
            LatexCommand.replace(inner) { c -> LatexSymbols[c.value] ?: c.value.removePrefix("\\") }
                .replace("{", "").replace("}", "").replace("_", "").replace("^", "")
        }
    }
    return converted.replace(Regex("""\\\((.+?)\\\)""")) { m ->
        LatexCommand.replace(m.groupValues[1]) { c -> LatexSymbols[c.value] ?: c.value.removePrefix("\\") }
    }
}

private fun lineOf(md: MdLine, entry: Int) = Line(
    text = md.runs.joinToString("") { it.text },
    entry = entry,
    runs = md.runs,
    fill = md.fill,
)

/**
 * Renders [blocks] for the console: headings, **bold**, *italic*, `code` chips, fenced code blocks on a tinted background,
 * lists, quotes, tables and links, all word-wrapped to [width]. Every line is tagged with the entry it belongs to
 * ([entry] maps a block's index to its entry number), so select mode can pick blocks. With [rich] off the text is shown
 * exactly as written; [plainGlyphs] swaps decorative characters for ASCII.
 */
fun renderMarkdown(blocks: List<MdBlock>, width: Int, entry: (Int) -> Int = { it }, rich: Boolean = true, plainGlyphs: Boolean = false): List<Line> {
    val out = ArrayList<Line>()
    blocks.forEachIndexed { index, block ->
        val e = entry(index)
        // Emoji and CJK take two terminal cells but one Mosaic cell, which shifts the rest of the row and breaks frames
        // and scrollbars; they are replaced by one-cell lookalikes before anything is measured or drawn.
        val text = sanitizeForTerminal(block.raw)
        if (!rich) {
            text.lines().forEach { l -> wrap(l, width).forEach { out += Line(it, Palette.ConsoleText, entry = e) } }
        } else {
            for (md in renderStyled(unmath(text), width, Palette.ConsoleText)) {
                out += lineOf(if (plainGlyphs) md.asciiOnly() else md, e)
            }
        }
        val next = blocks.getOrNull(index + 1)
        if (!(block.kind == BlockKind.Item && next?.kind == BlockKind.Item)) out += Line("", entry = e)
    }
    return out
}

private fun MdLine.asciiOnly() = MdLine(
    runs.map { r ->
        val text = r.text.replace(Regex("[●◦▪▫] "), "- ").replace("▎ ", "> ").replace("☐", "[ ]").replace("☑", "[x]").replace("◆", "*")
            .replace(Regex("[╭╮╰╯┬┴┼├┤]"), "+").replace("│", "|").replace(Regex("[─━]"), "-").replace(Regex("[█▌▸]"), "#")
        Run(text, r.color, r.background, r.style)
    },
    fill,
)

fun markdownLines(text: String, width: Int, rich: Boolean = true, plainGlyphs: Boolean = false): List<Line> =
    renderMarkdown(parseMarkdown(text), width, rich = rich, plainGlyphs = plainGlyphs)

/** [raw] Markdown with its markers removed, for "copy as plain text". */
fun plainText(raw: String): String = unmath(raw).lines().joinToString("\n") { l ->
    l.replace(Regex("(\\*\\*|__|`)"), "").replace(Regex("^#{1,6}\\s+"), "").replace(Regex("^(\\s*)[-*+]\\s+"), "$1• ")
}

/** What "copy code only" keeps of [text]: fenced code blocks and table rows, with their fences. Null when there are none. */
fun codeAndTables(text: String): String? {
    val parts = ArrayList<String>()
    val lines = text.lines()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        when {
            isFence(line) -> {
                var j = i + 1
                while (j < lines.size && !isFence(lines[j])) j++
                parts += lines.subList(i, minOf(j + 1, lines.size)).joinToString("\n")
                i = j + 1
            }
            line.trimStart().startsWith("|") -> {
                var j = i
                while (j < lines.size && lines[j].trimStart().startsWith("|")) j++
                parts += lines.subList(i, j).joinToString("\n")
                i = j
            }
            else -> i++
        }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
}
