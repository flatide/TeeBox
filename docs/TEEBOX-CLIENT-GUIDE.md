# TeeBoxClient User Guide

`client/com/flatide/teebox/client/TeeBoxClient.java` is a client for calling the ProperTee TeeBox server by **embedding** it into other programs. This document is a guide for developers who want to use this client.

> For the full server (`/api/*`) spec, see `docs/API-EXAMPLES.md` and `swagger.yaml`; for operations, see `docs/OPERATIONS-GUIDE.ko.md`. This document covers only the **client library** perspective.

---

## 1. Features

- **Single file, zero-dependency**: you only need to copy `TeeBoxClient.java`. No libraries beyond the JDK (Gson/Jackson, etc.) are required — it uses only `HttpURLConnection` plus a built-in mini JSON codec. It does not conflict with the host project's JSON library.
- **Java 7 compatible**: no lambdas/streams/`java.time`. Can be embedded into legacy servers. The source is verified by the `--release 8` gate, and the distribution jar is compiled to **Java 7 bytecode (major 51)** with a JDK 8 toolchain → loadable on a Java 7 JVM.
- **Scope**: script register/update, execution, tracking. Since it assumes a closed/trusted internal network, there is **no authentication by default**.

---

## 2. Setup

Choose whichever of the two approaches is more convenient.

### Option A — Embed the source

1. Copy `client/com/flatide/teebox/client/TeeBoxClient.java` into the host project. **Keep the package path (`com/flatide/teebox/client/`)**.
2. No separate build configuration or dependency additions are needed. It compiles as-is.
3. Import and use:

```java
import com.flatide.teebox.client.TeeBoxClient;
```

> A Java 7 host can also use **Option B (jar)** directly (the jar is bytecode 51). Choose Option A when it is hard to have JDK 8 in the build environment, or when you want to control compilation directly with the host's compiler.

### Option B — Use the prebuilt jar (when embedding the source is burdensome)

Build the jar from the TeeBox repository.

```bash
./gradlew clientJar          # → build/libs/teebox-client-<version>.jar  (e.g. teebox-client-0.11.0.jar)
./gradlew clientSourcesJar   # (optional) sources jar for IDE source attachment
```

- The generated jar is **zero-dependency** (uses only the JDK), so it does not conflict with the host's JSON library.
- The bytecode is **Java 7 (major 51)**, so it loads as-is on a **Java 7 or later host**.

> **Build environment note**: the latest JDKs (17+) cannot generate bytecode 7, so `clientJar` compiles with `-source/-target 7` using a **JDK 8 toolchain**. JDK 8 must be detected on the build machine — Gradle auto-detects an installed JDK 8, and if it is not detected you can specify the path via `org.gradle.java.installations.paths` (e.g. in `~/.gradle/gradle.properties`). A regular build without JDK 8 (`./gradlew build`) is unaffected.

Example of adding the jar to the host build:

```groovy
// Gradle
dependencies {
    implementation files('libs/teebox-client-0.11.0.jar')
}
```

```xml
<!-- Maven (after installing locally) -->
<dependency>
  <groupId>com.flatide</groupId>
  <artifactId>teebox-client</artifactId>
  <version>0.11.0</version>
</dependency>
```

Either way, the usage code is the same:

```java
import com.flatide.teebox.client.TeeBoxClient;
```

---

## 3. Quick start

```java
import com.flatide.teebox.client.TeeBoxClient;
import java.util.LinkedHashMap;
import java.util.Map;

TeeBoxClient teebox = new TeeBoxClient("http://teebox-host:18080");

// 1) Register + activate a script (version omitted → server auto-increments "1", "2" ...)
teebox.registerScript("calc_sum", "return {\"sum\": a + b}\n", true);

// 2) Run with input values (props) and wait until it finishes (suitable for short scripts)
Map<String, Object> props = new LinkedHashMap<String, Object>();
props.put("a", 40);
props.put("b", 2);
Map<String, Object> result = teebox.runAndWait("calc_sum", null, props, 30000L);

Map<?, ?> data = (Map<?, ?>) result.get("resultData");
Object sum = data.get("sum");   // 42.0  (note that numbers are parsed as Double)
```

---

## 4. Creating & configuring the client

### Constructor

```java
TeeBoxClient teebox = new TeeBoxClient("http://teebox-host:18080");
```

- `baseUrl` is required (`IllegalArgumentException` if `null`/empty). A trailing `/` is removed automatically.

### Timeouts (chainable)

| Method | Default | Description |
|--------|--------|------|
| `setConnectTimeoutMs(int)` | 5000 | TCP connect timeout (ms) |
| `setReadTimeoutMs(int)` | 15000 | Response read timeout (ms) |

```java
teebox.setConnectTimeoutMs(3000).setReadTimeoutMs(20000);
```

> ⚠️ The total wait time for the polling helpers (`runAndWait`, `waitForRunTerminal`, `waitForPublished`) is specified separately via the method argument `timeoutMs`. `readTimeoutMs` is the timeout for **a single individual HTTP call**.

### Thread usage

The settings (timeouts/tokens) are plain mutable fields that are not synchronized. **Set them once at creation time before sharing the instance.** Once configured, it is safe to issue requests concurrently from multiple threads (each request opens its own connection and holds no per-request state). However, calling a setter concurrently with in-flight requests is not guaranteed to be safe.

---

## 5. Authentication (optional)

A default closed-network deployment has no authentication. You only need to specify a token when the operator has configured one.

- When a single shared token is sufficient:

```java
teebox.setBearerToken("shared-token");
```

- When the operator has split the tokens per namespace (the server's `clientApiToken`/`publisherApiToken`/`adminApiToken`):

```java
teebox.setClientApiToken("client-secret")
      .setPublisherApiToken("publisher-secret")
      .setAdminApiToken("admin-secret");
```

**Token resolution rule**: it first uses the namespace token matching the request path, and if absent falls back to the shared token from `setBearerToken` (same as the server's `apiToken` fallback).

| Call path | Token used | Fallback |
|-----------|-----------|------|
| `/api/client/*` (run/track) | `clientApiToken` | `bearerToken` |
| `/api/publisher/*` (script management) | `publisherApiToken` | `bearerToken` |
| `/api/admin/*` | `adminApiToken` | `bearerToken` |

---

## 6. Script management

### 6.1 Version policy (important)

- **Auto-increment when the version is omitted**: integer labels `"1"`, `"2"`, … are assigned automatically (existing maximum integer + 1). Explicit labels (including strings like `"v1"`) can also be used as-is.
- **Active version concept**: when you omit the version at run time, the **"active" version is run, not the newest version**. Even after adding a new version, the existing active version keeps being served until you activate it with `activate=true` (for staging/rollback purposes).

### 6.2 Register

```java
// (A) Version auto-increment + activate
Map<String, Object> detail = teebox.registerScript("calc_sum", source, true);
// The assigned version is detail.get("activeVersion") (when active) or the newest item in the versions list

// (B) Explicit version
teebox.registerScript("calc_sum", "v1", source, true);

// (C) Specify description / labels as well
teebox.registerScript("calc_sum", "v1", source, "Compute the sum", labels, true);
```

### 6.3 Add a version (= update)

```java
// Version auto-increment
teebox.addScriptVersion("calc_sum", newSource, true);   // next integer version, activate immediately

// Explicit version
teebox.addScriptVersion("calc_sum", "v2", newSource, true);
```

### 6.4 Change the active version

```java
teebox.activateScriptVersion("calc_sum", "1");
```

> **The responses of `registerScript` / `addScriptVersion` / `activateScriptVersion`** are all the **full script detail** object, identical to `getScript` (see the §6.5 example below). The auto-assigned version and the current active version can be read directly from the response's `activeVersion` and `versions[]`.
>
> ```java
> Map<String, Object> detail = teebox.addScriptVersion("calc_sum", newSource, true);
> String assigned = (String) detail.get("activeVersion");   // e.g. "2" (auto-incremented)
> ```

### 6.5 Lookup

```java
List<Object> scripts = teebox.listScripts();          // full script list
Map<String, Object> one = teebox.getScript("calc_sum"); // detail (versions/active/settings)
String src = teebox.getScriptContent("calc_sum");        // active version source
String srcV1 = teebox.getScriptContent("calc_sum", "1"); // specific version source

// Active version only: versions[] is reduced to the single active version (other fields are the same)
List<Object> activeScripts = teebox.listActiveScripts();
Map<String, Object> oneActive = teebox.getActiveScript("calc_sum");
```

> `getScriptContent(...)` returns **only the source string** (the server sends `{scriptId, version, content}` but the client extracts only `content`). e.g. `"return {\"ok\": true, \"sum\": a + b}\n"`.
>
> **`getActiveScript` / `listActiveScripts`** are the same as `getScript` / `listScripts`, but they reduce each script's `versions[]` **to the single active version** before returning (client-side filtering; the remaining fields such as `activeVersion` and settings stay the same). If there is no active version, `versions` is an empty array.

#### `getScript(scriptId)` response example

```jsonc
{
  "scriptId": "calc_sum",
  "activeVersion": "1",         // when the version is omitted at run time, this version runs
  "createdAt": 1781670773694,   // epoch ms (Double in Java)
  "updatedAt": 1781670773741,
  "maxConcurrentRuns": 4,       // 0 = unlimited (default)
  "immediate": false,           // true = immediate execution bypassing the global queue
  "deletedAt": 0,               // 0 = not deleted, >0 = soft-delete timestamp
  "versions": [                 // newest version first
    {
      "version": "2",
      "description": "",
      "labels": [],
      "sha256": "3dd7c382...",  // version content hash
      "createdAt": 1781670773729,
      "active": false
    },
    {
      "version": "1",
      "description": "Sum of two numbers",
      "labels": [],
      "sha256": "8def0777...",
      "createdAt": 1781670773694,
      "active": true            // = activeVersion
    }
  ]
}
```

```java
String active = (String) one.get("activeVersion");
List<?> versions = (List<?>) one.get("versions");
```

#### `listScripts()` response example

It is an **array** of the same object as `getScript`.

```jsonc
[
  { "scriptId": "calc_sum", "activeVersion": "1", "maxConcurrentRuns": 4, "versions": [ /* ... */ ], "...": "..." },
  { "scriptId": "greeter",  "activeVersion": "1", "maxConcurrentRuns": 0, "versions": [ /* ... */ ], "...": "..." }
]
```

```java
for (Object item : teebox.listScripts()) {
    Map<?, ?> s = (Map<?, ?>) item;
    System.out.println(s.get("scriptId") + " @ " + s.get("activeVersion"));
}
```

---

## 7. Execution & tracking

### 7.1 Execution model (sync vs async)

The TeeBox server is **asynchronous**.

- `submitRun(...)` returns immediately and gives you a `runId` (status `QUEUED`, or `PENDING` if the per-script concurrent-run limit is hit). **At this point the script has not run yet.**
- The result is obtained by polling with the `runId`. All of this client's wait helpers are **client-side polling**, so even if a timeout or dropped connection occurs, **the server's execution is not aborted** — just poll again with the same `runId`.
- Terminal states: `COMPLETED` / `FAILED` / `SERVER_RESTARTED`.

### 7.2 Submit

```java
// Version omitted → run the active version
Map<String, Object> submitted = teebox.submitRun("calc_sum", props);
String runId = (String) submitted.get("runId");

// Explicit version
teebox.submitRun("calc_sum", "1", props);
```

> **Accessing props inside the script**: each key of the submitted `props` is read directly as an **individual variable** in the script (e.g. `props={"a":40,"b":2}` → `a`, `b`). In addition, the entire input can be accessed at once through the reserved object **`_PROPS`** — `PRINT(_PROPS)`, `JSON_FORMAT(_PROPS)` (full dump/debugging), `KEYS(_PROPS)` (iteration), `_PROPS.a` (individual), `return {"echo": _PROPS}` (pass through as-is). Inside a function/`multi`, use `::_PROPS`.
>
> **System variable `_SYS`**: TeeBox injects a reserved object **`_SYS = {runId, scriptId, version}`** into every run. The script can learn its own run id and so on — `_SYS.runId`, `_SYS.scriptId`, `_SYS.version` (inside a function use `::_SYS.runId`). `_SYS` is injected as a global variable and is **not included in `_PROPS`** (which holds user input only). e.g. `PRINT("my runId:", _SYS.runId)`.

#### `submitRun(...)` response example (HTTP 202, right after submit)

```jsonc
{
  "runId": "run-20260617-133304-465-cf31",  // the key used for subsequent tracking
  "scriptId": "calc_sum",
  "version": "1",                // the version actually selected (the active version)
  "status": "QUEUED",            // or PENDING when the concurrent-run limit is hit — not yet running
  "createdAt": 1781670784465,
  "hasExplicitReturn": false
}
```

> At submit time there is not yet a `startedAt`/`endedAt`/`resultSummary` (before execution).

### 7.3 Status/result lookup

```java
Map<String, Object> summary = teebox.getRun(runId);              // full summary (includes published)
Map<String, Object> status  = teebox.getRunStatus(runId);        // status only
Map<String, Object> result  = teebox.getRunResult(runId);        // result (after termination)
Map<String, Object> tasks   = teebox.getRunTasksSummary(runId);  // task counts by status
List<Object> runs = teebox.listScriptRuns("calc_sum");           // run list for the script
List<String> out  = teebox.getRunStdoutLines(runId);             // script PRINT output (list of lines)
List<String> err  = teebox.getRunStderrLines(runId);             // script stderr (list of lines)
```

> **Streaming large results (`STREAM_FILE`)**: returning a big file like a 6MB JSON after `READ_LINES`+`JOIN`+`JSON_PARSE` copies the whole thing into the script engine heap multiple times, causing memory and speed problems. Instead, if the script returns just a descriptor with **`return STREAM_FILE("/path/to/big.json", "application/json")`**, TeeBox **streams that file directly as the response** (no parsing, no full buffering). The client receives it with `streamRunResult`:
>
> ```java
> // Receive the result as a file/stream (neither engine nor client holds the whole thing in memory)
> java.io.OutputStream sink = new java.io.FileOutputStream("result.json");
> try { teebox.streamRunResult(runId, sink); } finally { sink.close(); }
> ```
>
> - For a stream result, `getRunResult(runId)` returns `resultData = {stream:true, contentType, size}` (the server path is hidden), so you can tell it is a stream target. Receive the actual bytes with `streamRunResult`.
> - The `STREAM_FILE` path must be **within an allowed root** (`propertee.teebox.streamRoots`, default `dataDir`). If it is outside, the script fails.
> - Because it is by reference, **the file must exist until the result is retrieved** (TeeBox does not copy or own it).

> **stdout/stderr lookup**: the script's `PRINT(...)` output is received via `getRunStdout(runId)` (full map) or `getRunStdoutLines(runId)` (lines only). **It is queryable even during execution (RUNNING)** and remains after termination. However, since the server keeps **a ring buffer of only the most recent `MAX_LOG_LINES` (default 200 lines)**, very long output keeps only the tail. The `getRunStdout` response shape:
>
> ```jsonc
> {
>   "runId": "run-...", "scriptId": "printer", "version": "1",
>   "status": "RUNNING",        // or COMPLETED, etc.
>   "stream": "stdout",
>   "lines": ["line one", "line two 42", "done"],
>   "lineCount": 3
> }
> ```

#### `getRun(runId)` response example (after termination)

```jsonc
{
  "runId": "run-20260617-133304-465-cf31",
  "scriptId": "calc_sum",
  "version": "1",
  "status": "COMPLETED",            // QUEUED/PENDING/RUNNING/COMPLETED/FAILED/SERVER_RESTARTED
  "createdAt": 1781670784465,       // submit time
  "startedAt": 1781670784470,       // execution start (absent before it starts)
  "endedAt": 1781670784481,         // termination (absent before it ends)
  "hasExplicitReturn": true,        // whether the script produced a value with return
  "resultSummary": "{ \"ok\": true, \"sum\": 42 }"  // result summary string (up to 300 chars)
}
```

> `getRun`'s `resultSummary` is a **summary string**. Receive the structured result (`resultData`) with `getRunResult(runId)` (below). If there are values published via `outputRules`, a `published` map field is added.

#### `getRunStatus(runId)` response example

Almost the same as `getRun` but **without `resultSummary`** (status/timestamps focused, lightweight for polling). If it has not started/ended yet, `startedAt`/`endedAt` are absent.

```jsonc
// Running (RUNNING) — no endedAt
{
  "runId": "run-20260617-220708-141-f339",
  "scriptId": "slow",
  "version": "1",
  "status": "RUNNING",
  "createdAt": 1781701628141,
  "startedAt": 1781701628142,
  "hasExplicitReturn": false
}

// After termination (COMPLETED)
{
  "runId": "run-20260617-134906-277-7149",
  "scriptId": "calc_sum",
  "version": "1",
  "status": "COMPLETED",
  "createdAt": 1781671746278,
  "startedAt": 1781671746282,
  "endedAt": 1781671746293,
  "hasExplicitReturn": true
}
```

```java
String s = String.valueOf(teebox.getRunStatus(runId).get("status")); // "RUNNING" / "COMPLETED" ...
```

#### `getRunResult(runId)` response example

```jsonc
{
  "runId": "run-20260617-133304-465-cf31",
  "scriptId": "calc_sum",
  "version": "1",
  "status": "COMPLETED",
  "hasExplicitReturn": true,
  "resultData": { "ok": true, "sum": 42 }   // the actual structured result (sum is Double 42.0)
}
```

#### `getRunTasksSummary(runId)` response example

Aggregates that run's tasks by status.

```jsonc
{
  "runId": "run-20260617-222351-196-9c86",
  "total": 1,
  "running": 0,
  "completed": 1,
  "failed": 0,
  "killed": 0,
  "other": 0     // states not in the above categories (QUEUED, etc.)
}
```

#### `listScriptRuns(scriptId)` response example

Returns that script's runs as an **array** (each item has the same shape as the `getRun` summary).

```jsonc
[
  {
    "runId": "run-20260617-222351-196-9c86",
    "scriptId": "task_job",
    "version": "1",
    "status": "COMPLETED",
    "createdAt": 1781702631196,
    "startedAt": 1781702631201,
    "endedAt": 1781702631241,
    "hasExplicitReturn": true,
    "resultSummary": "{ \"ok\": true }"
  }
]
```

### 7.4 Wait until termination

```java
// (A) When you want to receive the status payload
Map<String, Object> terminalStatus = teebox.waitForRunTerminal(runId, 30000L);

// (B) "Submit + wait + return result" in one call (suitable for short/immediate scripts)
Map<String, Object> result = teebox.runAndWait("calc_sum", null, props, 30000L);
Map<?, ?> data = (Map<?, ?>) result.get("resultData");

// (C) For file-streaming scripts (returning STREAM_FILE): "submit + wait + stream" in one call
java.io.OutputStream sink = new java.io.FileOutputStream("result.json");
try { teebox.runAndStream("streamer", null, props, sink, 60000L); } finally { sink.close(); }
```

- `waitForRunTerminal` polls with a 50ms→1s backoff and, once a terminal state is reached, returns the **status payload** (same shape as `getRunStatus`). On exceeding `timeoutMs` it throws `IOException` (the message includes the `runId` → re-pollable).
- `runAndWait` performs submit→wait→result lookup and returns the **result payload** (same shape as `getRunResult`, including `resultData`). If the run is not `COMPLETED`, it throws `IOException` (includes the server `errorMessage`).
- **`runAndStream`** is **dedicated to file-streaming scripts** that return `STREAM_FILE` — it performs submit→wait→`streamRunResult` (streaming) in one call and returns the number of bytes written (nowhere — engine, response, or client — holds the whole thing in memory). Failures **after** submit (non-`COMPLETED`, timeout, non-stream result 409) are thrown as **`RunStreamException`**, and you can extract the runId via **`getRunId()`** (no message parsing needed). Even on a client timeout the run continues on the server, so you can re-retrieve it with `streamRunResult` using that runId. The streaming version of `runAndWait`.

```java
try {
    teebox.runAndStream("streamer", null, props, sink, 60000L);
} catch (TeeBoxClient.RunStreamException e) {
    String runId = e.getRunId();   // can later retry with teebox.streamRunResult(runId, sink)
}
```

---

## 8. Output capture (receiving a long job's ID, etc. mid-run)

You can capture values that the script prints to stdout (e.g. a background job ID) with a regex, expose them in the `published` map, and have the client wait for that value.

### 8.1 Create an outputRule

```java
import java.util.List;
import java.util.ArrayList;

List<Map<String, Object>> rules = new ArrayList<Map<String, Object>>();

// Simple form: publish the first match of pattern (capture group 1) from stdout as jobId
rules.add(TeeBoxClient.outputRule("jobId", "JOB_ID=(\\S+)"));

// Full form: outputRule(publishKey, pattern, stream, captureGroup, firstOnly)
rules.add(TeeBoxClient.outputRule("token", "TOKEN:(\\w+)", "stdout", 1, true));
```

| Parameter | Meaning | Default (simple form) |
|----------|------|----------------|
| `publishKey` | the key published in the `published` map | (required) |
| `pattern` | regex | (required) |
| `stream` | `stdout` / `stderr` | `stdout` |
| `captureGroup` | capture group number to use | `1` |
| `firstOnly` | whether to publish only the first match | `true` |

### 8.2 Register together with the rules

```java
teebox.registerScript("long_job", source, true, rules);
// or when adding a version: teebox.addScriptVersion("long_job", source, true, rules);
```

### 8.3 Wait for the published value

```java
String runId = (String) teebox.submitRun("long_job", props).get("runId");

// When the script prints jobId during execution, retrieve that value (execution continues)
Object jobId = teebox.waitForPublished(runId, "jobId", 60000L);  // e.g. "abc123" (the captured string)
```

- `waitForPublished` polls `getRun`'s `published` map and, once the key is published, returns **that value (usually the captured string)** (returning it does not stop execution). If the run ends without publishing the key, or `timeoutMs` is exceeded, it throws `IOException`.
- For reference, the `published` map in the `getRun` response looks like this (it includes a `<key>.detectedAt` key carrying the publish time):

```jsonc
"published": {
  "jobId": "abc123",
  "jobId.detectedAt": 1781702632309
}
```

---

## 9. Return shapes & exceptions

### Return types

The built-in JSON codec maps responses to the following types.

| JSON | Java |
|------|------|
| object | `Map<String, Object>` (`LinkedHashMap`, preserves insertion order) |
| array | `List<Object>` |
| string | `String` |
| number | `Double` ← **`Double` even when it looks like an integer** (e.g. `42` → `42.0`) |
| true/false | `Boolean` |
| null | `null` |

> To treat a number as an integer, convert it with `((Number) v).intValue()` / `.longValue()`.

### Exceptions

| Exception | When it occurs |
|------|-----------|
| `IllegalArgumentException` | a required argument is missing (`scriptId`/`content`/`runId`, etc.), an invalid `baseUrl` |
| `IOException` | HTTP status mismatch (message has `method path -> HTTP code: body`), network error, wait timeout, non-`COMPLETED` termination (`runAndWait`) |
| `InterruptedException` | a wait helper (`runAndWait`/`waitForRunTerminal`/`waitForPublished`) is interrupted during `Thread.sleep`. **`runAndStream` is the exception** — it wraps an interrupt in `RunStreamException` too (restoring the interrupt flag, cause is `InterruptedException`) and does not throw `InterruptedException` directly |
| `RunStreamException` (extends `IOException`) | `runAndStream` throws it for every failure **after** submit (timeout, non-`COMPLETED`, non-stream 409, interrupt). The runId can be recovered via `getRunId()` |

```java
try {
    Map<String, Object> result = teebox.runAndWait("calc_sum", null, props, 30000L);
} catch (IOException e) {
    // HTTP error / failed termination / timeout — diagnostic info in e.getMessage()
} catch (InterruptedException e) {
    Thread.currentThread().interrupt();
}
```

---

## 10. Full method reference

The full list of public methods. For detailed response shapes/examples, see §4–§9 in the body.

### Constructor
| Method | Description |
|--------|------|
| `new TeeBoxClient(String baseUrl)` | Create a client. `baseUrl` required (`IllegalArgumentException` if empty), trailing `/` removed automatically |

### Configuration (chainable, returns `TeeBoxClient`) — §4·§5
| Method | Description |
|--------|------|
| `setConnectTimeoutMs(int)` | TCP connect timeout (ms, default 5000) |
| `setReadTimeoutMs(int)` | Response read timeout (ms, default 15000) |
| `setBearerToken(String)` | Shared token for all namespaces (fallback) |
| `setClientApiToken(String)` | Token dedicated to `/api/client` |
| `setPublisherApiToken(String)` | Token dedicated to `/api/publisher` |
| `setAdminApiToken(String)` | Token dedicated to `/api/admin` |

### Scripts — §6
| Method | Returns | Description |
|--------|------|------|
| `registerScript(scriptId, content, activate)` | `Map` | Register + version auto-increment |
| `registerScript(scriptId, content, activate, outputRules)` | `Map` | Auto-increment + output capture rules |
| `registerScript(scriptId, version, content, activate)` | `Map` | Register with explicit version |
| `registerScript(scriptId, version, content, description, labels, activate)` | `Map` | Specify version + description/labels |
| `addScriptVersion(scriptId, content, activate)` | `Map` | Add a version (auto-increment) |
| `addScriptVersion(scriptId, content, activate, outputRules)` | `Map` | Auto-increment + output rules |
| `addScriptVersion(scriptId, version, content, activate)` | `Map` | Add with explicit version |
| `activateScriptVersion(scriptId, version)` | `Map` | Change the active version |
| `listScripts()` | `List<Object>` | Full script list |
| `listActiveScripts()` | `List<Object>` | List (each script's `versions[]` reduced to the active version only) |
| `getScript(scriptId)` | `Map` | Script detail (versions/active/settings) |
| `getActiveScript(scriptId)` | `Map` | Detail (`versions[]` reduced to the active version only) |
| `getScriptContent(scriptId)` | `String` | Active version source |
| `getScriptContent(scriptId, version)` | `String` | Specific version source |
| `static outputRule(publishKey, pattern)` | `Map` | Output capture rule builder (stdout, first match, group 1) |
| `static outputRule(publishKey, pattern, stream, captureGroup, firstOnly)` | `Map` | Output capture rule builder (full spec) |

### Execution / tracking — §7·§8
| Method | Returns | Description |
|--------|------|------|
| `submitRun(scriptId, props)` | `Map`(`runId`) | Submit active version for execution (async, 202) |
| `submitRun(scriptId, version, props)` | `Map`(`runId`) | Submit with a specified version |
| `getRun(runId)` | `Map` | Full summary (includes `published`·`resultSummary`) |
| `getRunStatus(runId)` | `Map` | Status/timestamps only (for lightweight polling) |
| `getRunResult(runId)` | `Map` | Result (`resultData`). A redacted descriptor for a stream result |
| `getRunTasksSummary(runId)` | `Map` | Task counts by status |
| `listScriptRuns(scriptId)` | `List<Object>` | Run list for the script |
| `getRunStdout(runId)` | `Map` | Captured stdout (`lines`/`lineCount`). Queryable during RUNNING too |
| `getRunStderr(runId)` | `Map` | Captured stderr (same shape) |
| `getRunStdoutLines(runId)` | `List<String>` | stdout lines only |
| `getRunStderrLines(runId)` | `List<String>` | stderr lines only |
| `streamRunResult(runId, OutputStream)` | `long`(bytes) | Stream a `STREAM_FILE` result to an OutputStream (for large data; the caller closes the stream). `IOException`(409) if not a stream result |
| `waitForRunTerminal(runId, timeoutMs)` | `Map`(status) | Client-side poll until termination (50ms→1s backoff). `IOException` on exceeding the timeout |
| `runAndWait(scriptId, version, props, timeoutMs)` | `Map`(result) | Submit→wait→result. `IOException` on non-`COMPLETED`/timeout |
| `runAndStream(scriptId, version, props, OutputStream, timeoutMs)` | `long`(bytes) | `STREAM_FILE` scripts only: submit→wait→stream in one call. Failures after submit are `RunStreamException` (recover runId via `getRunId()`) |
| `waitForPublished(runId, key, timeoutMs)` | `Object` | Poll until `published[key]` appears and return that value (§8) |

### JSON utilities (optional)
| Method | Returns | Description |
|--------|------|------|
| `static TeeBoxClient.Json.parse(String)` | `Object` | JSON string → `Map`/`List`/`String`/`Double`/`Boolean`/`null` |
| `static TeeBoxClient.Json.write(Object)` | `String` | value → JSON string (`Map`/`List`/`Number`/`Boolean`, etc.) |

```java
// JSON utility usage example (built-in codec, no separate library needed)
Object parsed = TeeBoxClient.Json.parse("{\"a\":1,\"b\":[2,3]}");
String json   = TeeBoxClient.Json.write(parsed);   // {"a":1,"b":[2,3]}
```

### Exceptions
| Exception | When |
|------|------|
| `IllegalArgumentException` | missing required argument / invalid `baseUrl` |
| `IOException` | HTTP status mismatch (message has `method path -> HTTP code: body`), network error, wait timeout, non-`COMPLETED` termination |
| `RunStreamException`(extends `IOException`) | failure after submit in `runAndStream`. Provides `getRunId()` |
| `InterruptedException` | `runAndWait`/`waitForRunTerminal`/`waitForPublished` interrupted during `Thread.sleep` (but `runAndStream` wraps it in `RunStreamException`) |

---

## 11. End-to-end example — tracking a long job

```java
import com.flatide.teebox.client.TeeBoxClient;
import java.util.*;

TeeBoxClient teebox = new TeeBoxClient("http://teebox-host:18080");

// Output rule that publishes "JOB_ID=xxxx" from stdout as jobId
List<Map<String, Object>> rules = new ArrayList<Map<String, Object>>();
rules.add(TeeBoxClient.outputRule("jobId", "JOB_ID=(\\S+)"));

// Register + activate (auto version)
teebox.registerScript("batch", batchSource, true, rules);

// Submit a run (async)
Map<String, Object> props = new LinkedHashMap<String, Object>();
props.put("target", "/data/in");
String runId = (String) teebox.submitRun("batch", props).get("runId");

try {
    // Obtain jobId mid-run (execution keeps going)
    Object jobId = teebox.waitForPublished(runId, "jobId", 60000L);
    System.out.println("started job: " + jobId);

    // Wait until final termination
    Map<String, Object> status = teebox.waitForRunTerminal(runId, 600000L);
    if ("COMPLETED".equals(String.valueOf(status.get("status")))) {
        Map<String, Object> result = teebox.getRunResult(runId);
        System.out.println("result: " + result.get("resultData"));
    } else {
        System.out.println("run ended: " + status.get("status"));
    }
} catch (IOException e) {
    // HTTP/timeout error
} catch (InterruptedException e) {
    Thread.currentThread().interrupt();
}
```

---

## 12. Caveats summary

- **Numbers come in as `Double`**. Integer conversion needed.
- **Running with the version omitted = the active version** (not the newest).
- The wait helpers' timeout is **client-side only** and does not stop server execution. Re-poll with the same `runId`.
- Process termination (kill) is out of the client's scope — use the TeeBox admin UI/`/api/admin/...` or the admin API.
- If you modify the file directly, keep it **Java 7 compatible** (no lambdas/streams/`java.time`).
