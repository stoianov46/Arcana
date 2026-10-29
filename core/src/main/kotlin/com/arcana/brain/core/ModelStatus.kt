package com.arcana.brain.core

/** What the machine's memory is doing, for the "waiting for the model" line: macOS reports swap through `sysctl`; elsewhere (or on any failure) it is simply unknown. */
object SystemMemory {
    /** How full the swap file is, in percent, or null when unknown or when there is no swap in use. */
    fun swapPercent(): Int? = runCatching {
        val process = ProcessBuilder("sysctl", "-n", "vm.swapusage").redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().readText()
        process.waitFor()
        parseSwap(text)
    }.getOrNull()

    /** `total = 12288.00M  used = 10872.44M  free = 1415.56M  (encrypted)` -> 88. */
    fun parseSwap(text: String): Int? {
        fun mb(key: String) = Regex("$key = ([0-9.]+)M").find(text)?.groupValues?.get(1)?.toDoubleOrNull()
        val total = mb("total") ?: return null
        val used = mb("used") ?: return null
        return if (total <= 0) null else (used * 100 / total).toInt()
    }
}

/**
 * One sentence on why a request has not produced a word yet, from what Ollama and the machine say (the *Waiting for the model…* line): the model is not loaded yet (and what
 * else is, which Ollama has to make room for), or it is loaded and reading the prompt (with how much of it runs on the CPU, which is slow), and whether the memory is tight.
 * Ollama does not report the progress of reading a prompt, so the size of the prompt is an estimate and no time is promised.
 */
object ModelStatus {
    /** A swap file this full means the machine is short of memory. */
    const val TIGHT_SWAP = 80

    fun describe(model: String, loaded: List<LoadedModel>?, promptTokens: Int, swapPercent: Int?): String {
        val parts = ArrayList<String>()
        if (loaded == null) parts += "Ollama does not answer the status question"
        else {
            val mine = loaded.firstOrNull { sameModel(it.name, model) }
            if (mine == null) {
                val others = loaded.map { it.name }
                parts += "loading $model into memory" + if (others.isEmpty()) "" else " (${others.joinToString(", ")} ${if (others.size == 1) "is" else "are"} loaded and may be unloaded first)"
            } else {
                parts += if (promptTokens > 0) "loaded, reading about ${UsageStats.compact(promptTokens.toLong())} tokens of conversation" else "loaded, reading the prompt"
                if (mine.cpuPercent >= 10) parts += "${mine.cpuPercent}% of it runs on the CPU, so slowly"
                if (loaded.size > 1) parts += "${loaded.size - 1} other model${if (loaded.size == 2) "" else "s"} also in memory"
            }
        }
        if (swapPercent != null && swapPercent >= TIGHT_SWAP) parts += "memory is tight (swap $swapPercent% full)"
        return parts.joinToString("; ")
    }

    /** Ollama lists a model as `name:tag`; a name typed without `:latest` is the same model. */
    fun sameModel(loadedName: String, model: String) = loadedName == model || loadedName == "$model:latest" || loadedName == model.removeSuffix(":latest")
}
