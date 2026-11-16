package com.arcana.brain.core

import kotlinx.serialization.json.JsonPrimitive

/**
 * The sampling options Arcana can send to Ollama with a chat request, and everything that needs to know about them in one place (the request, Settings, the Chat dialog, the status
 * indicator). Two modes: **model** sends none of them, so the model's own defaults (its Modelfile, shown by `/api/show`) apply; **custom** sends the values set here. In custom mode the
 * temperature is always sent, every other option only when it has a value (blank = the model's own).
 */
object Sampling {
    const val MODEL = "model"
    const val CUSTOM = "custom"

    /** The words the screens use for the two modes, in one place so Settings, the Chat dialog and the status pane never say it differently. [modeShort] is for a narrow column. */
    fun modeLabel(mode: String): String = if (mode == CUSTOM) "Custom" else "Model defaults"
    fun modeShort(mode: String): String = if (mode == CUSTOM) "Custom" else "Defaults"

    /** One option: [key] is Ollama's name, [short] the letters the status bar uses, the range is what Ollama accepts sensibly. */
    class Param(val key: String, val label: String, val short: String, val min: Double, val max: Double, val step: Double, val integer: Boolean, val start: Double, val help: String) {
        fun format(v: Double): String = if (integer) v.toInt().toString() else "%.2f".format(java.util.Locale.ROOT, v).trimEnd('0').trimEnd('.').ifEmpty { "0" }
    }

    val params: List<Param> = listOf(
        Param("temperature", "Temperature", "T", 0.0, 2.0, 0.1, false, 0.7, "How adventurous the choice of the next word is: 0 is focused and repeatable, 1 is the usual, above 1 is wild. Lower for facts and code, higher for ideas."),
        Param("top_p", "Top P", "P", 0.0, 1.0, 0.05, false, 0.9, "Keeps only the most likely words whose chances add up to this share (0.9 = the top 90%). Lower is safer and plainer, 1 keeps every word."),
        Param("top_k", "Top K", "K", 1.0, 200.0, 5.0, true, 40.0, "Keeps only this many of the most likely words at each step (20 is common). Lower is safer; a very high number leaves the choice to Top P."),
        Param("min_p", "Min P", "min", 0.0, 1.0, 0.01, false, 0.05, "Drops every word less than this share as likely as the best one (0.05 = a twentieth). 0 switches it off; it adapts to how sure the model is."),
        Param("presence_penalty", "Presence penalty", "pres", -2.0, 2.0, 0.1, false, 0.5, "Pushes the model away from words it has already used, so it wanders to new topics (above 0). Too high makes it drift or switch language; negative repeats more."),
        Param("repeat_penalty", "Repeat penalty", "rep", 0.5, 2.0, 0.05, false, 1.1, "Pushes against repeating the same words (1 = no push, 1.1 is mild). Too high gives odd wording; a loop retry raises it by itself."),
    )

    fun param(key: String): Param = params.first { it.key == key }

    /** The value set for [key] in the configuration (null = blank: the model's own). The temperature is never blank. */
    fun get(c: AppConfig, key: String): Double? = when (key) {
        "temperature" -> c.temperature
        "top_p" -> c.topP
        "top_k" -> c.topK?.toDouble()
        "min_p" -> c.minP
        "presence_penalty" -> c.presencePenalty
        "repeat_penalty" -> c.repeatPenalty
        else -> null
    }

    /** [c] with [key] set to [v] (null = blank); values are held inside the option's range. */
    fun set(c: AppConfig, key: String, v: Double?): AppConfig {
        val p = param(key)
        val x = v?.coerceIn(p.min, p.max)
        return when (key) {
            "temperature" -> if (x == null) c else c.copy(temperature = x)
            "top_p" -> c.copy(topP = x)
            "top_k" -> c.copy(topK = x?.toInt())
            "min_p" -> c.copy(minP = x)
            "presence_penalty" -> c.copy(presencePenalty = x)
            "repeat_penalty" -> c.copy(repeatPenalty = x)
            else -> c
        }
    }

    /** Typed text to a value: blank = null (the model's own; refused for the temperature); anything else must be a number inside the range. */
    fun parse(text: String, p: Param): Double? {
        val t = text.trim().replace(',', '.')
        if (t.isEmpty()) { require(p.key != "temperature") { "${p.label} cannot be blank: it is always sent in custom mode" }; return null }
        val v = t.toDoubleOrNull() ?: throw IllegalArgumentException("${p.label} must be a number")
        require(v in p.min..p.max) { "${p.label} must be between ${p.format(p.min)} and ${p.format(p.max)}" }
        require(!p.integer || v == Math.floor(v)) { "${p.label} must be a whole number" }
        return v
    }

    /**
     * One step of [dir] (-1 or +1). A blank value first becomes the model's own value ([own], from `/api/show`) when it lists one, else [Param.start]; stepping below the
     * minimum of an optional option makes it blank again (the model's own).
     */
    fun step(c: AppConfig, p: Param, dir: Int, own: Map<String, String>): AppConfig {
        val now = get(c, p.key)
        if (now == null) return set(c, p.key, own[p.key]?.toDoubleOrNull() ?: p.start)
        val rounded = Math.round((now + dir * p.step) * 1000) / 1000.0
        return if (p.key != "temperature" && rounded < p.min - 1e-9) set(c, p.key, null) else set(c, p.key, rounded)
    }

    /** Custom mode, starting from the model's own temperature (when it lists one) instead of an old stored value, so switching on never silently changes how the model answers. */
    fun toCustom(c: AppConfig, own: Map<String, String>): AppConfig =
        if (c.samplingMode == CUSTOM) c else c.copy(samplingMode = CUSTOM, temperature = own["temperature"]?.toDoubleOrNull()?.coerceIn(0.0, 2.0) ?: c.temperature)

    /** Back to the model's own defaults: the options are cleared (the temperature is kept for the next time custom is switched on). */
    fun toModel(c: AppConfig): AppConfig = c.copy(samplingMode = MODEL, topP = null, topK = null, minP = null, presencePenalty = null, repeatPenalty = null)

    /** What is sent: nothing in model mode; in custom mode the temperature and every option that has a value. */
    fun options(c: AppConfig): Map<String, JsonPrimitive> {
        if (c.samplingMode != CUSTOM) return emptyMap()
        return buildMap {
            for (p in params) {
                val v = get(c, p.key) ?: continue
                put(p.key, if (p.integer) JsonPrimitive(v.toInt()) else JsonPrimitive(v))
            }
        }
    }

    /** One option as the model really gets it: [fromSettings] says it is a value set here, not the model's own. */
    class Effective(val param: Param, val value: Double, val fromSettings: Boolean)

    /** The options the model works with now, in order: what is set (custom mode) or else what the model's Modelfile says ([own]); options neither has are left out. */
    fun effective(c: AppConfig, own: Map<String, String>): List<Effective> = params.mapNotNull { p ->
        val set = if (c.samplingMode == CUSTOM) get(c, p.key) else null
        when {
            set != null -> Effective(p, set, true)
            else -> own[p.key]?.toDoubleOrNull()?.let { Effective(p, it, false) }
        }
    }
}
