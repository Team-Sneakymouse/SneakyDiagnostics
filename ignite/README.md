# SneakyDiagnostics Ignite companion

Install `SneakyDiagnostics-Ignite-<version>.jar` in Ignite's `mods/` directory alongside the independently installable Paper plugin in `plugins/`. Restart after installing or replacing the companion. Keep one version of each artifact. Both individual scheduler captures and `/diagnostics all` automatically start and stop the probe; scheduler status shows availability.

## Compatibility

The target is Paper 26.2+, with compilation and runtime verification against Paper 26.2 build 116, Java 25, and Ignite 1.2.1 (Mixin 0.8.7, MixinExtras 0.5.3). There is no exact Minecraft/Paper build-number gate. Recompilation is needed when the required internal scheduler contracts change, rather than for every release. Future releases have not been verified merely because their version is newer.

Structural checks require scheduler/task/future constructors, cancellation methods, the runners map, task class access, async delegation into `CraftTask.run`, callable invocation, pending-queue execution of internal tasks, and listener dispatch through `RegisteredListener.callEvent` into `EventExecutor.execute` (Paper API; `PaperEventManager` calls it for every plugin listener). Required injection counts and Mixin validation add checks during transformation. A mismatch fails startup with an incompatibility error instead of silently producing partial measurements. These checks cannot prove unchanged semantics in a future implementation; run the runtime proof when updating Paper or Ignite. Velocity activates no scheduler Mixins. Server/loader classes are compile-only and excluded from the artifact. Mixins do not target versioned CraftBukkit packages or anonymous cancellation-runnable class names.

## Measurements and limits

The scheduler report adds an `ignite` section with groups by event, plugin owner, runnable/consumer/callable class, sync flag, and cancellation caller. Events are `creation`, `execution`, `cancel_task_request`, `cancel_plugin_request`, and `deferred_cancel_scan`. Each group has started/completed/throwing counts, total/max nanoseconds, and first/last observation offsets. Repeated individual cancellation requests are also counted across the capture.

Cancellation wrappers propagate the request's owner/body/caller through an internal queued task, attributing its entire deferred scan when it executes. No anonymous `$2` target is assumed. This measures the main scheduler's deferred scans; separate async scheduler management-executor cancellation scans are not timed by this hook. For cancellation and deferred groups, `sync` describes the target identity; deferred scans themselves execute on the main thread. Unknown targets use an unknown owner/class and a default sync flag, which must not be interpreted as a known sync task.

Execution measures runnable and consumer bodies and the callable invocation, preserving exceptions and return values. Counts describe observed calls, not successfully removed tasks. Timings are inclusive wall time, not CPU time; request, deferred, and execution scopes may overlap and must not be summed. Creation timing covers probe bookkeeping, not registration latency. Cancellation caller is the first external stack frame, not proven plugin ownership. Tasks created before capture may have unknown cancellation identity when no active runner can supply it.

### Slow calls (when, not only how much)

Groups are whole-capture aggregates. To place time on the timeline, the probe also keeps individual slow calls:

- `slowExecutions`: scheduler scopes (execution, cancellation requests, deferred scans; never creation bookkeeping) of at least 1 ms (`limits.slowNanos`). Each record has the group labels, `startOffsetMicros`/`endOffsetMicros` from the shared capture origin, `nanos` (inclusive wall time), `thread`, `throwing` and `endedAfterDeadline`.
- `slowListeners`: plugin event-listener calls of at least 1 ms: owner plugin, listener class, event class, priority, `asyncEvent`, the same timing fields. `observedInvocations` counts every listener call made while recording. Only one executor call is timed; nested events and anything it opens (an inventory's `InventoryOpenEvent` listeners) are inside its time and also recorded on their own when slow.
- `runningAtStop`: up to 32 scheduler scopes begun but not ended when the capture stopped, with their running time so far (`count` is the total). Completion, this set and the slow list change under one lock, so a call racing the stop is reported either as running or as completed, never neither.
- `slowListeners.runningAtStop` / `runningAroundStop`: listener calls begun but not ended at stop (counted, not listed: tracking each call would cost more than the hook itself), and those enclosing the stop on its own thread (normally SneakyDiagnostics' tick-end listener). A call still running on another thread makes the listener list incomplete. `ServerTickEndEvent` listeners run after Paper measured the tick duration.
- `measuredTicks`: for every server tick (current tick), the interval Paper measured as its duration, taken from the tick's first `ServerTickEndEvent` listener call (Paper reads the duration just before that dispatch) minus `getTickDuration()`. Recording it costs one type check per listener call while recording and one short synchronized append per tick; at most capture seconds x 40 entries, with `dropped` counting the rest. It lets readers exclude tick-end work exactly, even when no listener record of it was kept.

`thread` is `server`, `other` or `unknown` (no server, as in the runtime proof). `tick` is present only for `server`: `Bukkit.getCurrentTick()`, the counter shared-probe reports use and the timeline records as `firstCurrentTick/lastCurrentTick`. It is not the timeline's tick number, which stops while the server pauses when empty (see the schema's tick identity section). Async calls overlap ticks and have no tick identity. Each kind keeps its 512 longest calls; a longer call replaces the shortest, `observed` counts every call over the threshold and `evicted` those left out, so `complete` is false when the list is partial. Eviction does not change `igniteComplete`, which describes the aggregates. Fast calls cost a clock read on each side and a comparison (listeners: plus two uncontended counters and a thread check, to count calls still running at stop); labels are read and records allocated only for slow calls. Slow records overlap each other and plugin sections, so sum only their union per thread.

State is bounded to 2,048 groups, 100,000 task identities, 100,000 cancellation IDs, 512 slow scheduler and 512 slow listener calls, with 192-character labels. Overflow is explicit. In-flight scopes at stop are reported without a completed duration; late completions cannot mutate the detached report. Hooks bypass aggregation outside captures, and recording expires at the requested monotonic deadline even during a stalled tick. Hooks still add an idle branch and cancellation-origin field access. Instrumented execution uses synchronized aggregation and adds overhead, especially under concurrent async load.

The server classloader owns all probe state. A local Standard MBean, `com.teamsneakymouse.sneakydiagnostics:type=SchedulerProbe`, exposes protocol version 1, status, start, and stop using only JDK values. The plugin accesses it through the platform MBean server without linking companion classes. No remote JMX connector is opened by this project.

## Verification

```powershell
.\gradlew.bat build
.\gradlew.bat :ignite:test -PcompatibilityServerJar=D:/path/to/versions/26.2/paper-26.2.jar
```

The second command verifies contracts against an extracted server JAR. Use the actual server JAR under `versions/`, not the Paperclip launcher. Contract tests also reject removed methods and changed internal execution paths; probe tests cover caps, attribution, expiration, and report isolation.

For the optional runtime proof, prepare an ignored `.scratch/scheduler-proof/` directory containing `ignite.jar`, a Paper launcher named `paper.jar`, and its matching `cache/`, `versions/`, and `libraries/` directories from an isolated installation. No world, EULA acceptance, server configuration, or listener is needed: the proof uses a custom entrypoint instead of starting Minecraft.

```powershell
.\gradlew.bat :ignite:verifyRuntime
# Or use another prepared directory:
.\gradlew.bat :ignite:verifyRuntime -PruntimeProofDirectory=D:/path/to/proof
```

The task places companion and proof JARs in a dedicated `diagnostics-mods/` folder and uses the freshly built plugin JAR through a separate classloader. Keep that folder reserved for these two JARs. It checks sync/async/consumer/callable execution, checked and unchecked exception preservation (including a listener's `EventException`), individual/plugin/future cancellation, deferred attribution, slow scheduler and listener records, idle behavior, and bridge communication. Warm single-thread loops print indicative idle/recording costs for a task run and a listener call; they exclude creation/cancellation and are not a server TPS benchmark. On the development machine (2026-11-09, repeated runs) they printed 20-25 ns idle / 300-450 ns recording per task run and 6-15 ns idle / 50-60 ns recording per trivial listener call, executor included; the spread between identical runs is noise of an unpinned loop. This proof does not exercise live command routing or a loaded Minecraft workload.
