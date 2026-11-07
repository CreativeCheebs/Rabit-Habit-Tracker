# Habit Lab project guidance for Codex

Read `PROGRESS.md` first for current work ownership, deployment state, and operational lessons, then read `CLAUDE.md` for shared engineering rules. Respect the ownership restrictions in the progress log (including the current read-only ownership of `composeApp/`) unless the user explicitly authorizes work there. Read the actual build files before assuming a documented task exists.

## Project boundaries

- `composeApp/`: Compose Multiplatform, Material 3, SQLDelight; Android, iOS, and JVM targets as configured in Gradle.
- `sync-server/`: Kotlin/JVM Ktor server with a separate standalone Gradle catalog.
- `sync-server/src/main/resources/webapp/`: hosted JavaScript/HTML/CSS app.
- There is no configured Kotlin/Wasm target. Keep the root and standalone server catalogs separate and preserve existing version pins unless the task requires an upgrade.

## Codex workflows

Repository skills live in `.agents/skills/`. Select the relevant workflow for the task; explicit invocation uses `$skill-name`:

- `$kmp-feature`: portable app features and platform boundaries.
- `$ui-quality-review`: Compose and hosted browser usability/accessibility review.
- `$sql-delight-change`: schema, query, driver, and upgrade work.
- `$release-readiness`: review release evidence and outstanding gates.
- `$engineering-review`: architecture, Android, hosted web, QA, security, UI/UX, or release review using the ported Claude roles.
- `$project-quality`: discover and run applicable existing quality tools and report evidence.

Use review perspectives within the current task. Do not spawn independent agents merely because role files exist; delegate only when authorized by the user or applicable instructions.

## Verification and tools

Use existing Gradle tasks, scripts, and CI in place. Skills provide instructions and do not install tools or create permissions. On Windows use `gradlew.bat`; verify generated tasks before claiming a gate ran. Select checks proportionate to the change and report failures, skips, and missing tools honestly.

The hosted browser E2E suite defaults to a LAN server and its authenticated flows modify data. Before running it, inspect the script, set `HABITLAB_WEB` to an authorized test instance, and use a disposable test account for mutating flows. A request to run checks does not authorize database deletion, resetting accounts, or production deployment. Read `ui-qa/README.md` for prerequisites, not as authorization to execute its reset/deployment examples.

Keep secrets, signing material, production databases, and local transfer tooling out of commits and output. Do not deploy or commit unless requested. Update `PROGRESS.md` and relevant docs when completing an implementation step.
