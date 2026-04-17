# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

ProperTee TeeBox is an HTTP API and admin UI service for remote ProperTee script execution, run management, task monitoring, and script registry. Java 17, Gradle build, uses the built-in `com.sun.net.httpserver` — no frameworks.

## Build & Run Commands

```bash
# Build
./gradlew build

# Run tests (JUnit 4)
./gradlew test

# Run a single test class
./gradlew test --tests "com.propertee.tests.TeeBoxServerTest"

# Run a single test method
./gradlew test --tests "com.propertee.tests.TeeBoxServerTest.testMethodName"

# Run dev server (requires scriptsRoot and dataDir)
./gradlew run \
  -Dpropertee.teebox.scriptsRoot=$PWD/demo/teebox \
  -Dpropertee.teebox.dataDir=/tmp/propertee-teebox-data

# Build deployable fat JAR + distribution ZIP
./gradlew teeBoxZip

```

## Architecture

**Entry point:** `TeeBoxMain` → loads `TeeBoxConfig` → starts `TeeBoxServer` → shutdown hook.

**Core flow:** HTTP request → `TeeBoxServer` (routing + auth) → `RunManager` (coordination) → `ScriptExecutor` (ProperTee interpreter wrapper).

### Key Classes

- **`TeeBoxServer`** — Routes requests across 3 API namespaces (`/api/client`, `/api/publisher`, `/api/admin`) with independent Bearer token auth, plus `/admin` HTML UI and `/health` endpoint.
- **`RunManager`** — Central coordinator. Receives `RunRequest`, resolves script via registry, submits to `ThreadPoolExecutor`, tracks state via `RunRegistry` (in-memory) + `RunStore` (disk). Background `ScheduledExecutorService` handles flush (2s) and maintenance/retention (60s). Per-script concurrency control with pending queue and dequeue-on-completion. Separate `immediateExecutor` for scripts marked as immediate. Supports graceful shutdown (drain mode: rejects new runs, waits for in-flight to complete, then exits).
- **`ManagedTaskEngine`** — Implements `TaskRunner`, wraps `DefaultTaskRunner` (from core) with disk persistence, indexing, archival, multi-instance ownership (Java 17 ProcessHandle), and querying. Control plane layer over core's lightweight process execution.
- **`ScriptRegistry`** — Version-controlled script store in `dataDir/script-registry/`. Validates IDs, parses syntax, computes SHA-256. Supports per-script execution settings (`maxConcurrentRuns`, `immediate`) and `outputRules` for task output capture. Soft-delete with retention period: DELETE marks `deletedAt`, background maintenance purges after 7 days (configurable via `propertee.teebox.scriptRetentionMs`).
- **`ScriptExecutor`** — Stateless. Parses script → creates interpreter with builtins → runs scheduler → collects result. Receives `TaskRunner` interface (not concrete type).
- **`RunRegistry`** — In-memory `ConcurrentHashMap` cache with ring buffers (max 200 lines stdout/stderr). Retention: active (<24h), archived (24h-7d, compressed logs), purged (>7d, deleted).
- **`RunStore`** — File-based persistence (`dataDir/runs/`). Atomic writes via temp file + rename. Synchronized methods.
- **`AdminPageRenderer`** — Server-rendered HTML via string concatenation. Dashboard, script list, run/task detail pages.
- **`TaskOutputWatcher`** — Incrementally reads task stdout/stderr files, matches regex patterns from script metadata, publishes captured values to RunInfo.published.
- **`OutputWatchingTaskRunner`** — TaskRunner wrapper that auto-registers output watchers for the first task created in a run.
- **`OutputPublishRule`** — Data model for output capture rules: stream, pattern, captureGroup, publishKey, firstOnly.
- **`TeeBoxClient`** — Java HTTP client wrapper for API endpoints. Submits runs via `/api/client/scripts/{scriptId}/runs`.

### 3 API Namespaces

- **Client** (`/api/client`) — Run submission & polling
- **Publisher** (`/api/publisher`) — Script registration, version activation, execution settings, script deletion
- **Admin** (`/api/admin`) — System inspection, run/task detail, kill operations

Full API spec in `swagger.yaml` (OpenAPI 3.0).

### Publisher API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/publisher/scripts` | List all scripts |
| POST | `/api/publisher/scripts` | Register script version (with optional outputRules) |
| GET | `/api/publisher/scripts/{id}` | Get script detail |
| POST | `/api/publisher/scripts/{id}/versions` | Add version |
| POST | `/api/publisher/scripts/{id}/activate` | Activate version |
| PUT | `/api/publisher/scripts/{id}/settings` | Update execution settings (maxConcurrentRuns, immediate) |
| DELETE | `/api/publisher/scripts/{id}` | Delete script (soft-delete) |
| POST | `/api/publisher/scripts/{id}/restore` | Restore a soft-deleted script |

### Admin API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/admin/health` | Server health status |
| GET | `/api/admin/system` | System info |
| GET | `/api/admin/drain-status` | Current drain status with counts |
| POST | `/api/admin/shutdown` | Initiate graceful shutdown (body: `{"maxWaitMs": 300000}`) |
| GET | `/api/admin/runs` | List runs with filters |
| GET | `/api/admin/runs/{runId}` | Run detail |
| GET | `/api/admin/tasks` | List tasks |
| GET | `/api/admin/tasks/{taskId}` | Task detail |
| POST | `/api/admin/tasks/{taskId}/kill` | Kill task |
| POST | `/api/admin/runs/{runId}/kill-tasks` | Kill all tasks for a run |

### Run Status

`RunInfo.status` values:
- `QUEUED` — waiting in the global thread pool queue
- `PENDING` — blocked by per-script concurrency limit (`maxConcurrentRuns`)
- `RUNNING` — script is executing
- `COMPLETED` — finished successfully
- `FAILED` — terminated with an error
- `SERVER_RESTARTED` — run was active when the server restarted and could not be resumed

## Configuration

Settings loaded from: system properties (highest priority) → config file (`--config` / `-c`) → defaults.

All properties prefixed `propertee.teebox.*`: `bind`, `port` (default 18080), `scriptsRoot`, `dataDir` (both required), `maxRuns` (default 4), `apiToken` (fallback), `clientApiToken`, `publisherApiToken`, `adminApiToken`, `runRetentionMs` (24h), `runArchiveRetentionMs` (7d).

## Dependencies

- `com.propertee:propertee-core:0.4.0` — ProperTee interpreter (ScriptParser, builtins, scheduler, TaskRunner)
- `com.google.code.gson:gson:2.11.0` — JSON serialization
- `junit:junit:4.13.2` — Tests

## Testing

Tests are in `src/test/java/com/propertee/tests/`. Integration tests (`TeeBoxServerTest`) start a live server with temp directories. Config tests (`TeeBoxConfigTest`) test property loading and token fallback.

## Run Submission

Runs are submitted exclusively via the script registry: `POST /api/client/scripts/{scriptId}/runs`. The legacy `POST /api/client/runs` (scriptPath-based) endpoint has been removed. All scripts must be registered via the publisher API first.

## Concurrency Model

- `ThreadPoolExecutor` for script execution (bounded by `maxRuns`)
- `ScheduledExecutorService` for background maintenance
- `ConcurrentHashMap` for run cache
- `synchronized` blocks for file I/O in `RunStore` and `ScriptRegistry`
