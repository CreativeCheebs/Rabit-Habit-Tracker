import { chromium } from 'playwright';

const BASE = process.env.HABITLAB_WEB || 'http://192.168.0.8:8788';
const failures = [];
const check = (name, ok, extra) => {
  console.log((ok ? 'PASS ' : 'FAIL ') + name + (extra ? ' — ' + extra : ''));
  if (!ok) failures.push(name);
};

const browser = await chromium.launch({ headless: true });
const page = await browser.newPage({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 1 });
const pageErrors = [];
page.on('pageerror', (e) => pageErrors.push(String(e)));

// Static + API surface intact.
const health = await page.request.get(BASE + '/health');
check('health ok', health.ok());
const css = await page.request.get(BASE + '/styles.css');
check('styles.css served', css.ok());
const js = await page.request.get(BASE + '/app.js');
check('app.js served', js.ok());
const yjs = await page.request.get(BASE + '/yaml.js');
check('yaml.js served', yjs.ok());
const bad = await page.request.post(BASE + '/api/sign-in', { data: { username: 'nobody', password: 'wrong' } });
check('bad sign-in is 401', bad.status() === 401);

// Login screen renders, no JS errors.
await page.goto(BASE + '/', { waitUntil: 'networkidle' });
await page.waitForSelector('#loginForm');
check('login form visible', await page.locator('#loginUser').isVisible());
check('no page errors on load', pageErrors.length === 0, pageErrors.join(' | '));
await page.screenshot({ path: 'web-login.png' });

// Wrong password shows the styled error, stays on login.
await page.fill('#loginUser', 'nobody');
await page.fill('#loginPass', 'wrong');
await page.click('#loginBtn');
await page.waitForSelector('#loginError.show');
check('wrong password shows error', await page.locator('#loginError').isVisible());
check('stays on login screen', await page.locator('#loginWrap').isVisible());

// Authed flow when a test account is provided.
const user = process.env.HABITLAB_TEST_USER, pass = process.env.HABITLAB_TEST_PASS;
const tag = Date.now().toString(36);
const RUNNAME = 'Web test ' + tag;
const YAMLNAME = 'Yaml run ' + tag;
const YAMLNEW = 'Yaml run edited ' + tag;
if (user && pass) {
  const waitSynced = () => page.waitForFunction(
    () => document.getElementById('syncLabel').textContent === 'Synced', null, { timeout: 20000 });
  await page.fill('#loginUser', user);
  await page.fill('#loginPass', pass);
  await page.click('#loginBtn');
  await page.waitForSelector('#v-runs.active', { timeout: 20000 });
  await waitSynced();
  check('runs screen after login', true);
  check('sidebar visible', await page.locator('.sidenav').isVisible());
  // Build a run from scratch through the desktop builder.
  await page.getByRole('button', { name: 'Build a run' }).click();
  await page.waitForSelector('#v-builder.active');
  await page.getByRole('button', { name: 'Start from scratch' }).click();
  await page.locator('.add-habit').click();
  await page.locator('#builderHabits .habit-editor input.field').first().fill('Drink water');
  await page.locator('#runName').fill(RUNNAME);
  await page.getByRole('button', { name: 'Create run' }).click();
  await page.waitForSelector('#v-runs.active');
  await waitSynced();
  check('habit shows', await page.locator('#runsBody').getByText('Drink water').count() === 1);
  await page.screenshot({ path: 'web-runs.png' });
  // Build a second run from pasted YAML (starts tomorrow so it is latest).
  const tomorrow = new Date(Date.now() + 86400000);
  const ty = `${tomorrow.getFullYear()}-${String(tomorrow.getMonth() + 1).padStart(2, '0')}-${String(tomorrow.getDate()).padStart(2, '0')}`;
  await page.getByRole('button', { name: '＋ New run' }).click();
  await page.getByRole('button', { name: 'From YAML' }).click();
  await page.locator('#yamlText').fill(
    `name: ${YAMLNAME}\ndays: 7\nstart: ${ty}\nhabits:\n  - name: E2E yaml one\n    group: E2E\n    points: 2\n    every: 1 day\n  - name: E2E yaml two\n    days_per_week: 3\n`);
  await page.getByRole('button', { name: 'Load into editor' }).click();
  check('yaml loads two editors', await page.locator('#builderHabits .habit-editor').count() === 2);
  await page.getByRole('button', { name: 'Create run' }).click();
  await page.waitForSelector('#v-runs.active');
  await waitSynced();
  check('yaml habit shows', await page.locator('#runsBody').getByText('E2E yaml one').count() === 1);
  // Edit that run's name through the edit view (it is latest: starts tomorrow).
  await page.getByRole('button', { name: 'Edit run' }).click();
  await page.waitForSelector('#v-edit.active');
  await page.locator('#editName').fill(YAMLNEW);
  await page.getByRole('button', { name: 'Save changes' }).click();
  await page.waitForSelector('#v-runs.active');
  await waitSynced();
  await page.getByRole('button', { name: 'Edit run' }).click();
  await page.waitForSelector('#v-edit.active');
  check('edited name saved', await page.locator('#editName').inputValue() === YAMLNEW);
  await page.getByRole('button', { name: 'Cancel' }).click();
  // Log both YAML habits Done with real swipes (nav hidden; reach it directly).
  await page.evaluate(() => window.showView('quicklog'));
  await page.waitForSelector('#swipeCard');
  await page.screenshot({ path: 'web-quicklog.png' });
  const swipeRight = async () => {
    const box = await page.locator('#swipeCard').boundingBox();
    const x = box.x + box.width / 2, y = box.y + box.height / 2;
    await page.mouse.move(x, y);
    await page.mouse.down();
    await page.mouse.move(x + 220, y, { steps: 12 });
    await page.mouse.up();
  };
  await swipeRight();
  await page.waitForTimeout(700);
  await swipeRight();
  await page.waitForSelector('.quick-summary', { timeout: 10000 });
  check('quick log summary', await page.locator('.quick-summary').getByText('Quick Log complete').count() === 1);
  await waitSynced();
  // Wipe local state: done-today habits must stay done after re-download.
  await page.evaluate(() => localStorage.clear());
  await page.reload({ waitUntil: 'networkidle' });
  await page.fill('#loginUser', user);
  await page.fill('#loginPass', pass);
  await page.click('#loginBtn');
  await page.waitForSelector('#v-runs.active', { timeout: 20000 });
  await waitSynced();
  check('yaml habit persisted', await page.locator('#runsBody').getByText('E2E yaml one').count() === 1);
  await page.evaluate(() => window.showView('quicklog'));
  await page.waitForSelector('.quick-summary', { timeout: 10000 });
  check('done check-ins persisted', await page.locator('.quick-summary').getByText('Quick Log complete').count() === 1);
  check('no page errors after sync', pageErrors.length === 0, pageErrors.join(' | '));
  await page.screenshot({ path: 'web-runs-synced.png' });
} else {
  console.log('SKIP authed flow (set HABITLAB_TEST_USER/HABITLAB_TEST_PASS)');
}

await browser.close();
if (failures.length) { console.error('FAILURES: ' + failures.join(', ')); process.exit(1); }
console.log('ALL CHECKS PASSED');
