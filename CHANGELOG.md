# Changelog

All notable changes to TeeBox are documented here.

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
