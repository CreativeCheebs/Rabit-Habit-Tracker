---
name: sql-delight-change
description: Safely evolve SQLDelight schema and queries across Android, iOS, and JVM persistence.
---
# SQLDelight change workflow

Read root `AGENTS.md` and `CLAUDE.md` for scope and ownership. Inspect the current SQLDelight configuration before choosing commands: the project has no schema-output snapshot verification configured at the time of this port. Establish a reviewed baseline and upgrade path when the requested schema work requires it; never claim migration verification passed merely because a task was absent.

1. Read current schema, generated schema snapshots, all migrations, and configured driver factories.
2. Never edit released migration files; add a new forward migration for each schema evolution.
3. Update `.sq` queries and generated schema baseline using the project's configured Gradle task.
4. Preserve existing data and add migration tests for changed/converted data.
5. Verify Android, Native/iOS, and SQLite/JVM drivers separately. The hosted browser app persists through the server API, not SQLDelight.
6. Discover and run the actual migration verification and relevant test tasks.
7. Summarize upgrade impact, any data-loss risk, and recovery/backup considerations.
