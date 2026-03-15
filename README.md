# TeeBox

TeeBox is the ProperTee execution service module. It exposes an HTTP admin UI and JSON API for submitting ProperTee scripts, tracking runs, monitoring external tasks, and performing manual task control.

## What It Provides

- `/api/client/*` for run submission and result polling
- `/api/publisher/*` for script registration and activation
- `/api/admin/*` for run/task inspection and control
- namespaced `client`, `publisher`, and `admin` JSON APIs
- `/admin` HTML UI for operators
- external task tracking through `TaskEngine`
- persisted run/task indexes with archive and purge support

## Main Components

- [`TeeBoxMain.java`](/Users/journey/Flatide/propertee-java/propertee-teebox/src/main/java/com/propertee/teebox/TeeBoxMain.java)
  - process entry point
- [`TeeBoxServer.java`](/Users/journey/Flatide/propertee-java/propertee-teebox/src/main/java/com/propertee/teebox/TeeBoxServer.java)
  - HTTP routing and API/admin handlers
- [`RunManager.java`](/Users/journey/Flatide/propertee-java/propertee-teebox/src/main/java/com/propertee/teebox/RunManager.java)
  - run lifecycle and task lookup
- [`RunRegistry.java`](/Users/journey/Flatide/propertee-java/propertee-teebox/src/main/java/com/propertee/teebox/RunRegistry.java)
  - persistent run storage and indexing
- [`AdminPageRenderer.java`](/Users/journey/Flatide/propertee-java/propertee-teebox/src/main/java/com/propertee/teebox/AdminPageRenderer.java)
  - server-rendered admin UI

## Quick Start

```bash
./gradlew teeBoxZip
./gradlew runTeeBox \
  -Dpropertee.teebox.scriptsRoot=$PWD/propertee-teebox/demo/teebox \
  -Dpropertee.teebox.dataDir=/tmp/propertee-teebox-data
```

Open `http://127.0.0.1:18080/admin`.

## API Namespaces

- `client`
  - submit runs by `scriptId/version` or `scriptPath`
  - poll run status and fetch results
- `publisher`
  - register script versions and activate the default version
- `admin`
  - inspect tasks, runs, and threads
  - kill tasks and run-owned tasks

The practical boundary is:
- upstream application servers use `client` and `publisher`
- TeeBox operators use `admin`

## Mock Upstream Harness

`TeeBoxUpstreamMockMain` simulates an upstream service that registers a script, submits a run, waits for completion, and prints the result.

Example:

```bash
./gradlew :propertee-teebox:runTeeBoxUpstream \
  -Dpropertee.teebox.upstream.baseUrl=http://127.0.0.1:18080 \
  -Dpropertee.teebox.upstream.scriptId=calc_sum \
  -Dpropertee.teebox.upstream.version=v1 \
  -Dpropertee.teebox.upstream.scriptFile=$PWD/propertee-teebox/demo/teebox/05_registered_sum.pt \
  -Dpropertee.teebox.upstream.activate=true \
  -Dpropertee.teebox.upstream.propsJson='{"a":40,"b":2}'
```

Useful settings:

- `propertee.teebox.upstream.baseUrl`
- `propertee.teebox.upstream.apiToken`
- `propertee.teebox.upstream.clientApiToken`
- `propertee.teebox.upstream.publisherApiToken`
- `propertee.teebox.upstream.adminApiToken`
- `propertee.teebox.upstream.scriptId`
- `propertee.teebox.upstream.version`
- `propertee.teebox.upstream.scriptFile`
- `propertee.teebox.upstream.scriptPath`
- `propertee.teebox.upstream.propsJson`
- `propertee.teebox.upstream.submit`
- `propertee.teebox.upstream.wait`
- `propertee.teebox.upstream.waitMs`

## GitHub Download

`propertee-teebox-dist.zip` is published on GitHub Releases when a repository tag such as `v0.3.1` is pushed.

```bash
git tag v0.3.1
git push origin v0.3.1
```

## Configuration

Primary settings use the `propertee.teebox.*` prefix:

- `propertee.teebox.bind`
- `propertee.teebox.port`
- `propertee.teebox.scriptsRoot`
- `propertee.teebox.dataDir`
- `propertee.teebox.maxRuns`
- `propertee.teebox.apiToken`
- `propertee.teebox.clientApiToken`
- `propertee.teebox.publisherApiToken`
- `propertee.teebox.adminApiToken`
- `propertee.teebox.runRetentionMs`
- `propertee.teebox.runArchiveRetentionMs`

Token behavior:

- `apiToken`: default fallback for all API namespaces
- `clientApiToken`: overrides `client` routes
- `publisherApiToken`: overrides `publisher` routes
- `adminApiToken`: overrides `admin` routes

## Related Docs

- Deployment bundle: [`deploy/teebox/README.md`](/Users/journey/Flatide/propertee-java/propertee-teebox/deploy/teebox/README.md)
- Demo scripts: [`demo/teebox/README.md`](/Users/journey/Flatide/propertee-java/propertee-teebox/demo/teebox/README.md)
- Evolution plan: [`demo/teebox/PLAN.md`](/Users/journey/Flatide/propertee-java/propertee-teebox/demo/teebox/PLAN.md)
- API boundary plan: [`docs/teebox-api-boundary-plan-ko.md`](/Users/journey/Flatide/propertee-java/docs/teebox-api-boundary-plan-ko.md)
