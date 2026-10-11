# SneakyDiagnostics Ignite companion

This module produces `SneakyDiagnostics-Ignite-<version>.jar` for Ignite's `mods/` directory.
It currently contains discovery metadata only: there are no Mixins or active probes yet.
The Paper plugin remains independently installable and has no dependency on this artifact.

The first hook will add version-pinned Paper userdev/Mixin dependencies, required injection
checks, and tests against the target server classes. Hook tests and compatibility notes
belong in this module. Do not bundle server classes, loader libraries, or plugin classes.

Design the communication seam with the first hook. Shared bridge types must have one
runtime classloader owner; bundling independent copies into both JARs is not a communication
mechanism. Add a shared module once that ownership and interface are concrete.
