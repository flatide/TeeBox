# Changelog

All notable changes to TeeBox are documented here.

## 1.7.0

- **Syntax-highlighting code editor in the admin script UI.** The script-content textareas (register,
  add-version, edit-source) are now ProperTee code editors ported from the ProperTee playground:
  syntax highlighting (keywords, builtins, strings, numbers, comments, operators), a line-number
  gutter, and Tab / auto-indent / `Ctrl`+`/` comment shortcuts. Each editor carries a **built-in
  function reference panel** — the 59 builtins by category; click one for its signature, description,
  return/failure notes, and a runnable sample, then **Insert** to drop `NAME()` at the cursor. The
  highlighter and the builtin catalog are ported verbatim; run/debug features were intentionally left
  out. Editors keep the plain `<textarea>` as the form field (form submit and a no-JS fallback both
  still work). Assets are inlined (TeeBox serves no static files); the heavier JS loads only on the
  two editor pages, so the dashboard and login page stay lean.

## 1.6.0

- **Embedded ProperTee runtime upgraded 0.2.0 → 0.5.0 (spec v0.6.0 → v0.9.0).** TeeBox now bundles
  [`propertee2-java`](https://github.com/flatide/propertee2-java) 0.5.0, picking up three spec batches.
  TeeBox's own HTTP API, embeddable client, and host behavior are **unchanged** (no application-code
  change; the full suite passes on 0.5.0), but the **language your scripts run in has breaking
  changes** — review scripts before upgrading (canonical migration notes: propertee2-java
  `docs/LANGUAGE.md` §Changelog):
  - ⚠️ **spec v0.7.0 (breaking):** non-boolean `if`/`loop` conditions now error (`Condition requires a
    boolean value`); `and`/`or` short-circuit (right operand not evaluated when the left decides);
    single-arg `RANDOM(max)` removed (use `RANDOM(0, max - 1)`); `SLICE(arr, start, count)` — the third
    arg is a **count**, not an end index (migrate `SLICE(a, s, e)` → `SLICE(a, s, e - s + 1)`); `LEN` on
    a non-collection errors.
  - ⚠️ **spec v0.8.0 (breaking):** first-class `null` — `null` is a reserved word, and `JSON_PARSE` no
    longer normalizes JSON `null` to `{}` (round-trips are now lossless).
  - **spec v0.9.0 (nearly non-breaking):** Lua-style `elseif` added; `elseif` becomes reserved.
- HTTP builtins, task-output merge, access logging, version display, and the multi-user admin UI from
  earlier releases are unaffected.

## 1.5.0

- **Multi-user admin UI with per-script ownership.** The `/admin` HTML UI now supports multiple named
  users with roles (`admin` / `user`) instead of a single config admin. Two files under
  `dataDir/users/` back it: **`users.json`** (the roster — an array of `{username, role}`,
  operator-managed and hand-edited, read fresh per login) and **`credentials.json`** (password hashes,
  TeeBox-managed). A user has no password until **first login**, when the password they type is hashed
  (**PBKDF2-HMAC-SHA256**, per-user salt, constant-time verify) and stored — plaintext is never kept.
  Sessions now carry `{username, role}`.
- **Ownership authorization.** Each script records an `owner` (the UI user that first registered it).
  Admins may act on any script; a regular `user` may only **modify / run / kill-tasks on scripts they
  own**, and may register new scripts (becoming owner). Enforcement is server-side in `AdminHandler`
  (403 on violation; `/admin/shutdown` is admin-only), and the UI hides buttons the viewer can't use.
  When a roster exists the **entire `/admin` UI requires login — GET reads (script source, run/task
  output) are gated, not only mutations** (the login page stays open). The `/api/*` namespaces are
  **unchanged** — token-gated and unrestricted (no ownership checks); API-registered scripts have no
  owner (admin-only in the UI).
- **Bootstrap & compatibility.** `adminUser`/`adminPassword` now *seed* the roster: when it's empty and
  `adminUser` is set, `{adminUser, admin}` is created (with `adminPassword` as the initial hashed
  credential if provided). Login is required exactly when a roster exists; with no roster (and no
  `adminUser`) the UI stays fully open. Note: a deployment that set only `adminUser` (no password) was
  previously open and now requires that admin to log in (password set on first login).

## 1.4.0

- **HTTP builtins (`HTTP_GET`, `HTTP_POST`, `HTTP`) now work in embedded ProperTee scripts.** They were
  missing from the ProperTee v2 runtime's builtin catalog, so HTTP calls were dead; the runtime
  ([`propertee2-java`](https://github.com/flatide/propertee2-java)) restored them and TeeBox now bundles
  that fix. Each returns the v1 Result shape `{status, ok, value:{status, body, headers}}` — a non-2xx
  response is `ok=false` with the real status/body, a transport failure is `ok=false` with `status=0`;
  `HTTP_POST` serializes an object body to JSON. They run off the cooperative baton (`Coop.blocking`),
  through `TeeBoxPlatformProvider`'s host HTTP, so concurrent `multi` workers aren't stalled. No TeeBox
  application-code change was needed — covered by a new end-to-end test.
- **API access logging is now scoped to the `/api` context only.** The per-request access log
  (added in 1.3.0) previously also covered the `/admin` operator HTML UI; it now logs only `/api` — the
  JSON API called by external/upstream servers (client + publisher + admin API). `/admin`, `/health`,
  and `/` are unlogged.

## 1.3.0

- **Per-request API access logging.** The `/api` and `/admin` contexts now emit one access-log line
  per request on a dedicated `access` logger — method, path (+query), client IP (honoring
  `X-Forwarded-For`), response status, and elapsed ms — e.g.
  `GET /api/client/runs?limit=10 from 127.0.0.1 -> 200 (4ms)`. Emitted in a `finally`, so it fires on
  success and error alike (a handler that threw before sending headers logs `-> no-response` at
  `warn`). Request/response bodies are deliberately **not** logged (they can carry API tokens, script
  source, or large payloads). `/health` and `/` are left unlogged to avoid load-balancer/static noise.
  Operators can retune or silence it independently in `log4j2.xml`
  (`<Logger name="access" level="WARN"/>`).
- **The TeeBox version is now displayed at runtime.** The build version is baked into a classpath
  resource and surfaced in the startup banner (log + stdout: `TeeBox <version> listening on ...`), the
  admin UI top-nav (`TeeBox v<version>`, on every page), and the system API
  (`SystemInfo.teeboxVersion` via `GET /api/admin/system`).

## 1.2.0

- **Run-output endpoints now also return external task (`SHELL`) output, merged into the response.**
  A run's script `PRINT` output and the stdout/stderr of the `SHELL` tasks it spawns are captured
  separately (script output in an in-memory ring buffer; task output on disk under the task dir), so
  the client previously had no client-scoped way to read task output — only the admin task endpoint
  exposed it, and only as a 4 KB tail. `GET /api/client/runs/{runId}/stdout` (and `/stderr`) now add:
  - `taskLines` / `taskLineCount` — the merged task output of the run, in spawn order. Most scripts
    run a single `SHELL`, so this is simply that command's output, fetched by `runId` alone.
  - `taskCount` and a `tasks` breakdown (`taskId, command, status, exitCode, lineCount`) to attribute
    lines when more than one task ran.
  - `taskLinesTruncated` — whether the line cap dropped earlier lines.

  The existing `lines` / `lineCount` (script `PRINT` output) are unchanged, so this is backward
  compatible.
- **Task output is tailed to its last 200 lines by default**, mirroring the script-output ring buffer
  (`RunRegistry.MAX_LOG_LINES`). Override per request with `?taskLines=N` (`<= 0` disables the line
  cap); a 1 MB per-task byte cap remains underneath as a disk-read guard so even an uncapped request
  can't load a multi-GB task into one response.
- **Embeddable client (`TeeBoxClient`):** new `getRunTaskStdoutLines(runId)` /
  `getRunTaskStderrLines(runId)` convenience accessors, plus `getRunStdout`/`getRunStderr` and the
  task-line accessors gain a `maxTaskLines` overload. `getRunStdoutLines`/`getRunStderrLines` continue
  to return only the script lines. Still Java 7 bytecode (loads on Java 7+).
- **Test coverage:** end-to-end tests through the deployable client for single-SHELL merge + line cap,
  multiple sequential SHELL tasks (spawn order + breakdown), and parallel `multi`/`thread` SHELL tasks
  (set membership, non-flaky).

## 1.1.2

- **Fix `Cannot mark persisted: not terminal` crash during task recovery.** A persisted task
  whose `meta.json` had no status and no lifecycle (older data or an interrupted write) crashed
  `init()` / disk load: a null status was inferred as terminal-persistable, but its rebuilt
  lifecycle is ACTIVE. Recovery now treats a null/unknown status as not-yet-terminal, and
  `markPersisted()` is guarded by the lifecycle invariant (only when actually terminal) at every
  recovery call site, so the same exception can't recur for a future unknown status.
- **Restore v1 lowercase task-status metadata compatibility.** v2's `TaskStatus` dropped the gson
  `@SerializedName` annotations, so legacy `"status":"running"` metadata read as `null` (and was
  then re-finalized, losing the original status). A `TaskStatus` gson adapter now (de)serializes by
  the lowercase `value()` form — tolerant of both the lowercase value and the uppercase enum name,
  writing the lowercase v1 wire form — so v1 task metadata recovers to the correct status.

## 1.1.1

- **`THUMBNAIL` now restricts its source and destination paths to the configured allowed roots** —
  the same boundary `STREAM_FILE` enforces (`propertee.teebox.streamRoots`, default = the run data
  dir). Each path is canonicalized and must resolve inside a root (the source must also be an
  existing file); anything outside is rejected with a `Result.error`. `THUMBNAIL` is now registered
  only when that allowed-roots policy is present (as `STREAM_FILE` already was). Addresses the 1.1.0
  note about `THUMBNAIL` touching arbitrary filesystem paths.

## 1.1.0

- **New host builtin `THUMBNAIL(srcPath, destPath, maxWidth, [maxHeight])`** for the embedded
  ProperTee (a TeeBox-only builtin, not part of the ProperTee language). Scales an image —
  anything `ImageIO` can read (PNG/JPEG/...) — to fit within the given bounds preserving aspect
  ratio (never upscaling) and writes a PNG, returning `{path, width, height}` on success or a
  `Result.error` on a missing/unreadable image or bad arguments. It runs **off the cooperative
  baton** (registered via the propertee2 §3.1 blocking-external contract), so the disk + CPU work
  never stalls concurrent `multi` workers.
  - Reads/writes the given filesystem paths directly (like the other file builtins) — restrict
    paths at the deployment layer when running untrusted scripts.

## 1.0.0

**TeeBox now runs on the ProperTee v2 runtime (`propertee2`).**

- Switched the embedded engine from ProperTee v1 (`propertee-java` — Java 7/8, stepper
  runtime) to ProperTee v2 (`propertee2-java` — the Java 25 virtual-thread / cooperative
  runtime). The composite build now includes `../propertee2-java`.
- **Requires Java 25** at build and runtime — v2 uses virtual threads (Project Loom) and
  `ScopedValue`. Deploy targets that install a runtime separately must use a Java 25
  build. The `-with-runtime` bundle now defaults to OpenJDK 25.0.2 Linux x86_64
  (`defaultRuntimeLinuxX64Url`/`Sha256` in `build.gradle`, overridable via
  `propertee.teebox.runtimeLinuxX64Url`); was JDK 21.0.2.
- **No TeeBox application code changed.** v2 exposes the same `com.flatide.*` API surface
  TeeBox links against (the `com.flatide.task` engine, the `interpreter`/`scheduler`
  façade, `platform`/`runtime`/`core`/`parser`). The only repo changes are
  `settings.gradle` (the runtime it includes) and `build.gradle` (Java 25 toolchain).
- Reverting to the v1 runtime only requires pointing `settings.gradle` back at
  `../propertee-java` and dropping the toolchain to Java 17.

The last release on the ProperTee v1 runtime is tagged **`v0.12.0-propertee-v1`**.

## 0.12.0

- Webhook MVP; dist rebuild. Last release on the ProperTee v1 runtime.
