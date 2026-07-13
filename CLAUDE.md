# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

ProperTee TeeBox is an HTTP API and admin UI service for remote ProperTee script execution, run management, task monitoring, and script registry. Built with a **JDK 25 toolchain** (the embedded ProperTee v2 runtime needs virtual threads + `ScopedValue`), Gradle build, uses the built-in `com.sun.net.httpserver` — no frameworks. Only the embeddable client (`client/`) is held to Java 7 bytecode; everything else compiles and runs on Java 25.

## Build & Run Commands

Requires the **sibling repo `../propertee2-java`** (the ProperTee v2 reference runtime) to be checked out: `propertee-core` is resolved through a Gradle **composite build**, not from a Maven repo (`settings.gradle` declares `includeBuild('../propertee2-java')` with a `dependencySubstitution` mapping `com.flatide:propertee-core` → `project(':propertee-core')`). The `0.9.0` Maven coordinate in `build.gradle` is only a substitution key; the code is built from `../propertee2-java/propertee-core` — i.e. **whatever is checked out there** (TeeBox rides the propertee2 working tree; rebuild + run the suite after runtime-side changes). **Minimum engine: propertee2-java 0.13.0** — TeeBox 1.13.0's editor lint calls the `BuiltinFunctions.knownFunctionNames()` host API shipped there (an older sibling checkout fails to compile).

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
| `fetchRuntimeLinuxX64` | downloads + SHA-256-verifies + unpacks **OpenJDK 25.0.2** linux-x64 to `build/runtime-linux-x64` (URL/SHA in `build.gradle`, overridable via `-Dpropertee.teebox.runtimeLinuxX64Url`) |
| `teeBoxDistWithRuntime` / `teeBoxZipWithRuntime` | dist bundle including the Linux Java 25 runtime under `runtime/` |
| `clientJar` | `build/libs/teebox-client-<version>.jar` — the embeddable zero-dependency client packaged as a jar. **Java 7 bytecode (major 51), loads on Java 7+.** Compiled by `compileClientJava7` via a **JDK 8 toolchain** (JDK 17+ alone cannot emit bytecode 7); Gradle auto-detects an installed JDK 8 (point it at one with `org.gradle.java.installations.paths` if needed). The general `build`/`check` does **not** need JDK 8 — that path uses `compileStandaloneClient` (`--release 8` on the JDK 25 build). |
| `clientSourcesJar` | `build/libs/teebox-client-<version>-sources.jar` (IDE source attachment) |
| `distJars` | copies the two standalone jars into `./dist`: `teebox-client-<version>.jar` (client) and `propertee-teebox-<version>.jar` (runnable server fat jar). Sub-tasks `clientJarToDist` / `teeBoxJarToDist`. Needs the JDK 8 toolchain (for `clientJar`). |

**`./dist` holds the committed release artifacts:** `propertee-teebox-<version>-dist.zip` (full bundle, from `teeBoxZip`), `propertee-teebox-<version>.jar` (server fat jar), and `teebox-client-<version>.jar` (embeddable client). The jars are produced/refreshed by `distJars`.

**Java targets:** the whole build (server + tests) uses a **JDK 25 toolchain** (`java { toolchain { languageVersion = 25 } }`) — the embedded core (`../propertee2-java/propertee-core`) needs virtual threads + `ScopedValue`, so the host that embeds it compiles and runs on 25 too. The bundled/recommended deploy runtime is **OpenJDK 25.0.2** (fetched by `fetchRuntimeLinuxX64`). The Java 7 bytecode constraint applies **only** to the embeddable client (`client/`), built by the separate `compileClientJava7` / JDK 8 path. (Older docs saying "Java 17 / JDK 21" are stale — the 1.0.0 switch to the v2 runtime moved everything to 25.)

## Architecture

**Entry point:** `TeeBoxMain` → `RuntimePolicy.requireNonRoot()` → loads `TeeBoxConfig` → sets `propertee.task.baseDir` = `dataDir/tasks` → starts `TeeBoxServer` → shutdown hook.

**Core flow:** HTTP request → `TeeBoxServer` (routing + auth) → `RunManager` (coordination) → `ScriptExecutor` (ProperTee interpreter wrapper) → `TaskRunner` chain (see Task Execution).

### Key Classes

- **`TeeBoxServer`** — Routes requests across top-level contexts: `/api` (3 namespaces, each Bearer-token auth), `/admin` (HTML UI with its own session login), `/health` (unauthenticated), and `/` (302 → `/admin`). The embedded `com.sun.net.httpserver` instance runs on a cached thread pool; `stop()` drains it with a 5s await.
- **`AdminSessionManager` / `UserStore`** — Multi-user cookie/session login for the `/admin` HTML UI (see Authentication). `UserStore` owns the roster + PBKDF2 password hashes under `dataDir/users/`; sessions carry `{username, role}`. Entirely separate from the API Bearer tokens.
- **`RunManager`** — Central coordinator. See Run Management.
- **`ManagedTaskEngine`** — Implements core `TaskRunner`; control-plane layer over platform process execution. See Task Execution.
- **`ScriptRegistry`** — Version-controlled script store in `dataDir/script-registry/`. Validates IDs/versions against `[A-Za-z0-9._-]+`, parses syntax via `ScriptParser.parse`, computes SHA-256 per version. **Versions are optional on register/add-version: blank ⇒ the registry auto-assigns the next sequential integer label (`"1"`, `"2"`, … = highest numeric version + 1); an explicit label (incl. legacy `"v1"`) is still honored and coexists.** Version-less *runs* resolve the **active** version (the one promoted via `activate` / the admin UI), **not** the newest — so a freshly added version isn't served until activated (enables staging/rollback). Per-script execution settings (`maxConcurrentRuns`, `immediate`), an `owner` (UI username of the first registrant, set only at creation; drives admin-UI ownership checks — see Authentication), and per-version `outputRules` for output capture. Soft-delete: DELETE sets `deletedAt`; background maintenance purges after `scriptRetentionMs` (default 7d); `restoreScript` clears `deletedAt`. **`deleteVersion` (1.12.2)** hard-deletes one version (metadata entry + `.tee` file, no restore window); the **active version is protected** (explicit error — set another active first). **`duplicateScript` (1.12.2)** copies everything (all versions' content+metadata, active choice, settings) to a new id — the supported "rename" path (no in-place rename by design: it would break the caller contract and race in-flight runs; run history stays with the source); rejects target collisions and soft-deleted sources; version files are written before `script.json` so an interrupted copy leaves nothing half-registered. `updateVersionContent` treats `outputRules` as tri-state (null = keep, empty = clear, non-empty = replace).
- **`ScriptExecutor`** — Stateless. Receives a `PlatformProvider` (`TeeBoxPlatformProvider`, carries `dataDir`) and a `TaskRunner` interface (not a concrete type). Parses script → builds builtins → runs `Scheduler` → returns `ExecutionResult`.
- **`RunRegistry`** — In-memory `ConcurrentHashMap` cache backed by `RunStore`, with stdout/stderr ring buffers capped at 200 lines (`MAX_LOG_LINES`). **List/count/filter queries are served entirely from this map** (1.14) — it holds every non-purged run, so no disk is touched on the read path; only the returned page is deep-copied. Retention tiers (see Run Management).
- **`RunStore`** — Per-run file persistence (`dataDir/runs/<runId>.json`). Atomic writes via temp file + rename; all public methods `synchronized`. **No on-disk run index** (1.14): pre-1.14 kept a `runs/index.json` rewritten on every state transition (O(retained runs) per write — the old scaling bottleneck); startup recovery scans the directory instead (a corrupt/foreign `.json` — invalid, wrong shape, or a `runId` that doesn't match its filename (path-traversal/shadowing guard: the runId is the write path) — is skipped with a warning, never blocks startup), and a leftover legacy `index.json` is deleted at startup — **startup fails if it can't be deleted**, because a stale index would make a rolled-back pre-1.14 TeeBox permanently hide runs written since.
- **`AdminPageRenderer`** — Server-rendered HTML via string concatenation. Has a read-only mode (`isReadOnly()`) that hides mutating buttons when the admin UI is not logged in.
- **`SystemInfoCollector` / `SystemInfo`** — Back `GET /api/admin/system`: JVM/OS, heap/non-heap, dataDir disk space, and on-disk sizes of runs/tasks/script-registry (directory walk capped at 10000 files, result **TTL-cached 30s** — the dashboard auto-refresh polls every 5s per viewer; memory/uptime/disk-free stay live). Constructed only when a `TeeBoxConfig` is supplied.

## Authentication (two independent systems)

TeeBox has **two unrelated auth mechanisms**:

1. **API Bearer tokens** (for `/api/*`). Each namespace resolves its own token, falling back to the shared `apiToken`:
   - `/api/client` → `clientApiToken` → `apiToken`
   - `/api/publisher` → `publisherApiToken` → `apiToken`
   - `/api/admin` → `adminApiToken` → `apiToken`

   Check is exact `Authorization: Bearer <token>`. **If the resolved token (and the `apiToken` fallback) is null/empty, that namespace is unauthenticated** (`isAuthorized` returns true).

2. **Admin UI multi-user login** (for the `/admin` HTML UI) — `AdminSessionManager` over a `UserStore`. Multiple named users, each with a role (`admin` or `user`), backed by two files under `dataDir/users/`:
   - **`users.json`** — the roster (an array of `{username, role}`), **operator-managed**: admins add/remove users by hand-editing it. Read fresh on every login, so edits apply without a restart. `role` other than `admin` normalizes to `user`.
   - **`credentials.json`** — password hashes, **TeeBox-managed**. A user has no entry until they set a password on **first login** (the password they type is hashed and stored). Hashing is **PBKDF2-HMAC-SHA256** (JDK built-in, per-user random salt, 210k iterations, constant-time verify); the plaintext is never stored.

   `POST /admin/login` validates username+password (or provisions the password on first login) and sets an `HttpOnly`, `Path=/admin`, 8h `Max-Age` cookie `teebox-session=<32-byte hex>`; sessions are in-memory (`ConcurrentHashMap` of token→`Session{username, role, expiry}`, 8h expiry, reaped by a periodic maintenance task). `POST /admin/logout` clears it. **Login is required exactly when a roster exists** (`UserStore.hasRoster()`); with no roster the UI is fully open. Login gates **only POST (mutating) admin-UI actions** — all GET pages stay viewable in read-only mode (`AdminPageRenderer.isReadOnly()`). This does NOT use `adminApiToken`.

   **Bootstrap admin:** on startup `UserStore.seedAdminIfEmpty(adminUser, adminPassword)` seeds the roster with `{adminUser, admin}` when the roster is missing/empty and config `adminUser` is set; if `adminPassword` is also set it becomes that admin's initial hashed credential (legacy single-admin login preserved), otherwise the admin sets it on first login. **Note:** a deployment that set only `adminUser` (no `adminPassword`) was fully open before and now requires that admin to log in (password set on first login).

   **Per-script ownership (regular users):** each `ScriptInfo` carries an `owner` (the UI username that first registered it; `null` for legacy/API-registered scripts). Admins may act on any script; a `user` may only **modify/run/kill-tasks on scripts they own**. Any logged-in user may register a *new* script (becoming its owner). Enforcement is server-side in `TeeBoxServer.AdminHandler` (`canModifyScript`/`canModifyRun`/`canModifyTask` → 403; `/admin/shutdown` is admin-only); `AdminPageRenderer` also hides buttons the viewer can't use (display only — the server is the gate). The `/api/*` namespaces are **unaffected** (token-gated, unrestricted — no ownership checks).

**Open-by-default posture:** an unset namespace token (with no `apiToken` fallback) leaves that API namespace open; no user roster (and no `adminUser` to seed one) leaves the UI open. Security-relevant default.

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
| POST | `/api/client/scripts/{scriptId}/runs` | Submit a registered script. Optional **`X-TeeBox-User` header** = submitter id (nullable; sanitized, ≤128 chars) — recorded as `RunInfo.submittedBy`, shown on the admin Runs pages, and returned as `submittedBy` in run status/summary/result responses. Admin-UI submits record the session username instead. The **caller IP** is also recorded (`RunInfo.submittedFrom`, XFF-aware like the access log) — run detail page "From (IP)" + admin run-detail JSON only, never client-facing responses. Display/audit only, not auth |
| GET | `/api/client/scripts/{scriptId}/runs` | List runs for a script |
| GET | `/api/client/runs` | List runs (status/offset/limit) |
| GET | `/api/client/runs/{runId}` | Run summary |
| GET | `/api/client/runs/{runId}/status` | Run status only |
| GET | `/api/client/runs/{runId}/result` | Run result data (stream descriptors redacted to `{stream,contentType,size}`). Also carries an **additive `result` field** (1.9.0) — the whole run as a ProperTee Result (`{status, ok, value}`, run = "thread #0"): terminal COMPLETED → `{done, ok:true, value}`, FAILED → `{error, ok:false, value:<errorMessage>}`. Client accessor `TeeBoxClient.getRunEnvelope`. |
| GET | `/api/client/runs/{runId}/result-stream` | Stream a `STREAM_FILE` result's bytes (raw file, no buffering; 409 if not a stream result) |
| GET | `/api/client/runs/{runId}/stdout` | Captured run stdout: script `PRINT` output (`lines`) **+ merged external `SHELL` task output** (`taskLines`, default last 200 lines, override `?taskLines=N` (`<=0` = no line cap); `taskLineCount`, `taskLinesTruncated`, `taskCount`, per-task `tasks` breakdown). Existing `lines`/`lineCount` unchanged (backward compatible). |
| GET | `/api/client/runs/{runId}/stderr` | Captured run stderr (same shape: script `lines` + task `taskLines`) |
| GET | `/api/client/runs/{runId}/tasks-summary` | Task status counts |

The legacy `POST /api/client/runs` (scriptPath-based) endpoint has been removed from the server.

**First-class `null` at the serialization boundary:** a script's result may contain the engine's first-class `null` (spec v0.8.0 — `null != {}`), e.g. `return {"coupon": null}` or `return null`. The engine represents it as the fieldless singleton `com.flatide.propertee2.value.JsonNull.NULL`, which plain Gson reflects into `{}` (silently turning `null` into "absence"). `JsonNullGsonAdapter` (a streaming `TypeAdapter` that flips `serializeNulls` on only while writing that one value — so unrelated Java-`null` response fields stay omitted, no global `serializeNulls`) fixes this and is registered on every Gson that serializes a run result value tree (`TeeBoxServer.gson` for the client-result + admin-RunInfo responses; `RunStore`'s persistence Gson; the webhook Gson defensively). **Any new Gson that serializes `resultData`/a run value must register it.** The **disk round-trip** is covered on the load side (1.10.1): `RunStore.parseRun` re-parses the `resultData` subtree with the engine's own `value/JsonParser` instead of Gson's generic Object mapping — restoring `JsonNull.NULL` (a *present* `"resultData": null` key means first-class null; an absent key means a value-less run, since Gson omits Java-null fields) **and** engine number shapes (Gson's Object mapping turns every number into `Double`, so `"n": 1` used to be served as `1.0` after a restart). The served result JSON is byte-identical across a restart (pinned by `resultDataSurvivesAServerRestartByteFaithfully`; value-shape matrix in `RunStoreTest`). Files written before 1.10.x carry `{}` where `null` was — unrecoverable, they load as-is. Separately, `ScriptExecutor` yields `{}` (not Java null) for a script with no top-level `return` and no `result` global, so "absence" is `{}` per "no implicit null".

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
| DELETE | `/api/publisher/scripts/{id}/versions/{version}` | Hard-delete one version (active version → 400; returns updated ScriptInfo) |
| POST | `/api/publisher/scripts/{id}/duplicate` | Copy all versions + active choice + settings to `{"newScriptId"}` → 201 (the supported "rename" path) |

### Admin API Endpoints (`/api/admin`)

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/admin/health` | Server health status (admin-token-gated) |
| GET | `/api/admin/system` | System info |
| GET | `/api/admin/drain-status` | Current drain status with counts |
| POST | `/api/admin/shutdown` | Initiate graceful shutdown (body: `{"maxWaitMs": 300000}`) |
| GET | `/api/admin/runs` | List runs with filters: `status`, `instant` (`exclude`\|`only`; absent = all), `q` (case-insensitive substring on runId or scriptId), `offset`/`limit` |
| GET | `/api/admin/runs/{runId}` | Run detail |
| GET | `/api/admin/runs/{runId}/threads` | List threads for a run |
| GET | `/api/admin/runs/{runId}/tasks` | List tasks for a run |
| POST | `/api/admin/runs/{runId}/kill-tasks` | Kill all tasks for a run |
| GET | `/api/admin/tasks` | List tasks |
| GET | `/api/admin/tasks/{taskId}` | Task detail |
| POST | `/api/admin/tasks/{taskId}/kill` | Kill task |

### Admin UI routes (`/admin/*`, server-rendered HTML)

A parallel control surface to the JSON APIs: `GET /admin`, `/admin/scripts`, `/admin/runs`, `/admin/scripts/{id}` (**optional `?version=` selects which version the source editor targets**, default active), `/admin/runs/{id}`, `/admin/tasks/{id}`; `POST /admin/submit`, `/admin/scripts/validate` (stateless editor pre-check: parse + unknown-builtin lint, JSON result), `/admin/scripts/register`, `/admin/scripts/update-source`, `/admin/scripts/settings/{id}`, `/admin/scripts/activate/{id}`, `/admin/scripts/delete-version/{id}` (version in form; active version protected), `/admin/scripts/duplicate/{id}` (`newScriptId` in form; duplicator becomes owner), `/admin/scripts/delete/{id}`, `/admin/scripts/restore/{id}`, `/admin/shutdown`, `/admin/runs/{id}/kill-tasks`, `/admin/tasks/{id}/kill`; `GET /admin/login`, `POST /admin/login`, `POST /admin/logout`; AJAX fragments `GET /admin/fragments/{dashboard-runs|dashboard-sysinfo|nav-counts|all-runs|run-detail/{id}|task-detail/{id}}`.

**Runs page filters:** the `/admin/runs` list has a Status select, an **Include instant** checkbox (**default unchecked = instant runs hidden** — runs of `immediate=true` scripts tend to be high-frequency and would drown the list; the API-only `instant=only` remains available to API consumers), and a debounced **search** box (case-insensitive substring on script name or run ID). All three are server-side via the `all-runs` fragment's `status`/`instant`/`q` params (same semantics as `/api/admin/runs`). Whether a run is instant is **recorded per run at submit time** (`RunInfo.immediate`, from the script's `immediate` setting); filtering runs in-memory over the registry map (1.14 — no disk index). Instant rows carry an `instant` tag in the table, and the table has a dedicated **By** column showing the submitter identity (`RunInfo.submittedBy` — dash for anonymous; the same shared `renderRunsTableFragment` serves the dashboard). Legacy runs read back as non-instant.

**First registration is metadata-only (1.10.x):** the scripts-list **Register** modal collects only a Script ID and POSTs a **content-less** `/admin/scripts/register`, which creates an **empty script shell** (`RunManager.createScript` → `ScriptRegistry.createScript`: no versions, not active) and redirects to the detail page. No editor / file upload in the modal — you write the code afterward. A content-less register against an *existing* script is a 400 saying `Script already exists: <id>` (1.12.2 — it used to blame missing content). This means **a script can exist with zero versions**, and (see below) **versions never auto-activate except in the one-shot API path** — so activation is always an explicit operator step.

The script detail page (`/admin/scripts/{id}`) drives version management from two places (the standalone "Add New Version" card was removed in 1.8.x):
- **Versions table** — each row has **Set active** (non-active rows → `/admin/scripts/activate/{id}`) and **Edit**, a link to `/admin/scripts/{id}?version={v}` that opens *that* version (active or not) in the source editor; the row being edited shows an `Editing` marker.
- **Version Source card** — the single edit **and** add-version surface. It shows the selected version (requested `?version=`, else active, else latest) in the code editor, **or renders empty for a shell** ("New Version Source — no versions yet", *Save as new version* only). Two submit buttons over one `<textarea name=content>`: **Save** (overwrites the selected version in place via `/admin/scripts/update-source`; the button carries the version in its own `name/value`; hidden on a shell — nothing to overwrite) and **Save as new version** (a `formaction` override to `/admin/scripts/register` with no version ⇒ auto-increment; adjacent *Description* / *Set new version active* apply only here). Only the clicked submit button contributes its `name/value`, so the two never collide — no JS. **Both save paths land back on the version they wrote**: an in-place Save preserves `?version=`, and *Save as new version* redirects to `?version=<assigned label>` (via `ScriptRegistry.registerVersionDetailed`, which reports the auto-assigned label — never inferred from version ordering). This is load-bearing, not cosmetic: the no-`?version=` fallback is the ACTIVE version, and new versions don't auto-activate — before 1.15.1 the save-as-new redirect dropped the param, the editor silently reloaded the OLD active content, and the next Save (whose button targets the displayed version) overwrote the old version with content meant for the new one ("contents swapped" + old source destroyed; pinned by `saveAsNewVersionLandsOnTheNewVersionNotTheOldActive`). The Save button is labeled with its overwrite target (`Save (3)`). A callout warns while there is no active version, and the **Run Script** card is hidden until at least one version exists.

**Auto-activate rule (`ScriptRegistry.registerVersion`):** a version auto-activates only when `activate` is requested **or** the script is created together with its first version *in the same call* (`scriptCreatedNow`). So the Publisher API's one-shot `register` (content included) still yields an immediately-runnable active version, while a version added to a pre-existing script — **including a shell** — never auto-activates. `/admin/scripts/register` thus serves shell creation (content-less), first-time one-shot register, and "save as new version" — same `registerScriptVersion`/`createScript` paths as the Publisher API.

### Admin UI code editor (`propertee-editor.css` / `propertee-editor.js`)

The script-source `<textarea>`s are progressively enhanced into a syntax-highlighting ProperTee code editor **ported verbatim from the ProperTee playground** (`../propertee-js/docs/index.html` — the highlighter and builtin catalog are copied unchanged; only the wiring is new). `AdminPageRenderer` inlines the two assets from `src/main/resources/` at class-load time (`loadResource` → `EDITOR_CSS`/`EDITOR_JS` statics) because **TeeBox serves no static files** and the login gate would block any `/admin` asset request — so all CSS/JS must be inline. The CSS is appended to every page's `<style>`; the heavier JS (`editorScript()`) is emitted only on the script **detail** page (the sole editor surface since the Register modal became metadata-only in 1.10.x), keeping the dashboard and scripts list lean. Any `<textarea data-pt-editor>` is upgraded to a transparent textarea over a `<pre>` syntax overlay + line gutter; `data-pt-panel` also attaches a builtin-function reference panel, resizable against the editor via a drag handle. Layout invariant (learned via regressions — see CHANGELOG 1.7.1/1.8.2): the **textarea is the sole height authority** (gutter + overlay are absolute layers pinned to it, never free-growing), and `.btn` height is normalized across `<button>`/`<a>`/`<span>` so mixed action buttons line up.

**Syntax pre-check (1.13.0):** the version-source form has an outlined **Check syntax** button and intercepts Save/Save-as-new (`syntaxCheckScript()` in `AdminPageRenderer`): both post the editor content to `POST /admin/scripts/validate`, which parses with the exact parser the save paths reject with (`ScriptLint` -> `ScriptParser.parse`) and lints unknown ALL-CAPS calls with a nearest-name suggestion (zero false positives — all-uppercase is reserved for builtins/host, spec v0.12.0). The known-name set is **enumerated from the runtime**, never hardcoded: facade `BuiltinFunctions.knownFunctionNames()` (**engine >= 0.13.0**) + `ScriptExecutor.knownFunctionNames()` adding TeeBox's STREAM_FILE/THUMBNAIL — keep those registrations and names in step. The lint blocks only the UI save; the publisher API stays syntax-only, and if the endpoint is unreachable the save proceeds (server-side save validation is the backstop).

## Run Management (`RunManager` / `RunRegistry`)

`RunManager` resolves a `RunRequest` via the script registry, then submits to one of two executors: a fixed-size `ThreadPoolExecutor` (`runExecutor`, sized by global `maxRuns`) for normal scripts, or an unbounded cached `immediateExecutor` for scripts with `immediate=true` (bypasses the global queue). Per-script `maxConcurrentRuns` is enforced **independently of** the executor choice: over-limit runs are marked `PENDING` and parked in a per-script `ConcurrentLinkedQueue`; on each run's completion `dequeueNextRun` re-marks a pending run `QUEUED` and resubmits it, transferring the slot. Idle/purged per-script tracking entries are reaped during maintenance to bound map growth.

Background work runs on a single-thread `ScheduledExecutorService`:
- **Flush task — every 2s (`FLUSH_INTERVAL_MS`, hardcoded):** persists dirty runs and scans output watchers (`RunInfo.published`).
- **Maintenance task — default 60s, configurable via `propertee.teebox.maintenanceIntervalMs`:** run retention (archive/purge), task archival, soft-deleted script purge + concurrency-map cleanup, plus any `addMaintenanceTask` hooks (an extension point for other components).

Graceful shutdown (drain mode): `startDraining(maxWaitMs)` rejects new runs, waits for active+queued+pending to reach zero, then exits the JVM (forced exit on timeout). The exit goes through an injectable `RunManager.ExitHandler` (production default `System.exit(0)`; hook via `TeeBoxServer.getRunManager().setExitHandler`) — **any test exercising drain MUST inject a no-op**: a real `System.exit(0)` kills the test fork, and Gradle treats the clean exit as success, silently skipping every test scheduled after it (this truncated the suite undetected until 1.10.0 — when claiming "suite green", compare the XML `tests=` counts in `build/test-results` against the `@Test` counts). `getQueuedCount()`/health aggregate three sources: `runExecutor` queue + `immediateExecutor` queue + per-script pending runs.

Prefer bounded tail reads (`getTaskStdoutTail`/`getTaskStderrTail`) over full stdout/stderr to avoid OOM on large task output.

**`RunRegistry` retention tiers** (windows configurable via `propertee.teebox.runRetentionMs` / `runArchiveRetentionMs`):
- **active** (terminal age < 24h): full data retained.
- **archived** (24h–7d): logs trimmed to the last 50 stdout / 20 stderr lines; `threads` emptied, `properties` cleared; `published` retained. **`resultData` is kept intact until purge** (changed post-1.15.0 — it used to be nulled at archive, leaving only the 300-char `resultSummary`): the result is the run's product and stays fetchable/serving for the whole archive window, at the accepted cost of archived results staying heap-resident (large payloads should use `STREAM_FILE`). This is line-count truncation + dropping heap-heavy *diagnostics* — **NOT** log compression.
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

## Webhooks (opt-in run-terminal callbacks) — `com.flatide.teebox.webhook`

A durable "notify me when the run finishes" mechanism, **disabled by default**. A run submission may include a `callback` (a string URL or `{"url": ...}`); on run terminal, `RunManager`'s `WebhookDispatcher.onRunTerminal(run)` enqueues a `WebhookDelivery` to a file-backed outbox (`${dataDir}/webhooks/`, `WebhookStore`) and retries POSTing it with backoff until a 2xx (**DELIVERED**) or the attempt budget is exhausted (**DEAD**). Survives restart: PENDING records resume, and a reconcile re-enqueues any terminal run that has a callback but no delivery record (idempotent, keyed by `runId`). Enabled/validated by config: `webhookEnabled` (default false — `parseCallback` rejects with HTTP 400 when off), `webhookUrlAllowlist` (comma-separated `host[:port]`, **required when enabled**; callback URLs are scheme + allowlist checked), `webhookTimeoutMs` (per-POST connect/read, default 10000). Covered by `WebhookDispatcherTest` / `WebhookServerIntegrationTest`.

## Task Execution Subsystem

**Runner delegation chain (runtime):**
`ScriptExecutor` wraps the received `TaskRunner` in a **`NonClosingTaskRunner`** → `OutputWatchingTaskRunner` (only when the script defines `outputRules`; otherwise `ManagedTaskEngine` is used directly) → `ManagedTaskEngine` → `UnixTaskRunner` (Linux/macOS) **or** `SimulatedTaskRunner` (Windows).

**⚠️ Shared-engine lifecycle invariant (1.12.2):** one `ManagedTaskEngine` is shared by **all concurrent runs**, but the interpreter closes its runner when a run finishes (`BuiltinFunctions.shutdown()` in `ScriptExecutor`'s finally). `NonClosingTaskRunner` makes that per-run shutdown a no-op — without it, any short script completing cleared the shared in-memory task map and every other run's in-flight `SHELL()` failed with `Unknown task` (while the detached process and disk metadata survived, so the UI kept showing RUNNING). The real engine shutdown belongs to `RunManager.shutdown()` at server exit only. Pinned by `ConcurrentRunTaskIsolationTest`; never hand a run a view whose `shutdown()` reaches the shared engine.

Runner selection is **by platform, not config** (`ManagedTaskEngine.createRunner()` checks `os.name`). There is no `DefaultTaskRunner` — propertee-core ships only `SimpleTaskRunner`/`UnsupportedTaskRunner`, neither of which TeeBox uses.

- **`ManagedTaskEngine`** — Implements core `TaskRunner`. Control-plane layer adding disk persistence (`tasks/task-<id>/meta.json`, `archive.json`, plus stdout/stderr/exitcode/pid files), an **in-memory task index** (1.14 — `tasks/index.json` is gone; it was rewritten on every task save. Built from the `init()` recovery scan, kept incremental by saveMeta/archive/delete; a leftover legacy file is deleted at startup, **startup fails if it can't be deleted** — rollback safety, same contract as the run index. The 60s retention sweep picks candidates from entries instead of re-reading every task's JSON, and **refreshes restart-restored transient tasks** — runner-unowned, so nothing else notices their process exiting; without this they'd show "running" forever), archival, multi-instance ownership, per-task locking (`withTaskLock` over a per-`taskId` monitor), and querying. `taskStatusesByRun` serves the admin runs tables' per-run task counts/killed/lost badges from entries alone (no disk). Wraps a platform runner, **not** a core runner. `TeeBoxTaskInfo` extends core `TaskInfo` with `phase` and `lossReason`.
- **`UnixTaskRunner`** — Launches detached `/bin/sh` scripts, preferring `setsid` so each task is its own process-group leader (`pgid == pid`); a wrapper script captures `$?` to an exit-code file. Used on Linux (deploy target) and macOS (dev).
- **`SimulatedTaskRunner`** — Windows-only; launches no real process, writes a placeholder stdout and schedules synthetic COMPLETED after ~250ms. Dev/UI testing only.

### Task lifecycle model (`com.flatide.teebox.lifecycle`)

The **authoritative** task state; core `Task.status` is *derived* from it (`syncStatusFromLifecycle`).

`TaskLifecycle` is a 3-axis state machine plus a `persisted` flag:
- **phase** — `ACTIVE` / `TERMINAL`
- **terminalState** — `COMPLETED` / `FAILED` / `KILLED` / `LOST`
- **lossReason** — `PROCESS_MISSING` / `PID_REUSED`

Invariants: ACTIVE has no terminalState; TERMINAL requires one; `lossReason` ⇒ LOST; terminal is monotonic. **kill-wins**: `KILLED` may override another terminal state, but only *before* it is persisted (`markPersisted()` locks it). **first-terminal-wins** among COMPLETED/FAILED/LOST. `deriveLegacyStatus()` maps lifecycle → legacy status; lifecycle is serialized into meta/archive JSON and reloaded on startup (`normalizeFromRunner` migrates legacy tasks). Archived tasks' lifecycle is stripped from memory and lazily re-read from `archive.json`.

### Restart recovery & ownership (ProcessHandle)

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
- **Outbound HTTP** — the core `HTTP_GET`/`HTTP_POST`/`HTTP` builtins are **available and unrestricted** in TeeBox: `TeeBoxPlatformProvider` extends `DefaultPlatformProvider`, whose `httpRequest` (HttpURLConnection) is inherited as-is. This is a deliberate closed-network default-allow — scripts can reach any URL the host can (an SSRF surface in untrusted-script scenarios). To restrict it, override `httpRequest` in `TeeBoxPlatformProvider` (e.g. host allowlist) — there is currently no allowlist/flag.
- **`STREAM_FILE(path, [contentType])` (host builtin, stream result)** — TeeBox-only builtin registered in `ScriptExecutor` (via `builtins.register`, returns a **raw descriptor object**, not a `Result`). A script returns `STREAM_FILE(path)` instead of reading+`JOIN`+`JSON_PARSE`+returning a large file — the payload never enters the engine heap, the deep-copies, or the buffered JSON response. The descriptor `{"__teebox_stream__":true,"path","contentType","size"}` rides as `resultData` (tiny); `GET .../result-stream` re-validates and streams the file straight to the socket in 64KB chunks (O(1) heap, `Content-Length`=file size). `GET .../result` returns a **redacted** descriptor (no server path). **Path is confined** to allowed roots (`StreamResultSupport`): `propertee.teebox.streamRoots` (a `File.pathSeparator` list), default `[dataDir]` — a path outside fails the script with a clear error (validated at `STREAM_FILE` time and again before streaming, TOCTOU-safe). Lifecycle is **reference-only**: the file must outlive the result fetch (TeeBox does not copy/own it); the descriptor now survives archival like any `resultData` (post-1.15.0 — archive used to null it after 24h), so a stream result stays fetchable until run purge **as long as the referenced file still exists**. Client helpers: `TeeBoxClient.streamRunResult(runId, OutputStream)` (fetch a stream result), and `runAndStream(scriptId, version, props, OutputStream, timeoutMs)` (the one-call convenience for streaming scripts — submit → client-side poll to terminal → stream; the streaming-script analogue of `runAndWait`).
- **`THUMBNAIL(srcPath, destPath, maxWidth, [maxHeight])` (host builtin)** — TeeBox-only image scaler registered in `ScriptExecutor` as a **blocking** builtin (`builtins.registerBlocking`, so the CPU/disk work runs off the cooperative baton via the `Coop.blocking` contract and never stalls concurrent `multi` workers). Scales via `Thumbnailer`/`ImageIO`, preserving aspect ratio (never upscaling), writes a PNG, returns `{path, width, height}` or a `Result.error`. Both paths are confined to the same allowed roots as `STREAM_FILE` (`StreamResultSupport` / `propertee.teebox.streamRoots`), and it is registered only when that allowed-roots policy is present.

## Logging

Log4j2 (`log4j-api`/`log4j-core` 2.24.3). All logging goes through the `TeeBoxLog` static facade (the component string is the logger name; `AUDIT` is the logger for command allow/block decisions). Config in `src/main/resources/log4j2.xml`: console (SYSTEM_ERR) + rolling file `teebox.log` under `${propertee.teebox.logDir}` (default `logs`), 50MB/daily rotation, gzipped, 30 kept. Distribution bundles `deploy/teebox/log4j2.xml`.

**Access log:** a dedicated `access` logger emits one line per request — method, path (+query), client IP (honoring `X-Forwarded-For`), status, elapsed ms (e.g. `GET /api/client/runs?limit=10 from 127.0.0.1 -> 200 (4ms)`). It is scoped to the **`/api` context only** (external/upstream callers) via `accessLogged()` in `registerContexts()`; `/admin`, `/health`, and `/` are unlogged. Bodies are deliberately not logged (tokens/source/payloads). Retune/silence independently with `<Logger name="access" .../>`.

## Configuration

Settings load order: system properties (`-D...`, highest) → config file (`--config` / `-c` path) → defaults.

`TeeBoxConfig` properties (prefix `propertee.teebox.`):

| Property | Default | Notes |
|----------|---------|-------|
| `bind` | `127.0.0.1` | bind address |
| `port` | `18080` | |
| `dataDir` | — | **REQUIRED** (the only required setting); holds `runs/`, `tasks/`, `script-registry/`, `users/` |
| `maxRuns` | `64` | global thread-pool size for all scripts combined |
| `apiToken` | — | fallback for the three namespace tokens |
| `clientApiToken` / `publisherApiToken` / `adminApiToken` | — | each falls back to `apiToken` |
| `adminUser` / `adminPassword` | — | **bootstrap** admin UI user: seeds `users.json` with `{adminUser, admin}` when the roster is empty (`adminPassword`, if set, becomes the initial hashed credential). Multi-user login is roster-driven (`dataDir/users/`); see Authentication |
| `webhookEnabled` | `false` | opt-in run-terminal callbacks (see Webhooks) |
| `webhookUrlAllowlist` | — | comma-separated `host[:port]` allowlist, **required when `webhookEnabled`** |
| `webhookTimeoutMs` | `10000` | per-POST connect/read timeout for webhook delivery |

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

- `com.flatide:propertee-core` (version 0.9.0 — a substitution key only) — supplied via **composite build** from `../propertee2-java` (dependency substitution; not fetched from Maven). Provides `ScriptParser`, builtins, `Scheduler`, `TaskRunner`. **Requires >= 0.13.0** (`BuiltinFunctions.knownFunctionNames()` for the editor's unknown-builtin lint, since TeeBox 1.13.0).
- `com.google.code.gson:gson:2.11.0` — JSON serialization
- `org.apache.logging.log4j:log4j-api` / `log4j-core` 2.24.3 — logging
- `junit:junit:4.13.2` — tests

## Testing

Tests live in `src/test/java/com/flatide/tests/` (plus a few in `com/flatide/teebox/`). Many are **live-server integration tests** that start a real `TeeBoxServer` on a temp `dataDir` and drive it over HTTP — including the deployable `client/` source (pulled into the test source set, see `build.gradle`), so integration tests exercise the real client, not a mock. Not exhaustive:

| Test class | Coverage |
|------------|----------|
| `TeeBoxServerTest` | Live-server integration: auth, kill, results, run lifecycle |
| `TeeBoxMultiUserUiTest` | Admin-UI login/ownership, the injected code editor, per-version editing (`?version=`) |
| `StandaloneClientIntegrationTest` | End-to-end through the deployable `client/` `TeeBoxClient` |
| `UserStoreTest` / `AdminSessionManagerTest` | PBKDF2 credentials, roster, cookie sessions |
| `AccessLogTest` | `/api`-scoped access logging |
| `WebhookDispatcherTest` / `WebhookServerIntegrationTest` | Webhook outbox, retry, restart reconcile |
| `StreamResultTest` / `ThumbnailBuiltinIntegrationTest` | `STREAM_FILE` / `THUMBNAIL` host builtins |
| `CommandGuardTest` / `RuntimePolicyTest` | Shell allow/block guard; root-UID blocking |
| `TaskLifecycleTest` / `ManagedTaskEngineTest` | Task state machine; kill-after-restart, disk recovery |
| `DurationParserTest` / `SimulatedTaskRunnerTest` / `TeeBoxConfigTest` | Duration parsing; Windows runner; config/token fallback |

## Run Submission

Runs are submitted exclusively via the script registry: `POST /api/client/scripts/{scriptId}/runs`. The legacy `POST /api/client/runs` (scriptPath-based) endpoint has been removed. All scripts must be registered via the Publisher API first.

**`_SYS` system variables** — for each run, `ScriptExecutor` injects a reserved global object `_SYS = {runId, scriptId, version}` so a script can read its own TeeBox run id (e.g. `_SYS.runId`, or `::_SYS.runId` inside a function). It is injected as a **global variable** (`visitor.variables`), **not** into `properties` — so the core's `_PROPS` object stays user-input only (`HAS_KEY(_PROPS, "_SYS")` is false). A user script may shadow `_SYS` by assigning it (it is a global, not a keyword). TeeBox-only (host injection; not a core/language feature).

## Execution Model (async submit, blocking run, blocking SHELL)

Three distinct layers, often confused:

1. **API submit — fully asynchronous.** `POST /api/client/scripts/{scriptId}/runs` enqueues the run and returns **`202 Accepted`** immediately with a run summary in status `QUEUED` (or `PENDING` if the per-script `maxConcurrentRuns` limit is hit). The script has **not executed yet**. There is **no synchronous "run-and-wait" endpoint** — clients poll `GET .../runs/{runId}/status` (status only) or `.../runs/{runId}/result` (result data). Actual execution happens on a background pool (`runExecutor`, or `immediateExecutor` when `immediate=true`). For a blocking convenience, `TeeBoxClient.runAndWait(...)` (and the lower-level `waitForRunTerminal(...)`) submit then **client-side-poll** to a terminal state (`COMPLETED` / `FAILED` / `SERVER_RESTARTED`, 50ms→1s backoff). Deliberately client-side, not a server endpoint: the server stays async so a client timeout/dropped connection never aborts the run — just re-poll the same `runId`. Best for short / `immediate` scripts.

2. **The run (background job) — blocks until the whole script finishes.** The pool thread calls `ScriptExecutor.execute` → `Scheduler.run(mainStepper)`, which returns only when the script *and all of its threads* complete. Status transitions `QUEUED → RUNNING → COMPLETED / FAILED`; result/output are available only after completion.
   - **Threads (`thread` / `multi … monitor`)** run concurrently under the core `Scheduler` (cooperative scheduling + async builtins). A `multi … monitor N` block **awaits all its threads** (collecting results into `result.<name>.value`, firing a monitor callback every `N` ms). The run reaches `COMPLETED` only after every thread finishes — threads are intra-run parallelism, never fire-and-forget.
   - **`SLEEP()` is fully cooperative on the propertee2 core** — wherever it appears (statement, nested `if`/`loop`/function body, mid-expression): the fiber suspends in place and the run's other `multi` workers and `monitor` ticks keep advancing. (The old stepper-era "nested SLEEP falls back to blocking `Thread.sleep`" limitation belonged to the frozen propertee-java v1 core and no longer applies.) See `../propertee2-java/docs/LANGUAGE.md` §Blocking and Suspension.

3. **`SHELL(...)` — synchronous to the script, detached at the OS level.** The core `SHELL` builtin (`taskRunner.execute()` then `waitForCompletion(taskId, 0)`) **blocks the calling ProperTee thread until the external process exits**, then returns its captured (heap-capped) stdout/stderr. e.g. `result = SHELL("sleep 300; …")` does not return for 300s.
   - Underneath, the process is launched **detached** (`setsid`, its own process group `pgid==pid`) and tracked on disk as a Task. "Detached" means **process-group isolation** (clean group-kill, no orphan re-parenting) — **not** fire-and-forget; the script still waits.
   - While `SHELL` blocks, the task is killable via the admin UI / `POST /api/admin/tasks/{taskId}/kill`; killing it makes the blocked `SHELL` return.
   - `SHELL` is an **async builtin** in the scheduler, so it blocks only *its own* thread — other threads keep running. To run shells in parallel, wrap each in its own `thread`. `SHELL("cmd", {"timeout": ms})` kills the process on timeout (default: wait indefinitely).

## Embeddable client (`client/`)

`client/com/flatide/teebox/client/TeeBoxClient.java` is a **standalone, zero-dependency, Java 7** client for embedding TeeBox into other (often legacy) programs. It is a **single self-contained source file** — copy it into the host project, **or** depend on the prebuilt jar from the `clientJar` task (`build/libs/teebox-client-<version>.jar`, **Java 7 bytecode** built via a JDK 8 toolchain so it loads on Java 7+; `compileStandaloneClient` + `StandaloneClientIntegrationTest` keep it in the build/test path). It uses only the JDK (`HttpURLConnection` + a tiny built-in JSON parser/writer in the nested `TeeBoxClient.Json`), so it never conflicts with a host's Gson/Jackson. Scope: register/update scripts (`registerScript` / `addScriptVersion` / `activateScriptVersion`; each has an overload that omits the version so the server auto-increments it), list/read scripts (`listScripts` / `getScript` / `getScriptContent`; `listActiveScripts` / `getActiveScript` are client-side variants that reduce each script's `versions` list to just the active version), run (`submitRun`), and track (`getRunStatus` / `getRunResult` / `getRunTasksSummary` / `getRunStdout` / `getRunStderr` / `getRunStdoutLines` / `getRunStderrLines` / `waitForRunTerminal` / `runAndWait` / `runAndStream` / `streamRunResult` / `waitForPublished`). `getRunStdout`/`getRunStderr` return the run's captured `PRINT` output (the most recent `MAX_LOG_LINES` lines — a server ring buffer; readable while RUNNING and after). Register/add-version overloads accept `outputRules` (build them with the static `outputRule(...)` helper) so callers can capture a long job's id from stdout and await it via `waitForPublished(runId, key, timeoutMs)`. No auth by default (internal-network target); set a shared token with `setBearerToken(...)`, or per-namespace tokens with `setClientApiToken(...)` / `setPublisherApiToken(...)` when the operator splits `clientApiToken`/`publisherApiToken` (the client picks the right token per request path). **Distinct from** the in-module `com.flatide.teebox.TeeBoxClient` (compiled on the Java 25 build, depends on Gson, used only by the test suite). JDK 17+ cannot emit bytecode 7 (`javac --release 7` is unsupported), so two toolchains cover the source: `compileStandaloneClient` (`--release 8` on the JDK 25 build) is the always-on `check` gate, and `clientJar` → `compileClientJava7` uses a **JDK 8 toolchain** (`-source/-target 7`) to emit the real bytecode-7 artifact. Keep the source free of Java 8+ APIs/syntax (no lambdas/streams/`java.time`). The client also gained `getRunEnvelope` (the run-result envelope) alongside the run/track accessors. Run-submitting methods take an optional trailing **`userId`** (`submitRun(scriptId, version, props, callbackUrl, userId)` / `runAndWait(..., timeoutMs, userId)` / `runAndStream(..., timeoutMs, userId)`; null = anonymous) — sent as the `X-TeeBox-User` header and recorded on the run as `submittedBy`.

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
- `docs/TEEBOX-CLIENT-GUIDE.md` (English) / `docs/TEEBOX-CLIENT-GUIDE.ko.md` (Korean) — user manual for the embeddable `client/.../TeeBoxClient.java` (setup, scripts, runs, output capture, full method reference). Keep the two in sync.
- `demo/teebox/` — five `.tee` scripts (`01_basic_run.tee` … `05_registered_sum.tee`) plus README/PLAN. (README.md's reference to a `.pt` file is a README bug; only `.tee` exists.)
