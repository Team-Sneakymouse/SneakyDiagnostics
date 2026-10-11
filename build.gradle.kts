import groovy.json.JsonSlurper
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.ZipFile

plugins { base }

group = "io.github.team-sneakymouse"
version = providers.exec {
    workingDir(rootDir)
    commandLine("git", "show", "-s", "--format=%ct:%h", "--abbrev=12", "HEAD")
}.standardOutput.asText.map { commit ->
    val (timestamp, hash) = commit.trim().split(":", limit = 2)
    val date = DateTimeFormatter.ofPattern("yyyy.MM.dd").withZone(ZoneOffset.UTC)
        .format(Instant.ofEpochSecond(timestamp.toLong()))
    "$date-$hash"
}.get()

subprojects {
    apply(plugin = "java")
    apply(plugin = "maven-publish")
    group = rootProject.group
    version = rootProject.version
    val artifactName = if (name == "plugin") rootProject.name else "${rootProject.name}-Ignite"
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(25))
        withSourcesJar()
    }
    dependencies {
        "testImplementation"(platform("org.junit:junit-bom:5.11.4"))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }
    tasks.withType<Test>().configureEach { useJUnitPlatform() }
    tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8" }
    tasks.withType<Jar>().configureEach {
        archiveBaseName.set(artifactName)
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
        duplicatesStrategy = DuplicatesStrategy.FAIL
    }
    extensions.configure<PublishingExtension> {
        publications {
            create<MavenPublication>("maven") {
                artifactId = artifactName
                from(components["java"])
                pom {
                    name.set(artifactName)
                    description.set(if (project.name == "plugin")
                        "On-demand Paper server diagnostics for investigating TPS issues"
                        else "Optional Ignite instrumentation companion for SneakyDiagnostics")
                    url.set("https://github.com/Team-Sneakymouse/SneakyDiagnostics")
                    scm {
                        url.set("https://github.com/Team-Sneakymouse/SneakyDiagnostics")
                        connection.set("scm:git:git@github.com:Team-Sneakymouse/SneakyDiagnostics.git")
                    }
                }
            }
        }
        repositories {
            maven {
                name = "sneakyrp"
                url = uri("https://maven.sneakyrp.com/releases")
                credentials(PasswordCredentials::class)
                authentication { create<org.gradle.authentication.http.BasicAuthentication>("basic") }
            }
        }
    }
    tasks.withType<PublishToMavenRepository>().configureEach { dependsOn(rootProject.tasks.named("check")) }
}

tasks.assemble { dependsOn(":plugin:assemble", ":ignite:assemble") }
tasks.check { dependsOn(":plugin:check", ":ignite:check") }
tasks.clean { dependsOn(":plugin:clean", ":ignite:clean") }

val verifyArtifacts by tasks.registering {
    group = "verification"
    description = "Checks artifact separation and shared version metadata."
    dependsOn(":plugin:jar", ":ignite:jar")
    val pluginJar = project(":plugin").tasks.named<Jar>("jar").flatMap { it.archiveFile }
    val igniteJar = project(":ignite").tasks.named<Jar>("jar").flatMap { it.archiveFile }
    inputs.files(pluginJar, igniteJar)
    inputs.property("version", project.version.toString())
    doLast {
        ZipFile(pluginJar.get().asFile).use { jar ->
            val descriptor = checkNotNull(jar.getEntry("plugin.yml")) { "Plugin descriptor missing" }
            val text = jar.getInputStream(descriptor).bufferedReader().use { it.readText() }
            check(text.contains("version: '${project.version}'")) { "Plugin version differs from build version" }
            check(jar.getEntry("com/teamsneakymouse/sneakydiagnostics/SneakyDiagnosticsPlugin.class") != null) { "Plugin entry point missing" }
            check(jar.getEntry("ignite.mod.json") == null) { "Ignite metadata must stay in the companion JAR" }
        }
        ZipFile(igniteJar.get().asFile).use { jar ->
            val descriptor = checkNotNull(jar.getEntry("ignite.mod.json")) { "Ignite descriptor missing" }
            val metadata = jar.getInputStream(descriptor).use { JsonSlurper().parse(it) as Map<*, *> }
            check(metadata["id"] == "sneakydiagnostics") { "Unexpected Ignite mod ID" }
            check(metadata["version"] == project.version.toString()) { "Ignite version differs from build version" }
            check(metadata["mixins"] is List<*>) { "Ignite mixin registration must be explicit" }
            check(jar.getEntry("plugin.yml") == null) { "Plugin metadata must stay in the plugin JAR" }
            check(jar.getEntry("com/teamsneakymouse/sneakydiagnostics/SneakyDiagnosticsPlugin.class") == null) { "Companion must not bundle the plugin" }
        }
    }
}
tasks.check { dependsOn(verifyArtifacts) }

tasks.register("publishAll") {
    group = "publishing"
    description = "Checks both modules and publishes both Maven artifacts."
    dependsOn(":plugin:publishMavenPublicationToSneakyrpRepository", ":ignite:publishMavenPublicationToSneakyrpRepository")
}
// Preserve the original repository-level publishing command.
tasks.register("publishMavenPublicationToSneakyrpRepository") {
    group = "publishing"
    dependsOn("publishAll")
}
tasks.register("publishToMavenLocal") {
    group = "publishing"
    dependsOn("check", ":plugin:publishToMavenLocal", ":ignite:publishToMavenLocal")
}
