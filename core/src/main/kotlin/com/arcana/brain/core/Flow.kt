package com.arcana.brain.core

/** What a stage of a [Flow] does. Planner, worker and reviewer are the roles a skill run already has; the analyst is the stage before the planner. */
enum class StageKind(val label: String) {
    Analyst("Analyst"), Planner("Planner"), Worker("Worker"), Reviewer("Reviewer"),
}

/** One stage: what it does and the model that does it. A blank [model] means the stage is skipped (the worker's blank means the chat model). */
data class FlowStage(val kind: StageKind, val model: String)

/**
 * A fixed sequence of stages, each with its own model (see docs/oracul.md, *Flows*). It is the fixed-sequence block of the orchestration design: the
 * stages run in order and each sees what the earlier ones wrote. The planner, worker and reviewer are applied through the roles of a skill run
 * ([SkillRoles]) and the analyst by `ChatSession.analyse`, so a flow adds no second way of running a model.
 */
data class Flow(val name: String, val stages: List<FlowStage>) {
    fun model(kind: StageKind): String = stages.firstOrNull { it.kind == kind }?.model.orEmpty()

    /** The flow's models as skill roles, which is how the worker, the reviewer and the planner are applied. */
    fun roles() = SkillRoles(worker = model(StageKind.Worker), reviewer = model(StageKind.Reviewer), planner = model(StageKind.Planner))

    companion object {
        const val OFF = "off"
        const val CAREFUL = "careful"

        /** What [AppConfig.flow] may be. */
        val NAMES = listOf(OFF, CAREFUL)

        /** The flow [AppConfig.flow] selects, or null when none. */
        fun of(config: AppConfig): Flow? = when (config.flow) {
            CAREFUL -> careful(config)
            else -> null
        }

        /**
         * The first preset: analyse the request, plan, work, review. Each stage takes the model of its own setting, else the role table (planner, worker,
         * reviewer), else the Oracul model; the worker stays the chat model when nothing is set. A flow with no model for a stage skips that stage.
         */
        fun careful(config: AppConfig): Flow {
            val oracul = Oracul.model(config)
            fun pick(own: String) = own.trim().ifEmpty { oracul }
            return Flow(
                "Careful",
                listOf(
                    FlowStage(StageKind.Analyst, pick(config.flowAnalyst)),
                    FlowStage(StageKind.Planner, pick(config.rolePlanner)),
                    FlowStage(StageKind.Worker, config.roleWorker.trim()),
                    FlowStage(StageKind.Reviewer, pick(config.roleReviewer)),
                ),
            )
        }
    }
}
