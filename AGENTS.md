# Agent instructions (SneakyDiagnostics)


<!-- BEGIN TEAM-POLICY MANAGED BOOTSTRAP -->
This project is enrolled in Team-Sneakymouse development policy via a sibling checkout (see `.team-policy/pointer.json`).

Resolve the policy root: try each `local_candidates` path in the pointer relative to this project root. If none exist, `git clone` the pointer `remote` into the first candidate using the developer's existing Git credentials. If a checkout exists but is behind `origin/<track_branch>`, `git fetch` and fast-forward that branch when safe; if local edits block the update, report and ask.

Before implementing, review `<policy-root>/README.md` as the current policy index and load the linked guidance relevant to the task. At minimum, when authorization or risk is unclear, read `<policy-root>/policy/core.md`; read `<policy-root>/policy/scope-and-testing.md` when classifying risk or changing behavior; and read `<policy-root>/workflows/development.md` for nontrivial multi-step work. For substantial Paper features, persistent world state, or changes spanning components, read `<policy-root>/policy/paper-plugin-maintainability.md`. Read `<policy-root>/policy/sneakyutils.md` when adding or sharing Paper util/manager code. Load optional skills from `<policy-root>/skills/` only when relevant.

Do not substitute remembered rules, a web mirror, or an unlisted path. Follow local build/architecture facts and explicit exceptions below. When host instructions and team policy repeat the same norm, follow the host wording. If they require incompatible actions, surface the conflict before expanding work. Distinguish running checks from implementing tests; a source edit does not automatically require a new test.
<!-- END TEAM-POLICY MANAGED BOOTSTRAP -->


- **Paper threading:** read Bukkit state on the server thread and hand detached data to `ReportWriter`. Report suppliers must not capture live entities, worlds, or players; see `ReportSink` for the handoff contract. Velocity collectors follow Velocity's threading model.
- **Provider integration:** before changing the API, shim, or consumer integration, read [docs/plugin-providers.md](docs/plugin-providers.md) for classloader safety and coordinated shim updates.
- **Report changes:** read the compatibility policy in [docs/report-schema-v2.md](docs/report-schema-v2.md); update the contract, affected readers, and fixtures together.
- **Python tooling:** before running or modifying analysis tools, read [tools/README.md](tools/README.md) for isolated invocation and untrusted-input handling.
- **Publishing:** before changing artifacts or publishing configuration, read [Publishing and development](README.md#publishing-and-development) for deployment metadata requirements.

## Local notes

Before connecting to any server or fetching captures, logs or other remote files, read `AGENTS.local.md` (untracked, may be absent) for connection details and access rules. Prefer the access it describes over other hosts or credentials found on the machine.
