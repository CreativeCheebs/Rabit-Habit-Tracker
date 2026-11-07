# Compose UI quality and visual QA

## Design review checklist

- Build a distinctive visual language from a small token set: color roles, type scale, spacing, shape, elevation, and motion. Material 3 provides structure; product-specific choices make the interface recognizable.
- Check small phones, large phones, tablets/resizable windows, and narrow/wide browser widths. Use adaptive layouts rather than assuming a single screen size.
- Cover loading, empty, error, offline, disabled, and success states.
- Give controls clear accessible names, roles, state, and actions. Verify focus order, touch target size, keyboard navigation on web, text scaling, contrast, reduced motion, and light/dark themes.
- Verify system bars, safe areas, back behavior, text input, scrolling, and dialogs independently on Android and web.

## Test layers

1. Common behavior tests with `kotlin.test` for portable state and domain behavior.
2. Compose semantics/component tests where the selected Compose Multiplatform version exposes supported test APIs for the target.
3. Android UI integration tests using AndroidX Compose testing APIs and emulator/device execution.
4. Hosted browser E2E tests against the sync server. Use Playwright with accessible role/name locators; add axe checks for automated accessibility signals.
5. Screenshot comparison for a small curated set of stable routes/viewports. Review diffs manually; font rendering and platform differences make raw pixel gates noisy.

For performance, measure browser startup, interaction responsiveness, and key journeys on representative desktop and mobile hardware. Add Lighthouse CI or equivalent budgets only after establishing stable baselines; a single synthetic score is not a user experience verdict.

## Visual review record

For each user-facing screen change, record: route/screen, platform and browser/device, viewport, theme, keyboard/accessibility check, screenshot location, and known deviations. Never call a screenshot test a complete UX review: it cannot assess task clarity or usability by itself.

### 2026-11-08 — Runs and Habits lifecycle

- Hosted browser app, Chromium, light/system palette: reviewed at 1440x900 and 390x844 using disposable in-memory data. Screenshots: `docs/screenshots/web-runs-desktop.png` and `docs/screenshots/web-runs-mobile.png`.
- Corrected the narrow-screen run selector/action wrapping and prevented long-duration day cells from collapsing into overlapping labels and markers. The day grid remains horizontally scrollable on small screens.
- Automated role/name interactions covered creation, editing, normalized duplicate rejection, active-run blocking, stop, selection, deletion, YAML download/import, copy, and optional rest-day logging. No page exceptions were observed.
- Android parity was compiled into the QA APK and exercised by JVM database tests. Physical-device visual/interaction review remains deferred until ADB sees a connected QA device.

The repository already has routes and Playwright checks for the hosted browser app. Keep golden screenshot comparisons curated and review pixel diffs manually because rendering changes can be noisy.
