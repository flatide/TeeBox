# Changelog

All notable changes to TeeBox are documented here.

## 1.25.3

Third review round on the debug feature — honest command outcomes:

- **A never-executed command can no longer report success.** A resume command drained at
  session end (e.g. the duplicate Continue queued during the run's LAST pause) used to return
  `accepted=true`. Commands now carry an explicit rejected state: stale-pause refusals and
  session-end drains both return `accepted=false` with the reason for every command kind, and
  the by-id outcome (`GET .../command/{id}`) marks them `rejected`.
- **A mistyped `generation` field is a 400, not a silent unpin.** `"generation":"3"` (or a
  non-integral number) used to be treated as "unspecified", quietly disabling the stale-frame
  protection the client asked for. Present-but-invalid now rejects; absent or JSON null stays
  "unpinned".
- **The console polls every timed-out command, not just evals.** A Continue/Step queued behind
  a long eval can time out too; any `timedOut` response with a `commandId` is now polled to its
  final outcome in the console log.

## 1.25.2

Second review round on the debug feature — command/state atomicity and console result polling:

- **Command handling and session state transitions now serialize on one monitor.** The 1.25.1
  generation guard still left a window: a command could pass the PAUSED check, stall, and
  enqueue after the session resumed — landing in a drained (ended) queue where nothing would
  ever answer it, or capturing a different pause's generation than the one it checked. Now
  `command()`'s state check + generation capture + enqueue, the handler's pause/resume
  transitions, and `finalizeSession`'s ENDED+drain all take the session monitor, so a command
  is enqueued if and only if the pause it validated is still current, and never after the final
  drain (`wakeForCancel` is guarded the same way).
- **Clients can pin a command to the pause they saw.** The status payload now carries
  `pauseGeneration`; sending it back as the command's optional `generation` field makes the
  server refuse (409) when the session has since paused somewhere else — a remote caller never
  acts on a frame it never looked at. The console sends it automatically with every
  continue/step/eval.
- **The console now shows long-eval outcomes.** The session-authed
  `GET /admin/debug/{sid}/command/{commandId}` route mirrors the API one, and the debug page
  polls it whenever a command reports `timedOut` — the final return value or error of a
  long-running eval reaches the browser instead of stopping at "still evaluating". The command
  wait is tunable (`-Dpropertee.teebox.debugCommandWaitMs`, default 15s), and the real timeout
  path is now exercised by test end-to-end.

## 1.25.1

Review hardening of the 1.25.0 debug feature — three fixes plus one documented decision:

- **Documented decision: a debug re-run executes the CURRENT content of the recorded script
  version.** Editing the version after the failure and then re-debugging is a supported
  workflow, not a fidelity bug (user decision; the eventual goal is editing the source from
  within a debug session and re-running). Consequence: the failing-line auto breakpoint assumes
  the text hasn't moved. Stated in the UI (confirm dialog + console banner) and pinned by test.
  The related real race IS fixed: the source run's properties/settings are now copied under the
  same monitor as the archive check, so the maintenance archiver can no longer clear the
  properties between check and copy (which would have replayed empty inputs).
- **Duplicate/stale commands can no longer consume a later pause.** Every pause bumps a
  generation; a command carries the generation it was issued against, and the handler refuses
  (as `conflict`) any non-quit command from an earlier pause — a double-sent Continue no longer
  skips the next breakpoint, and a retried eval cannot run in the wrong frame. Every command
  response now carries a `commandId`; a command whose 15s wait timed out stays queued and its
  outcome is queryable via `GET /api/admin/debug/{sid}/command/{commandId}` instead of being
  retried (double-executed). The console UI also disables the resume buttons the moment one is
  clicked.
- **open/shutdown race closed.** The shutdown flag flip + debug-executor close now serialize
  with `open()`'s prepare+submit under the same lock, and a submit that still loses the race
  terminalizes its already-registered run (CANCELLED) instead of leaving it QUEUED forever.
- **Malformed debug requests are 400s, never "no body".** The open endpoint used to swallow any
  body error (broken JSON, wrong `breakpoints` type, oversize) and proceed to execute the
  script; now only a genuinely empty body means "auto breakpoint only". Breakpoint lists reject
  non-numeric elements outright (a malformed list must not half-apply), and malformed JSON on
  any debug endpoint (and `parseJsonBody` users generally) is a 400 rather than a 500.

## 1.25.0

- **Interactive debug re-runs of finished runs** (requires the propertee2 0.26.0 engine — its
  new v1-façade debug hooks). A terminal run — typically a FAILED one — can be re-executed
  under the engine debugger: **Debug Re-run** on the run detail page (admins only; eval is code
  execution) opens a live console at `/admin/debug/{sessionId}` with pause state, the paused
  statement + call stack, locals/globals, an eval console (read AND write the paused scope),
  step over/in/out, continue, quit, and live line breakpoints. When the source run failed with
  a positioned error, a breakpoint is pre-set on the failing line, so the session pauses just
  before the statement that failed — with the exact input properties and script **version** of
  the original run.
- **`DebugSessionManager` — separate from the run pool by design.** A paused session holds its
  engine fiber frozen for as long as the operator thinks, so debug runs execute on a small
  dedicated executor (`propertee.teebox.debugMaxSessions`, default 2; opening beyond the cap is
  rejected) and never occupy production `maxRuns` slots. Debug runs are exempt from the run
  execution timeout and per-script concurrency; abandonment is handled by an idle sweep instead
  (`propertee.teebox.debugIdleTimeoutMs`, default 30m — any API touch, status polling included,
  keeps a session alive). Ending a session — quit, admin cancel of the debug run, idle sweep,
  shutdown — lands as **CANCELLED, never FAILED** (the engine's `DebugQuit` is mapped like an
  abort; a paused run is additionally woken via the session's command queue, since an engine
  abort alone cannot unwind a fiber parked in the debug handler).
- **JSON API** (admin token): `POST /api/admin/runs/{runId}/debug` (optional
  `{"breakpoints":[..]}`) → session; `GET /api/admin/debug[/{sessionId}]`;
  `POST /api/admin/debug/{sessionId}/command` (`continue` / `stepOver` / `stepIn` / `stepOut` /
  `eval` + `source` / `quit`); `PUT /api/admin/debug/{sessionId}/breakpoints`. Runs record
  `debug`/`debugOf`/origin `"debug"`, and the run detail page links the lineage both ways.
- Guards: only terminal, non-archived source runs (archiving drops the input properties, so a
  faithful re-run is impossible); a debug re-run **re-executes side effects** (SHELL/HTTP/file
  writes) — the UI says so before opening a session. Known limit (engine): breaks fire on the
  main thread only — `thread` worker and `monitor` bodies do not pause.

## 1.24.0

- **Regular-user (`user` role) permissions tightened for runs, with a run origin identifier.**
  Every run now records **`origin`**: `"ui"` (submitted from the TeeBox admin UI) or `"api"`
  (external client API); runs persisted before the field read back null and are treated like
  external. Kill-tasks / Cancel in the admin UI now require, for a regular user, that the run
  is **their own UI submission** (`origin == "ui"` and `submittedBy` == their username) — an
  API-submitted run is admin-only in the UI even when it executes the user's own script (the
  previous rule keyed on script ownership, which let a user kill external callers' runs). The
  runs list shows a `ui`/`api` tag next to the submitter, the run detail page gains an Origin
  field, and the run status/summary/result payloads carry `origin` alongside `submittedBy`.
  Already enforced and unchanged: system functions (shutdown, user management) are admin-only,
  and running/editing/deleting another user's script is denied by ownership. `/api/*` stays
  token-gated and unrestricted.

## 1.23.0

Hardening round from a code review — five high findings plus five medium, all confirmed and fixed:

- **Account takeover window closed (first-login provisioning).** Adding a user and resetting a
  password used to leave the account claimable by whoever logged in first with any password.
  Now the admin can set an **initial/temporary password** at add and reset time (recorded
  immediately — no claimable window; blank keeps the legacy first-login flow as an explicit
  choice), and every logged-in user gets a **self-service password change page**
  (`/admin/password`, top-right Password button: current + new + confirm; the user's *other*
  sessions are logged out on change, the changing one stays). First-login provisioning itself
  is now **atomic** (check-and-set under one lock — two concurrent first logins can no longer
  both win), and a **corrupt `credentials.json` fails closed**: all UI logins are refused until
  the operator repairs or consciously removes the file (it used to silently start empty,
  putting every account back into the claimable state).
- **Negative `captureGroup` no longer wedges a run.** The API/form accepted negative groups,
  `matcher.group(-1)` threw on every match, and the completion flush re-threw from the error
  path — the run never reached a terminal state (permanently RUNNING). `normalize()` now
  clamps `captureGroup` to >= 0 (0 = full match), and the watcher flushes are **isolated**:
  a throwing watcher is logged and dropped, never blocking the run's terminal transition or
  the shared 2s flush tick.
- **Output watcher end-of-run race fixed.** The periodic scan (maintenance thread) and the
  run-completion flush (run executor thread) could hit the same watcher concurrently — its
  offsets/remainders/capture counts are plain mutable state, so captures could duplicate or
  overrun `maxCaptures`. `scan()`/`finalScan()` are now serialized per watcher.
- **Remaining persistence writes made atomic** (temp + rename): `script.json`, version `.tee`
  sources (an in-place Save could destroy the existing source on a crash/full disk), and task
  `meta.json`/`archive.json` (a truncated meta loses the running process's tracking record
  across a restart). New shared `AtomicFiles` helper; RunStore/UserStore already had it.
- **Embedded engine is now traceable.** The composite build embeds whatever `../propertee2-java`
  has checked out, so a TeeBox commit alone doesn't pin the engine. Every build now stamps the
  engine version + git commit (with a `-dirty` suffix for local changes) into
  `teebox-version.properties`; it shows in the startup banner and the admin nav
  ("TeeBox v… · engine …"). Release procedure: record the stamped engine version/commit in the
  release notes — reproducing a jar means checking out that engine commit.

Medium findings:

- **Shutdown order fixed**: `RunManager.shutdown()` used to clear watchers and tear down the
  shared task engine *before* awaiting the run executors — an in-flight `SHELL()` died with
  "Unknown task" and its final captures were lost. Now: stop accepting runs → await the
  executors (flush/timeout schedulers stay alive during the drain) → then schedulers, webhooks,
  watchers, registry flush, and the task engine last.
- **Hand-edited roster changes apply to live sessions**: session resolution now revalidates
  against the current `users.json` on every request. The roster parse is cached by the file's
  **content bytes** (not mtime/length, which can miss a same-length edit within the timestamp
  granularity — and this is the permission revocation path; the file is tiny, so the
  per-request read is cheap). A user removed by hand loses their session on the next request
  instead of riding out the 8h window; a hand-edited role change takes effect immediately —
  pinned with a deliberately same-byte-length role swap.
- **Renderer viewer identity is per-request again**: `AdminPageRenderer` kept the viewer's
  name/role in singleton fields while the HTTP pool renders concurrently, so one user's page
  could show another's identity and buttons (display-only — every POST is server-gated — but a
  leak). The viewer context is now a thread-local set at handler entry and per request.
- **Request bodies are capped at 10MB** (`/api` and `/admin`, the pre-auth login POST included):
  a declared oversize is rejected from the Content-Length header before buffering, and the
  streaming read enforces the cap regardless (chunked bodies carry no header).
- **UTF-8 characters split across the watcher's 64KB read boundary survive**: the undecoded
  tail is now kept as bytes and decoding stops at a newline (a hard character boundary), so a
  multi-byte character straddling two reads no longer decodes into replacement characters and
  Unicode captures are no longer missed.

Follow-up high findings on the fixes above:

- **Watcher capture ORDERING race closed for real**: serializing `scan()`/`finalScan()` alone
  still allowed the periodic tick to read older content, lose the CPU, and apply it to the
  run's published map AFTER the completion flush applied newer content — captures out of order
  and the "latest" value rolled back. The scan and its apply now run under the watcher monitor
  as one unit at both call sites.
- **Shutdown no longer strands per-script PENDING runs as QUEUED**: a run finishing during the
  executor drain triggered `dequeueNextRun`, which promoted the pending run to QUEUED and
  submitted it into the already-closed executor (`RejectedExecutionException`, run stuck in a
  non-terminal QUEUED). `dequeueNextRun` now honors `shutdownRequested` (the run stays PENDING
  — startup recovery reports it honestly as SERVER_RESTARTED), with a defensive
  revert-to-PENDING if the submit still races the flag; `submit()` rejects during shutdown
  like it does while draining; and after a forced `shutdownNow()` the executors are awaited
  again so interrupted runs finish unwinding before the watchers and the task engine are torn
  down. Pinned by a live test: 1 RUNNING + 1 PENDING at `stop()` — the active run completes
  during the drain and the pending run ends PENDING, never QUEUED.

- **Submit vs shutdown TOCTOU closed**: a submit that passed the entry check could still park a
  PENDING run or hit the already-closed executor after shutdown began (stranding a QUEUED run).
  The shutdown flag flip + executor close and submit's final park/submit decision now serialize
  under one lifecycle lock with an authoritative re-check (a run caught by it is marked FAILED
  "Server is shutting down" and the caller gets the same rejection as the entry check). Lock
  order lifecycleLock → per-script count → run monitor, taken nowhere in reverse.
- **`startDraining()` joins the same lifecycle lock**: it used to flip `draining` under the
  `this` monitor, so a submit holding the lifecycle lock could pass the draining check while the
  drain thread — seeing nothing enqueued yet — declared the drain complete and exited the JVM
  under a freshly accepted run. The flag check-and-set now happens under `lifecycleLock`:
  submit-first means the drain poll counts the enqueued run; drain-first means submit's
  authoritative re-check rejects.
- When the post-interrupt grace also fails (a run ignoring interrupts), the teardown proceeds —
  an embedded `stop()` cannot wait forever — but now logs an ERROR naming the still-active run
  IDs and the voided assumption (their captures may be partial; their SHELL calls die once the
  engine closes). Standalone process exit is unaffected.

Low findings:

- **`activeRuns` no longer leaks a completed Future for very fast runs**: the entry was put
  *after* `executor.submit()`, so a run that finished before the put re-inserted its already
  completed Future, which lingered until run purge. The put and the worker's cleanup-remove now
  order themselves through the shared run monitor.
- **Gradle 10 readiness**: the last Groovy space-assignment (`testLogging` events /
  exceptionFormat) converted to explicit `=` assignment — `--warning-mode all` is deprecation-
  clean.

## 1.22.0

- **Admin UI: user management** (`/admin/users`) — a logged-in **admin** gets a **Users** menu
  for the roster: add users (role `user`/`admin`; no password entered — the new user sets it on
  first login, per the existing flow), change roles, reset passwords (drops the credential so
  the next login records a new one), and delete users (roster entry + credential). Role
  changes, resets and deletions **invalidate the target's live sessions immediately** — a
  deleted user's cookie no longer rides out the 8h session window, and a demoted admin loses
  admin powers now, not at next login. The **last remaining admin cannot be deleted or
  demoted** (UI lockout guard); self-service actions on your own account are allowed (with a
  confirm) when another admin remains. Server-side gates: roster mode + admin session required
  on every route (403 for regular users, 409 in open mode — no users to manage); the menu is
  display-only sugar. Roster/credential writes are now atomic (temp + rename) since the UI
  writes them — a crash mid-write can no longer truncate `users.json` (whose parser fails
  closed, which would have locked every operator out). Hand-editing `users.json` keeps working
  and is picked up live. Ops guides updated (and their stale "GET pages stay viewable without
  login" claim corrected — the whole `/admin` UI has been login-gated since the multi-user
  release).
- **Editor: one click on Save saves — it no longer takes two.** The save interception runs the
  syntax check and then resubmits the form, but the client-side check (the primary path since
  it moved into the browser) is synchronous, so the resubmitting `submitter.click()` still ran
  *inside* the original submit event dispatch — and the browser's firing-submission-events
  guard silently drops a re-entrant submission. Result: the first Save only rendered "No
  syntax errors." and armed the pass-through flag; only the second Save actually saved. The
  resubmit is now deferred out of the dispatch (`setTimeout 0`), which also keeps the async
  server-fallback path working. Both submit buttons ride the same handler and both were
  affected. Verified in a real browser against 1.21.0 (one click checked but did not save)
  and against the fix: one click on **Save** overwrites and lands back on `?version=<v>`,
  one click on **Save as new version** creates the next version and lands on it (no
  auto-activate, as designed).

## 1.21.0

- **Editor syntax check catches up to spec v0.19.0 (`multi ... limit K`)**: the inlined
  browser bundle driving the client-side Check syntax / save interception was still the
  v0.18.0-spec build, so it rejected the new concurrency-cap clause with "missing 'do' at
  'limit'" even though the engine accepts it. Refreshed verbatim from the propertee-js
  v0.19.0-spec build (the `limit` keyword highlight landed separately). The next dist build
  embeds engine 0.21.0, so the server-side validate/save parser and actual runs accept the
  new grammar too.

## 1.20.0

- **Editor synced to the propertee2 0.20.0 engine builtins** (spec v0.18.0): **`READ_FILE`**
  (whole file as one string), **`READ_JSON_FILE`** (read + JSON-parse in one call, BOM-
  tolerant — replaces the READ_LINES + JOIN + JSON_PARSE idiom) and **`WRITE_JSON_FILE`**
  (JSON_FORMAT output + trailing newline; lossless round-trip, null included). The server
  lint / `PT_KNOWN` enumerate them from the runtime automatically; refreshed the two static
  assets: the inlined browser bundle (verbatim from propertee-js v0.18.0-spec build) and
  `propertee-editor.js` (highlighter name list + reference-panel catalog entries, ported
  verbatim from the playground). The next dist build embeds engine 0.20.0 (0.19.0's
  SimpleTaskRunner CLI change does not affect TeeBox — it ships its own runners).

## 1.19.0

- **Embedded engine upgraded to propertee2 0.17.0** (spec v0.17.0): scripts gain the literal
  position-search builtins **`FIND(s, sub)`** (all 1-based positions, ascending, overlapping
  included; `[]` if absent), **`FIND_FIRST(s, sub)`** and **`FIND_LAST(s, sub)`** (first/last
  position, `0` if absent). The editor picks them up across all three layers: the server lint
  and the injected `PT_KNOWN` set enumerate them from the runtime automatically, the inlined
  browser bundle is refreshed from propertee-js v0.17.0 (client-side `checkScript` parses
  them), and the syntax highlighter + built-in reference panel gained their entries (ported
  verbatim from the playground). Minimum engine for building TeeBox stays 0.16.0 — the new
  builtins are additive and TeeBox itself does not call them.

## 1.18.0

- **Output-capture rules now target a task by `SHELL()` execution order, not by key.** The
  1.17.0 `taskKey` mechanism (tagging a task via the `TEEBOX_TASK_KEY` env var) proved
  unusable for distinguishing SHELL() calls in practice and is **removed**; the rule field
  **`taskIndex`** replaces it: `0` (default) = the run's first task — identical to the legacy
  behavior — `1` = the second task, and so on. Only successfully launched tasks consume an
  index, and no script changes are needed. Caveat: order is deterministic only for sequential
  SHELL calls (parallel `multi`/`thread` SHELLs are created in scheduling order). A 1.17.x
  script.json carrying `taskKey` loads fine — the unknown field is ignored and the rule falls
  back to `taskIndex` 0 (first task).
- **`firstOnly` is folded into `maxCaptures` — one capture knob.** The capture mode and the
  cap overlapped (`firstOnly: true` ≡ `maxCaptures: 1`, `false` ≡ `0`), so the boolean is gone
  from the rule model: **`maxCaptures`** is the single knob — `1` (the new default) = the
  first match only, `0` = unlimited (every match until the task terminates), `N` = up to N.
  The published shape is now **uniform for every rule**: `key` = latest value, `key.values` =
  ordered capture list, `key.count`, `key.detectedAt` = last capture time (a default rule
  simply holds one value; consumers reading only `key`/`key.detectedAt` are unaffected). The
  admin-UI rule form drops the Mode select (Task Index / Max Captures inputs remain), and the
  run page folds the companion keys into one row per capture key. **Deprecated input alias**:
  raw JSON `firstOnly` is still accepted (`true` → maxCaptures 1; `false` with no explicit
  maxCaptures → 0), so 1.17.x client jars keep working, and persisted 1.17.x rules migrate on
  load (1.17.x wrote `maxCaptures: 0` even for first-only rules — the flag disambiguates).
  Shipped client builders: `outputRule(publishKey, pattern)` (first match, first task) and
  `outputRule(publishKey, pattern, stream, captureGroup, taskIndex, maxCaptures)`;
  `continuousOutputRule` and the boolean `firstOnly` overload are removed — recompile
  embedders that used the 1.17.x signatures.

## 1.17.1

- **Editor: pressing Enter on a horizontally scrolled long line no longer leaves the text flush
  against the line-number gutter.** The 12px gap left of the code was `padding-left` inside the
  scrollable content, so the browser's minimal scroll-caret-into-view left `scrollLeft` resting
  at ~12px and column 0 rendered tight against the gutter from then on. The gap now lives
  outside the scroll area (textarea `margin-left` + syntax-overlay `left` offset), so column 0
  always sits 12px from the gutter, scrolled or not. Ported from the same fix in the ProperTee
  playground (propertee-js e4a3236); the companion horizontal scroll-sync fix (3aabe34) was
  already present in the TeeBox port.

## 1.17.0

Output capture learns to keep capturing. Continuous rules existed in name only (`firstOnly:
false` was accepted but dead): a stream with only continuous rules was never even read, at most
one match per scan chunk was taken, and the publish layer froze every key at its first value.

- **Continuous capture (`firstOnly: false`) now works**: every match is captured — all matches in
  a chunk, across scans — until the task terminates or the rule's new **`maxCaptures`** is
  reached (`0` = unlimited, the default). Continuous keys publish `key` = latest value (so
  `waitForPublished` is unchanged), `key.values` = the ordered capture list, `key.count`, and
  `key.detectedAt` = last capture time. firstOnly rules keep their exact legacy shape.
- **Rules can target a task by key** (new rule field **`taskKey`**): the script tags a task via
  the reserved env var `TEEBOX_TASK_KEY` — `SHELL("cmd", {"env": {"TEEBOX_TASK_KEY":
  "worker1"}})` — and a rule with `taskKey: "worker1"` watches that task instead of the run's
  first (keyless rules keep the first-task behavior; first task per key wins when several share
  one). Settable via the Publisher API, the shipped client, and the admin-UI rule form (new
  Mode / Task Key / Max Captures fields).
- **Completion no longer loses tail output**: the final watcher flush now drains the log to EOF
  (it used to read at most one 64KB chunk, so a fast-writing task's late matches could be
  missed even with firstOnly). The periodic scan's budget rises 64KB → 1MB per 2s tick.
- **Restart fidelity**: reloaded `published` numeric metadata (`key.detectedAt`, `key.count`) is
  normalized back to integers — it used to be served as `1.7E12`-style doubles after a restart.
- Shipped client (`client/…/TeeBoxClient.java`, Java 7): `continuousOutputRule(publishKey,
  pattern, stream, captureGroup, taskKey, maxCaptures)` builder and
  `waitForPublishedCount(runId, key, minCount, timeoutMs)` (returns `key.values` once
  `key.count` reaches `minCount`). Both need a 1.17.0+ server; older servers capture only the
  first match.

## 1.16.0

Run cancellation, run execution timeouts, and runaway-run containment — built on the engine's new
cooperative abort API (propertee2 0.16.0). Until now a script stuck in an `infinite` loop could not
be stopped at all: the kill endpoints only reach SHELL child processes, drain waits for active
runs, and a spinning script pinned a virtual-thread carrier (JDK vthreads are not time-sliced),
degrading every other run on the server.

- **Cancel a run**: `POST /api/client/runs/{id}/cancel` (client token; `X-TeeBox-User` recorded in
  the reason for audit) and `POST /api/admin/runs/{id}/cancel`, plus a Cancel Run button on the
  admin run page (owner-checked like kill-tasks). QUEUED/PENDING runs cancel immediately (the
  per-script concurrency slot is released — a leaked slot would deadlock the script); a RUNNING
  run is aborted cooperatively and its SHELL tasks are killed on a background thread, so the
  endpoints return **202** at once and callers poll to `CANCELLED`. A run that completes before
  the abort lands stays `COMPLETED` — a finished run is never flipped.
- **New terminal status `CANCELLED`** (reason in `errorMessage`: who cancelled, or the timeout),
  wired through the terminal sets (archival/purge, webhook delivery, both TeeBoxClients'
  `waitForRunTerminal`), the run envelope (`{status:"error", ok:false, value:<reason>}` — a
  cancelled run never polls as still-running), the runs-page filter, and swagger. Rollback note:
  a pre-1.16 TeeBox reading a persisted CANCELLED run parses its status as null and flips it to
  SERVER_RESTARTED on startup — cosmetic only.
- **Run execution timeout**: per-run `timeoutMs` submit field, server-wide `runTimeoutMs` config
  default (off unless set; duration syntax). The clock starts at RUNNING — queue wait does not
  count, so a busy server never times out runs that were never given CPU. Expiry goes through the
  same cancel path (`CANCELLED`, reason `"Cancelled: run exceeded timeout (N ms)"`).
- **Runaway containment** (engine side, propertee2 0.16.0): the abort checkpoint also
  `Thread.yield()`s every 1024th statement/iteration, so one CPU-bound script can no longer starve
  the other runs' virtual threads of carrier time.
- **Script-output truncation is now visible**: the run stdout/stderr endpoints report
  `totalLineCount` and `truncated` alongside the ring-buffered `lines` (the cap itself is now
  configurable via `runOutputMaxLines`, default unchanged at 200 — raising it multiplies run
  memory, so it stays conservative).
- Shipped client (`client/…/TeeBoxClient.java`, Java 7): `cancelRun(runId)` + CANCELLED in the
  terminal set — without it, `waitForRunTerminal` would spin on cancelled runs until timeout.
- Known limit (inherent to the cooperative engine): a host call that never returns delays the
  abort until it returns. In practice that is only SHELL without a `timeout` option — and the
  cancel kills the run's tasks, which unblocks the wait; the HTTP builtins have 30s timeouts.

Pinned by `RunCancelTest` (cancel across RUNNING/QUEUED/PENDING, slot release, thread teardown,
task kill, 404/409, admin UI redirect+notice, output cap) and `RunTimeoutTest` (per-run, server
default, off-by-default, no stray cancel of fast runs, queue wait excluded). 216 tests green.

## 1.15.2

- **Admin UI: killing a task no longer freezes the page.** The kill button used to run the whole
  termination sequence on the HTTP handler thread before responding — SIGTERM→SIGKILL escalation
  with 1s exit polls, a 500ms exit-code grace read, meta persistence, all while holding the
  per-task lock — so the browser sat on a pending POST for 3–4+ seconds (more when the process was
  stuck in uninterruptible I/O), and the page's 5s auto-refresh queued up behind the same lock,
  reading as a hang. `POST /admin/tasks/{id}/kill` and `POST /admin/runs/{id}/kill-tasks` now hand
  the kill to a background thread and redirect immediately with `?killRequested=1`; the target
  page shows a "Kill requested" notice and its auto-refresh picks up the KILLED state when the
  kill lands. Kill-all over a run's N tasks (killed serially) benefits the most. The `/api/admin`
  kill endpoints are unchanged — still synchronous, their response still reports the kill outcome.
  Pinned by `adminUiKillShouldRedirectImmediatelyAndKillInBackground`.
- **Editor: the syntax pre-check now runs client-side via the propertee-js `checkScript`.** The
  Check syntax button and the save interception used to POST to `/admin/scripts/validate` on every
  click; the ProperTee JS engine now ships a one-call `checkScript` (syntax + built-in typo lint,
  written to mirror that endpoint), so its browser bundle is inlined on the script detail page and
  the check is instant with no server round-trip. The known-name set is NOT the JS engine's own:
  the page injects the Java-runtime-enumerated catalog (engine + TeeBox host builtins — the same
  set the server lint uses), so the two checks cannot disagree on what is a known function
  (verified live: identical verdicts and identical unknown-function messages). If the bundle is
  unavailable the old server POST is the automatic fallback, and server-side save validation stays
  the backstop regardless. Costs ~360KB inlined on the script detail page only (TeeBox serves no
  static files); the dashboard and list pages stay lean.
- **Editor: the built-in function panel is now hidden by default, toggled by a ƒ button.** The
  reference panel took a third of the editor width but most edits don't need it. A small ƒ button
  pinned to the editor's top-right corner shows/hides the panel (the resize handle hides with it),
  and the choice persists per browser (localStorage), so operators who keep it open keep it open.
  Editor layout, highlighter, and panel content are unchanged.

## 1.15.1

- **A run's result now survives archival.** Archiving (terminal age past `runRetentionMs`,
  default 24h) used to null `resultData`, leaving only the 300-char `resultSummary` — fetching a
  day-old run's result returned a truncated string instead of the value the script produced. The
  result is the run's product, so it is now kept intact (in memory and in the run file) until the
  run is purged (`runArchiveRetentionMs`, default 7d); the client `/result` endpoint, admin run
  detail, and stream-result descriptors keep working for archived runs. Everything else about
  archival is unchanged: stdout/stderr still trim to 50/20 lines, threads and input properties are
  still dropped. Note the heap trade-off — archived results stay resident for the archive window;
  scripts with large payloads should return `STREAM_FILE` (tiny descriptor, bytes stream from
  disk). Pinned by `archivedRunKeepsItsResultDataWhileTrimmingTheRest`.
- **Fix: "Save as new version" no longer flips the editor back to the old version (perceived
  version-content swap, with silent data loss).** After saving a new version the redirect dropped
  `?version=`, and the detail page's fallback selected the ACTIVE version — the new version never
  auto-activates, so the editor silently reloaded the OLD content. A user who kept editing and hit
  **Save** (which targets the displayed version) then overwrote the old version with content meant
  for the new one: the previous version ended up holding the newest edits while the new version
  kept the earlier draft ("contents swapped"), and the old version's original source was destroyed
  without warning. The save-as-new redirect now lands on the version it just created
  (`?version=<assigned>`, reported by the registry rather than inferred from version ordering),
  so the editing session continues on the new version. The **Save** button is also labeled with
  its overwrite target (e.g. `Save (3)`) so the destructive action always names where it writes.
  Pinned by `saveAsNewVersionLandsOnTheNewVersionNotTheOldActive`.

## 1.15.0

- **Adopts the propertee2 0.15.0 namespace** (`com.flatide.propertee2.*`). The engine moved its
  v1-API compatibility packages from the bare `com.flatide.{core, interpreter, platform, runtime,
  scheduler, task, parser}` to `com.flatide.propertee2.*`; this release is the matching import
  sweep — 25 files, imports and fully-qualified references only, zero behavior change. Maven
  coordinates are unchanged (`com.flatide:propertee-core`). 202 tests green.

## 1.14.0

- **Perf: run listing/counting is served from memory; the on-disk run index is gone.** Every runs
  list/count (admin UI fragments, `/api/admin/runs`, `/api/client/runs`, per-script listings) used
  to re-read and re-parse the whole `runs/index.json` and load a run file per returned row — and,
  worse, every run state transition (submit → RUNNING → terminal → archive, plus each purge)
  rewrote that entire index, an O(all retained runs) write serialized under the same lock the read
  path needs. With the default 7-day retention this was the first scaling bottleneck at high run
  volumes. The registry's in-memory map already holds every non-purged run, so queries (status /
  instant / search filters, ordering, pagination — semantics unchanged) now run entirely in memory,
  and `runs/index.json` is no longer written at all: startup recovery scans the run files directly,
  a leftover index from an older version is deleted at startup (a rollback then rebuilds a fresh
  one instead of trusting a stale copy that would hide newer runs), and purging N runs is now N
  file deletes instead of N full index rewrites. Per-run files are still written on every state
  transition (durability is unchanged), and list requests no longer flush dirty runs to disk on
  the request thread. Filtering reads each run under its monitor (the same one state transitions
  write under), so listings see the latest status. Startup recovery is hardened for the new
  scan-everything model: a corrupt or foreign `.json` in `runs/` (invalid JSON, wrong shape, or a
  `runId` that does not match its filename — the runId is reused as the write path, so a mismatch
  could redirect recovery writes outside the runs directory or shadow another run) is skipped with
  a warning instead of blocking startup, and if a leftover legacy index cannot be deleted (after
  retries) the server refuses to start — silently keeping a stale index would make a later
  rollback hide every run written since. Pinned by `RunRegistryListTest`.
- **Perf: the task index moved in-memory too; `tasks/index.json` is gone.** Same pathology as the
  run index: every task save re-read and rewrote the whole file, and every task listing re-parsed
  it. The index now lives in memory — built once from the task-dir scan `init()` already does for
  restart recovery, kept incremental by save/archive/delete — and the 60-second retention sweep
  picks its candidates from the in-memory entries instead of re-reading every retained task's
  JSON from disk each cycle (only actionable tasks are materialized, with a fresh re-check before
  acting). The sweep now also refreshes restart-restored tasks the runner doesn't own: their
  process exiting used to be noticed only when a task listing happened to materialize them, so
  without it a restored task whose process died would show "running" forever and never age into
  archive/purge — now the sweep alone takes it terminal and archives it. A leftover legacy
  `tasks/index.json` is deleted at startup with the same refuse-to-start-if-undeletable rollback
  guard as the run index. Pinned in `ManagedTaskEngineTest`.
- **Perf: admin runs tables no longer fetch each row's tasks.** The dashboard and all-runs tables
  ran a full task-index query per run row — and materialized every task, costing a disk read per
  archived task — just to show the task count and killed/lost badges. One index pass now serves
  all rows (`taskStatusesByRun`); the run detail page keeps the full task fetch (it renders the
  task table anyway).
- **Perf: dataDir size walk is TTL-cached (30s).** `GET /api/admin/system` and the dashboard
  sysinfo fragment walked runs/tasks/script-registry (up to 3×10000 file stats) on every call —
  every 5 seconds per viewer with auto-refresh on. Expiry admits exactly one walker
  (double-checked lock), so concurrent viewers hitting an expired TTL reuse its result instead of
  each re-walking. Sizes are informational and slow-moving; memory/uptime/disk-free readings stay
  live.

## 1.13.0

- **Editor: syntax check before saving.** Saving a script with a syntax error used to bounce to the
  error page. The version-source editor now has an outlined **Check syntax** button, and **Save /
  Save as new version run the same check first** — while it reports problems the save is blocked and
  the errors appear under the form (positioned parser messages). Backed by a stateless
  `POST /admin/scripts/validate` that runs the exact parser the save paths reject with, so the
  pre-check can never disagree with the save; if the endpoint is unreachable the save proceeds and
  the server-side validation stays the backstop.
- **Editor: built-in function typo detection.** The same check flags calls like `SHEL(...)` /
  `JSON_PRASE(...)`: all-uppercase names are reserved for built-ins/host functions (spec v0.12.0),
  so an ALL-CAPS call outside the runtime's known set is a guaranteed call-time failure — zero
  false positives. Reports position and the nearest name (`Line 1:4 - unknown function 'SHEL' (did
  you mean 'SHELL'?)`); lowercase calls are never flagged (possible script functions, including
  forward references); dead branches are scanned. The known-name set is enumerated from the runtime
  (new engine host API `BuiltinFunctions.knownFunctionNames`, propertee2-java composite HEAD) plus
  TeeBox's `STREAM_FILE`/`THUMBNAIL`, so engine catalog additions flow in automatically. The
  publisher API's save behavior is unchanged (syntax-only) — the lint blocks only the UI save.
- **Editor: per-version description editing.** The description field prefills with the selected
  version's current description, and plain **Save** writes it back with the content — a
  description-only edit is just Save without touching the code; clearing the field clears it.
  Previously a description could only be set when registering a new version.

## 1.12.2

- **Critical: a completing run no longer breaks other runs' `SHELL()` tasks.** All concurrent runs
  share one task engine, but each run's interpreter closed its task runner when it finished — so any
  short script completing cleared the shared in-memory task map, and every other run's in-flight
  `SHELL()` failed with `Unknown task: <id>` (typically surfacing as a positioned error at the
  script's `UNWRAP`, failing the run) while the detached process kept running and the task kept
  showing RUNNING with live output in the UI. Seen in the field on a 2h+ SHELL run. Each run now gets
  a non-closing view of the shared engine (`NonClosingTaskRunner`); the real engine shutdown happens
  only at TeeBox server shutdown. This also cures the latent Windows form (the simulated runner's
  completion scheduler was being shut down by the first run to finish, wedging all later tasks).
  Runs already failed by this cause are not recoverable — check the still-running task's output and
  side effects, kill it or let it finish, then re-run on the fixed version.
- **Delete a specific script version.** Inactive versions get a confirmed **Delete** button in the
  Versions table (`POST /admin/scripts/delete-version/{id}`), and the publisher API gains
  `DELETE /api/publisher/scripts/{id}/versions/{version}` (returns the updated script). The active
  version is protected — set another version active first. Hard delete, no restore window; a run
  pinned to a deleted version is refused at submit.
- **Duplicate a script to a new id** — the supported "rename" path: duplicate, point callers at the
  new id, then delete the old script (run history stays with the source). Copies every version
  (content + description/labels/sha256/output rules), the active-version choice, and execution
  settings; the copy is immediately runnable. Admin UI: a **Duplicate Script** card on the script
  detail page (the duplicating user becomes the copy's owner); publisher API:
  `POST /api/publisher/scripts/{id}/duplicate` with `{"newScriptId": "..."}` → 201. Target-id
  collision, unknown or soft-deleted source are explicit errors.
- **Registering an existing script id now says so.** The metadata-only Register modal returned
  "Script content is required" when the id already existed; it now reports
  `Script already exists: <id>`.

## 1.12.1

- **Windows: run saves no longer fail on transient file locks.** On Windows, external scanners
  (antivirus real-time protection, the search indexer) briefly hold freshly written files, and the
  run-file save's tmp → final rename over a held file fails with a sharing violation — surfacing as
  `Failed to save run ...: being used by another process` and failing the run (seen in the field
  under repeated instant runs; POSIX renames over open files, so macOS/Linux never showed it). The
  store now retries the rename with a short backoff (5 attempts, 20/40/80/160 ms) before giving up,
  covering both the per-run file and `index.json`; a retry that succeeds logs a WARN. If the error
  persists on a Windows host, check that only one TeeBox instance points at the `dataDir`, and
  consider excluding it from real-time antivirus scanning.
- **Scripts list shows which scripts are instant.** The admin Scripts list tags the Script ID with
  the same `instant` badge the Runs list uses when the script is `immediate=true` (shown whether or
  not a version is active yet — the setting applies as soon as one is). Previously you had to open
  each script's settings to see it.

## 1.12.0

- **Adopts the spec v0.15.0 reference runtime** (`propertee2-java` 0.12.0, composite-built — the
  v1.0-gate number/rejection batch plus one additive builtin). No host code change; the only
  observable effect is in **run output number rendering**. `PRINT` / task-line output now renders
  numbers per **ECMA-262** (via the engine's display formatter): divergent-band decimals print
  plain instead of leaking Java scientific notation — `0.0001` (was `1.0E-4`), `15000000.5` (was
  `1.50000005E7`), `6000000000` (was `6.0E9`). Everyday values (integers, small decimals,
  timestamps) are unchanged.
  - The **result envelope / `resultData` JSON is unaffected** — it is serialized host-side via Gson
    (with the engine `JsonParser` disk round-trip), not the engine's display formatter, so the
    machine-facing result contract is byte-stable.
  - Other spec v0.14.0 changes do not surface in TeeBox: **nominal number identity** is a no-op (the
    reference already behaved this way), and **load-time rejection of blocked constructs** does not
    apply because TeeBox configures no hidden keywords / ignored functions.
  - **New builtin available (spec v0.15.0)**: `CONTAINS(array, item)` — membership check (in
    addition to the existing string-substring form). Purely additive; scripts can now use it.
- Full suite green (187) on the new engine.

## 1.11.2

- **Runs now record the caller's IP address at submit time** (`submittedFrom`), for both API and
  admin-UI submits — resolved the same X-Forwarded-For-aware way as the access log (first XFF hop
  behind a proxy, else the socket peer). Shown on the **run detail page** as **From (IP)** and carried
  in the admin run-detail JSON; deliberately **not** echoed in the client-facing run responses
  (caller IPs stay operator-side). Audit/display only.

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
