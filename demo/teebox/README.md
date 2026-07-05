# TeeBox Demo

This folder contains sample scripts for the TeeBox admin server added in `com.flatide.teebox`.

## Start the server

Run the TeeBox server with a temporary `dataDir`:

```bash
./gradlew --no-daemon \
  -Dpropertee.teebox.dataDir=/tmp/propertee-teebox-data \
  run
```

Open:

```text
http://127.0.0.1:18080/admin
```

## Sample scripts

- `01_basic_run.tee`
  - Fast success path for basic run submission and log output.
- `02_multi_threads.tee`
  - Shows `multi`, child thread activity, monitor ticks, and final result collection.
- `03_long_task_kill.tee`
  - Starts a long-running external task and waits for it so an admin can kill it from the UI or API.
- `04_detached_task.tee`
  - Starts multiple delayed external commands in parallel and shows their final task results.
- `05_registered_sum.tee`
  - Minimal registered-script example for the `publisher -> client` flow.
- `06_run_envelope.tee`
  - **One script, three outcomes, one envelope** (TeeBox 1.9.0). Prop-driven (`{"mode": "ok" | "data-error" | "fail"}`), it produces a plain success, a script-returned `ERR(...)` Result (nested as data), and a `FAIL(...)`-escalated run failure — all read through the additive `result` field of `GET /api/client/runs/{runId}/result`, which always has the one ProperTee-Result shape `{status, ok, value}`. Also exercises spec v0.10.0 (`ERR`/`FAIL`), `elseif` (v0.9.0), and `_PROPS`.

## Suggested checks

1. Submit `01_basic_run.tee` and confirm the run moves to `COMPLETED`.
2. Submit `02_multi_threads.tee` and watch the thread table update.
3. Submit `03_long_task_kill.tee`, open the task page, and use `Kill Task`.
4. Submit `04_detached_task.tee`, let the run finish, and confirm the task is still visible in the task list.
5. Register `05_registered_sum.tee` through the upstream mock and confirm the result JSON returns `sum = 42`.
6. Register `06_run_envelope.tee` and run it three times — the `result` envelope unifies every outcome:

   ```bash
   # register (once)
   curl -s -X POST http://127.0.0.1:18080/api/publisher/scripts \
     -H 'Content-Type: application/json' \
     -d "$(python3 -c 'import json;print(json.dumps({"scriptId":"envelope_demo","content":open("demo/teebox/06_run_envelope.tee").read(),"activate":True}))')"

   # run each mode, then read the envelope
   curl -s -X POST http://127.0.0.1:18080/api/client/scripts/envelope_demo/runs \
     -H 'Content-Type: application/json' -d '{"props":{"mode":"ok"}}'          # then: {"mode":"data-error"}, {"mode":"fail"}
   curl -s http://127.0.0.1:18080/api/client/runs/<runId>/result
   ```

   Expected `result` field per mode (poll early enough and you also see `{"status":"running","ok":false,"value":{}}`):

   | mode | RunStatus | `result` envelope |
   |---|---|---|
   | `ok` | COMPLETED | `{"status":"done","ok":true,"value":{"job":"envelope-demo","shell_ok":true,"answer":42}}` |
   | `data-error` | COMPLETED | `{"status":"done","ok":true,"value":{"status":"error","ok":false,"value":{"reason":"upstream said no","code":503}}}` — the run succeeded; the inner Result is the script's data |
   | `fail` | FAILED | `{"status":"error","ok":false,"value":"Runtime Error at line 37:4: upstream unreachable: giving up"}` — since 1.9.1 the message pinpoints the `FAIL` site |

## Upstream Mock Example

Run the upstream mock against a live TeeBox instance:

```bash
./gradlew runTeeBoxUpstream \
  -Dpropertee.teebox.upstream.baseUrl=http://127.0.0.1:18080 \
  -Dpropertee.teebox.upstream.scriptId=calc_sum \
  -Dpropertee.teebox.upstream.version=v1 \
  -Dpropertee.teebox.upstream.scriptFile=$PWD/demo/teebox/05_registered_sum.tee \
  -Dpropertee.teebox.upstream.activate=true \
  -Dpropertee.teebox.upstream.propsJson='{"a":40,"b":2}'
```

## Useful API endpoints

- `POST /api/client/scripts/{scriptId}/runs`
- `GET /api/client/runs`
- `GET /api/client/runs/{runId}`
- `GET /api/admin/runs/{runId}`
- `GET /api/admin/tasks`
- `GET /api/admin/tasks/{taskId}`
- `POST /api/admin/tasks/{taskId}/kill`
- `POST /api/admin/runs/{runId}/kill-tasks`
