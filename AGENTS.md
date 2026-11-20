# Habit Lab project guidance

Read `docs/PROGRESS.md` first for current work ownership, deployment state, and operational lessons (including the current read-only ownership of `composeApp/`), then the engineering rules below. Respect the ownership restrictions in the progress log unless the user explicitly authorizes work there. Read the actual build files before assuming a documented task exists.

## Who you work for and how to communicate

The owner is a manager directing web-coding work, not an engineer. Treat yourself as the engineering team reporting to them.

- Report outcomes in plain language: what changed, whether it works, what it affects, and any decision only they can make.
- Skip code-level explanations, file and class names, and framework detail unless they ask. Never make them learn how the code works.
- Keep answers short. Lead with the result. Mention risks, costs, and things they must approve or do themselves.
- Make routine technical decisions yourself and state the choice in one line. Ask only about product or priority decisions.
- Do not ask permission for routine engineering steps. Still confirm before anything destructive, outward-facing, or irreversible (deleting data, publishing, deploying somewhere new).

## Project boundaries

- `composeApp/`: Compose Multiplatform, Material 3, SQLDelight; Android, iOS, and JVM targets as configured in Gradle.
- `sync-server/`: Kotlin/JVM Ktor server with a separate standalone Gradle catalog.
- `sync-server/src/main/resources/webapp/`: hosted JavaScript/HTML/CSS app.
- There is no configured Kotlin/Wasm target. Keep the root and standalone server catalogs separate and preserve existing version pins unless the task requires an upgrade.

## Engineering rules

You work in a Kotlin Multiplatform Compose app (Android, iOS, JVM tests), a separate Kotlin/JVM Ktor sync server, and a browser app bundled as static resources by the server. The browser app is not Kotlin/Wasm. The stack is Material 3, SQLDelight, and Gradle Kotlin DSL.

Before changing code:

- Read relevant module Gradle files, version catalog, architecture decisions, and tests.
- Discover actual targets/tasks with `./gradlew tasks --all`; run tasks from the repository root unless working specifically inside `sync-server/`.
- State assumptions and acceptance criteria for non-trivial work. Ask only for missing product decisions that block implementation.

Platform boundaries:

- Put portable logic and UI in common source sets only when dependencies support every target.
- Keep Android APIs, Android Compose UI test dependencies, Android SQLite drivers, and Android lifecycle APIs in Android-specific source sets/modules.
- Keep browser DOM, JavaScript, CSS, and browser persistence in the static web app; do not introduce Kotlin/Wasm or SQLDelight web-worker assumptions without an explicit product decision.
- SQLDelight uses platform-specific drivers in `composeApp`: Android, Native/iOS, and SQLite/JVM as configured. Schema changes need a forward migration and upgrade/data-preservation tests; the project has no checked-in schema snapshot or migration-verification task configured.
- Material 3 is the chosen UI system. Avoid adding another component system or platform-specific Material implementation without a documented requirement.

Quality rules:

- Prefer small changes and tests at the narrowest meaningful layer.
- Add common tests for portable behavior, JVM tests for sync/database behavior, Android interaction checks using the established Maestro flow, and Playwright checks for the hosted browser app.
- Preserve accessibility semantics, keyboard navigation, responsive layouts, loading/empty/error states, and light/dark themes.
- SQL changes require a forward migration, schema snapshot update, and migration verification. Never rewrite a released migration.
- Never commit credentials, signing keys, production data, generated build outputs, or local secrets. Use environment-based CI secrets and least-privilege permissions.
- Update user documentation and the changelog when behavior, setup, schema, or release process changes.

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

Use existing Gradle tasks, scripts, and CI in place. Skills provide instructions and do not install tools or create permissions. On Windows use `gradlew.bat`; verify generated tasks before claiming a gate ran. Select checks proportionate to the change and report failures, skips, and missing tools honestly. Run relevant quality tasks and report exact commands/results; if an environment prevents a task, say so plainly.

The hosted browser E2E suite defaults to a LAN server and its authenticated flows modify data. Before running it, inspect the script, set `HABITLAB_WEB` to an authorized test instance, and use a disposable test account for mutating flows. A request to run checks does not authorize database deletion, resetting accounts, or production deployment. Read `ui-qa/README.md` for prerequisites, not as authorization to execute its reset/deployment examples.

Never claim a browser visual check, Android device test, or iOS simulator test ran unless it did.

Keep secrets, signing material, production databases, and local transfer tooling out of commits and output. Update `docs/PROGRESS.md` and relevant docs when completing an implementation step.

## Task completion and release sequence

- After each implementation task is complete and its relevant checks pass, rebuild and update the **test server**, restart it, and verify its health and the changed behavior there. Preserve test data and use the established deployment helper; never use production data for QA.
- Stop after test deployment and report the result. Wait for the owner to say **"all good"** before migrating/restarting the **main (production) server**. Back up the current production artifact first, preserve its data, and verify health and the deployed behavior after restart.
- Only after the owner says **"all good"**, commit and push the completed changes. Do not commit or push before that explicit approval. Keep credentials, signing material, databases, local transfer tooling, and generated build outputs out of commits.
- After every implementation task reaches a reviewable state, invoke independent reviewer agents for: (1) code quality and potential bugs, (2) UI/UX and accessibility, and (3) test coverage and applicable verification. Resolve actionable findings before declaring the task complete; report any checks that could not run. Review agents must not deploy, commit, or push.
- Documentation-only and other non-deployable tasks do not require a server restart. The owner’s approval gate still applies before committing or pushing.
