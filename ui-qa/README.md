# UI and UX comparison

This folder uses three open-source tools for different checks:

- **Playwright** captures the supplied HTML mockup at Android's 360 × 800 dp viewport, rendered at 2×.
- **Pixelmatch** compares those PNGs with ADB screenshots and writes pixel-diff images plus a changed-pixel ratio.
- **Maestro CLI** (Apache-2.0) exercises the installed Compose app through Android's accessibility/UI layer. It works with physical Android devices and does not need test code in the app.

The diff is a visual aid, not a UX score: status-bar differences, font rasterization, device font scale, sample data, scroll position, and dynamic content all affect raw pixels. Maestro checks navigation and interaction behavior separately.

## Run the comparison

```powershell
npm install --prefix ui-qa
npx --prefix ui-qa playwright install chromium
npm --prefix ui-qa run capture
npm --prefix ui-qa run capture-device
npm --prefix ui-qa run compare
```

`capture-device` uses ADB to open Runs and Quick Log and writes the screenshots at the project root. It does not submit or modify check-ins.

The comparison outputs are `ui-qa/diff-runs.png` and `ui-qa/diff-quicklog.png`; the raw changed-pixel ratio appears in the terminal.

## Run the interaction review

Install Maestro CLI 2.12.1 from its official Windows release and extract it to `ui-qa/tools/maestro`, or place `maestro` on `PATH`. Then run:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21.0.12.1'
$env:MAESTRO_CLI_NO_ANALYTICS = '1'
ui-qa\tools\maestro\bin\maestro.bat test ui-qa\flows\quicklog-review.yaml
```

The flow checks Runs → Quick Log navigation, opens and closes the comment field, and captures each state without clearing app data or recording a check-in.

## Webapp end-to-end (`webapp-check.mjs`)

Desktop Playwright checks against the live server: static bundle, login + 401 path, then (with a test account) scratch run creation, YAML run creation, run editing, swipe logging, and a wipe-and-redownload persistence proof.

```powershell
$env:HABITLAB_TEST_USER = 'webtest'
$env:HABITLAB_TEST_PASS = '<password>'
node ui-qa/webapp-check.mjs
```

The authed flow assumes a clean slate for deterministic "latest run" picks. Reset the throwaway account's database before a run (server stopped, owner's database untouched):

```powershell
python send_tool/ship/send.py 192.168.0.8 --timeout 120 --run 'cd ~/HabitLab-sync; kill $(ps -o pid= -C java); sleep 4; rm data/2cae3fde0b88cf1274fe58ec039302cced1b36287d57df0dab4c033a84da09f6.sqlite; (set -a; . ./habitlab.env; set +a; setsid nohup java -jar build/libs/HabitLabSyncServer-all.jar >> server.log 2>&1 < /dev/null &); sleep 12; curl -s http://127.0.0.1:8788/health; echo'
```

`yaml-test.mjs` covers the run-file parser with Node's built-in runner: `node --test ui-qa/yaml-test.mjs`. `desktop-shots.mjs` captures the desktop mockup views.
