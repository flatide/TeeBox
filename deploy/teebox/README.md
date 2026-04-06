# TeeBox Deployment

This bundle runs TeeBox without Gradle.

## Build the bundle

```bash
./gradlew teeBoxZip
```

This produces the default GitHub release artifact without a bundled Java runtime.

If you explicitly want a Linux x86_64 Java 21 runtime bundled into the zip:

```bash
./gradlew fetchRuntimeLinuxX64 teeBoxZipWithRuntime
```

By default this downloads the official OpenJDK 21.0.2 Linux/x64 archive from `download.java.net` and verifies its SHA-256.

Override only if needed:

```bash
./gradlew fetchRuntimeLinuxX64 teeBoxZipWithRuntime \
  -Dpropertee.teebox.runtimeLinuxX64Url=https://example.com/java21-runtime-linux-x64.tar.gz \
  -Dpropertee.teebox.runtimeLinuxX64Sha256=<sha256>
```

Environment-variable alternatives:

- `PROPERTEE_TEEBOX_RUNTIME_LINUX_X64_URL`
- `PROPERTEE_TEEBOX_RUNTIME_LINUX_X64_SHA256`

Output:

```text
build/distributions/propertee-teebox-dist.zip
build/distributions/propertee-teebox-dist-with-runtime.zip
```

## Deploy on another server

1. Copy `propertee-teebox-dist.zip` to the target server.
2. Unzip it.
3. Download a Linux x86_64 Java 21 runtime archive separately.
4. Create a `runtime/` directory under the unzipped TeeBox root and unpack the runtime there so `runtime/bin/java` exists.
5. Edit `conf/teebox.properties`.
6. Create the configured `dataDir` directory.
7. Start the server:

```bash
./bin/run-teebox.sh
```

The deployed directory should look like:

```text
propertee-teebox-dist/
  bin/
  conf/
  lib/
  runtime/
    bin/java
```

If you choose to use the optional bundled artifact instead:

1. Build `propertee-teebox-dist-with-runtime.zip`.
2. Copy and unzip it on the target server.
3. Edit `conf/teebox.properties`.
4. Start the server:

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
- `teeBoxZip` is the recommended GitHub release artifact. It does not include a Java runtime.
- If `runtime/bin/java` exists in the deployed bundle, `run-teebox.sh` uses it before `JAVA_HOME` or system `java`.
- The default bundled runtime source is the official OpenJDK 21.0.2 Linux/x64 archive from `download.java.net`.
- `fetchRuntimeLinuxX64` unpacks downloads into `build/runtime-linux-x64/`.
- `teeBoxZipWithRuntime` is optional and mainly useful for local packaging or controlled internal distribution.
- System properties in `JAVA_OPTS` override values from `conf/teebox.properties`.
- The server exposes `/admin` HTML UI, `/health`, and namespaced `/api/client/*`, `/api/publisher/*`, `/api/admin/*` JSON endpoints.
- For split credentials, use `propertee.teebox.clientApiToken`, `propertee.teebox.publisherApiToken`, and `propertee.teebox.adminApiToken`.
