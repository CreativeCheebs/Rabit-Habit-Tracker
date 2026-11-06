# Gradle quality and test gates

Task names depend on plugin version, module path, configured targets, and source sets. Treat the following as the expected task families, then confirm them using `./gradlew tasks --all` before copying into CI.

| Gate | Typical task | Scope |
|---|---|---|
| Format | `:composeApp:ktlintCheck` | Kotlin app source; configured via root catalog |
| Static analysis | `:composeApp:detekt` | Kotlin app source; review findings and maintain a deliberate baseline |
| Unit tests | `:module:allTests` | KMP test targets configured in that module; does not imply Android instrumentation |
| Android compile/package | `:app:assembleDebug` | Android application only |
| Android UI | Maestro flow in `ui-qa/README.md` | Android device or emulator; runs outside Gradle |
| iOS simulator tests | `:composeApp:iosSimulatorArm64Test` | macOS with Xcode; unavailable on Linux runners |
| Browser UI | `node ui-qa/webapp-check.mjs` | Requires a running server; authenticated paths also need disposable test credentials |
| SQLDelight migration | Not configured yet | Add schema output, checked-in baseline, and migration tasks when versioned schema migrations begin |

The browser app is JavaScript/HTML/CSS; this project has no Kotlin/Wasm target. `allTests` is not guaranteed to include Android instrumentation or iOS simulator tests on every host. The current CI runs JVM/server tests on Linux and iOS simulator tests on macOS.

## Suggested staged adoption

1. `./gradlew tasks --all` and verify target-specific compile/test tasks.
2. Run `ktlintCheck`, `detekt`, and common/platform unit tests.
3. Establish a SQLDelight schema snapshot and migration verification when versioned migrations are introduced.
4. Add or run the existing Android Maestro interaction flow on an emulator/device.
5. Add browser E2E to CI only after provisioning a disposable test server and credentials.
6. Gate merges on all required checks and upload test reports on failure.

Keep quality gates deterministic and use exact module paths. Do not add Wasm tasks unless the product explicitly adopts a Kotlin/Wasm target.
