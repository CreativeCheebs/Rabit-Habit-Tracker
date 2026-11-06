---
name: release-readiness
description: Check release quality for Android, iOS, and hosted browser app artifacts, migrations, security gates, changelog, and rollback.
---
# Release readiness

1. Discover and run platform-specific build/test tasks for Android, iOS, JVM server, and hosted browser app.
2. Confirm SQLDelight schema and migration verification and upgrade tests.
3. Check CI secret scanning, dependency findings, workflow permissions, and release signing secret boundaries.
4. Review Android release configuration, iOS framework outputs, and hosted browser assets/security headers separately.
5. Confirm changelog, versioning, user-facing docs, known limitations, rollout plan, and rollback path.
6. Report evidence and unresolved blockers. Never publish or deploy unless the user explicitly requests it.
