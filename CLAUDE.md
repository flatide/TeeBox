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

# Run upstream mock harness (for integration testing)
./gradlew runTeeBoxUpstream \
  -Dpropertee.teebox.upstream.baseUrl=http://127.0.0.1:18080 \
  -Dpropertee.teebox.upstream.scriptId=calc_sum \
  -Dpropertee.teebox.upstream.version=v1 \
  -Dpropertee.teebox.upstream.scriptFile=$PWD/demo/teebox/05_registered_sum.pt \
  -Dpropertee.teebox.upstream.activate=true
```

## Architecture

**Entry point:** `TeeBoxMain` → loads `TeeBoxConfig` → starts `TeeBoxServer` → shutdown hook.

**Core flow:** HTTP request → `TeeBoxServer` (routing + auth) → `RunManager` (coordination) → `ScriptExecutor` (ProperTee interpreter wrapper).

### Key Classes

- **`TeeBoxServer`** — Routes requests across 3 API namespaces (`/api/client`, `/api/publisher`, `/api/admin`) with independent Bearer token auth, plus `/admin` HTML UI. Largest class (~37KB).
- **`RunManager`** — Central coordinator. Receives `RunRequest`, resolves script (file path or registry), submits to `ThreadPoolExecutor`, tracks state via `RunRegistry` (in-memory) + `RunStore` (disk). Background `ScheduledExecutorService` handles flush (2s) and maintenance/retention (60s).
- **`ScriptRegistry`** — Version-controlled script store in `dataDir/script-registry/`. Validates IDs, parses syntax, computes SHA-256.
- **`ScriptExecutor`** — Stateless. Parses script → creates interpreter with builtins → runs scheduler → collects result. Uses callback interface for stdout/stderr/thread events.
- **`RunRegistry`** — In-memory `ConcurrentHashMap` cache with ring buffers (max 200 lines stdout/stderr). Retention: active (<24h), archived (24h-7d, compressed logs), purged (>7d, deleted).
- **`RunStore`** — File-based persistence (`dataDir/runs/`). Atomic writes via temp file + rename. Synchronized methods.
- **`AdminPageRenderer`** — Server-rendered HTML via string concatenation (~42KB). Dashboard, script list, run/task detail pages.
- **`TeeBoxClient`** — Java HTTP client wrapper for all API endpoints.

### 3 API Namespaces

- **Client** (`/api/client`) — Run submission & polling
- **Publisher** (`/api/publisher`) — Script registration & version activation
- **Admin** (`/api/admin`) — System inspection, run/task detail, kill operations

Full API spec in `swagger.yaml` (OpenAPI 3.0).

## Configuration

Settings loaded from: system properties (highest priority) → config file (`--config` / `-c`) → defaults.

All properties prefixed `propertee.teebox.*`: `bind`, `port` (default 18080), `scriptsRoot`, `dataDir` (both required), `maxRuns` (default 4), `apiToken` (fallback), `clientApiToken`, `publisherApiToken`, `adminApiToken`, `runRetentionMs` (24h), `runArchiveRetentionMs` (7d).

## Dependencies

- `com.propertee:propertee-core:0.3.0` — ProperTee interpreter (ScriptParser, builtins, scheduler)
- `com.google.code.gson:gson:2.11.0` — JSON serialization
- `junit:junit:4.13.2` — Tests

## Testing

Tests are in `src/test/java/com/propertee/tests/`. Integration tests (`TeeBoxServerTest`) start a live server with temp directories. Config tests (`TeeBoxConfigTest`) test property loading and token fallback.

## Known Issues

See `ANALYSIS.md` for details:
1. **`TeeBoxClient.submitRun()` uses wrong endpoint** — sends to `/api/client/runs` but server expects `/api/client/scripts/{scriptId}/runs`
2. **Archive/purge tests timeout** — maintenance interval is 60s but test timeout is 8s

## Concurrency Model

- `ThreadPoolExecutor` for script execution (bounded by `maxRuns`)
- `ScheduledExecutorService` for background maintenance
- `ConcurrentHashMap` for run cache
- `synchronized` blocks for file I/O in `RunStore` and `ScriptRegistry`
