# Rabit - Habit and Goals Tracker

Android first, built with Compose Multiplatform, Kotlin, Material 3 dark theme, and SQLDelight. The shared UI and database schema have Android and iOS targets; the web target is intentionally deferred.

## Project documents

- [Progress and operational notes](docs/PROGRESS.md)
- [Product specification](docs/habit-tracker-product-spec.md) and [roadmap](docs/phase-2-roadmap.md)
- [Offline sync guide](docs/OFFLINE-SYNC-GUIDE.md)
- [Mobile mockup](docs/mockups/habit-tracker-mobile-mockups.html) and [desktop mockup](docs/mockups/habit-tracker-desktop-mockups.html)
- [Screenshots](docs/screenshots/) and [UI quality workflow](ui-qa/README.md)

## Android

The stable Android version is **1.0.1** (Android version code **3**). The app follows Semantic Versioning: increase the major version for incompatible changes, the minor version for backward-compatible features, and the patch version for backward-compatible fixes. Increase `versionCode` to a new, higher integer for every Android release; it is independent of the three-part version name. Update `CHANGELOG.md` when preparing a release. A version in the source does not by itself publish an APK or create a Git tag.

Open the project in Android Studio or build with the Gradle wrapper:

```powershell
./gradlew.bat :composeApp:assembleDebug
adb install -r composeApp/build/outputs/apk/debug/composeApp-debug.apk
```

For a signed release APK on Windows, use an existing Android signing keystore and key alias:

```powershell
.\scripts\build-android-release.ps1 -Keystore "C:\path\to\release-key.jks" -Alias "your-key-alias"
```

The script asks for the keystore and key passwords and saves the signed APK in `composeApp/build/outputs/apk/release/HabitLab-release.apk`. Use the same signing key as previous releases to install an update; keep the keystore and passwords backed up securely. Increase `versionCode` before distributing an update when required. The script does not create or save signing credentials.

The first launch seeds a sample 21-day run and four habits in the local SQLite database. Use the run selector to inspect active or stopped runs, and Edit to change a run's name, length, or habits. Only one run may be active at a time: stop the current run before creating another. A stopped run keeps its habits and check-ins but cannot receive new logs. Deleting a run removes its membership links while preserving reusable habits and their check-in history. Habit names must be unique within a run after trimming whitespace and ignoring letter case. The editor can copy the run as YAML for external editing; this configuration does not include check-ins or identifiers. Editing a shared habit changes it in all runs that use it, and removing it from a run does not delete its history. Quick Log writes dated Done, Partial, and Skipped check-ins and comments locally. The app shows Runs, Quick Log, Goals and Settings. Analytics and Trackers stay in the code but are hidden from the dock. Weekly-target and partial/effort scoring remain open per the product spec; docs/PROGRESS.md has the change log.

## Sync server (Linux)

The standalone project is in `sync-server/`. It is built on the Linux host using the included Gradle wrapper; the resulting fat JAR is `build/libs/HabitLabSyncServer-all.jar` on that host. The provided signed transfer scripts can upload the project archive and run the Linux build in `~/HabitLab-sync`, separate from JitterBug.

For a containerized server and bundled web app, see the [Docker setup and Docker Hub guide](docs/docker.md). The repository includes `sync-server/Dockerfile` as the build recipe and `compose.yaml` for local operation with persistent data.

People create their own accounts (web login card or the phone's Connect dialog; `POST /api/register`); passwords are stored as salted PBKDF2 hashes in `accounts.sqlite` under the data directory, and `HABITLAB_SYNC_ALLOW_SIGNUP=false` turns sign-up off. Accounts pre-listed in `HABITLAB_SYNC_USERS` still work but are optional. Configure the server outside the repository before starting the service:

```sh
export HABITLAB_SYNC_USERS='{"account@example.com":"replace-with-a-long-password"}'
export HABITLAB_SYNC_SECRET='replace-with-a-random-secret-of-at-least-32-bytes'
export HABITLAB_SYNC_DATA="$HOME/HabitLab-sync/data"
export HABITLAB_SYNC_HOST=127.0.0.1
java -jar build/libs/HabitLabSyncServer-all.jar
```

`POST /api/sign-in` accepts `{"username":"…","password":"…"}` and returns an account plus signed bearer token. `POST /api/sync` accepts the offline-sync guide's device/token/change envelope, with `X-Account-Id` and `Authorization: Bearer …` headers. Data files are keyed by a hash of the account. The server applies field-level last-writer-wins stamps (clock, device, then value tie-break), persists changes transactionally in SQLite, and returns changed fields since the supplied token. `/health` is public.

The web app shows a short welcome on the first sign-in for each account in a browser. Under Settings → API tokens, create a named integration token for another app. It is shown only once, expires after 90 days, and can be revoked individually. These tokens have read-only access to synced data through `GET /api/integration/habit`, `/run`, `/run_habit`, and `/check_in`; see [API integration](docs/api-integration.md) for examples and limitations.

Keep the service on loopback behind an authenticated HTTPS reverse proxy for remote clients. Do not expose the plain HTTP listener directly to an untrusted network.

## Web app

The server hosts the Rabit web app at `/` (same origin as the API, so no CORS is needed). Open `http://<host>:<port>/` in a browser and sign in with a sync-server account. Select an active or stopped run on the Runs page to view its habits. The run manager can stop or delete runs, and creation is blocked while another run is active. Deleting a run preserves habits and check-in history. Duplicate habit names in one run are rejected after case and whitespace normalization. Interval habits resting after a completion are under “Not due today”; expand the section to log them anyway. Edit run offers a YAML download of the current editor settings; importing it creates a new run and does not restore check-ins or IDs. Copying a run includes standalone habits only when selected. Shared habits remain linked, so editing one changes it in every run that uses it. Analytics, Trackers, and Quick Log stay in the code with their nav entries hidden. The web bundle ships inside the server; see `ui-qa/README.md` for browser checks.

## For agents continuing this work

Start with `docs/PROGRESS.md`: live deployment, sync contract, webapp architecture, test accounts, verification gates, and every operational lesson so far.

## Engineering quality gates

Codex project instructions are in `AGENTS.md`; Codex skills are in `.agents/skills/`, alongside the existing Claude setup in `.claude/`. Ask Codex to use `$kmp-feature`, `$ui-quality-review`, `$sql-delight-change`, `$release-readiness`, `$engineering-review`, or `$project-quality`. For example: “Use $project-quality to check the relevant tools, then $engineering-review to review the changed server and web code.” The seven Claude review roles are preserved as references in the engineering-review skill; they do not automatically launch subagents. These skills reuse the existing tools and CI rather than installing programs. Start a new Codex chat in this project if the newly added skills do not appear in the current skill list. Project skill location and format follow the [official Codex skills documentation](https://developers.openai.com/codex/skills).

This repository currently has three distinct runtime surfaces: Android and iOS from `composeApp`, a Kotlin/JVM sync server in `sync-server`, and a separately maintained browser app bundled in the server resources. The browser app is not a Kotlin/Wasm target. Keep dependencies and CI checks scoped to the surface they validate.

The root Gradle catalog (`gradle/libs.versions.toml`) owns KMP, Compose, SQLDelight, and Android/iOS tooling. The smaller `sync-server/gradle/libs.versions.toml` owns only the standalone server. Do not merge the catalogs or add Android/Compose dependencies to the server catalog.

Run the relevant checks from the repository root:

```powershell
./gradlew.bat :composeApp:ktlintCheck :composeApp:detekt :composeApp:jvmTest
./gradlew.bat :composeApp:assembleDebug
node --test ui-qa/yaml-test.mjs
```

Run `node ui-qa/webapp-check.mjs` only after setting `HABITLAB_WEB` to a running test server; its authenticated flow also needs disposable `HABITLAB_TEST_USER` and `HABITLAB_TEST_PASS` credentials.

Android UI review uses the existing Playwright/Pixelmatch/Maestro workflow documented in `ui-qa/README.md`. iOS simulator tests require macOS and an installed Xcode toolchain; they do not run on the Linux CI runner. Keep SQLDelight changes forward-only and test schema/data upgrades before release. Add browser accessibility and performance gates around the actual hosted web app, not Wasm tasks.

CI also runs secret scanning and pull request dependency review. Require the relevant checks in branch protection, review scanner findings, and keep production signing and deployment secrets out of pull request workflows. See `docs/` and `.claude/` for specialist review roles, setup guidance, migration policy, security, release readiness, and UI review.
