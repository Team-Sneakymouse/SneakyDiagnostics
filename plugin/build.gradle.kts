plugins {
    java
    `maven-publish`
}

dependencies {
    implementation(project(":api"))
    compileOnly("io.papermc.paper:paper-api:26.2.build.116-stable")
    compileOnly("com.velocitypowered:velocity-api:3.4.0-SNAPSHOT")
    testImplementation("io.papermc.paper:paper-api:26.2.build.116-stable")
    testImplementation("com.velocitypowered:velocity-api:3.4.0-SNAPSHOT")
}

// The service and its providers must resolve the exact same API classes at runtime.
tasks.jar { from(project(":api").sourceSets.main.get().output) }

tasks.register<Test>("verifyMagicSpells") {
    description = "Verifies the rebuilt MagicSpells JAR against the provider service in a separate classloader."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter { includeTestsMatching("*MagicSpellsJarTest") }
    val jar = providers.gradleProperty("magicSpellsJar")
    if (jar.isPresent) {
        inputs.file(jar)
        systemProperty("magicSpellsJar", jar.get())
    }
    doFirst { require(jar.isPresent) { "Pass -PmagicSpellsJar=<absolute path to rebuilt deployable MagicSpells JAR>" } }
}

tasks.register<Test>("verifyVoice") {
    description = "Verifies the rebuilt SneakyConfigurableVoice provider in a separate classloader."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter { includeTestsMatching("*VoiceJarTest") }
    val jar = providers.gradleProperty("voiceJar")
    if (jar.isPresent) {
        inputs.file(jar)
        systemProperty("voiceJar", jar.get())
    }
    doFirst { require(jar.isPresent) { "Pass -PvoiceJar=<absolute path to rebuilt SneakyConfigurableVoice JAR>" } }
}

tasks.processResources {
    inputs.property("version", project.version.toString())
    filesMatching(listOf("plugin.yml", "velocity-plugin.json")) { expand("version" to project.version.toString()) }
}

// Opt-in contract/export proof against a real bundled spark JAR, without starting Minecraft.
tasks.register<Test>("verifySpark") {
    group = "verification"
    description = "Verifies the profiling adapter against a supplied spark JAR."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter { includeTestsMatching("*SparkJarTest") }
    val jar = providers.gradleProperty("sparkJar")
    if (jar.isPresent) {
        inputs.file(jar)
        systemProperty("sparkJar", jar.get())
    }
    doFirst { require(jar.isPresent) { "Pass -PsparkJar=<absolute path to spark-paper JAR>" } }
}
