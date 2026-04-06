# TeeBox Deployment

This bundle runs TeeBox without Gradle.

## Build the bundle

```bash
./gradlew teeBoxZip
```

To download and bundle a Linux x86_64 Java 21 runtime first:

```bash
./gradlew fetchRuntimeLinuxX64 teeBoxZip
```

By default this downloads the official OpenJDK 21.0.2 Linux/x64 archive from `download.java.net` and verifies its SHA-256.

Override only if needed:

```bash
./gradlew fetchRuntimeLinuxX64 teeBoxZip \
  -Dpropertee.teebox.runtimeLinuxX64Url=https://example.com/java21-runtime-linux-x64.tar.gz \
  -Dpropertee.teebox.runtimeLinuxX64Sha256=<sha256>
```

Environment-variable alternatives:

- `PROPERTEE_TEEBOX_RUNTIME_LINUX_X64_URL`
- `PROPERTEE_TEEBOX_RUNTIME_LINUX_X64_SHA256`

Output:

```text
build/distributions/propertee-teebox-dist.zip
```

## Deploy on another server

1. Copy `propertee-teebox-dist.zip` to the target server.
2. Unzip it.
3. If you want TeeBox to ignore the system Java and use a bundled runtime, either:
   - run `fetchRuntimeLinuxX64` before `teeBoxZip`, or
   - manually place a Linux x86_64 Java 21 runtime under `deploy/teebox/runtime-linux-x64/`
4. Edit `conf/teebox.properties`.
5. Create the configured `dataDir` directory.
6. Start the server:

```bash
./bin/run-teebox.sh
```

On Windows:

```cmd
bin\run-teebox.bat
```

The scripts accept extra CLI arguments and respect:

- `PROPERTEE_TEEBOX_CONFIG`
- `JAVA_HOME`
- `JAVA_OPTS`

## Notes

- The deployment jar is `lib/propertee-teebox.jar`.
- If `runtime/bin/java` exists in the deployed bundle, `run-teebox.sh` uses it before `JAVA_HOME` or system `java`.
- The default bundled runtime source is the official OpenJDK 21.0.2 Linux/x64 archive from `download.java.net`.
- `fetchRuntimeLinuxX64` unpacks downloads into `build/runtime-linux-x64/`; manually managed runtimes under `deploy/teebox/runtime-linux-x64/` still work.
- System properties in `JAVA_OPTS` override values from `conf/teebox.properties`.
- The server exposes `/admin` HTML UI, `/health`, and namespaced `/api/client/*`, `/api/publisher/*`, `/api/admin/*` JSON endpoints.
- For split credentials, use `propertee.teebox.clientApiToken`, `propertee.teebox.publisherApiToken`, and `propertee.teebox.adminApiToken`.
