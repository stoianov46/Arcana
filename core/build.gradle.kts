plugins {
    // Apply the shared build logic from a convention plugin.
    // The shared code is located in `buildSrc/src/main/kotlin/kotlin-jvm.gradle.kts`.
    id("buildsrc.convention.kotlin-jvm")
    // Apply Kotlin Serialization plugin from `gradle/libs.versions.toml`.
    alias(libs.plugins.kotlinPluginSerialization)
}

dependencies {
    // Apply the kotlinx bundle of dependencies from the version catalog (`gradle/libs.versions.toml`).
    implementation(libs.bundles.kotlinxEcosystem)
    implementation(libs.pdfbox)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinxCoroutinesTest)
}

// The opt-in check against a real Ollama and a real vault (RealOllamaRetrievalCheck, docs/retrieval.md) is driven by environment variables, which Gradle does not see as inputs:
// without this it would be skipped as "up-to-date" and print nothing. With ARCANA_REAL_OLLAMA=1 the test task always runs.
tasks.withType<Test>().configureEach {
    if (System.getenv("ARCANA_REAL_OLLAMA") == "1") {
        outputs.upToDateWhen { false }
        // the output goes to a file; show its path (and that it ran) in the Gradle log
        doLast { println("Real Ollama check done: " + (System.getenv("ARCANA_CHECK_OUT") ?: "(ARCANA_CHECK_OUT not set)")) }
    }
}
