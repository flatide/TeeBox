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
| `dataDir` | (required) | Data directory (runs, tasks, script-registry) |
| `maxRuns` | `64` | Maximum number of concurrent runs |
| `apiToken` | none | Bearer token shared across all APIs (fallback) |
| `clientApiToken` | none | Token specific to `/api/client` |
| `publisherApiToken` | none | Token specific to `/api/publisher` |
| `adminApiToken` | none | Token specific to `/api/admin` |
| `runRetentionMs` | `24h` | Run retention before transitioning to archived |
| `runArchiveRetentionMs` | `7d` | Archived run retention before deletion |
| `maintenanceIntervalMs` | `1m` | Background maintenance interval |
| `streamRoots` | `dataDir` | Allowed roots for `STREAM_FILE` results (a `File.pathSeparator`-separated list of directories; `:` on Linux/macOS, `;` on Windows). A streamable file path must canonicalize within one of these. See §3. |
| `webhookEnabled` | `false` | Enable run-terminal webhook delivery (opt-in). When off, a run submitted with a `callback` is rejected with HTTP 400. See §3. |
| `webhookUrlAllowlist` | none | **Comma-separated** `host[:port]` allowlist for callback URLs (required when enabled — an unset allowlist rejects every callback). A `host` entry matches any port; `host:port` must match exactly. |
| `webhookTimeoutMs` | `10000` | Per-POST connect/read timeout (ms) for webhook delivery. |

Environment variables:
- `PROPERTEE_TEEBOX_CONFIG` — Path to the configuration file (default: `conf/teebox.properties`)
- `JAVA_HOME` — Java installation path
- `JAVA_OPTS` — JVM options (`-Xmx`, `-D`, etc.). System properties take precedence over the configuration file.

Duration format:
- `runRetentionMs`, `runArchiveRetentionMs`, `maintenanceIntervalMs`
- `propertee.task.retentionMs`, `propertee.task.archiveRetentionMs`
- Supported suffixes: `ms`, `s`, `m`, `h`, `d`
- Examples: `500ms`, `30s`, `1m`, `24h`, `7d`

### Running

```bash
./bin/run-teebox.sh
```

### Dependencies

- Linux x86_64 Java 25 runtime (`runtime/bin/java`) or system Java 25+
- `setsid` (util-linux) — Required for task process group isolation. Included by default on Linux.
- For development, a `../propertee-java` composite build is required.

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

1. **Register script**: `POST /api/publisher/scripts/{scriptId}/versions/{version}`
2. **Activate version**: `POST /api/publisher/scripts/{scriptId}/activate/{version}`
3. **Submit run**: `POST /api/client/scripts/{scriptId}/runs`
4. **Poll results**: `GET /api/client/runs/{runId}`

Recommended operational patterns:
- A job-submit script should exit as soon as it obtains the job id.
- Job status polling should be split into a separate short script and invoked periodically by an external scheduler or cron.
- Avoid patterns that launch a background job inside a single ProperTee run and then perform a long `wait` or maintain a polling loop within the same run.

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

**Lifecycle (reference-only):** the descriptor only references the path — TeeBox does not copy or own the file. The file must outlive the result fetch, and the stream result is available during the active window (resultData is nulled when the run is archived after 24h).

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

### `SLEEP()` Nesting Caveat (current runtime behavior)

`SLEEP(ms)` is cooperative (non-blocking) only at a statement's top level, or when spawned directly as a worker (`thread a: SLEEP(500)`). When `SLEEP` runs **inside a `loop`, function, `if`, or `monitor` body**, the current ProperTee Java runtime honors it with a **blocking** `Thread.sleep` fallback:

- The sleep duration is correct (it no longer silently returns instantly).
- But it **blocks that run's scheduler thread**, so the run's other `multi` workers and `monitor` ticks do **not** advance during the sleep.
- Other runs are **not** affected — each run executes on its own pool thread.
- Single-threaded scripts are unaffected.

Practical guidance: for periodic work, prefer a short script invoked by an external scheduler/cron over a long `loop … do SLEEP(...) end` inside one run; and avoid `SLEEP` inside a `multi`/`monitor` body if you rely on concurrent worker/monitor progress. (A future runtime release makes nested `SLEEP` fully cooperative.)

---

## 6. Data Management

### Retention

**Run:**
```
Active (0~24h) → Archived (24h~7d) → Purged (7d~)
```
- Active: Full logs (up to 200 lines of stdout/stderr), thread info retained
- Archived: Thread list removed, stdout trimmed to 50 lines and stderr to 20 lines
- Purged: Deleted from disk

**Task:**
- Same retention model (`propertee.task.retentionMs`, `propertee.task.archiveRetentionMs`)

### dataDir Structure

```
dataDir/
  runs/           # run state JSON files
  tasks/          # task metadata, stdout/stderr logs
  script-registry/ # registered script versions
  webhooks/       # webhook delivery outbox (only when webhookEnabled)
```

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

**Key log components:**

| Component | Contents |
|-----------|----------|
| `TeeBox` | Server startup/shutdown |
| `AUDIT` | Task command allow/block |
| `API` | API request errors |
| `AdminUI` | Admin UI errors |
| `RunManager` | Run execution failures, flush/maintenance errors |
| `TaskEngine` | Task index/lifecycle errors, process group kill failures |
| `RunStore` | Run store I/O errors |
