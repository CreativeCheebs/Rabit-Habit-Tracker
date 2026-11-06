# Habit Lab

Android first, built with Compose Multiplatform, Kotlin, Material 3 dark theme, and SQLDelight. The shared UI and database schema have Android and iOS targets; the web target is intentionally deferred.

## Android

Open the project in Android Studio or build with the Gradle wrapper:

```powershell
./gradlew.bat :composeApp:assembleDebug
adb install -r composeApp/build/outputs/apk/debug/composeApp-debug.apk
```

The first launch seeds a sample 21-day run and four habits in the local SQLite database. Quick Log writes dated Done, Partial, and Skipped check-ins and comments locally. The current screens are an early UI slice; run editing, goal/tracker editing, due-rule evaluation, charts, and background sync still need implementation. Weekly-target and partial/effort scoring remain open per the product spec.

## Sync server (Linux)

The standalone project is in `sync-server/`. It is built on the Linux host using the included Gradle wrapper; the resulting fat JAR is `build/libs/HabitLabSyncServer-all.jar` on that host. The provided signed transfer scripts can upload the project archive and run the Linux build in `~/HabitLab-sync`, separate from JitterBug.

Configure credentials outside the repository before starting the service:

```sh
export HABITLAB_SYNC_USERS='{"account@example.com":"replace-with-a-long-password"}'
export HABITLAB_SYNC_SECRET='replace-with-a-random-secret-of-at-least-32-bytes'
export HABITLAB_SYNC_DATA="$HOME/HabitLab-sync/data"
export HABITLAB_SYNC_HOST=127.0.0.1
java -jar build/libs/HabitLabSyncServer-all.jar
```

`POST /api/sign-in` accepts `{"username":"…","password":"…"}` and returns an account plus signed bearer token. `POST /api/sync` accepts the offline-sync guide's device/token/change envelope, with `X-Account-Id` and `Authorization: Bearer …` headers. Data files are keyed by a hash of the account. The server applies field-level last-writer-wins stamps (clock, device, then value tie-break), persists changes transactionally in SQLite, and returns changed fields since the supplied token. `/health` is public.

Keep the service on loopback behind an authenticated HTTPS reverse proxy for remote clients. Do not expose the plain HTTP listener directly to an untrusted network.

## Web app

The server also hosts the Habit Lab web app at `/` (same origin as the API, so no CORS is needed). Open `http://<host>:<port>/` in a browser and sign in with a sync-server account. The desktop UI has Runs (full day grid, charts with hover values), Goals, a run builder (copy previous / scratch / paste-YAML from any AI chatbot), run editing, standalone habits that appear without a run, and an infinite-run mode when no run is running. Analytics, Trackers, and Quick Log stay in the code with their nav entries hidden. The bundle lives in `sync-server/src/main/resources/webapp/` and ships inside the fat JAR; UI checks run with `node ui-qa/webapp-check.mjs` (see `ui-qa/README.md`).

## For agents continuing this work

Start with `PROGRESS.md`: live deployment, sync contract, webapp architecture, test accounts, verification gates, and every operational lesson so far.
