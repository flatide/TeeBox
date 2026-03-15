# TeeBox Demo

This folder contains sample scripts for the TeeBox admin server added in `com.propertee.teebox`.

## Start the server

Run the TeeBox server with this folder as `scriptsRoot`:

```bash
./gradlew --no-daemon \
  -Dpropertee.teebox.scriptsRoot=$PWD/propertee-teebox/demo/teebox \
  -Dpropertee.teebox.dataDir=/tmp/propertee-teebox-data \
  runTeeBox
```

Open:

```text
http://127.0.0.1:18080/admin
```

## Sample scripts

- `01_basic_run.pt`
  - Fast success path for basic run submission and log output.
- `02_multi_threads.pt`
  - Shows `multi`, child thread activity, monitor ticks, and final result collection.
- `03_long_task_kill.pt`
  - Starts a long-running external task and waits for it so an admin can kill it from the UI or API.
- `04_detached_task.pt`
  - Starts an external task and exits the ProperTee run immediately so the task remains visible as a detached process.
- `05_registered_sum.pt`
  - Minimal registered-script example for the `publisher -> client` flow.

## Suggested checks

1. Submit `01_basic_run.pt` and confirm the run moves to `COMPLETED`.
2. Submit `02_multi_threads.pt` and watch the thread table update.
3. Submit `03_long_task_kill.pt`, open the task page, and use `Kill Task`.
4. Submit `04_detached_task.pt`, let the run finish, and confirm the task is still visible in the task list.
5. Register `05_registered_sum.pt` through the upstream mock and confirm the result JSON returns `sum = 42`.

## Upstream Mock Example

Run the upstream mock against a live TeeBox instance:

```bash
./gradlew :propertee-teebox:runTeeBoxUpstream \
  -Dpropertee.teebox.upstream.baseUrl=http://127.0.0.1:18080 \
  -Dpropertee.teebox.upstream.scriptId=calc_sum \
  -Dpropertee.teebox.upstream.version=v1 \
  -Dpropertee.teebox.upstream.scriptFile=$PWD/propertee-teebox/demo/teebox/05_registered_sum.pt \
  -Dpropertee.teebox.upstream.activate=true \
  -Dpropertee.teebox.upstream.propsJson='{"a":40,"b":2}'
```

## Useful API endpoints

- `POST /api/runs`
- `GET /api/runs`
- `GET /api/runs/{runId}`
- `GET /api/runs/{runId}/threads`
- `GET /api/runs/{runId}/tasks`
- `GET /api/tasks`
- `GET /api/tasks/{taskId}`
- `POST /api/tasks/{taskId}/kill`
- `POST /api/runs/{runId}/kill-tasks`
