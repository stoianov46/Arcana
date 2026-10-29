package com.arcana.brain.core

/** What kind of message this is, for the router: it decides which model should work on it (see docs/oracul.md, *Router*). */
enum class Route(val label: String) { Vision("an image"), Code("code") }

/**
 * The router block of the orchestration design, rules first: no model decides, so it costs nothing and can be understood. An image is attached → [Route.Vision]; a code block
 * or a source file name in the text → [Route.Code]; anything else → null (the chat model answers). The models are set in Settings → Helpers (*Route: images to* / *code to*).
 */
object Router {
    private val CODE_FILE = Regex("\\b[\\w./-]+\\.(kt|kts|java|py|js|jsx|ts|tsx|go|rs|c|h|cpp|cs|rb|php|swift|sql|sh|gradle)\\b")

    fun kindOf(text: String, hasImages: Boolean): Route? = when {
        hasImages -> Route.Vision
        text.contains("```") || CODE_FILE.containsMatchIn(text) -> Route.Code
        else -> null
    }

    /** The model set for [route]; blank = no routing for that kind. */
    fun modelFor(config: AppConfig, route: Route): String = when (route) {
        Route.Vision -> config.routeVision.trim()
        Route.Code -> config.routeCode.trim()
    }
}
