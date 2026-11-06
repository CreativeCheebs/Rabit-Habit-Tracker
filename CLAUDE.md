# Engineering rules

You work in the Habit Lab repository: a Kotlin Multiplatform Compose app targeting Android, iOS, and JVM tests; a separate Kotlin/JVM Ktor sync server; and a browser app bundled as static resources by the server. The browser app is not Kotlin/Wasm. The app uses Material 3, SQLDelight, and Gradle Kotlin DSL.

## Before changing code
- Read relevant module Gradle files, version catalog, architecture decisions, and tests.
- Discover actual targets/tasks with `./gradlew tasks --all`; run tasks from the repository root unless working specifically inside `sync-server/`.
- State assumptions and acceptance criteria for non-trivial work. Ask only for missing product decisions that block implementation.

## Platform boundaries
- Put portable logic and UI in common source sets only when dependencies support every target.
- Keep Android APIs, Android Compose UI test dependencies, Android SQLite drivers, and Android lifecycle APIs in Android-specific source sets/modules.
- Keep browser DOM, JavaScript, CSS, and browser persistence in the static web app under `sync-server/src/main/resources/webapp/`; do not introduce Kotlin/Wasm or SQLDelight web-worker assumptions without an explicit product decision.
- SQLDelight uses platform-specific drivers in `composeApp`: Android, Native/iOS, and SQLite/JVM as configured. Schema changes need a forward migration and upgrade/data-preservation tests; the current project has no checked-in SQLDelight schema snapshot or migration-verification task configured.
- Material 3 is the chosen UI system. Avoid adding another component system or platform-specific Material implementation without a documented requirement.

## Quality rules
- Prefer small changes and tests at the narrowest meaningful layer.
- Add common tests for portable behavior, JVM tests for sync/database behavior, Android interaction checks using the established Maestro flow, and Playwright checks for the hosted browser app.
- Preserve accessibility semantics, keyboard navigation, responsive layouts, loading/empty/error states, and light/dark themes.
- SQL changes require a forward migration, schema snapshot update, and migration verification. Never rewrite a released migration.
- Never commit credentials, signing keys, production data, generated build outputs, or local secrets. Use environment-based CI secrets and least-privilege permissions.
- Update user documentation and changelog when behavior, setup, schema, or release process changes.

## Finish protocol
- Run relevant quality tasks and report exact commands/results. If an environment prevents a task, say so plainly.
- Review the diff for unrelated edits, generated artifacts, secrets, and platform leakage.
- Never claim a browser visual check, Android device test, or iOS simulator test ran unless it did.
