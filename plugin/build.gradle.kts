plugins {
    java
    `maven-publish`
}

dependencies {
    implementation(project(":api"))
    compileOnly("io.papermc.paper:paper-api:26.2.build.116-stable")
    testImplementation("io.papermc.paper:paper-api:26.2.build.116-stable")
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
    filesMatching("plugin.yml") { expand("version" to project.version.toString()) }
}
