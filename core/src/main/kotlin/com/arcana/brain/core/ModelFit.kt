package com.arcana.brain.core

/**
 * Whether an installed model fits this machine's memory, so the model pickers can warn before a choice makes Ollama run out of memory or fall
 * back to the CPU (very slow). A rule of thumb, not a measurement: a model needs about its size on disk plus a fifth for the context cache, and
 * only about 70% of the memory is safe to use (the system and Arcana need the rest; on a Mac the GPU may use about that much).
 */
object ModelFit {
    enum class Fit { Both, Alone, TooBig }

    /** The memory models may use out of [ramBytes]. */
    fun budget(ramBytes: Long): Long = ramBytes * 70 / 100

    /** What a model of [sizeBytes] occupies when loaded. */
    fun need(sizeBytes: Long): Long = sizeBytes * 12 / 10

    /**
     * [Fit.Both]: it fits and can stay loaded with the [workerBytes] model (no swapping between the two); [Fit.Alone]: it fits only when the
     * worker is unloaded (Ollama swaps them, several seconds each time); [Fit.TooBig]: it does not fit, so it would swap to disk or run on the CPU.
     */
    fun fit(sizeBytes: Long, workerBytes: Long, ramBytes: Long): Fit {
        val room = budget(ramBytes)
        return when {
            need(sizeBytes) > room -> Fit.TooBig
            need(sizeBytes) + need(workerBytes) <= room -> Fit.Both
            else -> Fit.Alone
        }
    }

    fun label(fit: Fit): String = when (fit) {
        Fit.Both -> "fits with the main model"
        Fit.Alone -> "fits alone (swaps with the main model)"
        Fit.TooBig -> "too big for this memory"
    }

    /** The models worth suggesting: not an embedding model, and (when [needTools]) able to call tools. */
    private fun candidates(models: List<ModelDetails>, worker: String, needTools: Boolean) = models.filter {
        it.name != worker && it.sizeBytes > 0 && (it.capabilities.isEmpty() || "completion" in it.capabilities) && (!needTools || it.capabilities.isEmpty() || "tools" in it.capabilities)
    }

    /**
     * What to suggest on a machine with [ramBytes]: the Oracul is the largest model that fits at all (it answers rarely, a swap is acceptable),
     * the judge the largest that stays loaded together with the worker (it reads two answers and should be quick). Null when nothing fits.
     */
    data class Suggestion(val oracul: String?, val judge: String?)

    fun suggest(models: List<ModelDetails>, worker: String, ramBytes: Long, oraculTools: Boolean = true): Suggestion {
        if (ramBytes <= 0) return Suggestion(null, null)
        val workerBytes = models.firstOrNull { it.name == worker }?.sizeBytes ?: 0L
        val oracul = candidates(models, worker, oraculTools).filter { fit(it.sizeBytes, workerBytes, ramBytes) != Fit.TooBig }.maxByOrNull { it.sizeBytes }
        val judge = candidates(models, worker, false).filter { fit(it.sizeBytes, workerBytes, ramBytes) == Fit.Both }.maxByOrNull { it.sizeBytes } ?: oracul
        return Suggestion(oracul?.name, judge?.name)
    }

    /** The physical memory of this machine in bytes, 0 when the JVM does not say. */
    fun physicalMemory(): Long = runCatching { (java.lang.management.ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean).totalMemorySize }.getOrDefault(0L)

    fun gb(bytes: Long): String = "%.0f GB".format(bytes / 1e9)
}
