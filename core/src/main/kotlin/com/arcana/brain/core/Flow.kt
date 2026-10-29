package com.arcana.brain.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** What a stage of a [Flow] does. Planner, worker and reviewer are the roles a skill run already has; the analyst is the stage before the planner. */
enum class StageKind(val label: String) {
    Analyst("Analyst"), Planner("Planner"), Worker("Worker"), Reviewer("Reviewer"), Controller("Controller"),
}

/** One stage: what it does and the model that does it. A blank [model] means the stage is skipped (the worker's blank means the chat model). */
data class FlowStage(val kind: StageKind, val model: String)

/**
 * A fixed sequence of stages, each with its own model (see docs/oracul.md, *Flows*). It is the fixed-sequence block of the orchestration design: the
 * stages run in order and each sees what the earlier ones wrote. The planner, worker and reviewer are applied through the roles of a skill run
 * ([SkillRoles]) and the analyst by `ChatSession.analyse`, so a flow adds no second way of running a model.
 */
data class Flow(val name: String, val stages: List<FlowStage>, val redos: Int = 0) {
    fun model(kind: StageKind): String = stages.firstOrNull { it.kind == kind }?.model.orEmpty()

    /** The flow's models as skill roles, which is how the worker, the reviewer and the planner are applied. */
    fun roles() = SkillRoles(worker = model(StageKind.Worker), reviewer = model(StageKind.Reviewer), planner = model(StageKind.Planner))

    companion object {
        const val OFF = "off"

        /** The stage a `consult` call names (`analyst`, `planner`, `reviewer`), or null: the controller and the worker are not specialists the model can call. */
        fun consultKind(stage: String): StageKind? = when (stage.trim().lowercase()) {
            "analyst" -> StageKind.Analyst
            "planner" -> StageKind.Planner
            "reviewer" -> StageKind.Reviewer
            else -> null
        }

        const val CAREFUL = "careful"

        /** The most times the controller may send the worker back for one message. */
        const val MAX_REDOS = 5

        /** What [AppConfig.flow] may be. */
        val NAMES = listOf(OFF, CAREFUL)

        /** The flow [AppConfig.flow] selects, or null when none. */
        fun of(config: AppConfig): Flow? = if (!config.oraculEnabled) null else when (config.flow) {
            CAREFUL -> careful(config)
            else -> null
        }

        /**
         * The first preset: analyse the request, plan, work, review, control (a redo goes back to the worker, up to [AppConfig.flowRedos] times). Each stage takes the model of its own setting, else the role table (planner, worker,
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
                    FlowStage(StageKind.Controller, pick(config.flowController)),
                ),
                redos = config.flowRedos.coerceIn(0, MAX_REDOS),
            )
        }
    }
}

/** The pseudo-tool with which the main model calls a stage as a specialist (subagent style); the chat answers it (`ChatSession.consult`). Offered when [AppConfig.flowTools] is on. */
const val CONSULT = "consult"

/** How many times the model may consult in one message of the user. */
const val MAX_CONSULTS = 3

val CONSULT_DEFINITION: JsonObject = buildJsonObject {
    put("type", "function")
    putJsonObject("function") {
        put("name", CONSULT)
        put("description", "Ask a specialist model for help and get its answer. stage `analyst`: restates what is really asked and what is unclear in a request; `planner`: writes a short numbered plan for a task; `reviewer`: checks an answer or a result and says what is wrong or missing. " +
            "The specialist sees only the brief you give, not this conversation: put in everything it needs (the request, the text to check). Use it when a task is hard, unclear or risky, not for simple questions; at most $MAX_CONSULTS times per message.")
        putJsonObject("parameters") {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("stage") {
                    put("type", "string")
                    putJsonArray("enum") { add(kotlinx.serialization.json.JsonPrimitive("analyst")); add(kotlinx.serialization.json.JsonPrimitive("planner")); add(kotlinx.serialization.json.JsonPrimitive("reviewer")) }
                    put("description", "Which specialist to ask")
                }
                putJsonObject("brief") {
                    put("type", "string")
                    put("description", "Everything the specialist needs: the request, or the text to review")
                }
            }
            putJsonArray("required") { add(kotlinx.serialization.json.JsonPrimitive("stage")); add(kotlinx.serialization.json.JsonPrimitive("brief")) }
        }
    }
}
