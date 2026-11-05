# UI comparison — 2026-11-05

Captured the supplied HTML mockup with Playwright at 360 × 800 CSS px and device scale 2, and the installed Android app at 720 × 1600 through ADB. Pixelmatch used a 0.18 per-pixel threshold with anti-alias-only differences ignored.

| Screen | Differing pixels | Ratio |
| --- | ---: | ---: |
| Runs | 82,184 / 1,152,000 | 7.1% |
| Quick Log | 97,629 / 1,152,000 | 8.5% |

These ratios are raw image-diff counts, not usability scores. The Quick Log reference has four due habits while this device capture has three because Morning light was marked Done during earlier swipe validation. Native status-bar rendering and text rasterization also differ from the browser mockup.

The Maestro flow passed on the connected Android phone: it navigated Runs → Quick Log, opened the Comment field, and closed it again. The flow did not clear app data or create another check-in. The visual diffs are in `diff-runs.png` and `diff-quicklog.png`.
