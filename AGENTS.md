# Agent instructions (SneakyDiagnostics)


<!-- BEGIN TEAM-POLICY MANAGED BOOTSTRAP -->
This project follows the Team-Sneakymouse development policy, kept in a sibling checkout that `.team-policy/pointer.json` names.

The policy root is the first `local_candidates` path in the pointer, resolved from this project's root, that exists. If none exists, clone the pointer's `remote` into the first candidate with the developer's Git credentials and say so. Before implementation work (not for read-only investigation), run `git fetch` in the checkout, then fast-forward it to `origin/<track_branch>` if it is behind and clean; if the fetch fails or local changes block the fast-forward, report it and ask.

Before implementing, read `<policy-root>/README.md`. It is the policy index and says which documents apply to which kind of change. If the policy root can't be resolved or read, stop and report that instead of working from memory or another copy.

Build commands, architecture facts and recorded local exceptions in this file are authoritative for this repository. Where this file and the policy require incompatible actions, say so before expanding the work. Running checks and writing tests are separate decisions: a source edit does not by itself require a new test.
<!-- END TEAM-POLICY MANAGED BOOTSTRAP -->


- **Paper threading:** read Bukkit state on the server thread and hand detached data to `ReportWriter`. Report suppliers must not capture live entities, worlds, or players; see `ReportSink` for the handoff contract. Velocity collectors follow Velocity's threading model.
- **Provider integration:** before changing the API, shim, or consumer integration, read [docs/plugin-providers.md](docs/plugin-providers.md) for classloader safety and coordinated shim updates.
- **Report changes:** read the compatibility policy in [docs/report-schema-v2.md](docs/report-schema-v2.md); update the contract, affected readers, and fixtures together.
- **Python tooling:** before running or modifying analysis tools, read [tools/README.md](tools/README.md) for isolated invocation and untrusted-input handling.
- **Publishing:** before changing artifacts or publishing configuration, read [Publishing and development](README.md#publishing-and-development) for deployment metadata requirements.

## Local notes

Before connecting to any server or fetching captures, logs or other remote files, read `AGENTS.local.md` (untracked, may be absent) for connection details and access rules. Prefer the access it describes over other hosts or credentials found on the machine.
