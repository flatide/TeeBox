# TeeBox Operations Guide

## 1. Deployment

### Build

```bash
cd propertee-teebox && ./gradlew teeBoxZip
# → build/distributions/propertee-teebox-dist.zip
```

### Install

```bash
unzip propertee-teebox-dist.zip -d /opt/teebox
```

The default GitHub distribution zip does not include a Java runtime. On the deployment server, download a Linux x86_64 Java 25 runtime archive separately and extract it under `/opt/teebox/runtime/` so that `runtime/bin/java` exists.

Directory layout:
```
/opt/teebox/
  bin/run-teebox.sh     # launcher script
  conf/teebox.properties # configuration file
  lib/propertee-teebox.jar
  runtime/bin/java      # separately installed Java 25 runtime
```

### Configuration

`conf/teebox.properties`:
```properties
propertee.teebox.bind=127.0.0.1
propertee.teebox.port=18080
propertee.teebox.dataDir=/var/lib/teebox
propertee.teebox.maxRuns=64
```

| Property | Default | Description |
|----------|---------|-------------|
| `bind` | `127.0.0.1` | Bind address |
| `port` | `18080` | Listening port |
| `dataDir` | (required) | Data directory (runs, tasks, script-registry, users) |
| `maxRuns` | `64` | Maximum number of concurrent runs |
| `apiToken` | none | Bearer token shared across all APIs (fallback) |
| `clientApiToken` | none | Token specific to `/api/client` |
| `publisherApiToken` | none | Token specific to `/api/publisher` |
| `adminApiToken` | none | Token specific to `/api/admin` |
| `adminUser` | none | Bootstrap admin-UI login: seeds the user roster with this admin when the roster is empty. See "Admin UI login" below. |
| `adminPassword` | none | Optional initial password for `adminUser` (otherwise it is set on that admin's first login). |
| `streamRoots` | `dataDir` | Allowed roots for `STREAM_FILE` results (a `File.pathSeparator`-separated list of directories; `:` on Linux/macOS, `;` on Windows). A streamable file path must canonicalize within one of these. See §3. |
| `webhookEnabled` | `false` | Enable run-terminal webhook delivery (opt-in). When off, a run submitted with a `callback` is rejected with HTTP 400. See §3. |
| `webhookUrlAllowlist` | none | **Comma-separated** `host[:port]` allowlist for callback URLs (required when enabled — an unset allowlist rejects every callback). A `host` entry matches any port; `host:port` must match exactly. |
| `webhookTimeoutMs` | `10000` | Per-POST connect/read timeout (ms) for webhook delivery. |

**Duration / retention knobs — system properties ONLY.** The following are read exclusively as
`-D` system properties and are **silently ignored if put in `teebox.properties`** — set them via
`JAVA_OPTS` (e.g. `JAVA_OPTS="-Dpropertee.teebox.runRetentionMs=48h"`):

| System property | Default | Description |
|-----------------|---------|-------------|
| `propertee.teebox.runRetentionMs` | `24h` | Run retention before transitioning to archived |
| `propertee.teebox.runArchiveRetentionMs` | `7d` | Archived run retention before deletion (purge) |
| `propertee.teebox.maintenanceIntervalMs` | `1m` | Background maintenance interval |
| `propertee.teebox.scriptRetentionMs` | `7d` | Soft-deleted script retention before purge |
| `propertee.task.retentionMs` | `24h` | Task retention before archival |
| `propertee.task.archiveRetentionMs` | `7d` | Archived task retention before deletion |
| `propertee.teebox.logDir` | `logs` | Log output directory (see §7) |

Duration format: a bare number = ms, or suffixes `ms`, `s`, `m`, `h`, `d` (e.g. `500ms`, `30s`, `1m`, `24h`, `7d`).

Environment variables:
- `PROPERTEE_TEEBOX_CONFIG` — Path to the configuration file (default: `conf/teebox.properties`)
- `JAVA_HOME` — Java installation path
- `JAVA_OPTS` — JVM options (`-Xmx`, `-D`, etc.). System properties take precedence over the configuration file.

### Admin UI login (multi-user)

The `/admin` HTML UI has its own cookie/session login, **independent of the API Bearer tokens**:

- **No roster ⇒ fully open.** With no user roster (and no `adminUser` to seed one), the admin UI
  requires no login — the closed-network default. Likewise, an API namespace with no token stays
  unauthenticated. Review this posture before exposing TeeBox beyond a trusted network.
- **Roster** — `dataDir/users/users.json`, an **operator-managed** JSON array of
  `{"username": ..., "role": "admin"|"user"}`. Add/remove users by editing the file; it is read
  fresh on every login, so edits apply without a restart. Setting `adminUser` (and optionally
  `adminPassword`) seeds the roster with one admin at startup when it is missing/empty.
- **Passwords** — a user sets their password on **first login** (stored as a PBKDF2 hash in the
  TeeBox-managed `dataDir/users/credentials.json`; plaintext is never stored). To reset a password,
  remove that user's entry from `credentials.json` — the next login sets a new one.
- **What login gates** — only mutating admin-UI actions (register/edit/run/kill/settings/shutdown).
  All GET pages stay viewable read-only without a session. Regular (`user` role) accounts may only
  modify/run scripts they own (registered themselves); `admin` accounts may act on everything, and
  server shutdown is admin-only. The `/api/*` namespaces are unaffected (token-gated, no ownership
  checks).

### Running

```bash
./bin/run-teebox.sh
```

### Dependencies

- Linux x86_64 Java 25 runtime (`runtime/bin/java`) or system Java 25+
- `setsid` (util-linux) — Required for task process group isolation. Included by default on Linux.
- For development, the sibling `../propertee2-java` repo is required (composite build; the ProperTee v2 runtime).

---

## 2. API Structure

Three independent API namespaces, each with its own Bearer token authentication:

| Namespace | Path | Purpose |
|-----------|------|---------|
| Client | `/api/client` | Run submission and result retrieval |
| Publisher | `/api/publisher` | Script registration and version management |
| Admin | `/api/admin` | System inspection, run/task detail, kill operations |

Admin HTML UI: `/admin`

Full API specification: `swagger.yaml` (OpenAPI 3.0)

---

## 3. Script Execution Flow

```
Register script via Publisher API → Submit run via Client API → TeeBox executes → Retrieve results
```

1. **Register script**: `POST /api/publisher/scripts` (body: `scriptId`, `content`, optional `version` — blank ⇒ auto-increment `"1"`, `"2"`, … — and `activate`). Add a version to an existing script with `POST /api/publisher/scripts/{scriptId}/versions`.
2. **Activate version**: `POST /api/publisher/scripts/{scriptId}/activate` (body: `{"version": "..."}`). A version added to an existing script never auto-activates — activation is an explicit step (staging/rollback); version-less runs execute the **active** version, not the newest.
3. **Submit run**: `POST /api/client/scripts/{scriptId}/runs` (returns 202 + `runId`; async)
4. **Poll results**: `GET /api/client/runs/{runId}` (summary), `.../status`, `.../result`

Recommended operational patterns:
- A job-submit script should exit as soon as it obtains the job id.
- Job status polling should be split into a separate short script and invoked periodically by an external scheduler or cron.
- Avoid patterns that launch a background job inside a single ProperTee run and then perform a long `wait` or maintain a polling loop within the same run.

### Run Submitter Identity (`X-TeeBox-User`)

A run submit may carry an optional **`X-TeeBox-User`** request header identifying who submitted it
(the `TeeBoxClient` run methods take it as a trailing `userId` argument; `null` = anonymous, no header
sent). TeeBox sanitizes the value (control characters stripped, trimmed, capped at 128 chars) and
records it on the run as `submittedBy`. Runs submitted from the admin UI record the logged-in
operator's username in the same field.

Where it appears:
- Admin **Runs list** — a dedicated **By** column (dash for anonymous runs).
- Admin **run detail page** — the **Submitted By** field.
- Run JSON — `submittedBy` in run status/summary/result responses.

TeeBox also records the **caller IP** at submit time (`submittedFrom`; first `X-Forwarded-For` hop
when present, else the socket peer — same resolution as the access log). It is shown on the run
detail page (**From (IP)**) and in the admin run-detail JSON; it is **not** echoed in the
client-facing run responses.

This is **display/audit metadata only** — it is caller-supplied and not authenticated. Do not use it
for authorization; API access is still governed by the Bearer tokens.

### Runs List Filters (admin UI)

The `/admin/runs` list filters server-side:
- **Include instant** checkbox — **unchecked by default, so runs of `immediate=true` scripts
  ("instant runs") are hidden** (they tend to be high-frequency and would otherwise drown the list).
  Check it to include them; instant rows carry an `instant` tag.
- **Search box** — case-insensitive substring match on the script name or run ID.
- **Status** select and pagination combine freely with both.

`GET /api/admin/runs` accepts the same filters: `instant=exclude|only` (absent = all) and
`q=<substring>`, alongside `status`/`offset`/`limit`.

### Per-Script Concurrency Control

> **Note:** There are two separate concurrency limits:
> - **Global limit** (`propertee.teebox.maxRuns` in server config): Total concurrent runs across all scripts. Managed by the global thread pool.
> - **Per-script limit** (`maxConcurrentRuns` in script settings): Max concurrent runs for a specific script. Applies independently of the global limit.
>
> Immediate scripts bypass the global thread pool queue entirely (they use a separate unlimited thread pool), but still respect their own per-script limit.

Each script can have its own concurrency settings:

- **maxConcurrentRuns**: Maximum number of simultaneous runs for this script (0 = unlimited, uses global limit)
- **immediate**: When true, runs bypass the global queue and execute on a separate thread pool. The per-script concurrency limit (`maxConcurrentRuns`) still applies.

Immediate scripts bypass the global thread pool queue but still respect the per-script concurrency limit. The `immediate` flag only controls which executor is used, not whether concurrency limits are enforced.

| Configuration | Executor | Concurrency limit |
|---------------|----------|-------------------|
| `immediate=true, maxConcurrentRuns=0` | Immediate executor | Unlimited |
| `immediate=true, maxConcurrentRuns=3` | Immediate executor | PENDING if 3 already running |
| `immediate=false, maxConcurrentRuns=3` | Global executor | PENDING if 3 already running |
| `immediate=false, maxConcurrentRuns=0` | Global executor | Unlimited (global pool limit) |

Configure via Admin UI (Script detail → Execution Settings) or REST API:

```bash
# Set max 3 concurrent runs
curl -X PUT http://host:18080/api/publisher/scripts/my-script/settings \
  -H 'Content-Type: application/json' \
  -d '{"maxConcurrentRuns": 3, "immediate": false}'
```

When the per-script limit is reached, new runs enter PENDING status until a slot opens. Runs are dequeued automatically when a previous run completes.

### Task Output Capture

TeeBox can watch task stdout/stderr for regex patterns and publish matched values to the run metadata. This is configured per script version via output rules.

**Registering a script with output rules:**

```bash
curl -X POST http://host:18080/api/publisher/scripts \
  -H 'Content-Type: application/json' \
  -d '{
    "scriptId": "deploy",
    "version": "v1",
    "content": "result = SHELL(\"./deploy.sh\")",
    "activate": true,
    "outputRules": [{
      "stream": "stdout",
      "pattern": "Job <(\\d+)> is submitted",
      "captureGroup": 1,
      "publishKey": "jobId",
      "firstOnly": true
    }]
  }'
```

**Retrieving captured values:**

```bash
curl http://host:18080/api/client/runs/{runId}
# Response includes: "published": {"jobId": "12345", "jobId.detectedAt": 1712345678000}
```

Rules can also be configured via the Admin UI on the script detail page.

**How it works:**
- Only the first task created by the run is watched (prevents false matches from auxiliary tasks)
- The watcher incrementally reads the task's stdout.log file
- Matching happens per-line with configurable capture group
- `firstOnly: true` means only the first match is published (recommended)
- Captured values are persisted immediately and visible in both API and Admin UI

### Large Result Streaming (STREAM_FILE)

Returning a large file (e.g. a 6 MB JSON) by reading it into the engine — `READ_LINES` + `JOIN` + `JSON_PARSE` + `return` — copies the whole payload into the script-engine heap several times (and again into the buffered JSON response), causing memory pressure and slowness. Instead, a script can return a small **stream descriptor** that references a file, and TeeBox streams that file directly to the response (no parsing, no full-payload buffering, O(1) heap).

**In the script** — return `STREAM_FILE(path[, contentType])` instead of materializing the file:

```
return STREAM_FILE("/var/lib/teebox/exports/report.json", "application/json")
```

**Fetching the result:**

```bash
RUN_ID=...   # from submit + poll to terminal

# The normal /result returns a REDACTED descriptor (no server path)
curl http://host:18080/api/client/runs/$RUN_ID/result
# → { ..., "stream": true, "resultData": {"stream": true, "contentType": "application/json", "size": 7568901} }

# /result-stream streams the raw file bytes (Content-Type from the descriptor, Content-Length = size)
curl -s -o report.json http://host:18080/api/client/runs/$RUN_ID/result-stream
#   - returns HTTP 409 if the run's result is not a stream, or the file is gone
```

The embeddable client offers `streamRunResult(runId, OutputStream)` and the one-call `runAndStream(...)` convenience.

**Security — allowed roots (required to understand):**
- A streamable path must canonicalize within one of the configured `propertee.teebox.streamRoots` (default: `dataDir`). A path outside fails the script with a clear error. This is validated at `STREAM_FILE` time and again before streaming (TOCTOU-safe).
- `STREAM_FILE` exposes a file's bytes to API clients, so set `streamRoots` to the **minimum** directories needed — it is the only boundary preventing arbitrary file disclosure.

```properties
# allow streaming only from an exports directory (plus a shared mount)
propertee.teebox.streamRoots=/var/lib/teebox/exports:/mnt/shared
```

**Lifecycle (reference-only):** the descriptor only references the path — TeeBox does not copy or own the file. The file must outlive the result fetch. The descriptor itself survives archival like any run result (1.15.1+; older versions dropped it when the run archived after 24h), so a stream result stays fetchable until the run is purged — as long as the referenced file still exists.

### Run-Terminal Webhooks (callback)

Instead of polling, a client can ask TeeBox to **POST a notification when the run finishes**. TeeBox owns the retry: the run ends immediately (its slot is freed), and delivery is retried durably from a disk outbox until it succeeds or gives up — so a briefly-down receiver still gets the callback after it recovers. Opt-in via `webhookEnabled=true` + a `webhookUrlAllowlist`.

Submit with a `callback` (rejected with HTTP 400 if webhooks are disabled or the URL host is not on the allowlist):

```bash
curl -X POST http://host:18080/api/client/scripts/nightly_export/runs \
  -H 'Content-Type: application/json' \
  -d '{ "props": {}, "callback": { "url": "https://app.internal/teebox/callback" } }'
# -> 202 Accepted (the run is queued; the callback fires on terminal)
```

When the run reaches a terminal state TeeBox POSTs a JSON **SUMMARY** to that URL:

```json
{ "event": "run.terminal", "runId": "...", "scriptId": "nightly_export", "version": "3",
  "status": "COMPLETED", "endedAt": 1750900000000,
  "errorMessage": null, "resultSummary": "...", "published": { } }
```
with headers `X-TeeBox-Event: run.terminal` and `X-TeeBox-Delivery: <runId>`.

**Delivery semantics:**
- **At-least-once** — the receiver must be **idempotent**, keyed on `X-TeeBox-Delivery` (the runId). A lost 2xx ack causes a re-POST.
- **Retry** — non-2xx / transport failures retry with exponential backoff (base 5s, cap 10m) up to 12 attempts; then the delivery is marked **DEAD** (no more attempts).
- **Restart-safe** — the outbox lives in `${dataDir}/webhooks/`; pending deliveries resume after a TeeBox restart, and a reconcile re-enqueues any recently-terminal run (incl. `SERVER_RESTARTED`) whose callback was never delivered.
- **`status`** is the run's terminal state (`COMPLETED` / `FAILED` / `SERVER_RESTARTED`) — branch on it in the receiver.

**Security (allowlist is the boundary):**
- TeeBox POSTs to arbitrary URLs, so the callback host **must** be on `webhookUrlAllowlist` (comma-separated `host[:port]`). The URL scheme must be `http`/`https`. The host is validated at submit time **and** re-validated before each delivery — if the allowlist changes, an in-flight delivery to a now-disallowed host is killed (DEAD).

```properties
propertee.teebox.webhookEnabled=true
propertee.teebox.webhookUrlAllowlist=app.internal,app.internal:8443
```

> MVP scope: payload is SUMMARY-only; HMAC signing, custom auth headers, and per-script default callbacks are not yet implemented. For a stricter receiver, verify the `runId` against `GET /api/client/runs/{runId}` before acting.

### Script Deletion

Scripts use soft-delete with a retention period:

1. **Delete** (Admin UI "Delete" button or `DELETE /api/publisher/scripts/{id}`):
   - Marks `deletedAt = now` on the script
   - Script is hidden from normal list
   - Cannot be run (resolve fails)
   - Appears in "Deleted Scripts" section

2. **Retention** (default 7 days, configurable via `propertee.teebox.scriptRetentionMs`):
   - Script data remains on disk
   - Can be restored during this window

3. **Restore** (Admin UI "Restore" button or `POST /api/publisher/scripts/{id}/restore`):
   - Clears `deletedAt`
   - Script becomes active again

4. **Purge** (automatic):
   - Background maintenance (every 60s) permanently removes scripts past retention
   - Deletes the script directory and all versions

### Version Deletion

Individual versions can be hard-deleted — the **Delete** button on inactive rows of the Versions
table, or `DELETE /api/publisher/scripts/{id}/versions/{version}`:

- The **active version is protected** — set another version active first (a client run with no
  pinned version must never lose its target).
- Unlike script deletion there is no soft-delete/restore window: the version's metadata and stored
  content are removed immediately (the UI asks for confirmation).
- A run pinned to a deleted version is refused at submit; the active version keeps running.

### Script Duplication (the supported "rename" path)

TeeBox deliberately has no in-place rename — it would break the caller contract (clients submit by
script id) and race in-flight runs. To rename, duplicate instead:

1. **Duplicate** — the **Duplicate Script** card at the bottom of the script detail page, or
   `POST /api/publisher/scripts/{id}/duplicate` with `{"newScriptId": "..."}`. Copies every version
   (content + description/labels/sha256/output rules), the active-version choice, and the execution
   settings; the copy is immediately runnable. In the admin UI the duplicating user becomes the
   copy's owner.
2. **Point callers at the new id.**
3. **Delete the old script** once traffic has moved. Run history stays with the source — historical
   runs keep the old script id.

Target-id collisions, unknown sources, and soft-deleted sources are rejected with explicit errors.

---

## 4. Process Management

### Task Execution Model

TeeBox spawns an external process (task) for every `SHELL()` call in a ProperTee script.

```
TeeBox (Java)
  └── [setsid] /bin/sh <generated command file>
        └── user command
```

- On Linux/macOS, `UnixTaskRunner` executes tasks through `/bin/sh`.
- When `setsid` is available, a separate process group is used for isolation.
- On Windows, a simulated task runner is used instead of real external execution.
- One task engine is shared by **all concurrent runs** and its lifecycle belongs to the server: a
  finishing run never closes it (each run gets a non-closing view), so other runs' in-flight
  `SHELL()` tasks are unaffected by unrelated runs completing.

### Task Kill

**Always kill through TeeBox.**

- Admin UI: **Kill Task** button on the task detail page
- Admin API: `POST /api/admin/tasks/{taskId}/kill`
- Kill all tasks in a run: `POST /api/admin/runs/{runId}/kill-tasks`

TeeBox first attempts a process group kill, and falls back to collecting and killing the child process tree individually when needed.

### Killing Directly from the Shell (Not Recommended)

Process termination must go through the TeeBox UI or Admin API. Terminating only a single process with `kill <PID>` from a shell may leave child processes orphaned and will cause inconsistencies with TeeBox's lifecycle management.

### Graceful Shutdown

For maintenance, trigger a drain mode that rejects new runs and waits for in-flight runs to complete before exiting:

**Via Admin UI:** Dashboard → "Graceful Shutdown" button

**Via REST API:**
```bash
curl -X POST http://host:18080/api/admin/shutdown \
  -H 'Content-Type: application/json' \
  -d '{"maxWaitMs": 300000}'
```

**Behavior:**
- Immediately sets `draining=true`; all new `submit()` calls return HTTP 409 Conflict
- Background thread polls active/queued/pending counts every second
- When all counts reach 0, calls `System.exit(0)` which triggers JVM shutdown hook
- If `maxWaitMs` (default 5 min) elapses, forces shutdown

**Monitoring drain progress:**
```bash
curl http://host:18080/api/admin/drain-status
# {"draining": true, "drainStartedAt": 1712345678000, "activeRuns": 2, "queuedRuns": 3}
```

**Note:** This does not support a "cancel drain" operation. Once initiated, the server will shut down.

---

## 5. Script Authoring Guide

### Security Constraints

- TeeBox fails to start if launched as root.
- The `sudo` and `su` commands are blocked.
- Common shell syntax (`;`, `|`, `&&`, redirection, etc.) is permitted.
- Bare command execution is allowed.
- Catastrophic system-destruction commands are blocked (`shutdown`, `reboot`, dangerous `rm -rf`, `dd` targeting `/dev/*`, etc.).
- Control characters (`\n`, `\r`, `\0`) are blocked.
- Dangerous environment variables (`LD_PRELOAD`, `DYLD_*`) are blocked.
- `ENV`, `FILE_*`, `READ_LINES`, `WRITE_*`, `MKDIR`, `LIST_DIR`, and `DELETE_FILE` access the host environment through a `PlatformProvider` injected by TeeBox.

### Background Process Caveats

| Situation | When TeeBox kills | On normal exit |
|-----------|-------------------|----------------|
| Foreground command | Cleaned up | Cleaned up |
| `cmd &` (simple background) | Usually cleaned up | May remain |
| `setsid cmd &`, `nohup cmd &`, `disown` | Likely to remain | Remains |

**Recommendations:**

- If a background process is part of the task, it must be reaped with `wait`.
- Any background child that the script needs to clean up before exiting should be terminated explicitly.
- Processes detached with `setsid`, `nohup`, or `disown` may be considered outside the TeeBox task lifecycle.

### Example: Correct Background Usage

```sh
#!/bin/sh
# start background work
some_work &
WORKER_PID=$!

# do other work
do_something_else

# always reap with wait
wait $WORKER_PID
```

### `SLEEP()` Behavior (ProperTee v2 runtime)

On the ProperTee v2 runtime (TeeBox 1.0.0+), **`SLEEP(ms)` is fully cooperative wherever it
appears** — statement level, nested in `loop`/`if`/function bodies, or inside `multi`/`monitor`
blocks. The sleeping fiber suspends in place while the run's other `multi` workers and `monitor`
ticks keep advancing, and other runs are never affected. (The old v1-runtime limitation where a
nested `SLEEP` fell back to a blocking `Thread.sleep` no longer applies.)

Operational guidance stands regardless: for periodic work, prefer a short script invoked by an
external scheduler/cron over one long-lived run holding a `loop … SLEEP(...)` — long-running runs
occupy a slot of the global `maxRuns` pool for their whole lifetime and lose progress on a server
restart (`SERVER_RESTARTED`).

---

## 6. Data Management

### Retention

**Run:**
```
Active (0~24h) → Archived (24h~7d) → Purged (7d~)
```
- Active: Full logs (up to 200 lines of stdout/stderr), thread info, input properties, result retained
- Archived: Thread list and input properties removed, stdout trimmed to 50 lines and stderr to 20 lines. **The run's result (`resultData`) is kept until purge** (1.15.1+ — older versions dropped it at archival, leaving only the 300-char `resultSummary`)
- Purged: Deleted from disk

(Windows are the `propertee.teebox.runRetentionMs` / `runArchiveRetentionMs` system properties — see §1.)

**Task:**
- Same retention model (`propertee.task.retentionMs`, `propertee.task.archiveRetentionMs`)

### dataDir Structure

```
dataDir/
  runs/           # run state JSON files (one <runId>.json per run)
  tasks/          # task metadata, stdout/stderr logs (one task-<id>/ dir per task)
  script-registry/ # registered script versions
  users/          # admin-UI login roster + password hashes (see §1)
  webhooks/       # webhook delivery outbox (only when webhookEnabled)
```

**Index files are gone (1.14+).** `runs/index.json` and `tasks/index.json` no longer exist — run
and task listings are served from in-memory indexes, rebuilt from the data files at startup.
Operational notes:

- A leftover legacy `index.json` from an older version is **deleted automatically at startup**. If
  it cannot be deleted (permissions), **TeeBox refuses to start** with a message naming the file —
  remove it manually. (A stale index would make a rolled-back older TeeBox hide runs/tasks written
  since.)
- Rolling back to a pre-1.14 version is safe: the old version rebuilds its index from the data
  files when the file is missing.
- Do not place stray files in `runs/` — every `*.json` there is scanned at startup. A corrupt or
  foreign file is skipped with a warning (a run file's `runId` must match its filename), never
  blocking startup.

---

## 7. Monitoring

### Admin Dashboard

`http://<host>:<port>/admin` — Live dashboard.

- Active/queued run status
- JVM memory and disk usage
- Auto-refresh (5-second interval, toggleable)

### Health Endpoint

```bash
curl http://127.0.0.1:18080/health
```

### Run Detail

Information available on the run detail page:
- **Script Output**: ProperTee `PRINT()` output
- **Script Errors**: `PRINT_ERR()` output
- **Task Output**: stdout/stderr of each task (external process)
- **Input Properties**: Input values passed to the run
- For runs in progress, output is tracked in real time via auto-refresh.

### Run Status Lifecycle

Runs transition through these states:

| Status | Meaning |
|--------|---------|
| QUEUED | Run is in global thread pool queue, waiting for a worker |
| PENDING | Run is blocked by per-script concurrency limit (`maxConcurrentRuns`) |
| RUNNING | Run is actively executing |
| COMPLETED | Run finished successfully |
| FAILED | Run finished with error |
| SERVER_RESTARTED | Run was interrupted by server restart |

**Typical transitions:**
- `QUEUED → RUNNING → COMPLETED/FAILED` — normal flow
- `PENDING → QUEUED → RUNNING → COMPLETED/FAILED` — when blocked by script limit
- `RUNNING → SERVER_RESTARTED` — if server killed mid-execution

The dashboard Active Runs section shows QUEUED + PENDING + RUNNING. The "queued" counter in the top bar counts QUEUED + PENDING together.

### Logging

Log4j2-based. Output is written simultaneously to the console (stderr) and to a file.

**Log file location**: Configured via the `propertee.teebox.logDir` system property (default: `logs/`)

```
logs/
  teebox.log              # current log
  teebox-2026-03-24-1.log.gz  # rolled log
```

**Rolling policy:**
- Rolls on 50MB file size or daily
- Up to 30 files retained, then automatically deleted

**Changing configuration**: Edit `conf/log4j2.xml`, or specify a separate configuration file via the `PROPERTEE_TEEBOX_LOG4J` environment variable.

**Log format:**
```
2026-03-24 10:30:15.123 [INFO ] [AUDIT] ALLOWED runId=run-abc command=/path/script.sh
2026-03-24 10:30:20.456 [ERROR] [RunManager] Run failed: run-abc -- RuntimeException: ...
```

**Access log:** a dedicated `access` logger writes **one line per `/api/*` request** — method,
path (+query), client IP (first `X-Forwarded-For` hop honored), response status, elapsed ms:

```
GET /api/client/runs?limit=10 from 127.0.0.1 -> 200 (4ms)
```

Request/response **bodies are never logged** (they can carry tokens, script source, or large
payloads). `/admin`, `/health`, and `/` are not access-logged. Tune or silence it independently in
`log4j2.xml` via `<Logger name="access" level="..."/>`.

**Key log components:**

| Component | Contents |
|-----------|----------|
| `TeeBox` | Server startup/shutdown |
| `access` | One line per `/api/*` request (see above) |
| `AUDIT` | Task command allow/block |
| `API` | API request errors |
| `AdminUI` | Admin UI errors |
| `RunManager` | Run execution failures, flush/maintenance errors |
| `TaskEngine` | Task lifecycle errors, process group kill failures, legacy-index cleanup |
| `RunStore` | Run store I/O errors, skipped unparseable run files, legacy-index cleanup |
