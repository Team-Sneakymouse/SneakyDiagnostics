import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

plugins {
    java
    `maven-publish`
}

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

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.116-stable")
    testImplementation("io.papermc.paper:paper-api:26.2.build.116-stable")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
    withSourcesJar()
}

tasks.test { useJUnitPlatform() }
tasks.processResources {
    inputs.property("version", project.version.toString())
    filesMatching("plugin.yml") { expand("version" to project.version.toString()) }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = rootProject.name
            from(components["java"])
            pom {
                name.set("SneakyDiagnostics")
                description.set("On-demand Paper server diagnostics for investigating TPS issues")
                url.set("https://github.com/Team-Sneakymouse/SneakyDiagnostics")
                scm {
                    url.set("https://github.com/Team-Sneakymouse/SneakyDiagnostics")
                    connection.set("scm:git:https://github.com/Team-Sneakymouse/SneakyDiagnostics.git")
                }
            }
        }
    }
    repositories {
        maven {
            name = "sneakyrp"
            url = uri("https://maven.sneakyrp.com/releases")
            credentials(PasswordCredentials::class)
            authentication {
                create<org.gradle.authentication.http.BasicAuthentication>("basic")
            }
        }
    }
}

tasks.withType<PublishToMavenRepository>().configureEach { dependsOn(tasks.check) }

