import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

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
    version = if (name == "api") "1.0.0" else rootProject.version
    val artifactName = when (name) {
        "api" -> "${rootProject.name}-API"
        "plugin" -> rootProject.name
        else -> "${rootProject.name}-Ignite"
    }
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
                    description.set(when (project.name) {
                        "api" -> "Provider interface for optional SneakyDiagnostics integrations"
                        "plugin" -> "On-demand Paper server diagnostics for investigating TPS issues"
                        else -> "Optional Ignite instrumentation companion for SneakyDiagnostics"
                    })
                    url.set("https://github.com/Team-Sneakymouse/SneakyDiagnostics")
                    if (project.name != "api") properties.put("sneakyrp.deployment.type", if (project.name == "plugin") "paper-plugin" else "ignite-mod")
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

tasks.assemble { dependsOn(":api:assemble", ":plugin:assemble", ":ignite:assemble") }
tasks.check { dependsOn(":api:check", ":plugin:check", ":ignite:check") }
tasks.clean { dependsOn(":api:clean", ":plugin:clean", ":ignite:clean") }

tasks.register("publishAll") {
    group = "publishing"
    description = "Checks all modules and publishes the plugin and companion."
    dependsOn(":plugin:publishMavenPublicationToSneakyrpRepository", ":ignite:publishMavenPublicationToSneakyrpRepository")
}

tasks.register("publishApi") {
    group = "publishing"
    description = "Releases the independently versioned provider API; bump its version before another release."
    dependsOn(":api:publishMavenPublicationToSneakyrpRepository")
}
// Preserve the original repository-level publishing command.
tasks.register("publishMavenPublicationToSneakyrpRepository") {
    group = "publishing"
    dependsOn("publishAll")
}
tasks.register("publishToMavenLocal") {
    group = "publishing"
    dependsOn("check", ":api:publishToMavenLocal", ":plugin:publishToMavenLocal", ":ignite:publishToMavenLocal")
}
