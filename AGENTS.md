# Repository Guidelines

## Project Structure & Module Organization
This repository is a single Gradle Java service named `propertee-teebox`. Application code lives in `src/main/java/com/propertee/teebox`, with entry points in `TeeBoxMain` and `TeeBoxUpstreamMockMain`. Tests live in `src/test/java/com/propertee/tests`. Demo ProperTee scripts are under `demo/teebox`, and deployable packaging assets live in `deploy/teebox`. Build outputs are generated in `build/` and should not be edited by hand.

## Build, Test, and Development Commands
Use the Gradle wrapper so the project stays on the pinned toolchain.

- `./gradlew test`: runs the JUnit 4 test suite.
- `./gradlew run`: starts the TeeBox server with `com.propertee.teebox.TeeBoxMain`.
- `./gradlew runTeeBoxUpstream`: runs the upstream mock client harness.
- `./gradlew teeBoxZip`: builds `build/distributions/propertee-teebox-dist.zip`.

For local manual testing, start the server against the demo scripts:

```bash
./gradlew -Dpropertee.teebox.scriptsRoot=$PWD/demo/teebox \
  -Dpropertee.teebox.dataDir=/tmp/propertee-teebox-data run
```

## Coding Style & Naming Conventions
Target Java 17. Follow the existing style: 4-space indentation, braces on the same line, and descriptive PascalCase class names such as `RunManager` or `TeeBoxConfig`. Use `camelCase` for methods and fields, and keep package names under `com.propertee.teebox`. Prefer small, focused classes over adding unrelated logic to `TeeBoxServer`.

## Testing Guidelines
Tests use JUnit 4 with `org.junit.Test` and `Assert`. Add tests in `src/test/java/com/propertee/tests` and name classes `*Test`. Match current method naming style such as `serverShouldRequireBearerTokenWhenConfigured`. Cover both API behavior and configuration edge cases when changing server routes, auth, persistence, or script execution flow. Run `./gradlew test` before opening a PR.

## Commit & Pull Request Guidelines
There is no existing git history in this repository yet, so no commit convention is established by precedent. Use short imperative commit subjects, for example `Add admin API token fallback test`. Keep commits scoped to one concern. PRs should include a clear summary, the user-visible or API impact, test evidence, and screenshots for `/admin` UI changes. Link related issues or design notes when available.

## Configuration & Security Notes
Runtime settings use the `propertee.teebox.*` system-property prefix. Do not commit real API tokens or environment-specific paths. Keep secrets in local config or CI variables, and use the split namespace tokens (`clientApiToken`, `publisherApiToken`, `adminApiToken`) when testing auth-sensitive changes.
