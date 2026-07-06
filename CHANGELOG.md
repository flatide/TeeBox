# Changelog

All notable changes to TeeBox are documented here.

## 1.11.1

- **The Runs list gets a dedicated "By" column** showing each run's submitter (the 1.11.0
  `X-TeeBox-User` / admin-session identity). 1.11.0 rendered it as a dim `by <user>` suffix inside the
  Script column, which was easy to miss; it is now its own column between Script and Status (dash for
  anonymous runs). The table fragment is shared, so the dashboard's runs table shows it too.
- **Docs sweep for the 1.11.0 features.** Operations guides (en/ko) gain "Run Submitter Identity" and
  "Runs List Filters" sections; API-EXAMPLES gains `X-TeeBox-User` and `instant`/`q` curl examples —
  and corrects a stale example (`scriptId=` was never read by `GET /api/admin/runs`; use `q=` or the
  client per-script runs endpoint); `swagger.yaml` documents the new header/params and the
  `submittedBy`/`immediate` response fields.

## 1.11.0

- **Runs page filters & search.** The admin `/admin/runs` list gains an **Include instant** checkbox —
  **default unchecked, so runs of `immediate=true` scripts ("instant runs") are hidden** (they tend to be
  high-frequency and would drown the list; check to include them) — and a debounced **search box**
  matching a case-insensitive substring of the script name or run ID. Both are server-side (with the
  Status filter and pagination) via the `all-runs` fragment; instant rows carry an `instant` tag. To
  support this, **whether a run is instant is now recorded per run at submit time**
  (`RunInfo.immediate`, also in the run index so filtering never loads run files); legacy runs read back
  as non-instant. `GET /api/admin/runs` gains the same `instant=exclude|only` and `q` parameters
  (absent = all, backward compatible).
- **Runs now record who submitted them.** Run-submitting `TeeBoxClient` methods take an optional
  trailing `userId` (nullable): `submitRun(scriptId, version, props, callbackUrl, userId)`,
  `runAndWait(..., timeoutMs, userId)`, `runAndStream(..., timeoutMs, userId)` — sent as the
  **`X-TeeBox-User`** request header (no header when null). TeeBox sanitizes it (≤128 chars,
  display/audit only — **not** authentication) and records it as `submittedBy`: shown on the run detail
  page (**Submitted By**) and as `by <user>` in the Runs table, and returned in run status/summary/result
  JSON. Admin-UI submits record the logged-in operator's username in the same field. Existing client
  signatures are unchanged (still Java 7 bytecode); client guides (en/ko) updated.

## 1.10.2

- **Fix — the admin code editor now highlights and documents the spec v0.10.0 Results builtins.** The
  editor was a verbatim playground snapshot from before the spec batch, so `FAIL` / `UNWRAP` / `OK` /
  `ERR` / `IS_RESULT` rendered unhighlighted and were missing from the builtin reference panel. The
  highlighter regex and the panel catalog are re-synced from the playground (a new **Results** category,
  verbatim; the existing 59 entries were confirmed unchanged).
- **The TeeBox host builtins `STREAM_FILE` and `THUMBNAIL` are now highlighted too**, with their own
  **TeeBox Host** panel category (signature, return/failure notes, samples) — they are TeeBox-registered
  builtins, so they don't exist in the playground catalog and are kept clearly separated from the
  verbatim-synced parts. An injection-test guard now pins the catalog so future spec batches can't
  silently fall behind.

## 1.10.1

- **Fix — run results now survive a server restart byte-faithfully** (the disk half of 1.10.0's
  first-class-`null` fix, which covered only outbound API serialization). Two reload corruptions
  fixed in `RunStore`: the persistence Gson lacked `JsonNullGsonAdapter`, so the engine's `null`
  was already written to disk as `{}`; and reload used Gson's generic `Object` mapping, which
  turns JSON `null` into a Java null (the key then silently vanished from responses) and **every
  number into a `Double`** — a pre-existing bug where `"n": 1` was served as `"n": 1.0` after a
  restart. `RunStore` now registers the adapter on write and re-parses the `resultData` subtree
  with the engine's own JSON parser on load, restoring the exact engine value shapes
  (`JsonNull.NULL`, `Integer` vs `Double` by literal, insertion order). A restart integration
  test pins the served result JSON as byte-identical before/after; `RunStoreTest` pins the value
  matrix. Files written before 1.10.x carry `{}` where `null` was — those load unchanged
  (unrecoverable by design).

## 1.10.0

- **Fix — first-class `null` survives the API boundary.** A script result carrying ProperTee's
  first-class `null` (spec v0.8.0, `null != {}`) — e.g. `return {"coupon": null}` or
  `return null` — was serialized by Gson as `{}` (the engine's `JsonNull.NULL` singleton has no
  fields, and `{}` means "absence" in ProperTee), silently breaking the lossless JSON round-trip
  for API consumers. `JsonNullGsonAdapter` now emits it as JSON `null` (flipping `serializeNulls`
  only around that one value, so unrelated Java-`null` response fields — `errorMessage` etc. —
  are still omitted); registered on the API-response Gson (client result + envelope + admin
  `RunInfo`) and, defensively, the webhook Gson. Also: a script with no top-level `return` and no
  `result` global now yields `resultData: {}` (previously the key was absent), matching
  "no implicit null". Known limit: outbound-only — a result reloaded from disk after a restart
  still collapses `null`; reload-side reconstruction is a follow-up.
- **Admin UI — metadata-only first registration (script shells).** The Register modal collects
  only a Script ID; registering creates an **empty script shell** (no versions, not active) and
  lands on the detail page, where the code is written and a version activated explicitly. A
  version added to a pre-existing script (including a shell) **never auto-activates** — only the
  one-shot Publisher-API register (content included) still yields an immediately-runnable active
  version. A content-less register against an existing script is a 400.
- **Test infrastructure — the suite was silently truncated; fixed and fully green.** The drain
  test exercised the real graceful-shutdown path, whose `System.exit(0)` killed the test fork;
  Gradle treated the clean exit as success, so tests scheduled after it silently never ran
  (`TeeBoxServerTest`: only 2 of 27 executed). The drain path now exits through an injectable
  `RunManager.ExitHandler` (production unchanged); the test injects a recorder and also asserts
  the exit request. **Full suite verified: 181/181 tests across all 21 classes execute, 0
  failures** — including, for the first time in a while, the whole live-server integration class
  against the current embedded runtime.
- **Embedded runtime:** [`propertee2-java`](https://github.com/flatide/propertee2-java)
  0.9.1 → 0.9.2 — `[THREAD ERROR]`/`[MONITOR ERROR]` lines and loop-limit warnings now reach the
  host's **stderr** print sink as in v1 (they were mis-tagged as stdout in run logs), and
  `iterationLimitBehavior="warn"` (the run-submission `warnLoops` option) works again: the
  offending loop stops with a warning instead of failing the run. No TeeBox code change
  (composite build).

## 1.9.1

- **`errorMessage` (and the envelope's `value` for FAILED runs) now carries the error position** —
  `"Runtime Error at line L:C: <message>"` — pinpointing e.g. the `FAIL(...)` site. This is a
  propertee-core 0.9.1 fix (the v1 façade used to rethrow the engine's `TeeError` whose
  `getMessage()` lacked the position, breaking the v1 host contract where the position was baked
  into the exception message); TeeBox picks it up through the composite build — no TeeBox code
  change. Integration test pins the positioned prefix; demo `06_run_envelope.tee` docs updated
  with the exact live-verified envelope.
- **Embedded runtime note (belated for 1.9.0):** across 1.9.0/1.9.1 the bundled
  [`propertee2-java`](https://github.com/flatide/propertee2-java) moved 0.5.0 → 0.9.1
  (spec v0.9.0 → v0.12.0). New for scripts: `FAIL`/`UNWRAP`/`OK`/`ERR`/`IS_RESULT` + genuine
  Results (v0.10.0, additive), pinned name resolution (v0.11.0, additive), and ⚠️ **v0.12.0
  (breaking): all-uppercase script function definitions (`function LEN(...)`) are now a
  definition-time error** — the ALL-CAPS namespace is reserved for built-in/host functions
  (a corpus audit found no such definitions in TeeBox scripts). Hosts also gain the opt-in
  static validation pass (`validate`, ProperTee #9). Canonical migration notes:
  propertee2-java `docs/LANGUAGE.md` §Changelog.

## 1.9.0

- **Run-result envelope** (ProperTee `docs/design-draft-result-handling.md` §5 — the deferred
  host-side track, now landed). `GET /api/client/runs/{id}/result` gains an additive `result`
  field: the whole run viewed as a ProperTee Result — one `{status, ok, value}` shape for every
  outcome, exactly like a `multi` collection entry (the run is "thread #0"):
  `COMPLETED` → `{status:"done", ok:true, value:<resultData>}`, `FAILED` →
  `{status:"error", ok:false, value:<errorMessage>}`, not yet terminal →
  `{status:"running", ok:false, value:{}}`, `SERVER_RESTARTED` →
  `{status:"error", ok:false, value:"server restarted"}`. Client code is always
  `env.ok ? use(env.value) : handle(env.value)` — the three fragmented outcomes (runtime error /
  script-returned Result / plain value) unify without any shape inspection: a script that
  deliberately returns a Result simply nests inside `value` (its `ok:false` can never be mistaken
  for the run's failure), and a value-less run yields `value:{}` (the language's "no value").
  Existing fields and endpoints are unchanged (fully additive); the admin `RunInfo` surface and
  webhook payload deliberately do not carry the envelope (the webhook still omits `resultData`).
- **Client:** `TeeBoxClient.getRunEnvelope(runId)` — convenience accessor for the envelope
  (additive; Java 7 source level preserved). The envelope also arrives through the existing
  `getRunResult`/`runAndWait` maps under the `"result"` key.
- swagger.yaml: `RunResultEnvelope` schema, referenced from `ClientRunResult`.

## 1.8.2

- **Fix mismatched button heights/alignment in the Versions table Action column.** The `Edit` (an
  `<a class="btn">`) and `Editing` (a `<span class="btn">`) controls inherited the body `line-height`
  (1.5) while `Set active` (a `<button>`) used the UA default (~1.2), so the anchor/span rendered a few
  pixels taller and off-baseline. The `.btn` rule now pins one `line-height`, `display:inline-block`,
  `vertical-align:middle`, and `text-align:center` across all three element types, so the buttons are the
  same height and sit on the same line. CSS-only.

## 1.8.1

- **Any version can now be edited from the Versions list, not just the active one.** After the 1.8.0
  editing rework, the source editor only ever targeted the active version, so an inactive version could
  not be edited. Each row in the **Versions** table now has an **Edit** action that opens that version in
  the source editor (`/admin/scripts/{id}?version={v}`); the row being edited is marked **Editing**. The
  source card is retitled **Version Source (v)** with an `ACTIVE`/`inactive` badge, loads that version's
  content and output-capture rule, and its **Save** overwrites that specific version in place —
  **Save as new version** is unchanged. After an in-place save you stay on the version you edited (the
  redirect preserves `?version=`) instead of snapping back to the active one. With no `?version` the page
  behaves as before, editing the active version.

## 1.8.0

- **Reworked the script-editing UI on the admin script page.** The standalone "Add New Version" card is
  removed; the **Active Version Source** editor is now the single surface for both editing and adding
  versions. It has two submit buttons over the same content: **Save** overwrites the active version in
  place (`/admin/scripts/update-source`), and **Save as new version** registers the editor content as a
  new version (`/admin/scripts/register`, auto next #), with the adjacent *Description* and *Set new
  version active* controls applying to that new version. Only the clicked button contributes its
  `name`/`value`, so the two actions never collide (no JavaScript needed). The active source now shows
  `2` more table columns of code — the editor grew from 14 to **24 rows**, using the space the removed
  card freed.
- **The code editor and its builtin-function panel are now resizable side by side**, like the ProperTee
  playground: a drag handle between them trades width (the editor keeps a usable minimum, the panel a
  minimum of 220px), and the panel's height tracks the editor so the two stay aligned as the textarea is
  resized. The 1.7.1 editor-height fix is preserved — the gutter and syntax overlay remain pinned to the
  textarea's height, so neither the panel nor the line count can stretch the box.
- Note: the removed card's file-upload field is gone from this surface (paste the source instead); a new
  script's first version is still registered from the **Register** modal on the Scripts list.

## 1.7.1

- **Fix the code editor (1.7.0) confining the caret and scrollbar to the top portion of the box.** In a
  script longer than the visible rows, the textarea's scrollbar spanned only part of the editor and the
  caret could not reach past roughly the middle, so typing lower down misbehaved. The line-number gutter
  was a normal flex child with no height bound, so it grew to the full line count and — as the tallest
  child — stretched the `.pt-editor` box past the textarea's height (TeeBox's global `pre{max-height}`
  compounded it on the syntax overlay). The gutter and the syntax overlay are now absolute layers pinned
  to the box's top and bottom, so the **textarea alone sets the height**; both are clipped and
  scroll-synced to it. CSS-only change to `propertee-editor.css`; no behavior change to the highlighter,
  the builtin panel, or form submission.

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
