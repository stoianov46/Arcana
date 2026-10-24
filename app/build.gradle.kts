plugins {
    // Apply the shared build logic from a convention plugin.
    // The shared code is located in `buildSrc/src/main/kotlin/kotlin-jvm.gradle.kts`.
    id("buildsrc.convention.kotlin-jvm")

    // Mosaic renders Compose into the terminal, so every module with @Composable code needs the Compose compiler.
    alias(libs.plugins.kotlinPluginCompose)

    // Apply the Application plugin to add support for building an executable JVM application.
    application
}

dependencies {
    implementation(project(":core"))
    implementation(libs.mosaicRuntime)
    implementation(libs.bundles.kotlinxEcosystem)
    testImplementation(kotlin("test"))
}

application {
    // Define the Fully Qualified Name for the application main class
    // (Note that Kotlin compiles `App.kt` to a class with FQN `com.arcana.brain.app.AppKt`.)
    mainClass = "com.arcana.brain.app.AppKt"
    applicationName = "arcana"
}
