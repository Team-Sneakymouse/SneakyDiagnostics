plugins {
    java
    `maven-publish`
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.23"
}

repositories { maven("https://maven.fabricmc.net/") }
dependencies {
    // Baseline for compilation and contract tests, not a runtime version restriction.
    paperweight.paperDevBundle("26.2.build.116-stable")
    compileOnly("net.fabricmc:sponge-mixin:0.17.1+mixin.0.8.7")
    compileOnly("io.github.llamalad7:mixinextras-common:0.5.3")
    testImplementation("net.fabricmc:sponge-mixin:0.17.1+mixin.0.8.7")
    testImplementation("org.ow2.asm:asm-tree:9.9.1")
}
tasks.compileJava { options.compilerArgs.add("-proc:none") }
tasks.test {
    classpath += configurations.compileClasspath.get()
    providers.gradleProperty("compatibilityServerJar").orNull?.let {
        inputs.file(file(it))
        systemProperty("compatibilityServerJar", file(it).absolutePath)
    }
}
tasks.processResources {
    inputs.property("version", project.version.toString())
    filesMatching("ignite.mod.json") { expand("version" to project.version.toString()) }
}

tasks.register<Jar>("proofJar") {
    dependsOn(tasks.testClasses)
    archiveFileName.set("sneakydiagnostics-ignite-proof.jar")
    from(sourceSets.test.get().output)
}

// Opt-in: needs a local Ignite/Paper installation, but starts no Minecraft server.
tasks.register<Exec>("verifyRuntime") {
    val companionJar = tasks.jar.flatMap { it.archiveFile }
    val proofJar = tasks.named<Jar>("proofJar").flatMap { it.archiveFile }
    val pluginJar = project(":plugin").tasks.named<Jar>("jar").flatMap { it.archiveFile }
    dependsOn(tasks.jar, tasks.named("proofJar"), ":plugin:jar")
    doFirst {
        val directory = rootProject.file(providers.gradleProperty("runtimeProofDirectory").orElse(".scratch/scheduler-proof").get())
        require(directory.resolve("ignite.jar").isFile && directory.resolve("paper.jar").isFile) {
            "Prepare Ignite and Paper in $directory; see ignite/README.md"
        }
        project.copy { from(companionJar); into(directory.resolve("diagnostics-mods")); rename { "companion.jar" } }
        project.copy { from(proofJar); into(directory.resolve("diagnostics-mods")); rename { "proof.jar" } }
        workingDir(directory)
        commandLine("java", "-Dignite.locator=paper", "-Dignite.jar=paper.jar", "-Dignite.mods=diagnostics-mods",
            "-Dignite.target=com.teamsneakymouse.sneakydiagnostics.ignite.bootstrap.SchedulerProof",
            "-Dsneakydiagnostics.proof.pluginJar=${pluginJar.get().asFile.absolutePath}", "-jar", "ignite.jar")
    }
}
