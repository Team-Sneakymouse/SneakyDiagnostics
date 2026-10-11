plugins {
    java
    `maven-publish`
}

// Add version-pinned Paper userdev and compile-only Mixin dependencies with the first hook.
tasks.processResources {
    inputs.property("version", project.version.toString())
    filesMatching("ignite.mod.json") { expand("version" to project.version.toString()) }
}
