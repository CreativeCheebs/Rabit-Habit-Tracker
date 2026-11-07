---
name: engineering-review
description: Review Habit Lab changes from architecture, Android, hosted web, QA, security, design, or release perspectives using the project specialist roles.
---

# Engineering review

Read root `AGENTS.md` and shared `CLAUDE.md`, then inspect the relevant diff or requested feature. Choose the perspectives needed for the request and read only their reference files:

| Perspective | Reference |
|---|---|
| KMP architecture and compatibility | `references/kmp-architect.md` |
| Android lifecycle, UI, and driver integration | `references/android-engineer.md` |
| Hosted JavaScript web experience | `references/hosted-web-engineer.md` |
| Test coverage and acceptance evidence | `references/qa-engineer.md` |
| Security and data handling | `references/security-reviewer.md` |
| UI/UX and accessibility | `references/ui-ux-reviewer.md` |
| CI and release readiness | `references/release-engineer.md` |

These files are review perspectives converted from Claude role prompts, not registered Codex subagents. Apply them in the current conversation unless delegation is explicitly authorized. This skill is a read-only review: propose fixes without editing application code unless the user also requests fixes. Report actionable findings with file/line, user impact, and verification evidence. Distinguish code inspection from actually running tests or visually inspecting screens.
