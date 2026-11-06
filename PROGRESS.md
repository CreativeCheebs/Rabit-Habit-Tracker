# Habit Lab — progress log

Running record of what is built, what is live, and every operational lesson
learned. Read this file first before continuing work; append to it when you
finish a step (the owner commits after every step).

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
- `habit-tracker-mobile-mockups.html` — phone UI reference (static).
- `habit-tracker-desktop-mockups.html` — desktop UI reference (static, reviewed).
- `ui-qa/` — `webapp-check.mjs` (Playwright E2E, the maintained webapp test),
  `yaml-test.mjs` (`node --test`), `desktop-shots.mjs` (mockup captures),
  Maestro flows for Android, `README.md` with run/test-account docs.
- `send_tool/ship/` — deploy tooling. **Gitignored.** Holds the ship token,
  upload staging (`out/`), and helper scripts. Never commit its contents.
- Docs: `README.md` (server + webapp), `OFFLINE-SYNC-GUIDE.md` (protocol),
  `phase-2-roadmap.md`, `product-spec.md`, this file.

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
- Phase-2 items per `phase-2-roadmap.md` and `product-spec.md` beyond what
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
