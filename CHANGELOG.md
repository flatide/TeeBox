# Changelog

All notable changes to TeeBox are documented here.

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
