# Agent instructions (SneakyDiagnostics)

Shared conventions live in [team-development-policy](https://github.com/Team-Sneakymouse/team-development-policy). Read its README and applicable policy documents from the sibling `../team-development-policy` checkout before making changes.

- **Paper threading:** read Bukkit state on the server thread and hand detached data to `ReportWriter`. Report suppliers must not capture live entities, worlds, or players; see `ReportSink` for the handoff contract. Velocity collectors follow Velocity's threading model.
- **Provider integration:** before changing the API, shim, or consumer integration, read [docs/plugin-providers.md](docs/plugin-providers.md) for classloader safety and coordinated shim updates.
- **Report changes:** read the compatibility policy in [docs/report-schema-v2.md](docs/report-schema-v2.md); update the contract, affected readers, and fixtures together.
- **Python tooling:** before running or modifying analysis tools, read [tools/README.md](tools/README.md) for isolated invocation and untrusted-input handling.
- **Publishing:** before changing artifacts or publishing configuration, read [Publishing and development](README.md#publishing-and-development) for deployment metadata requirements.
