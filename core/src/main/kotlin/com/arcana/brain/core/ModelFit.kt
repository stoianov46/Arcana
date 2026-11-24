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

    /**
     * How many tokens of context fit next to a model whose weights take [weightsBytes], on a machine with [ramBytes]: what the models may use ([budget])
     * minus the weights, divided by what a token costs ([kvBytesPerToken]). 0 when the weights alone do not fit; null when it cannot be worked out. An estimate.
     */
    fun tokenRoom(ramBytes: Long, weightsBytes: Long, kvBytesPerToken: Long, usableBytes: Long = budget(ramBytes)): Long? {
        if (ramBytes <= 0 || weightsBytes <= 0 || kvBytesPerToken <= 0) return null
        return ((usableBytes - weightsBytes) / kvBytesPerToken).coerceAtLeast(0)
    }

    /**
     * What a token of context costs, seen from a loaded model: Ollama reports its whole size with the cache ([loadedBytes]) and the context it was loaded with; take away the
     * weights ([weightsBytes]) and divide. For models whose description does not say how big the cache is (MLX builds); 0 when it cannot be worked out.
     */
    fun observedKvPerToken(loadedBytes: Long, weightsBytes: Long, context: Int): Long =
        if (context <= 0 || weightsBytes <= 0 || loadedBytes <= weightsBytes) 0 else (loadedBytes - weightsBytes) / context

    /** What the models may really use: the Mac's GPU limit when it is known ([GpuMemory]; Metal refuses anything past it), else [budget]. */
    fun usable(ramBytes: Long, gpu: GpuMemory.Cap?): Long = gpu?.bytes ?: budget(ramBytes)

    /**
     * What [weightsBytes] with a context of [context] tokens will take once loaded: the weights plus the cache when what a token costs is known, else the rule of thumb [need].
     * 0 when the size is unknown.
     */
    fun loadedNeed(weightsBytes: Long, kvBytesPerToken: Long, context: Int): Long = when {
        weightsBytes <= 0 -> 0
        kvBytesPerToken > 0 && context > 0 -> weightsBytes + kvBytesPerToken * context
        else -> need(weightsBytes)
    }

    /**
     * The loaded models to unload before [model] is loaded needing [needBytes] (its weights and cache) with a context of [context] tokens (0 = whatever it has), so that
     * everything fits in [usableBytes]: none when it is already loaded with that context, when its size is unknown or when all of it fits; else the other models, biggest
     * first, until it fits. Ollama would make room itself only when it judges memory short, and its MLX runner can misjudge and crash ("Insufficient Memory").
     */
    fun toUnload(model: String, needBytes: Long, context: Int, loaded: List<LoadedModel>, usableBytes: Long): List<String> {
        if (needBytes <= 0 || usableBytes <= 0) return emptyList()
        val mine = loaded.firstOrNull { ModelStatus.sameModel(it.name, model) }
        if (mine != null && (context <= 0 || mine.context <= 0 || mine.context == context)) return emptyList()
        val others = loaded.filter { it !== mine }.sortedByDescending { it.sizeBytes }
        var used = others.sumOf { it.sizeBytes }
        val out = ArrayList<String>()
        for (o in others) {
            if (used + needBytes <= usableBytes) break
            out += o.name
            used -= o.sizeBytes
        }
        return out
    }

    /** The physical memory of this machine in bytes, 0 when the JVM does not say. */
    fun physicalMemory(): Long = runCatching { (java.lang.management.ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean).totalMemorySize }.getOrDefault(0L)

    fun gb(bytes: Long): String = "%.0f GB".format(bytes / 1e9)
}

/**
 * How much memory the GPU of an Apple-silicon Mac may take. The CPU and GPU share the memory, but macOS lets the GPU wire only part of it: about two thirds up to 36 GB, three
 * quarters above (the default, `iogpu.wired_limit_mb` = 0), or what `sudo sysctl iogpu.wired_limit_mb=…` set (until the next restart). Past it Metal refuses to allocate and
 * Ollama's MLX runner fails with "Insufficient Memory". Elsewhere (Linux, Windows, an Intel Mac) there is no such limit to read: [current] is null.
 */
object GpuMemory {
    /** The limit in [bytes]; [custom] when someone set `iogpu.wired_limit_mb`, false for the macOS default. */
    data class Cap(val bytes: Long, val custom: Boolean)

    private const val MB = 1L shl 20
    private const val GB = 1L shl 30

    /** The macOS default for a machine with [ramBytes]. */
    fun defaultBytes(ramBytes: Long): Long = if (ramBytes <= 36 * GB) ramBytes * 2 / 3 else ramBytes * 3 / 4

    /** The limit from the value of `iogpu.wired_limit_mb` ([wiredLimitMb]: 0 = the default); null when there is no value or no memory size. */
    fun cap(ramBytes: Long, wiredLimitMb: Long?): Cap? = when {
        ramBytes <= 0 || wiredLimitMb == null -> null
        wiredLimitMb > 0 -> Cap(wiredLimitMb * MB, true)
        else -> Cap(defaultBytes(ramBytes), false)
    }

    /** A higher limit worth suggesting, in MB: all the memory but a fifth (at least 6 GB) left for macOS and the apps. */
    fun suggestedMb(ramBytes: Long): Long = (ramBytes - maxOf(6 * GB, ramBytes / 5)).coerceAtLeast(0) / MB

    /** The command that raises the limit (asks for the password; lasts until the next restart). */
    fun raiseCommand(ramBytes: Long): String = "sudo sysctl iogpu.wired_limit_mb=${suggestedMb(ramBytes)}"

    @Volatile private var cached: Pair<Long, Cap?>? = null

    /** This machine's limit, read with `sysctl` at most every 30 seconds (it can be changed while Arcana runs); null when it is not a Mac with that setting. */
    fun current(): Cap? {
        cached?.let { (at, cap) -> if (System.currentTimeMillis() - at < 30_000) return cap }
        val cap = runCatching {
            if ("mac" !in System.getProperty("os.name").lowercase()) return@runCatching null
            val p = ProcessBuilder("sysctl", "-n", "iogpu.wired_limit_mb").redirectErrorStream(true).start()
            val text = p.inputStream.bufferedReader().readText()
            if (!p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS) || p.exitValue() != 0) null else cap(ModelFit.physicalMemory(), text.trim().toLongOrNull())
        }.getOrNull()
        cached = System.currentTimeMillis() to cap
        return cap
    }

    /** `21 GB (macOS default)` or `26 GB (set with sysctl)`. */
    fun describe(cap: Cap): String = "${ModelFit.gb(cap.bytes)} (${if (cap.custom) "set with sysctl" else "macOS default"})"
}
