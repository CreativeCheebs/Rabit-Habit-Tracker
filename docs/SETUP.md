# Setup guide

## 1. Adopt into a repository

This guide describes the current Habit Lab repository. Keep its existing module files and two catalogs separate. Preserve the checked-in Kotlin, Compose, SQLDelight, AGP, and Gradle pins unless a deliberate compatibility review calls for an upgrade.

## 2. Project targets

Habit Lab currently targets Android, iOS, and JVM tests in `composeApp`. Its browser application is a separate JavaScript/HTML/CSS app bundled in `sync-server/src/main/resources/webapp/`; it is not Kotlin/Wasm. Put portable app logic and UI in `commonMain`, and isolate Android, iOS, and JVM integrations in their target source sets.

Persistence uses SQLDelight with Android, Native/iOS, and SQLite/JVM drivers. SQLDelight is configured, but the project does not yet have checked-in schema snapshots or migrations.

## 3. Install Claude Code workflow

Claude Code discovers repository instructions from `CLAUDE.md`, subagents in `.claude/agents/`, and skills in `.claude/skills/<skill>/SKILL.md`. Review any generated edits; agents are role prompts, not isolated security boundaries. Start with `/kmp-feature` or `/ui-quality-review`.

## 4. Enable quality checks

Discover tasks with `./gradlew tasks --all`. The checked-in workflow runs app JVM tests, sync-server tests, Android build, parser tests, and iOS simulator tests on macOS. Browser E2E tests use `ui-qa/webapp-check.mjs` against a live server and are kept out of PR CI because they require a running service and optionally disposable test-account credentials.

Run formatting and static analysis locally before enabling required checks. Store signing credentials only in GitHub Actions secrets; do not include release signing in pull request workflows. Apply branch protection so all selected CI gates must pass.

## 5. Browser quality and release

Run the hosted web app through the sync server and review important screens at mobile and desktop widths, keyboard-only operation, zoom, contrast, focus order, loading/empty/error states, and browser devtools errors. The existing `ui-qa/webapp-check.mjs` provides browser journey coverage when pointed at a disposable test server.

Serve the web app behind the sync server or a separately configured HTTPS reverse proxy. Use a restrictive Content Security Policy based on actual assets, HSTS at the hosting layer, and no secrets in browser code. Browser security headers are separate from Android app controls.
