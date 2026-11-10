# Changelog

- Renamed the app to Rabit - Habit and Goals Tracker.

- Added reusable Windows scripts for local container rebuilds and signed Android release APKs.

- Added the new Habit Lab icon to the Android launcher and hosted web app.

All notable changes are documented here. Follow Keep a Changelog and Semantic Versioning.

## [Unreleased]

## [1.2.0] - Manual run activation

- Android and web Manage runs can make a stopped run active. The previous active run is stopped and its habits and check-ins are kept.
- Web stopped-run history is read-only and Quick Log excludes stopped-run habits.
- Increased Android version code to 6.

## [1.1.1] - Single current run

- Android and browser now show one current run without a run selector. Old parallel active runs are stopped automatically; their habits and check-in history remain.
- Starting a new run is blocked while an active run exists. The Android add button opens run management so the current run can be stopped first.
- Server source remains tracked; local server databases, logs, and machine-specific Gradle settings are ignored.
- Increased Android version code to 5.

## [1.1.0] - Daily text sharing

- Added Android per-habit sharing opt-in and a Share page that copies daily completion as text.
- Added Share settings for habit status, today's percentage, and whole-run percentage. Choices currently persist on the device only.
- Increased Android version code to 4.

## [1.0.1] - CI and formatting

- Corrected Gradle wrapper invocation in CI and allowed independent checks to run after failures.
- Formatted Kotlin sources and configured ktlint to recognize Compose function naming conventions.
- Increased Android version code to 3 for this release.

## [1.0.0] - Stable baseline

The Android stable baseline was version 1.0.0 (version code 2).

The following entries describe the functionality included in this baseline.

### Added
- Web and Android: run lifecycle controls for selecting stopped runs, stopping the active run, and deleting a run without deleting its habits or check-in history.
- Browser regression coverage and reviewed desktop/mobile screenshots for run creation, editing, duplicate-name validation, YAML import/export, stop, selection, and deletion.
- Android: select an active run, edit its settings and habits, and copy its configuration as YAML. Existing check-ins are retained; shared habit changes affect all linked runs.
- Web: choose an active run, download its current editor as YAML, and expand not-due interval habits to log them manually. Android provides a similar collapsed rest-day section.
- Web welcome screens for first sign-in and revocable, read-only API integration tokens in Settings.
- Android: three-page welcome on first start explaining weight per habit (points earned over points possible), with animated burnup chart and Quick Log demos, then the sync choice (connect or use offline).
### Security
- Sync server: failed sign-ins are rate limited (per address and per username, HTTP 429 with a clear message); blocked attempts skip the costly password check.
- Web app: no inline event handlers any more, so the CSP allows scripts only from the server's own files.
- Sync server: refuses rows whose id, reference fields or field names fall outside a plain charset, so synced data cannot inject script into the browser app. Sends CSP, `X-Content-Type-Options`, `X-Frame-Options`, `Referrer-Policy`, and `Cache-Control: no-store` on API responses.
- Web app: ids used in inline handlers and option values are validated before rendering; incoming rows with unsafe ids or field names are ignored.
- Android: the sign-in box warns when the address is plain `http://` and not on a home/local network, since that traffic would cross the internet unencrypted. Lockout messages from the server are shown as written.
### Fixed
- Web and Android: prevent a second active run and reject duplicate habit names within a run after case and whitespace normalization.
- Web: keep long run grids aligned and horizontally usable at narrow mobile widths; keep run controls readable instead of overlapping.
- Web: copied runs no longer include standalone habits unless requested; copied habit edits are saved and run status is preserved when editing.
- Android: reset builder drafts when changing sources, avoid sample habits in an empty real run, and decode escaped names in YAML exported by the web app.
- Android: show only the selected source's habits, select a newly created run on this device, and preserve the previous run's duration when copying.
- Web app: a change to one field of a row from another device (e.g. a check-in state) no longer wipes the rest of the row, including its comment. Incoming fields are merged by stamp. A delete from another device keeps a tombstone, so later stale fields cannot bring back a half row.
- Android: signing in to a different server or account now asks first, then replaces this phone's synced data and restarts sync from zero, so one account's habits are never uploaded into another. Connecting from offline mode still keeps and uploads the phone's own data.
- Android: a malformed sign-in answer is reported instead of crashing, and cancelling a sign-in is no longer swallowed.
- Android: Back on the welcome pages steps to the previous page; dismissing sign-in returns to the welcome instead of leaving the sync choice unset. The welcome demos are described once for TalkBack and no longer clip at large text sizes.
- Initial engineering framework scaffold.
