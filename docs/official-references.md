# Official references checked 6 November 2026

These live pages change. Recheck them before upgrades; upstream docs and compatibility matrices take precedence over this repository's version catalog. Wasm references are background only: Habit Lab has no Kotlin/Wasm target.

## Kotlin and Compose
- [Kotlin/Wasm overview](https://kotlinlang.org/docs/wasm-overview.html) — Kotlin/Wasm browser support, Wasm GC/exception requirements, and Compose web target.
- [Get started with Compose Multiplatform and Wasm](https://kotlinlang.org/docs/wasm-get-started.html) — `wasmJsBrowserDistribution` task and production output.
- [Kotlin Multiplatform Gradle configuration](https://kotlinlang.org/docs/gradle-configure-project.html) — target configuration.
- [Compose Multiplatform releases](https://github.com/JetBrains/compose-multiplatform/releases) — release notes and Kotlin minimums. Release 1.12.0 states Kotlin 2.4.20 minimum for JS/Wasm; validate the Android/KMP plugin combination independently.
- [Compose Multiplatform web status](https://github.com/JetBrains/compose-multiplatform) — web support is Beta.
- [Kotlin Multiplatform testing](https://kotlinlang.org/docs/multiplatform/multiplatform-run-tests.html) — common and platform test expectations.
- [Android Compose UI testing](https://developer.android.com/develop/ui/compose/testing) — Android UI test APIs; do not apply Android dependencies to Wasm source sets.

## SQLDelight
- [SQLDelight documentation](https://sqldelight.github.io/sqldelight/)
- [SQLDelight JavaScript/Web Worker driver](https://sqldelight.github.io/sqldelight/js_sqlite/) — current asynchronous web worker driver and artifacts.
- [SQLDelight migrations](https://sqldelight.github.io/sqldelight/latest/multiplatform_sqlite/migrations/) — schema baseline and `verifySqlDelightMigration` task behavior.
- [SQLDelight releases](https://github.com/sqldelight/sqldelight/releases) — release compatibility and Wasm driver updates.

## Claude Code and CI
- [Claude Code memory/instructions](https://docs.anthropic.com/en/docs/claude-code/memory) — `CLAUDE.md` conventions.
- [Claude Code subagents](https://docs.anthropic.com/en/docs/claude-code/sub-agents) — repository-local agent definitions.
- [Claude Code skills](https://docs.anthropic.com/en/docs/claude-code/skills) — repository-local slash skills.
- [Gradle setup action](https://github.com/gradle/actions/blob/main/docs/setup-gradle.md) — caching and wrapper validation.
- [GitHub dependency review action](https://github.com/actions/dependency-review-action) — pull request dependency review.
- [Gitleaks GitHub Action](https://github.com/gitleaks/gitleaks-action) — credential scanning.
- [OSV-Scanner GitHub Action](https://google.github.io/osv-scanner/github-action/) — official reusable PR and scheduled dependency scans; resolve and commit lockfiles for dependable ecosystem scanning.

## Version notes

Habit Lab currently pins Kotlin 2.2.20, Compose Multiplatform 1.9.0, SQLDelight 2.1.0, and AGP 8.11.2. These existing project versions were preserved rather than upgraded as part of integrating quality gates. Validate their compatibility against the Gradle wrapper and official release notes before changing any pin. The sync server has its own catalog for Kotlin/JVM and Ktor and should remain independent.
