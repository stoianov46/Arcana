package com.arcana.brain.core

/**
 * A line diff in the shape of `git diff`, for showing what a change would do before the user allows it. Lines of the result:
 * `@@ -a,b +c,d @@` for each hunk, `  text` for context, `- text` for a removed line, `+ text` for an added one.
 */
object Diff {
    /** Texts with more lines than this (after the shared start and end are cut off) are shown as one big replacement, not compared. */
    private const val MAX_COMPARED = 2_000

    /** The diff from [old] to [new] with [context] unchanged lines around each change; empty when the texts are the same. */
    fun unified(old: String, new: String, context: Int = 2): List<String> {
        if (old == new) return emptyList()
        val a = old.lines()
        val b = new.lines()
        val ops = operations(a, b)
        val changed = ops.indices.filter { ops[it].kind != ' ' }
        if (changed.isEmpty()) return emptyList()
        // hunks: runs of changes whose gaps are no longer than twice the context
        val out = ArrayList<String>()
        var i = 0
        while (i < changed.size) {
            var j = i
            while (j + 1 < changed.size && changed[j + 1] - changed[j] <= 2 * context + 1) j++
            val from = (changed[i] - context).coerceAtLeast(0)
            val to = (changed[j] + context).coerceAtMost(ops.size - 1)
            val slice = ops.subList(from, to + 1)
            val oldStart = slice.firstNotNullOfOrNull { it.oldLine } ?: 0
            val newStart = slice.firstNotNullOfOrNull { it.newLine } ?: 0
            out += "@@ -$oldStart,${slice.count { it.kind != '+' }} +$newStart,${slice.count { it.kind != '-' }} @@"
            slice.forEach { out += (if (it.kind == ' ') "  " else "${it.kind} ") + it.text }
            i = j + 1
        }
        return out
    }

    private class Op(val kind: Char, val text: String, val oldLine: Int?, val newLine: Int?)

    private fun operations(a: List<String>, b: List<String>): List<Op> {
        var start = 0
        while (start < a.size && start < b.size && a[start] == b[start]) start++
        var endA = a.size
        var endB = b.size
        while (endA > start && endB > start && a[endA - 1] == b[endB - 1]) { endA--; endB-- }
        val ops = ArrayList<Op>()
        for (k in 0 until start) ops += Op(' ', a[k], k + 1, k + 1)
        val x = a.subList(start, endA)
        val y = b.subList(start, endB)
        if (x.size > MAX_COMPARED || y.size > MAX_COMPARED) {
            x.forEachIndexed { k, line -> ops += Op('-', line, start + k + 1, null) }
            y.forEachIndexed { k, line -> ops += Op('+', line, null, start + k + 1) }
        } else {
            // longest common subsequence of the middle parts
            val lcs = Array(x.size + 1) { IntArray(y.size + 1) }
            for (p in x.indices.reversed()) for (q in y.indices.reversed()) {
                lcs[p][q] = if (x[p] == y[q]) lcs[p + 1][q + 1] + 1 else maxOf(lcs[p + 1][q], lcs[p][q + 1])
            }
            var p = 0
            var q = 0
            while (p < x.size || q < y.size) {
                when {
                    p < x.size && q < y.size && x[p] == y[q] -> { ops += Op(' ', x[p], start + p + 1, start + q + 1); p++; q++ }
                    // a removal before an addition, as git writes it
                    p < x.size && (q == y.size || lcs[p + 1][q] >= lcs[p][q + 1]) -> { ops += Op('-', x[p], start + p + 1, null); p++ }
                    else -> { ops += Op('+', y[q], null, start + q + 1); q++ }
                }
            }
        }
        for (k in endA until a.size) ops += Op(' ', a[k], k + 1, k + 1 + (b.size - a.size))
        return ops
    }
}
