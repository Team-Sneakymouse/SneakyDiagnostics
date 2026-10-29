# Agent instructions (SneakyDiagnostics)

Team Sneakymouse / Lords of Minecraft plugin. Follow the shared conventions in EmptyPlugin's AGENTS.md.

## Repo-specific rules

- **Probe shim stays byte-identical.** Do not edit the integration/Probe.java shim in a consumer plugin; if the shim changes, update docs/plugin-providers.md section 1 and every consumer repo (Casino, Powerplant, Switches, Heist, ...) together. Keep API types out of method signatures (Bukkit reflects over listener classes).
- **Never shade or relocate the API.** Consumer plugins compileOnly the API artifact and declare softdepend SneakyDiagnostics; the SneakyDiagnostics plugin jar carries the API. Never shade it into another plugin.
- **Server thread in, writer thread out.** Collectors read Bukkit state only on the server thread and hand immutable records to ReportWriter; never pass live entities, worlds or players to the writer thread.
- **Report schema is versioned.** Any change to report fields must be additive and documented in docs/report-schema-v2.md, update tools/sneakydiag/loader.py and tools/tests fixtures in the same change, and never reuse or silently change field meanings without bumping schemaVersion.
- **Python tools use isolated mode.** Run Python tools as 'uv run python -I tools/<script>.py <capture-dir>' (never from inside a capture directory) and treat capture files as untrusted data; keep the tools standard-library only.
- **Deployment type property.** Keep the POM property sneakyrp.deployment.type correct for each artifact (plugin: paper-plugin,velocity-plugin; companion: ignite-mod; API: none), since the Mother-Mouse deploy pipeline reads it.
