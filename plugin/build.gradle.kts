plugins {
    java
    `maven-publish`
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.116-stable")
    testImplementation("io.papermc.paper:paper-api:26.2.build.116-stable")
}

tasks.processResources {
    inputs.property("version", project.version.toString())
    filesMatching("plugin.yml") { expand("version" to project.version.toString()) }
}
