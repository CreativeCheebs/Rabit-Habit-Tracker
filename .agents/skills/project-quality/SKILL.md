---
name: project-quality
description: Discover and run relevant Habit Lab Gradle, browser, Android UI, and security checks, reporting installed-tool gaps and actual results.
---

# Project quality tools

Read root `AGENTS.md`, `CLAUDE.md`, current module build files, `.github/workflows/quality.yml`, and `ui-qa/package.json`. For device or browser UI checks also read `ui-qa/README.md` and inspect the script being run.

Choose checks relevant to the change or requested audit. For a full audit, inventory all gates below and mark unavailable or unconfigured gates explicitly. Preserve existing dependency pins and lockfiles while checking the project; install missing prerequisites only when the task calls for setup or fixing them.

| Check | Existing entry point | Prerequisites / limits |
|---|---|---|
| Task discovery | Root `gradlew.bat tasks --all` (Unix: `./gradlew tasks --all`) | Gradle/JDK and dependency access; use when task names are uncertain |
| Kotlin formatting / static analysis | `:composeApp:ktlintCheck`, `:composeApp:detekt` | Confirm plugin/tasks; preserve existing baselines rather than regenerating them to suppress failures |
| App and server JVM tests | `:composeApp:jvmTest`, `:sync-server:test` from root | Root catalog applies to included server; standalone server uses its own catalog |
| Android package | `:composeApp:assembleDebug` | Android SDK; this is a build, not UI testing |
| iOS simulator | `:composeApp:iosSimulatorArm64Test` | macOS and Xcode; do not claim it ran on Windows/Linux |
| Web parser | `node --test ui-qa/yaml-test.mjs` | Local test with no live server |
| Browser interactions | `node ui-qa/webapp-check.mjs` | Inspect first; set `HABITLAB_WEB`; mutating journeys need authorized disposable test credentials |
| Screenshots / comparisons | `npm --prefix ui-qa run capture`, `capture-device`, `compare` | Playwright/Chromium; ADB/device for capture-device; report visual evidence and pixel-diff limitations |
| Android interactions | Existing Maestro flow under `ui-qa/flows/` | Installed Maestro and device/emulator; review flow before execution |
| Secrets / dependencies | Gitleaks and dependency review jobs in existing GitHub Actions | CI jobs are not proof of a local scan; check CLI availability or actual CI results |
| SQLDelight migrations | Inspect database configuration and snapshots, then discover task | No schema output/snapshot verification is configured at the time of this port; recheck current files before assuming the verification task exists |

Never use live account/database reset or deployment commands merely to satisfy a check. Keep credential values out of logs and reports. A request to run checks does not authorize changing live data or publishing artifacts.

Report commands and results: passed, failed, unavailable, unconfigured, or not run. Explain the reason for each missing gate and propose the smallest follow-up. Fix problems only when the user has requested implementation or fixes.
