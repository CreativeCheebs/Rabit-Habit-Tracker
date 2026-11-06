# Implementation plan

## Phase 1: establish the existing app build
- Maintain Android and iOS Compose Multiplatform targets and JVM tests.
- Keep Material 3 dependencies aligned with the existing version catalog.
- Keep common code portable and platform APIs in their respective source sets.

## Phase 2: persistence
- Maintain the existing SQLDelight schema and generated query APIs; introduce a baseline only as part of adopting versioned migrations.
- Maintain Android, Native/iOS, and SQLite/JVM driver factories behind repository abstractions.
- When versioned schema migrations are introduced, add a checked-in schema baseline, migration verification, and driver-specific persistence tests.

## Phase 3: engineering gates
- Add formatting, static analysis, common/JVM tests, Android UI review, iOS simulator tests, hosted browser tests, migration checks, secret scanning, and dependency review.
- Discover all task names from Gradle and set required branch protection checks.
- Publish reports and test artifacts on failed runs.

## Phase 4: product UI and browser delivery
- Define design tokens, adaptive navigation, accessibility semantics, and screen states.
- Add browser E2E/axe and curated screenshots after routes and stable selectors exist.
- Add HTTPS hosting, CSP/security headers, cache strategy, release notes, and rollback procedure.

## Release definition of done
- Android debug/release build and relevant device UI checks pass.
- The hosted web experience passes browser smoke/accessibility/visual review.
- SQLDelight migration verification and upgrade tests pass.
- No unresolved high severity dependency findings or exposed secrets.
- Changelog, documentation, and rollback notes are complete.
