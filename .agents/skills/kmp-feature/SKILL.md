---
name: kmp-feature
description: Implement a Kotlin Multiplatform feature across Android and iOS with explicit boundaries and verification.
---
# KMP feature workflow

Respect current ownership restrictions in `PROGRESS.md`; a generic feature request does not authorize editing a module reserved for another agent. Use `$project-quality` for existing check entry points and `$engineering-review` for requested specialist review.

1. Read `AGENTS.md`, `CLAUDE.md`, module Gradle files, design docs, and relevant tests.
2. Translate the request into acceptance criteria and platform scope.
3. Discover targets and tasks with `./gradlew tasks --all`; inspect dependency compatibility before adding libraries.
4. Put portable domain/state/UI in common code only if all targets support its APIs. Keep Android, iOS, and JVM integrations in their respective source sets. The browser app is separate JavaScript/HTML/CSS.
5. If persistence changes, add a forward SQLDelight migration when versioned migrations are established and test the affected Android, iOS, and JVM drivers.
6. Implement the smallest coherent change, including loading/empty/error states and accessibility semantics.
7. Run relevant tests and quality tasks. Report exact commands, results, and unavailable platform checks.
8. Review the diff for secrets, generated files, platform leakage, and documentation/changelog updates.
