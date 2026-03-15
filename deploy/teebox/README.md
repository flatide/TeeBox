# TeeBox Deployment

This bundle runs TeeBox without Gradle.

## Build the bundle

```bash
./gradlew teeBoxZip
```

Output:

```text
build/distributions/propertee-teebox-dist.zip
```

## Deploy on another server

1. Copy `propertee-teebox-dist.zip` to the target server.
2. Unzip it.
3. Edit `conf/teebox.properties`.
4. Create the configured `scriptsRoot` and `dataDir` directories.
5. Start the server:

```bash
./bin/run-teebox.sh
```

The script accepts extra CLI arguments and respects:

- `PROPERTEE_TEEBOX_CONFIG`
- `JAVA_HOME`
- `JAVA_OPTS`

## Notes

- The deployment jar is `lib/propertee-teebox.jar`.
- System properties in `JAVA_OPTS` override values from `conf/teebox.properties`.
- The server exposes `/admin` HTML UI and `/api/*` JSON endpoints.
- For split credentials, use `propertee.teebox.clientApiToken`, `propertee.teebox.publisherApiToken`, and `propertee.teebox.adminApiToken`.
