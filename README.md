# TeeBox

TeeBox is the ProperTee execution service module. It exposes an HTTP admin UI and JSON API for submitting ProperTee scripts, tracking runs, monitoring external tasks, and performing manual task control.

## What It Provides

- `/api/client/*` for run submission and result polling
- `/api/publisher/*` for script registration and activation
- `/api/admin/*` for run/task inspection and control
- namespaced `client`, `publisher`, and `admin` JSON APIs
- `/admin` HTML UI for operators
- interactive debug re-runs of finished runs (the shared syntax-highlighted source editor with
  entry-point pause, separate red source-error marker, clickable gutter breakpoints, stepping, and
  a playground-style Output/Variables workbench with clean Run stdout/stderr and eval at
  `/admin/debug/{sessionId}`, on a small dedicated executor; live sessions can
  be resumed from the admin `Debug` page or either
  related Run, and every re-execution of one source Run resets and reuses its single debug Run —
  or admins can Debug the Version Source's current unsaved buffer with its own Debug Props but no
  retained Run/Task history; session source is editable while paused/ended and Restart executes
  that unsaved buffer from entry (locked while running); main and `multi` worker pauses show the current logical thread, the
  full worker lifecycle list, and function returns crossed by the latest step, while monitor
  bodies remain excluded — see `DebugSessionManager`)
- debugger Globals includes the effective `_PROPS` object, including live eval changes
- admin-UI roles: `admin` (full access), `user` (owned scripts/UI Runs, including debugging), and
  read-only `monitor` (ordinary script/Run/Task visibility with Debug fully hidden)
- external task tracking through `ManagedTaskEngine`
- persisted per-run/per-task records with in-memory indexes, archive, and purge support
- ProperTee `import`: module ids map directly to registered `scriptId`s; active or exact numeric
  versions are pinned with their SHA-256 in each run's `imports` metadata
- canonical positive-integer script versions with server-side auto-numbering and a persistent
  high-water mark (nonnumeric labels such as `v1` are rejected)

## Main Components

- [TeeBoxMain.java](/Users/journey/Flatide/propertee-teebox/src/main/java/com/flatide/teebox/TeeBoxMain.java)
  - process entry point
- [TeeBoxServer.java](/Users/journey/Flatide/propertee-teebox/src/main/java/com/flatide/teebox/TeeBoxServer.java)
  - HTTP routing and API/admin handlers
- [RunManager.java](/Users/journey/Flatide/propertee-teebox/src/main/java/com/flatide/teebox/RunManager.java)
  - run lifecycle and task lookup
- [RunRegistry.java](/Users/journey/Flatide/propertee-teebox/src/main/java/com/flatide/teebox/RunRegistry.java)
  - persistent run storage and indexing
- [AdminPageRenderer.java](/Users/journey/Flatide/propertee-teebox/src/main/java/com/flatide/teebox/AdminPageRenderer.java)
  - server-rendered admin UI

## Quick Start

```bash
./gradlew teeBoxZip
./gradlew run \
  -Dpropertee.teebox.dataDir=/tmp/propertee-teebox-data
```

Open `http://127.0.0.1:18080/admin`.

For local development, TeeBox resolves `propertee-core` from the sibling composite build at `../propertee2-java` (the ProperTee v2 reference runtime).

## API Namespaces

- `client`
  - submit runs by `scriptId/version` via `/api/client/scripts/{scriptId}/runs`
  - poll run status and fetch results
- `publisher`
  - register script versions and activate the default version
- `admin`
  - inspect tasks, runs, and threads
  - kill tasks and run-owned tasks

The practical boundary is:
- upstream application servers use `client` and `publisher`
- TeeBox operators use `admin`

## Mock Upstream Harness

`TeeBoxUpstreamMockMain` simulates an upstream service that registers a script, submits a run, waits for completion, and prints the result.

Example:

```bash
./gradlew runTeeBoxUpstream \
  -Dpropertee.teebox.upstream.baseUrl=http://127.0.0.1:18080 \
  -Dpropertee.teebox.upstream.scriptId=calc_sum \
  -Dpropertee.teebox.upstream.version=1 \
  -Dpropertee.teebox.upstream.scriptFile=$PWD/demo/teebox/05_registered_sum.pt \
  -Dpropertee.teebox.upstream.activate=true \
  -Dpropertee.teebox.upstream.propsJson='{"a":40,"b":2}'
```

Useful settings:

- `propertee.teebox.upstream.baseUrl`
- `propertee.teebox.upstream.apiToken`
- `propertee.teebox.upstream.clientApiToken`
- `propertee.teebox.upstream.publisherApiToken`
- `propertee.teebox.upstream.adminApiToken`
- `propertee.teebox.upstream.scriptId`
- `propertee.teebox.upstream.version`
- `propertee.teebox.upstream.scriptFile`
- `propertee.teebox.upstream.propsJson`
- `propertee.teebox.upstream.submit`
- `propertee.teebox.upstream.wait`
- `propertee.teebox.upstream.waitMs`

## GitHub Download

`propertee-teebox-dist.zip` is the recommended GitHub release artifact. It does not include a Java runtime, so deploy targets should install a Linux x86_64 Java 25 runtime separately under `runtime/`. (As of 1.0.0, TeeBox runs on the ProperTee v2 runtime, which requires Java 25.)

```bash
git tag v1.30.0
git push origin v1.30.0
```

If you need a prebundled internal package instead, build `propertee-teebox-dist-with-runtime.zip` locally with:

```bash
./gradlew fetchRuntimeLinuxX64 teeBoxZipWithRuntime
```

## Configuration

Primary settings use the `propertee.teebox.*` prefix:

- `propertee.teebox.bind`
- `propertee.teebox.port`
- `propertee.teebox.dataDir`
- `propertee.teebox.maxRuns`
- `propertee.teebox.apiToken`
- `propertee.teebox.clientApiToken`
- `propertee.teebox.publisherApiToken`
- `propertee.teebox.adminApiToken`
- `propertee.teebox.runRetentionMs`
- `propertee.teebox.runArchiveRetentionMs`
- `propertee.teebox.maintenanceIntervalMs`
- `propertee.teebox.debugMaxSessions` (concurrent debug sessions, default 2)
- `propertee.teebox.debugIdleTimeoutMs` (debug-session idle kill, default 30m)

Duration-style settings accept `ms`, `s`, `m`, `h`, and `d` suffixes. Example: `500ms`, `1m`, `24h`, `7d`.

Token behavior:

- `apiToken`: default fallback for all API namespaces
- `clientApiToken`: overrides `client` routes
- `publisherApiToken`: overrides `publisher` routes
- `adminApiToken`: overrides `admin` routes

## Related Docs

- Deployment bundle: [deploy/teebox/README.md](/Users/journey/Flatide/propertee-teebox/deploy/teebox/README.md)
- Demo scripts: [demo/teebox/README.md](/Users/journey/Flatide/propertee-teebox/demo/teebox/README.md)
- Operations guide: [docs/OPERATIONS-GUIDE.md](/Users/journey/Flatide/propertee-teebox/docs/OPERATIONS-GUIDE.md)
- Korean operations guide: [docs/OPERATIONS-GUIDE.ko.md](/Users/journey/Flatide/propertee-teebox/docs/OPERATIONS-GUIDE.ko.md)
- API curl examples: [docs/API-EXAMPLES.md](/Users/journey/Flatide/propertee-teebox/docs/API-EXAMPLES.md)
