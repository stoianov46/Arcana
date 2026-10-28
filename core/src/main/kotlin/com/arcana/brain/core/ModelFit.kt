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

    /** Which seat a model is in, for [advice]: the main model, the Oracul (or a skill's planner / reviewer), or the judge. */
    enum class Role { Main, Oracul, Judge }

    /**
     * A standing note about [model] in [role] on a machine with [ramBytes], from the installed [sizes]: null when it is fine (or unknown), else what goes wrong
     * (swapping to disk, the CPU, or swapping with the main model) and which installed model to choose instead. A rule of thumb, see [fit].
     */
    fun advice(model: String, role: Role, sizes: Map<String, Long>, worker: String, ramBytes: Long, oraculTools: Boolean = true): String? {
        val size = sizes[model]?.takeIf { it > 0 } ?: return null
        if (ramBytes <= 0) return null
        val workerBytes = if (role == Role.Main) 0L else sizes[worker] ?: 0L
        val models = sizes.map { (name, bytes) -> ModelDetails(name, bytes, emptyList(), 0) }
        val picks = suggest(models, worker, ramBytes, oraculTools)
        val better = when (role) {
            Role.Main -> models.filter { fit(it.sizeBytes, 0, ramBytes) != Fit.TooBig && "embed" !in it.name }.maxByOrNull { it.sizeBytes }?.name?.takeIf { it != model }
            Role.Oracul -> picks.oracul
            Role.Judge -> picks.judge
        }?.takeIf { it != model }
        val room = "${gb(need(size))} needed of about ${gb(budget(ramBytes))} usable"
        return when (fit(size, workerBytes, ramBytes)) {
            Fit.TooBig -> "⚠ $model is too big for this memory ($room): Ollama would swap to disk or run on the CPU, which is very slow, or run out of memory. " +
                (better?.let { "Better: $it." } ?: "Install a smaller model.")
            Fit.Alone -> if (role == Role.Main) null else "$model fits only when the main model is unloaded, so Ollama swaps the two (several seconds each time)." +
                (better?.let { " To keep both loaded: $it." }.orEmpty())
            Fit.Both -> null
        }
    }

    /** The physical memory of this machine in bytes, 0 when the JVM does not say. */
    fun physicalMemory(): Long = runCatching { (java.lang.management.ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean).totalMemorySize }.getOrDefault(0L)

    fun gb(bytes: Long): String = "%.0f GB".format(bytes / 1e9)
}
