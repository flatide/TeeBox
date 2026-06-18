# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

ProperTee TeeBox is an HTTP API and admin UI service for remote ProperTee script execution, run management, task monitoring, and script registry. Java 17, Gradle build, uses the built-in `com.sun.net.httpserver` — no frameworks.

## Build & Run Commands

Requires the **sibling repo `../propertee-java`** to be checked out: `propertee-core` is resolved through a Gradle **composite build**, not from a Maven repo (`settings.gradle` declares `includeBuild('../propertee-java')` with a `dependencySubstitution` mapping `com.flatide:propertee-core` → `project(':propertee-core')`). The `0.7.0` Maven coordinate in `build.gradle` is only a substitution key; the code is built from `../propertee-java/propertee-core`.

```bash
# Build (compile + test). Gradle 9.3.1 wrapper.
./gradlew build

# Run tests (JUnit 4)
./gradlew test

# Run a single test class
./gradlew test --tests "com.flatide.tests.TeeBoxServerTest"

# Run a single test method
./gradlew test --tests "com.flatide.tests.TeeBoxServerTest.testMethodName"

# Run dev server (only dataDir is required)
./gradlew run -Dpropertee.teebox.dataDir=/tmp/propertee-teebox-data

# Build deployable fat JAR + distribution ZIP
./gradlew teeBoxZip
```

The only entry point / `mainClass` is **`com.flatide.teebox.TeeBoxMain`**. NOTE: `AGENTS.md` and `README.md` still reference a `TeeBoxUpstreamMockMain` class and a `./gradlew runTeeBoxUpstream` task — both have been **removed** and no longer exist (no such class in `src`, no such Gradle task). Treat those doc references as stale.

### Distribution Tasks (Gradle group `distribution`)

| Task | Produces |
|------|----------|
| `teeBoxJar` | fat jar `propertee-teebox.jar` (Main-Class `TeeBoxMain`) |
| `teeBoxDist` | `build/teebox-dist/` (lib/jar + conf + bin + log4j2.xml) |
| `teeBoxZip` | `build/distributions/propertee-teebox-dist.zip` (also copied to `./dist`) |
| `fetchRuntimeLinuxX64` | downloads + SHA-256-verifies + unpacks **OpenJDK 21** linux-x64 to `build/runtime-linux-x64` |
| `teeBoxDistWithRuntime` / `teeBoxZipWithRuntime` | dist bundle including the Linux Java 21 runtime under `runtime/` |
| `clientJar` | `build/libs/teebox-client-<version>.jar` — the embeddable zero-dependency client packaged as a jar. **Java 7 bytecode (major 51), loads on Java 7+.** Compiled by `compileClientJava7` via a **JDK 8 toolchain** (JDK 17+ alone cannot emit bytecode 7); Gradle auto-detects an installed JDK 8 (point it at one with `org.gradle.java.installations.paths` if needed). The general `build`/`check` does **not** need JDK 8 — that path uses `compileStandaloneClient` (`--release 8` on the JDK 21 build). |
| `clientSourcesJar` | `build/libs/teebox-client-<version>-sources.jar` (IDE source attachment) |

**Java targets:** code compiles to Java **17** (`source/targetCompatibility = 17`), but the bundled/recommended deploy runtime is **JDK 21** (fetched by `fetchRuntimeLinuxX64`). The Java 7 bytecode constraint applies only to the *core* repo (`../propertee-java/propertee-core`), not this module.

## Architecture

**Entry point:** `TeeBoxMain` → `RuntimePolicy.requireNonRoot()` → loads `TeeBoxConfig` → sets `propertee.task.baseDir` = `dataDir/tasks` → starts `TeeBoxServer` → shutdown hook.

**Core flow:** HTTP request → `TeeBoxServer` (routing + auth) → `RunManager` (coordination) → `ScriptExecutor` (ProperTee interpreter wrapper) → `TaskRunner` chain (see Task Execution).

### Key Classes

- **`TeeBoxServer`** — Routes requests across top-level contexts: `/api` (3 namespaces, each Bearer-token auth), `/admin` (HTML UI with its own session login), `/health` (unauthenticated), and `/` (302 → `/admin`). The embedded `com.sun.net.httpserver` instance runs on a cached thread pool; `stop()` drains it with a 5s await.
- **`AdminSessionManager`** — Cookie/session login for the `/admin` HTML UI (see Authentication). Entirely separate from the API Bearer tokens.
- **`RunManager`** — Central coordinator. See Run Management.
- **`ManagedTaskEngine`** — Implements core `TaskRunner`; control-plane layer over platform process execution. See Task Execution.
- **`ScriptRegistry`** — Version-controlled script store in `dataDir/script-registry/`. Validates IDs/versions against `[A-Za-z0-9._-]+`, parses syntax via `ScriptParser.parse`, computes SHA-256 per version. **Versions are optional on register/add-version: blank ⇒ the registry auto-assigns the next sequential integer label (`"1"`, `"2"`, … = highest numeric version + 1); an explicit label (incl. legacy `"v1"`) is still honored and coexists.** Version-less *runs* resolve the **active** version (the one promoted via `activate` / the admin UI), **not** the newest — so a freshly added version isn't served until activated (enables staging/rollback). Per-script execution settings (`maxConcurrentRuns`, `immediate`) and per-version `outputRules` for output capture. Soft-delete: DELETE sets `deletedAt`; background maintenance purges after `scriptRetentionMs` (default 7d); `restoreScript` clears `deletedAt`. `updateVersionContent` treats `outputRules` as tri-state (null = keep, empty = clear, non-empty = replace).
- **`ScriptExecutor`** — Stateless. Receives a `PlatformProvider` (`TeeBoxPlatformProvider`, carries `dataDir`) and a `TaskRunner` interface (not a concrete type). Parses script → builds builtins → runs `Scheduler` → returns `ExecutionResult`.
- **`RunRegistry`** — In-memory `ConcurrentHashMap` cache backed by `RunStore`, with stdout/stderr ring buffers capped at 200 lines (`MAX_LOG_LINES`). Retention tiers (see Run Management).
- **`RunStore`** — File-based persistence (`dataDir/runs/`). Atomic writes via temp file + rename; all public methods `synchronized`.
- **`AdminPageRenderer`** — Server-rendered HTML via string concatenation. Has a read-only mode (`isReadOnly()`) that hides mutating buttons when the admin UI is not logged in.
- **`SystemInfoCollector` / `SystemInfo`** — Back `GET /api/admin/system`: JVM/OS, heap/non-heap, dataDir disk space, and on-disk sizes of runs/tasks/script-registry (directory walk capped at 10000 files). Constructed only when a `TeeBoxConfig` is supplied.

## Authentication (two independent systems)

TeeBox has **two unrelated auth mechanisms**:

1. **API Bearer tokens** (for `/api/*`). Each namespace resolves its own token, falling back to the shared `apiToken`:
   - `/api/client` → `clientApiToken` → `apiToken`
   - `/api/publisher` → `publisherApiToken` → `apiToken`
   - `/api/admin` → `adminApiToken` → `apiToken`

   Check is exact `Authorization: Bearer <token>`. **If the resolved token (and the `apiToken` fallback) is null/empty, that namespace is unauthenticated** (`isAuthorized` returns true).

2. **Admin UI session login** (for the `/admin` HTML UI) — `AdminSessionManager`. Form-based username/password against config `adminUser`/`adminPassword`. `POST /admin/login` validates and sets an `HttpOnly`, `Path=/admin`, 8h `Max-Age` cookie `teebox-session=<32-byte hex>`; sessions are in-memory (`ConcurrentHashMap`, 8h expiry, reaped by a periodic maintenance task). `POST /admin/logout` clears it. **Login is enforced only when BOTH `adminUser` and `adminPassword` are set; otherwise the UI is fully open.** When enforced, login gates **only POST (mutating) admin-UI actions** — all GET pages stay viewable in read-only mode (`AdminPageRenderer.isReadOnly()`). This does NOT use `adminApiToken`.

**Open-by-default posture:** an unset namespace token (with no `apiToken` fallback) leaves that API namespace open; unset admin credentials leave the UI open. Security-relevant default.

## 3 API Namespaces

- **Client** (`/api/client`) — Run submission & polling
- **Publisher** (`/api/publisher`) — Script registration, version activation, execution settings, soft-delete/restore
- **Admin** (`/api/admin`) — System inspection, run/task detail, kill operations

`swagger.yaml` (OpenAPI 3.0) is informative but **partially stale**: it still lists the removed `POST /api/client/runs` and documents no health endpoint, though both `/health` and `/api/admin/health` exist.

### `/health` (top-level, unauthenticated)

`GET /health` is its own context (not under `/api`, so auth never runs), GET-only, returns `HealthStatus` (503 when unhealthy) — for load balancers/monitors. `GET /api/admin/health` returns the same payload but requires the admin token.

### Client API Endpoints (`/api/client`)

| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/client/scripts/{scriptId}/runs` | Submit a registered script |
| GET | `/api/client/scripts/{scriptId}/runs` | List runs for a script |
| GET | `/api/client/runs` | List runs (status/offset/limit) |
| GET | `/api/client/runs/{runId}` | Run summary |
| GET | `/api/client/runs/{runId}/status` | Run status only |
| GET | `/api/client/runs/{runId}/result` | Run result data |
| GET | `/api/client/runs/{runId}/tasks-summary` | Task status counts |

The legacy `POST /api/client/runs` (scriptPath-based) endpoint has been removed from the server.

### Publisher API Endpoints (`/api/publisher`)

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/publisher/scripts` | List all scripts |
| POST | `/api/publisher/scripts` | Register script version (version optional → auto-increment; optional outputRules) |
| GET | `/api/publisher/scripts/{id}` | Get script detail |
| GET | `/api/publisher/scripts/{id}/content` | Get a version's source (`?version=`; default active) → `{scriptId, version, content}` |
| POST | `/api/publisher/scripts/{id}/versions` | Add version (version optional → auto-increment) |
| POST | `/api/publisher/scripts/{id}/activate` | Activate version |
| PUT | `/api/publisher/scripts/{id}/settings` | Update execution settings (maxConcurrentRuns, immediate) |
| DELETE | `/api/publisher/scripts/{id}` | Delete script (soft-delete) |
| POST | `/api/publisher/scripts/{id}/restore` | Restore a soft-deleted script |

### Admin API Endpoints (`/api/admin`)

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/admin/health` | Server health status (admin-token-gated) |
| GET | `/api/admin/system` | System info |
| GET | `/api/admin/drain-status` | Current drain status with counts |
| POST | `/api/admin/shutdown` | Initiate graceful shutdown (body: `{"maxWaitMs": 300000}`) |
| GET | `/api/admin/runs` | List runs with filters |
| GET | `/api/admin/runs/{runId}` | Run detail |
| GET | `/api/admin/runs/{runId}/threads` | List threads for a run |
| GET | `/api/admin/runs/{runId}/tasks` | List tasks for a run |
| POST | `/api/admin/runs/{runId}/kill-tasks` | Kill all tasks for a run |
| GET | `/api/admin/tasks` | List tasks |
| GET | `/api/admin/tasks/{taskId}` | Task detail |
| POST | `/api/admin/tasks/{taskId}/kill` | Kill task |

### Admin UI routes (`/admin/*`, server-rendered HTML)

A parallel control surface to the JSON APIs: `GET /admin`, `/admin/scripts`, `/admin/runs`, `/admin/scripts/{id}`, `/admin/runs/{id}`, `/admin/tasks/{id}`; `POST /admin/submit`, `/admin/scripts/register`, `/admin/scripts/update-source`, `/admin/scripts/settings/{id}`, `/admin/scripts/activate/{id}`, `/admin/scripts/delete/{id}`, `/admin/scripts/restore/{id}`, `/admin/shutdown`, `/admin/runs/{id}/kill-tasks`, `/admin/tasks/{id}/kill`; `GET /admin/login`, `POST /admin/login`, `POST /admin/logout`; AJAX fragments `GET /admin/fragments/{dashboard-runs|dashboard-sysinfo|nav-counts|all-runs|run-detail/{id}|task-detail/{id}}`.

The script detail page (`/admin/scripts/{id}`) offers three version operations: **Add New Version** (a form posting to `/admin/scripts/register` with the scriptId prefilled — blank version auto-increments; "Set active immediately" optional, default off for staging), **Set active** (per-version, posts to `/admin/scripts/activate/{id}`), and **edit the active version's source** in place (`/admin/scripts/update-source`). `/admin/scripts/register` thus serves both first-time registration (from the scripts list) and adding a version to an existing script — same `registerScriptVersion` path as the Publisher API's register + add-version endpoints.

## Run Management (`RunManager` / `RunRegistry`)

`RunManager` resolves a `RunRequest` via the script registry, then submits to one of two executors: a fixed-size `ThreadPoolExecutor` (`runExecutor`, sized by global `maxRuns`) for normal scripts, or an unbounded cached `immediateExecutor` for scripts with `immediate=true` (bypasses the global queue). Per-script `maxConcurrentRuns` is enforced **independently of** the executor choice: over-limit runs are marked `PENDING` and parked in a per-script `ConcurrentLinkedQueue`; on each run's completion `dequeueNextRun` re-marks a pending run `QUEUED` and resubmits it, transferring the slot. Idle/purged per-script tracking entries are reaped during maintenance to bound map growth.

Background work runs on a single-thread `ScheduledExecutorService`:
- **Flush task — every 2s (`FLUSH_INTERVAL_MS`, hardcoded):** persists dirty runs and scans output watchers (`RunInfo.published`).
- **Maintenance task — default 60s, configurable via `propertee.teebox.maintenanceIntervalMs`:** run retention (archive/purge), task archival, soft-deleted script purge + concurrency-map cleanup, plus any `addMaintenanceTask` hooks (an extension point for other components).

Graceful shutdown (drain mode): `startDraining(maxWaitMs)` rejects new runs, waits for active+queued+pending to reach zero, then `System.exit(0)` (forced exit on timeout). `getQueuedCount()`/health aggregate three sources: `runExecutor` queue + `immediateExecutor` queue + per-script pending runs.

Prefer bounded tail reads (`getTaskStdoutTail`/`getTaskStderrTail`) over full stdout/stderr to avoid OOM on large task output.

**`RunRegistry` retention tiers** (windows configurable via `propertee.teebox.runRetentionMs` / `runArchiveRetentionMs`):
- **active** (terminal age < 24h): full data retained.
- **archived** (24h–7d): logs trimmed to the last 50 stdout / 20 stderr lines; `threads` emptied, `resultData` nulled (a 300-char `resultSummary` kept), `properties` cleared; `published` retained. This is line-count truncation + dropping heap-heavy fields — **NOT** log compression.
- **purged** (> 7d): removed from cache and disk.

On startup, any persisted run still in a non-terminal state is recovered as `SERVER_RESTARTED` (with `endedAt` + "Server restarted before run finished"), since it cannot be resumed.

### Run Status

`RunInfo.status` values (terminal = COMPLETED / FAILED / SERVER_RESTARTED):
- `QUEUED` — waiting in the global thread pool queue
- `PENDING` — blocked by per-script concurrency limit (`maxConcurrentRuns`)
- `RUNNING` — script is executing
- `COMPLETED` — finished successfully
- `FAILED` — terminated with an error
- `SERVER_RESTARTED` — run was active when the server restarted and could not be resumed

## Task Execution Subsystem

**Runner delegation chain (runtime):**
`ScriptExecutor` receives a `TaskRunner` → `OutputWatchingTaskRunner` (only when the script defines `outputRules`; otherwise `ManagedTaskEngine` is used directly) → `ManagedTaskEngine` → `UnixTaskRunner` (Linux/macOS) **or** `SimulatedTaskRunner` (Windows).

Runner selection is **by platform, not config** (`ManagedTaskEngine.createRunner()` checks `os.name`). There is no `DefaultTaskRunner` — propertee-core ships only `SimpleTaskRunner`/`UnsupportedTaskRunner`, neither of which TeeBox uses.

- **`ManagedTaskEngine`** — Implements core `TaskRunner`. Control-plane layer adding disk persistence (`tasks/task-<id>/meta.json`, `archive.json`, plus stdout/stderr/exitcode/pid files), a sorted `index.json`, archival, multi-instance ownership, per-task locking (`withTaskLock` over a per-`taskId` monitor), and querying. Wraps a platform runner, **not** a core runner. `TeeBoxTaskInfo` extends core `TaskInfo` with `phase` and `lossReason`.
- **`UnixTaskRunner`** — Launches detached `/bin/sh` scripts, preferring `setsid` so each task is its own process-group leader (`pgid == pid`); a wrapper script captures `$?` to an exit-code file. Used on Linux (deploy target) and macOS (dev).
- **`SimulatedTaskRunner`** — Windows-only; launches no real process, writes a placeholder stdout and schedules synthetic COMPLETED after ~250ms. Dev/UI testing only.

### Task lifecycle model (`com.flatide.teebox.lifecycle`)

The **authoritative** task state; core `Task.status` is *derived* from it (`syncStatusFromLifecycle`).

`TaskLifecycle` is a 3-axis state machine plus a `persisted` flag:
- **phase** — `ACTIVE` / `TERMINAL`
- **terminalState** — `COMPLETED` / `FAILED` / `KILLED` / `LOST`
- **lossReason** — `PROCESS_MISSING` / `PID_REUSED`

Invariants: ACTIVE has no terminalState; TERMINAL requires one; `lossReason` ⇒ LOST; terminal is monotonic. **kill-wins**: `KILLED` may override another terminal state, but only *before* it is persisted (`markPersisted()` locks it). **first-terminal-wins** among COMPLETED/FAILED/LOST. `deriveLegacyStatus()` maps lifecycle → legacy status; lifecycle is serialized into meta/archive JSON and reloaded on startup (`normalizeFromRunner` migrates legacy tasks). Archived tasks' lifecycle is stripped from memory and lazily re-read from `archive.json`.

### Restart recovery & ownership (Java 17 ProcessHandle)

`execute()` records the process `startInstant` as `pidStartTime`. On `init()`, transient tasks are re-evaluated: alive + matching start time (1000ms tolerance) ⇒ stays RUNNING; alive but mismatched ⇒ `LOST(PID_REUSED)`; dead ⇒ finalized from the exit-code file or `LOST(PROCESS_MISSING)`.

### Kill / orphan handling

PGID group-kill is used **only** when the task owns its group (`pgid == pid`, i.e. setsid-launched), so unrelated processes (including the TeeBox JVM's own group) are never signaled. Otherwise it kills descendants first, then the parent, to avoid re-parenting orphans (`UnixTaskRunner.terminateTask`, `ManagedTaskEngine.terminateRestoredTask`).

### `releaseTask(String)`

Part of the core `TaskRunner` interface (lets a runner free per-task memory). TeeBox makes it a deliberate **no-op** on all three implementations because the admin UI/API must inspect task history. Memory is reclaimed instead by retention-driven `archiveTask()` (after `propertee.task.retentionMs`, default 24h) and `deleteArchivedTask()` (after `propertee.task.archiveRetentionMs`, default 7d), which evict the runner's in-memory task plus lifecycle/lock maps.

### Output capture

When a script defines `outputRules`, `OutputWatchingTaskRunner` registers a `TaskOutputWatcher` for the run's **first** task (AtomicBoolean CAS, parallel-safe). The watcher incrementally tails the task's `stdout.log`/`stderr.log`, applies compiled regex `OutputPublishRule`s (`stream` default `stdout`, `pattern`, `captureGroup` default 1, `publishKey`, `firstOnly` default true), and publishes captured values to `RunInfo.published`. Watchers are force-flushed on run completion and removed once all rules match or the task dies.

## Security / Host Policy

- **`RuntimePolicy.requireNonRoot()`** — `TeeBoxMain` refuses to start as root (uid 0) on non-Windows hosts (shells out to `id -u`). A hard startup precondition.
- **`CommandGuard`** — validates every task command in `ManagedTaskEngine.execute()` before launch. Blocks privilege-escalation/destructive commands (`sudo`, `su`, `shutdown`, `reboot`, `poweroff`, `halt`, `init`, `mkfs.*`), `rm -rf` of system roots / `~` / `$HOME`, `dd of=/dev/*`, and control-char/newline injection; recurses into `sh/bash -c` payloads. Violations throw `CommandGuardException` (logged as `AUDIT BLOCKED`; allowed commands logged `AUDIT ALLOWED`).
- **Denied env vars** — task env containing `LD_PRELOAD`, `LD_LIBRARY_PATH`, or any `DYLD_*` is rejected (`validateEnv`).

## Logging

Log4j2 (`log4j-api`/`log4j-core` 2.24.3). All logging goes through the `TeeBoxLog` static facade (the component string is the logger name; `AUDIT` is the logger for command allow/block decisions). Config in `src/main/resources/log4j2.xml`: console (SYSTEM_ERR) + rolling file `teebox.log` under `${propertee.teebox.logDir}` (default `logs`), 50MB/daily rotation, gzipped, 30 kept. Distribution bundles `deploy/teebox/log4j2.xml`.

## Configuration

Settings load order: system properties (`-D...`, highest) → config file (`--config` / `-c` path) → defaults.

`TeeBoxConfig` properties (prefix `propertee.teebox.`):

| Property | Default | Notes |
|----------|---------|-------|
| `bind` | `127.0.0.1` | bind address |
| `port` | `18080` | |
| `dataDir` | — | **REQUIRED** (the only required setting); holds `runs/`, `tasks/`, `script-registry/` |
| `maxRuns` | `64` | global thread-pool size for all scripts combined |
| `apiToken` | — | fallback for the three namespace tokens |
| `clientApiToken` / `publisherApiToken` / `adminApiToken` | — | each falls back to `apiToken` |
| `adminUser` / `adminPassword` | — | admin UI session login (both required to enforce) |

There is **no `scriptsRoot`** setting — `TeeBoxConfig` has no such field and never reads it. Scripts live in the registry under `dataDir/script-registry/`. Any `-Dpropertee.teebox.scriptsRoot=...` is silently ignored.

**Duration / retention knobs** are read **only as system properties** (not from the config file, not surfaced on `TeeBoxConfig`), parsed by `DurationParser` (accepts a bare number = ms, or suffixes `ms` / `s` / `m` / `h` / `d`):

- `propertee.teebox.runRetentionMs` (default 24h) — active→archive cutoff
- `propertee.teebox.runArchiveRetentionMs` (default 7d) — archive→purge cutoff
- `propertee.teebox.maintenanceIntervalMs` (default 60s) — maintenance scheduler period
- `propertee.teebox.scriptRetentionMs` (default 7d) — soft-deleted script purge age
- `propertee.task.retentionMs` / `propertee.task.archiveRetentionMs` — core task retention (read by `ManagedTaskEngine`, defaults 24h / 7d)
- `propertee.teebox.logDir` — log4j2 output dir (default `logs`)

`TeeBoxMain` sets `propertee.task.baseDir` = `dataDir/tasks` at startup.

## Dependencies

- `com.flatide:propertee-core` (version 0.7.0) — supplied via **composite build** from `../propertee-java` (dependency substitution; not fetched from Maven). Provides `ScriptParser`, builtins, `Scheduler`, `TaskRunner`.
- `com.google.code.gson:gson:2.11.0` — JSON serialization
- `org.apache.logging.log4j:log4j-api` / `log4j-core` 2.24.3 — logging
- `junit:junit:4.13.2` — tests

## Testing

Tests live in `src/test/java/com/flatide/tests/`. Integration tests (`TeeBoxServerTest`) start a live server with temp directories.

| Test class | Coverage |
|------------|----------|
| `TeeBoxServerTest` | Live-server integration: auth, kill, results, run lifecycle |
| `TeeBoxConfigTest` | Property loading and token fallback |
| `CommandGuardTest` | Shell command allow/block guard |
| `RuntimePolicyTest` | Root-UID blocking / uid parsing |
| `TaskLifecycleTest` | Task state-machine transitions |
| `ManagedTaskEngineTest` | Kill-after-restart, disk recovery of running tasks |
| `DurationParserTest` | Duration suffix parsing |
| `SimulatedTaskRunnerTest` | Simulated (Windows) runner behavior |

## Run Submission

Runs are submitted exclusively via the script registry: `POST /api/client/scripts/{scriptId}/runs`. The legacy `POST /api/client/runs` (scriptPath-based) endpoint has been removed. All scripts must be registered via the Publisher API first.

## Execution Model (async submit, blocking run, blocking SHELL)

Three distinct layers, often confused:

1. **API submit — fully asynchronous.** `POST /api/client/scripts/{scriptId}/runs` enqueues the run and returns **`202 Accepted`** immediately with a run summary in status `QUEUED` (or `PENDING` if the per-script `maxConcurrentRuns` limit is hit). The script has **not executed yet**. There is **no synchronous "run-and-wait" endpoint** — clients poll `GET .../runs/{runId}/status` (status only) or `.../runs/{runId}/result` (result data). Actual execution happens on a background pool (`runExecutor`, or `immediateExecutor` when `immediate=true`). For a blocking convenience, `TeeBoxClient.runAndWait(...)` (and the lower-level `waitForRunTerminal(...)`) submit then **client-side-poll** to a terminal state (`COMPLETED` / `FAILED` / `SERVER_RESTARTED`, 50ms→1s backoff). Deliberately client-side, not a server endpoint: the server stays async so a client timeout/dropped connection never aborts the run — just re-poll the same `runId`. Best for short / `immediate` scripts.

2. **The run (background job) — blocks until the whole script finishes.** The pool thread calls `ScriptExecutor.execute` → `Scheduler.run(mainStepper)`, which returns only when the script *and all of its threads* complete. Status transitions `QUEUED → RUNNING → COMPLETED / FAILED`; result/output are available only after completion.
   - **Threads (`thread` / `multi … monitor`)** run concurrently under the core `Scheduler` (cooperative scheduling + async builtins). A `multi … monitor N` block **awaits all its threads** (collecting results into `result.<name>.value`, firing a monitor callback every `N` ms). The run reaches `COMPLETED` only after every thread finishes — threads are intra-run parallelism, never fire-and-forget.

3. **`SHELL(...)` — synchronous to the script, detached at the OS level.** The core `SHELL` builtin (`taskRunner.execute()` then `waitForCompletion(taskId, 0)`) **blocks the calling ProperTee thread until the external process exits**, then returns its captured (heap-capped) stdout/stderr. e.g. `result = SHELL("sleep 300; …")` does not return for 300s.
   - Underneath, the process is launched **detached** (`setsid`, its own process group `pgid==pid`) and tracked on disk as a Task. "Detached" means **process-group isolation** (clean group-kill, no orphan re-parenting) — **not** fire-and-forget; the script still waits.
   - While `SHELL` blocks, the task is killable via the admin UI / `POST /api/admin/tasks/{taskId}/kill`; killing it makes the blocked `SHELL` return.
   - `SHELL` is an **async builtin** in the scheduler, so it blocks only *its own* thread — other threads keep running. To run shells in parallel, wrap each in its own `thread`. `SHELL("cmd", {"timeout": ms})` kills the process on timeout (default: wait indefinitely).

## Embeddable client (`client/`)

`client/com/flatide/teebox/client/TeeBoxClient.java` is a **standalone, zero-dependency, Java 7** client for embedding TeeBox into other (often legacy) programs. It is a **single self-contained source file** — copy it into the host project, **or** depend on the prebuilt jar from the `clientJar` task (`build/libs/teebox-client-<version>.jar`, **Java 7 bytecode** built via a JDK 8 toolchain so it loads on Java 7+; `compileStandaloneClient` + `StandaloneClientIntegrationTest` keep it in the build/test path). It uses only the JDK (`HttpURLConnection` + a tiny built-in JSON parser/writer in the nested `TeeBoxClient.Json`), so it never conflicts with a host's Gson/Jackson. Scope: register/update scripts (`registerScript` / `addScriptVersion` / `activateScriptVersion`; each has an overload that omits the version so the server auto-increments it), list/read scripts (`listScripts` / `getScript` / `getScriptContent`), run (`submitRun`), and track (`getRunStatus` / `getRunResult` / `getRunTasksSummary` / `waitForRunTerminal` / `runAndWait` / `waitForPublished`). Register/add-version overloads accept `outputRules` (build them with the static `outputRule(...)` helper) so callers can capture a long job's id from stdout and await it via `waitForPublished(runId, key, timeoutMs)`. No auth by default (internal-network target); set a shared token with `setBearerToken(...)`, or per-namespace tokens with `setClientApiToken(...)` / `setPublisherApiToken(...)` when the operator splits `clientApiToken`/`publisherApiToken` (the client picks the right token per request path). **Distinct from** the in-module `com.flatide.teebox.TeeBoxClient` (Java 17, depends on Gson, used only by the test suite). JDK 17+ cannot emit bytecode 7 (`javac --release 7` is unsupported), so two toolchains cover the source: `compileStandaloneClient` (`--release 8` on the JDK 21 build) is the always-on `check` gate, and `clientJar` → `compileClientJava7` uses a **JDK 8 toolchain** (`-source/-target 7`) to emit the real bytecode-7 artifact. Keep the source free of Java 8+ APIs/syntax (no lambdas/streams/`java.time`).

## Concurrency Model

- `ThreadPoolExecutor` (`runExecutor`) for normal script execution, bounded by the global `maxRuns` config (default 64).
- Separate unbounded cached `immediateExecutor` for scripts with `immediate=true` (bypasses the global queue; per-script `maxConcurrentRuns` still applies).
- Per-script `maxConcurrentRuns` (set via Publisher API) limits concurrent runs for a specific script, independent of the global pool; over-limit runs go `PENDING` and are dequeued on completion.
- Single-thread `ScheduledExecutorService` for background flush (2s) + maintenance (configurable, default 60s).
- `ConcurrentHashMap` for the run cache and session/lifecycle/per-task-lock maps.
- `synchronized` blocks for file I/O in `RunStore` and `ScriptRegistry`; per-`taskId` monitors guard task state transitions in `ManagedTaskEngine`.
- The embedded HTTP server uses a cached thread pool (drained with a 5s await on stop).

## Docs & Demo

- `docs/OPERATIONS-GUIDE.md`, `docs/OPERATIONS-GUIDE.ko.md` (Korean), `docs/API-EXAMPLES.md`. (An untracked Korean "NFC 리더기" note in `docs/` is unrelated to TeeBox.)
- `docs/TEEBOX-CLIENT-GUIDE.ko.md` (Korean) — user manual for the embeddable `client/.../TeeBoxClient.java` (setup, scripts, runs, output capture, full method reference).
- `demo/teebox/` — five `.tee` scripts (`01_basic_run.tee` … `05_registered_sum.tee`) plus README/PLAN. (README.md's reference to a `.pt` file is a README bug; only `.tee` exists.)
