---
name: ui-quality-review
description: Review Compose screens and the hosted browser app for usability, Material 3 consistency, accessibility, and platform-specific visual behavior.
---
# UI quality review

Read root `AGENTS.md` and `CLAUDE.md`. Consult `ui-qa/README.md` for existing Playwright, Pixelmatch, ADB, and Maestro workflows. Inspect test scripts and use an authorized test instance before invoking hosted browser journeys; some flows write and delete account data.

Inspect the implementation and, when available, screenshots or the running application. Evaluate hierarchy, task clarity, spacing, responsive/adaptive layouts, Material 3 token use, product distinctiveness, keyboard and touch interactions, focus order, semantics, contrast, text scaling, theme modes, and loading/empty/error states.

Separate Android-specific and browser-specific findings. Recommend Android device checks for system bars, keyboard, back behavior, and configuration changes; recommend browser checks for keyboard navigation, zoom, narrow/wide layouts, screen reader semantics, and browser console/network failures. Record viewport/device, theme, and evidence. Do not claim a visual review if no visual evidence was provided.
