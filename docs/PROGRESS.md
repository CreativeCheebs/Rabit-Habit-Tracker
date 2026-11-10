# Habit Lab — progress log

Running record of what is built, what is live, and every operational lesson
learned. Read this file first before continuing work; append to it when you
finish a step (the owner commits after every step).

## Single current run and server ignore rules

- Android and hosted web remove the run selector. Android's run manager shows the current run by default, with older stopped runs behind “Show past runs.” The Android add button opens run management while an active run exists; the builder also retains its creation guard.
- Both clients reconcile legacy or concurrently synced multiple-active data by keeping the newest run (start date, then UID) active and stopping the others. This preserves run rows, memberships, habits, and check-ins. The reconciliation changes sync through the existing protocol. The server itself does not enforce a one-active-run constraint, so this is a client-side convergence rule.
- The owner chose to keep server source tracked. Ignore rules now explicitly cover local server databases, logs, and machine-specific Gradle settings, in addition to existing build and data exclusions.
- Android JVM tests, ktlint, Android debug assembly, server tests, and the isolated browser lifecycle journey passed. The browser journey refreshed the desktop and mobile screenshots. No Android device was connected for on-device interaction testing.
- Android 1.1.1 (version code 5) was built, signed with the external key, and verified with APK signature schemes v2 and v3. `/home/aakash/apks/Rabit-1.1.1-release.apk` matched the local SHA-256 `1f6a8505879da0fba55c28af3a7e97cdb0bef1b59e3574de33ff6f4920925fa0`.
- Test and production server JARs were backed up before restarting. The first root Gradle build left the standalone fat JAR stale; a direct `sync-server/gradlew.bat buildFatJar` rebuilt it with the new JavaScript. Both 8788 and 8789 passed health checks and served the updated app without `activeRunSelect`. Future server deployments must use the standalone wrapper and verify the bundled web asset, not only the source tree.

## Android daily text sharing

- Android's Runs toolbar now opens Share. Habit detail has an opt-in switch, off by default. Share lists only opted-in habits due today, shows ✓/◐/✕ for done/partial/other, and copies plain text headed `# Day N, D Month` with optional today and run percentages. The options are in Share settings.
- Sharing choices are device-local `sync_meta` preferences; the current sync protocol does not carry them to web or another phone. No SQLDelight table migration was needed. Percentages use only eligible days of opted-in habits through today; a selected run outside today's date shows an unavailable state.
- JVM sharing tests, ktlint and Android debug assembly passed. No Android device was connected, so interaction and clipboard behavior were not checked on device.
- Android 1.1.0 (version code 4) assembled as an unsigned release. Signing did not complete: the external `rabit-passphrase.txt` is a keytool transcript rather than a usable password source. No APK was transferred and the web server was not restarted for this Android-only change.
- GitHub follow-up: all ten open Dependabot PRs were closed at the owner's request. A v1.1.0 release draft points to main but remains unpublished without a signed APK. The root README is now for end users, with representative screenshots; detailed setup moved to `docs/developer-guide.md`. GitHub About description and topics were set.
- Release follow-up: the corrected external passphrase file signed Android 1.1.0 (code 4). The APK passed v2/v3 signature verification and package metadata checks, was uploaded to and published in the GitHub v1.1.0 release, then copied to `/home/aakash/apks/Rabit-1.1.0-release.apk`. The remote SHA-256 matched the local APK: `7e554b56a474395a0b642576328c277da8a18054394b92ffcdc424cffcbd9971`. No signing material entered the repository.

## Run lifecycle, duplicate protection, and responsive review

- CI formatting follow-up (2026-11-09): formatted app Kotlin sources and scripts, configured ktlint to allow PascalCase for `@Composable` functions, and fixed remaining line-length issues. The app ktlint check, app JVM tests, root server tests, and Android debug assembly passed together; seven web parser tests also passed. Existing ktlint baseline was preserved. One existing UI file retains a file-scoped wildcard-import exception. These changes and the workflow fix are local and GitHub Actions and iOS simulator testing remain unverified. Dependency review still needs the GitHub dependency graph enabled or diagnosed.
- Android 1.0.1 (version code 3) was built, signed with the existing Rabit key and verified; `/home/aakash/apks/Rabit-1.0.1-release.apk` matched the local SHA-256. The standalone server bundle was rebuilt; test and production jars were backed up before restart, both health endpoints reported OK and both pages returned HTTP 200.
- CI investigation (2026-11-09): the published Linux quality job exits 126 before Gradle runs; the tracked Gradle wrapper has no executable bit. The local workflow now invokes it with Bash on Linux and macOS and continues independent checks after a failure. This change has not been pushed or verified on GitHub. Locally the app JVM and server tests and Android debug assembly completed, and seven web parser tests passed; ktlint still fails on existing common sources, JVM tests and the build script. Detekt completed but its existing KMP source coverage remains unverified. An AGP Dependabot PR's dependency review failed because GitHub reports the dependency graph is unavailable; enable/check it in repository security settings. iOS requires a macOS runner. Do not merge dependency updates without passing checks and compatibility review.
- Owner's standing deployment request (2026-11-08): after each completed project task, build and sign the Android release APK using the external signing material, transfer it to `/home/aakash/apks/`, and rebuild/restart the hosted test and production web services. Keep credentials out of output and the repository, preserve server data, verify the APK and service health, and use a higher Android version code when distributing an updated release. Record any blocked step rather than claiming it succeeded.
- 2026-11-08: Android 1.0.0 (version code 2) was built, signed and signature-verified. `Rabit-1.0.0-release.apk` was transferred to `/home/aakash/apks/` and its SHA-256 matched the local file. The rebuilt server bundle was deployed to test 8788 and production 8789 after backing up the previous jars; both health endpoints returned OK and both web pages returned HTTP 200 with Rabit branding.

- Versioning: configured the Android stable version as 1.0.0 with Android version code 2, increased from 0.1/code 1. Documented Semantic Versioning and the independently increasing Android version code. No release APK was published or Git tag created as part of this version update.

- Swipe follow-up: removed the nested horizontal scroller from normal-layout day boxes, which could intercept habit swipes; longer histories wrap in rows of seven. Reveal content is clipped to its own row. QA build and JVM tests passed, and on the Samsung phone swiping from the day boxes revealed Done in one direction and Partial/Skipped in the other; tapping Done worked. During QA a sample check-in was temporarily marked skipped and restored to its previous Done state.

- Android UI follow-up: the run summary opens a full-screen manager for selecting, editing, stopping, and deleting runs; the Runs title, edit/offline shortcuts, selector text, and swipe instruction were removed. The habit list uses a shared card, grouping is configurable, and normal layout displays configurable 7/14/21-day boxes. Navigation and detail screens use fade transitions. Points-per-day shows the whole run in a scrollable chart with a daily maximum based on habit points; burnup spans the run using the maximum daily points multiplied by run duration. The Today bar uses today's recorded impact, regardless of the selected day.
- QA build and JVM tests passed; the QA APK was installed on the Samsung SM-M075F. On-device checks covered opening run management, cancelling a delete confirmation, the grouped card, hiding group names, selecting an earlier date within the run, and inspecting both charts. The date rail is bounded by the run's start and end; dates before the run are not selectable. Delete/stop execution, editing, all graph values and all animation states were not retested in this follow-up.

- Android Runs uses a compact horizontal date rail in place of week arrows: dates can be swiped, the selected date controls habit logging, and Today jumps back to the current run date. Date dots mark dates with completed impact. Android source was explicitly authorized for this UI change.
- Device follow-up: assembled and installed the separate QA APK on a Samsung SM-M075F; the date rail rendered, swiped, and Today returned its selected date to view after scrolling. The Today scroll behavior was fixed during this check. This did not verify impact calculations against new check-ins or the other proposed mockup changes.

- Web and Android now treat runs as active or stopped, allow stopped runs to remain selectable, require the current active run to be stopped before another is created, and expose run deletion. Deletion removes the run and its membership links but deliberately retains habits and check-in history. Quick Log and run-row logging use active runs only.
- Run creation and editing reject duplicate habit names within the same run after trimming/collapsing whitespace and ignoring letter case, even when the schedules differ. Legacy multiple-active data remains visible so the owner can stop or remove the unwanted runs.
- The hosted web run grid and action header were adjusted for narrow screens. Reviewed screenshots are `docs/screenshots/web-runs-desktop.png` (1440x900) and `docs/screenshots/web-runs-mobile.png` (390x844). The mobile view keeps the selector and actions readable and allows the full day grid to scroll without squeezing or overlapping cells.
- Verification passed: 59 Compose/JVM tests, sync-server tests, QA and debug APK assembly, an isolated Playwright lifecycle journey, JavaScript syntax validation, diff whitespace validation, and Gitleaks across 52 commits. The Playwright journey covers scratch creation, normalized duplicate rejection, active-run blocking, editing, YAML download/import, copying, stopped-run selection, optional rest-day logging, deletion, and retained habit data.
- Android device interaction completed on a Samsung SM-M075F using the separate `app.habitlab.qa` package; the production app and its data were not touched. The QA APK installed and launched successfully, Runs and Quick Log navigation rendered, stopping `PhoneTest` showed the retention warning and persisted the stopped state, and the selector listed that stopped run alongside legacy active runs. The multiple-active warning appeared, stopped runs exposed Delete but not Stop, and the delete confirmation correctly stated that habits/check-ins would remain; the test cancelled deletion and verified the run was still present. Maestro was not available, so these checks used ADB accessibility hierarchy and direct interaction rather than claiming an automated Maestro pass. Repository-wide ktlint is not clean and the default Detekt task still does not discover KMP sources. A temporary explicit-source Detekt audit reported 911 weighted findings, mostly existing style/complexity issues; that experimental configuration was not retained because it would turn the existing gate red without an agreed cleanup/baseline plan. These are baseline debt, not 911 failures introduced by this change, and must not be silently suppressed by regenerating a baseline.

## Run editing and rest-day review

- Follow-up: Android run editing now changes existing run and habit records while preserving linked check-ins; imported drafts can be edited, configuration YAML can be copied to the clipboard, and a run selector exposes active runs. Shared habit edits apply across runs. JVM tests cover editing without losing history and YAML export round-trip; a phone QA test edited a habit and confirmed the existing completion still contributed points. Android YAML is copied to the clipboard rather than saved directly as a file.
- The Habit Tracker test service on 8788 and production service on 8789 were updated and restarted from the tested web build. Each previous jar was backed up before replacement. Both health and Rabit branding checks passed; a new disposable account on test also passed browser registration, sign-in, creation, editing, sync, and fresh-browser recovery. The separately running local Docker service was not changed.
- The updated Android release was built, signed with the owner's external key, verified, and copied to `/home/aakash/apks/Rabit-release-20261107-170754.apk`; the remote checksum matched. Its version code remains 1, and installing over another APK requires a matching signing key.

- Android device follow-up: on a Samsung SM-M075F, installed the separate `app.habitlab.qa` offline build alongside the existing app. Tested scratch and YAML run creation, copying a seven-day run, same-day run selection, interval rest-day grouping and the accessible manual Done action. Fixed builder source lists showing unrelated habits, new runs failing to become current when start dates tied, and copies resetting their duration. The QA build and JVM tests passed. Android still lacks an existing-run editor and YAML export, and imported draft habits do not have a full edit control. The release app was not replaced. The QA run selection is device-local; existing runs cannot yet be selected through the Android UI.

- Web: active-run selection exposes other active runs; interval rest-day habits move to an expandable section and can still be logged. Edit offers YAML download. Copying excludes standalone habits by default, retains duration, and saves edits to shared habits; editing preserves run status. YAML is a configuration export, not a check-in or ID backup.
- Android: rest-day rows are expandable and remain loggable, empty real runs no longer show sample habits, switching builder sources clears drafts, and the YAML parser handles escaped names from web exports.
- Isolated Playwright desktop journeys passed for scratch creation, editing, YAML download/import, copying, run selection, and logging a rest-day habit. A separate disposable local server passed registration, sign-in, creation, editing, sync and recovery in a fresh browser; that test server was stopped. Seven web YAML tests, Android JVM tests, Android debug build, and server tests passed. No Android device or emulator was connected. Existing Android run editing and YAML export are still absent. Kotlin formatting check failed with numerous reported violations; detekt reported NO-SOURCE rather than performing analysis. SQLDelight migration verification is not configured.
- Gitleaks scanned 51 Git commits without findings. The local and LAN web services still serve the old branding; no service was deployed or restarted. The LAN service uses HTTP; login tokens have no per-session expiry or revocation. A release update also needs an appropriate version code and the original signing key.

## Repository organization and secret scan

- Moved project documents to `docs/`, mockups to `docs/mockups/`, and root screenshots to `docs/screenshots/`; the root README remains the project entry point. UI quality scripts now use the new paths.
- Removed the broad documentation exclusion from `.gitleaks.toml` so future scans include Markdown files. A full local Git history scan with default Gitleaks rules found no matches. A working-directory scan flagged an ignored local test-credentials file; no credential value was displayed.

## Docker packaging

- Added a multi-stage server image recipe and a Compose configuration with a persistent data volume, externally supplied secret, and loopback-only host port. The image includes the existing hosted web app; Android remains a separate APK. Setup and Docker Hub publishing instructions are in `docs/docker.md`.
- Built `habitlab-sync:local` and checked a disposable container's health, web page, and SQLite-backed account registration. The Compose service was also started locally on `127.0.0.1:8787`, and its health endpoint and web page responded successfully. Its generated local secret is in the Git-ignored `.env`; its persistent named volume is `rabit_habitlab-data`.

## App name

- Changed the visible Android and web app name to Rabit - Habit and Goals Tracker, using Rabit for compact branding. Package names and stored data identifiers remain unchanged.

## Reusable local build scripts

- Added Windows PowerShell scripts for rebuilding the local Docker service using the existing `.env` and persistent volume, and for building, aligning, signing and verifying an Android release APK with an existing user-supplied keystore. The Docker script does not delete volumes; the Android script prompts for passwords rather than storing them.

## Application icons

- Added the supplied carrot logo as the Android launcher icon and as browser, home-screen, and in-page branding assets. Android changes require a new app build; the hosted web app requires a separate deployment.

## Web welcome and integrations

- Added a three-page, skippable web welcome based on the existing Android onboarding, recorded once per account in each browser.
- Added named read-only integration tokens in web Settings. Tokens are random, stored only as hashes, expire after 90 days, and can be individually revoked. The `/api/integration/{table}` endpoints expose the account's synced habits, runs, run memberships and check-ins; they do not accept integration tokens for sync writes or token management. See `docs/api-integration.md`.
- Verified server Gradle tests, an isolated API test for authorization/account isolation/revocation, and browser tests for welcome and token creation/revocation at desktop and mobile widths. These changes have not been deployed to the running local or Linux server; rebuild the Docker service or deploy separately when requested.

## What this is

Habit Lab is a personal habit tracker: an Android app (Compose Multiplatform,
owned by another agent — do not touch `composeApp/`), a Kotlin/Ktor sync
server on a Linux box, and a dependency-free desktop webapp served by that
server at `/`. All three clients sync through the same per-user SQLite
databases on the server.

## Repo layout

- `composeApp/` — Android app. **Another agent owns it. Read-only for you.**
- `sync-server/` — Ktor sync server + webapp bundle.
  - `src/main/kotlin/app/habitlab/sync/Application.kt` — API + static hosting.
  - `src/main/resources/webapp/` — the webapp: `index.html`, `styles.css`,
    `app.js` (ES module), `yaml.js` (run-file parser, shared with tests).
  - Own `gradlew` / `gradle/libs.versions.toml` (Ktor 3.3.1, Kotlin 2.2.20).
- `docs/mockups/habit-tracker-mobile-mockups.html` — phone UI reference (static).
- `docs/mockups/habit-tracker-desktop-mockups.html` — desktop UI reference (static, reviewed).
- `ui-qa/` — `webapp-check.mjs` (Playwright E2E, the maintained webapp test),
  `yaml-test.mjs` (`node --test`), `desktop-shots.mjs` (mockup captures),
  Maestro flows for Android, `README.md` with run/test-account docs.
- `send_tool/ship/` — deploy tooling. **Gitignored.** Holds the ship token,
  upload staging (`out/`), and helper scripts. Never commit its contents.
- Docs: `README.md` (server + webapp), `docs/OFFLINE-SYNC-GUIDE.md` (protocol),
  `docs/phase-2-roadmap.md`, `docs/habit-tracker-product-spec.md`, this file.

## Live deployment (Linux 192.168.0.8)

- Service: `http://192.168.0.8:8788/` — webapp at `/`, API at `/api/*`.
- Server dir: `~/HabitLab-sync` (`build/libs/HabitLabSyncServer-all.jar`,
  `data/*.sqlite`, `habitlab.env` mode 600, `server.log`, `server.pid`).
- Env (`habitlab.env`, 5 `export` lines, single-line values): users JSON,
  secret, data dir, host, port. Sourced at start; server reads it once.
- Each account gets `data/<sha256(account)>.sqlite`, auto-created on first sync.
- Start command (receiver runs as `aakash`, env already in file):
  `cd ~/HabitLab-sync; (set -a; . ./habitlab.env; set +a; setsid nohup java
  -jar build/libs/HabitLabSyncServer-all.jar >> server.log 2>&1 < /dev/null &);
  sleep 12; curl -s http://127.0.0.1:8788/health`
- Stop by **exact numeric PID only** (`ps -o pid= -C java`, then
  `kill <pid>`). Never `pkill`/`pgrep -f` with the jar name: the pattern can
  match your own shell and SIGTERM it mid-restart (this happened). The
  `server.pid` file is unreliable — do not depend on it.
- Known log noise: `bind` exceptions in `server.log` are from restart attempts
  where the old process still held the port. They pre-date the running server
  and touched no data.

## Deploying (`send_tool/ship/send.py`)

- `python send_tool/ship/send.py 192.168.0.8 <local-file> [--timeout N]
  [--run '<shell-cmd>']`. Files land in the receiver dir (`~/JitterBug`);
  `--run` executes in `~`. Upload-then-`cp` into `~/HabitLab-sync`.
- The `--run` channel mangles some quoting: avoid double quotes, backticks,
  and `&&` chains in remote commands; `;` separators and unquoted `$()`
  work. Complex logic goes in an uploaded `.py` helper, run with a trivial
  `--run 'python3 ~/JitterBug/<helper>.py'`.
- Clock skew allowance is 2 min; same LAN is fine. Receiver port 8765.
- Flow for a webapp change: `buildFatJar` in `sync-server/` → copy jar to
  `send_tool/ship/out/habitlab-web.jar` → one `send.py` call with upload +
  restart `--run` → `webapp-check.mjs` against live.
- 30 MB upload ≈ 1–2 min; give `--timeout 180` and `yield_time_ms` ≥ 240000.

## Sync protocol (contract all clients share)

- `POST /api/sign-in {username, password}` → `{account, accessToken}`
  (401 otherwise). Accounts come from `HABITLAB_SYNC_USERS` in env.
- `POST /api/sync` (headers `X-Account-Id`, `Authorization: Bearer …`)
  body `{device, token, changes[]}` → `{token, changes[]}`.
- Row: `{t, u, v: {...}, s: {field: {c, d}}}`. Merge is last-writer-wins per
  field by clock (tie → device id, then value). `_deleted: true` is a
  tombstone. **Deletes are permanent per uid**: the server only ever stores
  `_deleted=true` and ignores later live writes to that uid.
- Tables/fields: `habit(uid,title,group_name,iteration,impact_points,
  recurrence_type[interval|weekly],recurrence_value,archived)`,
  `run(uid,name,start_date,duration_days,status,copied_from_uid)`,
  `run_habit(uid,run_uid,habit_uid)`, `check_in(uid,habit_uid,day,state
  [done|partial|skipped],comment)`, `goal(uid,description,start_date,
  target_date,progress)`, `tracker(uid,name,unit,cadence)`,
  `tracker_entry(uid,tracker_uid,recorded_at,value,note)`.
- UID conventions (cross-client compatible, keep them):
  run `run-<YYYY-MM-DD>-<hex>`, habit `h-<slug>-<hex>`, run link
  `<run>:<habit>`, check-in `<habit>:<day>`. Clocks are epoch-millis.
- Do not change the schema or protocol without coordinating with the
  Android agent: both clients and the server must agree.

## Webapp architecture (`sync-server/src/main/resources/webapp/`)

- Thin sync mirror in `localStorage`: full rows + per-field stamps.
  Unchanged fields re-send their *old* stamps, so server-side LWW protects
  them — this is what makes multi-device edits converge. Dirty queue is a
  `Map(key → seq)`; a push snapshots it and deletes only entries whose seq
  is unchanged (rows dirtied mid-push go out with the follow-up sync —
  the wholesale-`clear()` version of this lost data; see commit `34c2923`).
- `rows()` filters tombstoned rows. `deleteRow()` writes a tombstone that is
  kept in the mirror until the push succeeds.
- `app.js` is an ES module (`<script type="module">`, imports `yaml.js`).
  Module scope is not global, so every inline-handler callback is exposed
  via `Object.assign(window, …)` plus live getters for `builder`,
  `editRun`, `quickCommentOpen`. If you add an `onclick="…"` handler, export
  it there too or it will silently do nothing.
- Shell: sidebar (`Runs & Habits`, `Goals` visible; Analytics, Trackers,
  Quick Log hidden via `hidden` + an explicit CSS override — the sidebar's
  own `display:flex` beats the `hidden` attribute alone). Topbar has the
  title + account menu (sign in/out lives there; there is no login route —
  logged-out state shows a centered card). Login session = account + token
  in `localStorage`.
- Runs view: compact hero (dates, %, bar, earned/possible, 4-metric card),
  full-duration day grid with numbered header + today ring, points/day and
  burnup charts with hover tooltips, New/Edit run buttons.
- Builder (`openBuilder`): Copy previous (run select + include-standalone
  checkbox) / Start from scratch / From YAML (guide-prompt copy button,
  guide viewer, textarea, Load-into-editor validation, then the normal
  editors). Fields: name, start date (may backdate so past check-ins count),
  length, habit editors (name/group/points, days-per-week OR every-N-days).
- YAML format (`yaml.js`, tested by `ui-qa/yaml-test.mjs`): `name:`,
  `days: 1..365`, `start: YYYY-MM-DD`, `habits:` list with `name` (required),
  `group:`, `points:`, exactly one of `days_per_week: 1..7` / `every: N
  days`. The guide prompt (`YAML_GUIDE_PROMPT`) teaches chatbots this plus
  the due/points rules. The desktop mockup embeds a copy for preview.
- Edit run (`openEdit`): rename/re-date/re-length, in-place habit edits
  (shared across runs — the UI says so), attach existing, detach (link
  tombstoned, habit history kept), add new. Re-attaching a detached habit
  mints `<run>:<habit>#<ts>` via `linkUid()` because tombstones are
  permanent (see above).
- Standalone habits: habits with no `run_habit` row. Always listed, always
  due-eligible, creatable inline; attaching them to a run makes overlapping
  check-ins count automatically (check-ins are date-keyed, run-independent).
- Infinite mode: per-account flag shown when no active run exists. No run
  name, no points header; unlinked habits in a trailing-21-day grid;
  points/day + burnup windowed to 21 days with the all-time baseline carried
  in (line never restarts at zero). Building a run exits it.
- Due rules (mirror Android exactly): done-today excluded; interval habits
  due unless done within the last N days (missed stays due); weekly habits
  due daily. Only `done` earns points.

## Test accounts and verification

- Throwaway webapp account: username `webtest` (created for Playwright E2E;
  password lives only in the gitignored `TEST-CREDS.md`, never here).
  Owner's account(s) are unknown to agents — never touch
  `data/bcf6acb0…sqlite` or infer other passwords.
- `webapp-check.mjs` needs a deterministic "latest run", so reset the
  throwaway DB before authed runs: stop server, `rm
  data/2cae3fde…f6.sqlite` (full hash in `ui-qa/README.md`), start, rerun.
  Never delete a live DB file; never touch another account's file.
- Gates: `node --test ui-qa/yaml-test.mjs`, `node --check` the bundle JS,
  `node ui-qa/webapp-check.mjs` with `HABITLAB_TEST_USER/PASS` set
  (20 checks: bundle, login/401, scratch+YAML builds, edit, swipes,
  wipe-and-redownload persistence). `desktop-shots.mjs` captures the mockup.
- Screenshots of browser proof live next to the scripts (`web-*.png`).

## Working conventions in this repo

- The owner asked for a commit after every step: commit named files only
  (`git add <paths>`, never `git add -A`), one step per commit, no push
  unless asked. `send_tool/` is gitignored (token, jars, helpers stay out).
- Never rewrite git history. Never `rm`/`git clean` files you did not create.
- `muse.powershell` runs Windows PowerShell: no `&&`, no `tail`, redirect
  with `Select-Object`, quote remote `--run` bodies with single quotes.
- Real secrets (server env values, owner credentials) stay out of the repo,
  memory, and commit messages. Even the throwaway `webtest` password lives
  only in the gitignored `TEST-CREDS.md`, never in a commit.
- Verify on the real target (device ADB / live server / browser), not just
  by reading code. Screenshots are checked with the image reader, not by
  file size. A self-authored green check that replays your own assumption
  proves nothing — the E2E wipe-and-redownload exists for exactly this.
- Desktop webapp last shipped at `da911be`; Android UI work since then is logged in the sections below (see `git log`). Server process is detached
  (`setsid`, PPID 1) and survives session end; confirm with `/health`.

## Open / next

- Keyboard shortcuts for dot-grid logging (asked during mockup review,
  undecided).
- Mockup and app can drift: `desktop-shots.mjs` + `web-*.png` are the way
  to compare after changes.
- Phase-2 items per `docs/phase-2-roadmap.md` and `docs/habit-tracker-product-spec.md` beyond what
  is live here.


## Android app UI changes (2026-11-06, `composeApp/`)

The owner directed these changes directly, so the "read-only" note above is
superseded for this work. Everything below was checked on the ADB phone.

- **Dock:** Runs, Quick Log, Goals, Settings. `Destination.Analytics` and
  `Destination.Trackers` stay in the enum and `when` branches but are left out
  of `visibleDestinations`.
- **Header:** sync and `+` buttons now have a 12dp gap.
- **New run is a full page** (`RunBuilderPage`, replaces the dialog + popup).
  First control: Previous / Scratch / YAML. YAML mode has a "Copy AI prompt"
  button (`YAML_GUIDE_PROMPT`), a paste box, and "Load into editor".
  `ui/RunYaml.kt` is a Kotlin port of `webapp/yaml.js` plus an optional
  per-habit `icon:` key; `RunYamlTest` covers it.
- **Settings page** (`SettingsScreen`): sync server status/URL/account, change
  server, sync now, switch to offline, and the web app note (same address as
  the sync server). Dense layout switch.
- **Dense layout** (`ui_dense_layout` pref): one line per habit, 11dp
  non-clickable day marks in front of the name, day picked from a 1..7 strip
  above the list. Swipe logging is unchanged.
- **Habit icons:** 8 choices (sun, moon, target, lines, heart, book, drop,
  bolt) picked in the builder or via YAML. Stored device-locally in
  `sync_meta` as `habit_icon:<uid>` (`ui/UiPrefs.kt`), **not synced** — adding
  a synced field needs server and webapp coordination. Habits without a
  choice keep the glyph their name implied (`legacyIcon`).
- Theme: `secondaryContainer` set so selected chips are green-tinted, not
  Material purple.

### Step: theme system (light/dark/system + accent) — Android
- `ui/Theme.kt`: `Palette` built from dark/light + accent seed (8 accents).
  Inputs are snapshot state (`themeModePref`, `accentPref`, `systemDarkState`,
  `hidePointsPref`) so composables *and* draw lambdas follow changes.
  `HabitLabApp.kt` no longer has hard-coded colors: use `palette.*` (or the
  `ink/muted/surface/green/hero` getters). Never add `Color(0xFF…)` literals.
- Prefs (device-local, `sync_meta`): `ui_theme_mode`, `ui_accent`,
  `ui_hide_points`, `ui_dense_layout`. Settings page has Appearance + Habit
  list cards. `MainActivity` syncs status/nav bar colors and icon contrast.
- Hide-points is stored and exposed; row/summary hiding lands with the Runs
  redesign step.

### Step: Runs screen redesign (Android)
- New `ui/RunsScreen.kt` (old RunsScreen/summary/chart code removed from
  `HabitLabApp.kt`). Each habit is its own rounded surface card. Swiping
  moves the card (shadow lifts) and uncovers a filled action area whose
  buttons hang off the card's moving edge (pattern from saket/swipe, Apache-2.0):
  right = Done, left = Partial + Skipped. Release past 50% width fires
  Done/Skipped; shorter swipes open the tray; tap closes it. The reveal logic
  (`horizontalSwipe`, `revealSettle`) is unchanged and unit-tested.
- Week selector: stepper `‹ Week n of N ›` (any run length, not capped at 4)
  over a weekday-letter + date strip. Default is the week/day containing today.
- Day marks are circular, non-clickable, trailing the name (both layouts).
  "Hide points" setting removes points from rows and the run summary.
- Charts are now real: "Points per day" (selected week) and "Points burnup"
  (cumulative, from `check_in`); sample labels and the forecast line are gone.
- Local check-ins now bump `runsRevision` so the summary/charts update at once.
- Tapping a habit calls `onOpenHabit` (detail page lands in the next step).

### Step: habit detail page, icon catalog + picker, habit colors (Android)
- **Detail page** (`ui/HabitDetail.kt`): tap a habit row. Shows iteration,
  group, schedule, 3 stats (completion % of elapsed run days, current streak,
  total done), "Done per week" bars, a 28-day dot grid, and a Look card.
- **Icons:** `ui/HabitIcons.kt` is GENERATED (171 icons, Fluent System Icons,
  MIT, Microsoft) by `ui-qa/icon-catalog/gen.mjs` (`npm i @fluentui/svg-icons`
  in a scratch dir, copy script, `node gen.mjs`, copy output). Edit the list
  inside `gen.mjs`, never the Kotlin. Paths are parsed at runtime
  (`PathParser`) and cached. Old keys (`sun`, `moon`, `target`, `lines`,
  `heart`, `book`, `drop`, `bolt`) are aliased in `HabitIconView.kt`.
  YAML `icon:` accepts any catalog key (see `FEATURED_ICON_KEYS`).
- **Picker:** `IconPickerSheet` (bottom sheet, search over label+tags, adaptive
  56dp grid). Used in the builder and the detail page. `ColorSwatchRow` offers
  10 habit colors + "accent".
- Icon/color are device-local prefs (`habit_icon:<uid>`, `habit_color:<uid>`);
  they do not sync. Habit rows/detail tint with the habit color.

### Step: Goals screen (Android)
- `ui/Goals.kt` replaces the placeholder list. Mirrors the webapp: card per
  goal (title, %, "Started … · target …", progress bar), dashed "＋ New goal"
  card. Tap a goal to edit text / target date (YYYY-MM-DD) / progress (5%
  steps) or delete. New uid format matches the web (`g-<8 hex>`), start date
  is today. Queries added to `Schema.sq`: `selectGoals`, `updateGoal`; the
  existing sync triggers already cover goal insert/update/delete.
- Progress is manual (the web app has no progress editor either; its goals
  also sit at 0% unless edited here).
- Verified on device: create → edit progress → delete round trip; the test
  goal was deleted afterwards (a tombstone for `g-…` remains on the server).

### Cross-client notes (Android UI batch)
- `yaml.js` now accepts (and ignores) `icon:` so a file written from the
  Android prompt also loads in the web builder; covered by `yaml-test.mjs`.
  **Not deployed**: the live server still runs the previous jar until the next
  `buildFatJar` + ship (see "Deploying").
- Hide points removes points from rows, summary, Quick Log and both charts.
- Icon catalog is 171 icons; 11 wanted names do not exist in Fluent (yoga,
  meditation, bell, vacuum, mountain, mood_happy, alarm_on, …). Extend by
  editing the list in `ui-qa/icon-catalog/gen.mjs`. License notice sits next
  to it.
- Sizes used: 48dp touch targets for week stepper/back buttons, 40dp date
  circles, 56dp minimum habit row, 24dp-class icons (Material's 48dp minimum
  interactive size). Day dots are display-only, so they are 10–12dp.

### Fix: web "From YAML" → Create run did nothing useful
- Cause: pasted YAML was only applied by the separate "Load into editor"
  button. "Create run" ignored it and used whatever habits were in the editor
  (copied from the previous run, or none → a short "Add at least one habit"
  toast). Switching to From YAML also kept the copied habits.
- Fix in `webapp/app.js`: From YAML starts with an empty editor; "Create run"
  now parses the pasted text itself (`applyYaml`), shows errors in the red
  YAML box if invalid (and stays), otherwise creates the run from the YAML.
- Verified with Playwright against the live API using the new `app.js` via
  route interception (bad YAML → errors; good YAML → run with only the YAML
  habit). **Not deployed yet**: the live server still serves the old jar.
- Side effect: those checks created runs "Repro", "FixCheck", "FixCheck2"
  (the last starts 2030-01-01, so it is now the *latest* run) in the throwaway
  `webtest` account. Reset that DB before the next `webapp-check.mjs` run
  (see "Test accounts and verification").

### Two server instances + deploy helper (2026-11-06)
- **8788 `~/HabitLab-sync` is the TEST instance** (contains `webtest`, whose
  password is in the gitignored `TEST-CREDS.md`). **8789 `~/HabitLab-prod` is
  the owner's own copy**: same jar, own `data/`, own signing secret, no
  `webtest`, generated owner password. Its credentials live only in the
  gitignored `send_tool/ship/prod-creds.txt` and the server's
  `habitlab.env` (mode 600). Point real clients at 8789; run automated tests
  only at 8788.
- 8789 starts empty. Signing in from the phone uploads the phone's data.
- **Standing rule: rebuild and restart after every commit/change touching
  `sync-server/`.** `send_tool/ship/habitlab_deploy.py` (runs on the host)
  installs the uploaded jar into both dirs, stops each by exact PID (matched by
  working directory), restarts, and health-checks both. Run it with:
  `cd sync-server && ./gradlew.bat buildFatJar`, copy the jar to
  `send_tool/ship/out/habitlab-web.jar`, then from `send_tool/ship/out`:
  `python ../send.py 192.168.0.8 habitlab-web.jar ../habitlab_deploy.py
  --timeout 300 --run 'python3 ~/JitterBug/habitlab_deploy.py'`.
- Deployed `86d7355` (YAML create fix) to both; verified on the live 8788
  page without any route override.

### Account creation (server + web + Android), deployed to 8788 and 8789
- **Accounts no longer have to be pre-made.** New `sync-server/.../Accounts.kt`:
  `POST /api/register {username,password}` → same response as sign-in
  (`{account, accessToken}`), so the client is signed in immediately.
  `GET /api/config` → `{signup: bool}`. Rules: username 3-64 chars
  `[A-Za-z0-9._@+-]`, unique case-insensitively (409 if taken, 400 with a
  message if invalid), password 8-200 chars. Rate limit: 8 attempts per client
  address per 10 minutes (429). Turn it off with `HABITLAB_SYNC_ALLOW_SIGNUP=false`
  in `habitlab.env` (default on).
- Storage: `<HABITLAB_SYNC_DATA>/accounts.sqlite` (`account` table: username,
  random salt, PBKDF2-HMAC-SHA256 120k hash — never plain passwords). Sign-in
  for these accounts is case-insensitive and returns the canonical name.
  `HABITLAB_SYNC_USERS` accounts keep working and are now optional.
  `/api/sync` accepts any account that exists in either place.
- Web: login card has "New here? Create an account" (adds Repeat password,
  shows server messages; hidden if signup is off). Android: the Connect dialog
  has the same toggle (`SyncClient.signIn(..., create = true)`; server
  explanations surface via `SignInProblem.Refused`).
- Verified: curl on a local jar (short pw, bad name, duplicate in another case,
  wrong pw, sync auth, unknown account, 429), Playwright on the live 8788
  page (mismatch, too short, create, duplicate, sign in again), Android dialog
  on the phone (no account was created from the phone).
- Test accounts created on 8788 by those checks: `qa_…` (throwaway).
- The 8789 owner account created earlier is still an env account; creating a
  new account there works too. Remove it from `habitlab.env` if unwanted.

### Web Settings page (theme, accent, dense, hide points) — deployed
- New "Settings" nav entry (`v-settings`, `renderSettings()` in `app.js`):
  Theme (System/Light/Dark), 8 accent colors, Dense layout, Hide points, and
  the sync-server address + sign out. Stored in this browser only
  (`localStorage` key `habitlab.prefs`, kept across sign-out); the Android
  app has its own copy of the same settings.
- `buildPalette(dark, seed)` in `app.js` is the same recipe as Android's
  `Theme.kt` and writes the CSS variables on `:root`. **`styles.css` no longer
  contains hard-coded colors** (new vars: `--page --soft --soft2 --on-soft
  --divider --dim --track --skipped --skip-soft --partial-soft …`); SVG chart
  strokes use `style="stroke:var(--…)"`. Use variables for any new UI.
- Dense = compact rows, no icon/meta line, small round dots. Hide points hides
  `.point`, the metrics card, and `.pts-card` charts (CSS body classes
  `dense` / `hide-points`).
- Verified live on 8788 with Playwright: switch to Light + Blue, dense on,
  persisted across reload, no JS errors. Deployed to 8788 and 8789.
