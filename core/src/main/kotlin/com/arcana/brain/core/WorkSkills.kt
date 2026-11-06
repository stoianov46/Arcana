package com.arcana.brain.core

/**
 * Which skill a work item runs with (proposals/0002). A skill opts in to the kinds it works on (`handles:`), so only those are offered; the choice for an
 * item is, strongest first: the skill pinned to the item, the default for its kind (Settings → Skills), then automatic (research: the first skill that
 * handles research, else the first `…research-report`; every other kind: a plain chat). The picker shown when an item is started can change any of
 * the three.
 */
object WorkSkills {
    /** The value of a pin or a default that means "no skill: a plain chat". */
    const val NONE = "none"

    /** What a pin or a default is for the kind: its value in the settings. */
    fun defaultFor(config: AppConfig, kind: WorkKind): String = when (kind) {
        WorkKind.Research -> config.workSkillResearch
        WorkKind.Question -> config.workSkillQuestion
        WorkKind.Todo -> config.workSkillTodo
        WorkKind.Thought -> ""
    }.trim()

    /** The config with [value] as the default of [kind]. */
    fun withDefault(config: AppConfig, kind: WorkKind, value: String): AppConfig = when (kind) {
        WorkKind.Research -> config.copy(workSkillResearch = value.trim())
        WorkKind.Question -> config.copy(workSkillQuestion = value.trim())
        WorkKind.Todo -> config.copy(workSkillTodo = value.trim())
        WorkKind.Thought -> config
    }

    /** The skills that may run an item of [kind]: the ones that say so with `handles`, in the order they were found. */
    fun offered(skills: List<Skill>, kind: WorkKind): List<Skill> = skills.filter { kind in it.handles }

    /**
     * What the picker lists for [kind]: the skills that opted in with `handles`, or, while no skill has opted in for that kind, all of them (the second value is true then),
     * so the picker is never an empty dead end on a vault whose skills have not been given `handles:` yet.
     */
    fun pickable(skills: List<Skill>, kind: WorkKind): Pair<List<Skill>, Boolean> =
        offered(skills, kind).let { if (it.isNotEmpty() || skills.isEmpty()) it to false else skills to true }

    /** The skill an item will run with ([skill] null = a plain chat) and where that choice comes from, in words. */
    data class Choice(val skill: Skill?, val source: String)

    /** [pinned]: the skill pinned to the item (blank = none). */
    fun resolve(kind: WorkKind, pinned: String, skills: List<Skill>, config: AppConfig): Choice {
        fun named(name: String) = skills.firstOrNull { it.name == name }
        pinned.trim().takeIf { it.isNotEmpty() }?.let { p -> if (p == NONE) return Choice(null, "pinned to this item: a plain chat"); named(p)?.let { return Choice(it, "pinned to this item") } }
        defaultFor(config, kind).takeIf { it.isNotEmpty() }?.let { d -> if (d == NONE) return Choice(null, "the default for ${kind.id}: a plain chat"); named(d)?.let { return Choice(it, "the default for ${kind.id}") } }
        if (kind == WorkKind.Research) {
            (offered(skills, kind).firstOrNull() ?: skills.firstOrNull { it.name.endsWith("research-report") })?.let { return Choice(it, "automatic") }
        }
        return Choice(null, "a plain chat")
    }
}
