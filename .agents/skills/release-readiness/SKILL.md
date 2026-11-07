---
name: release-readiness
description: Check release quality for Android, iOS, and hosted browser app artifacts, migrations, security gates, changelog, and rollback.
---
# Release readiness

Read root `AGENTS.md`, `CLAUDE.md`, and `PROGRESS.md` for platform scope and ownership. Use `$project-quality` for concrete existing check entry points, and `$engineering-review` with the release perspective if a release audit is requested. Treat missing migration snapshots or unconfigured checks as gaps, not passing gates.

1. Discover and run platform-specific build/test tasks for Android, iOS, JVM server, and hosted browser app.
2. Confirm SQLDelight schema and migration verification and upgrade tests.
3. Check CI secret scanning, dependency findings, workflow permissions, and release signing secret boundaries.
4. Review Android release configuration, iOS framework outputs, and hosted browser assets/security headers separately.
5. Confirm changelog, versioning, user-facing docs, known limitations, rollout plan, and rollback path.
6. Report evidence and unresolved blockers. Never publish or deploy unless the user explicitly requests it.
