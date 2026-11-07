// Only targets a separately started local disposable server, never a live account.
import { chromium } from 'playwright';
import assert from 'node:assert/strict';

const base = process.env.HABITLAB_QA_URL || 'http://127.0.0.1:18787';
if (!['http://127.0.0.1:18787', 'http://192.168.0.8:8788'].includes(base)) throw new Error('Only the disposable local or designated test server is supported');
const user = `rabit-e2e-${Date.now()}@example.test`;
const password = 'disposable-test-password-123';
const browser = await chromium.launch({ headless: true });
try {
  let page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  const response = await page.request.post(base + '/api/register', { data: { username: user, password } });
  assert.equal(response.status(), 200, 'create disposable account');
  const login = async () => {
    await page.goto(base);
    await page.locator('#loginUser').fill(user);
    await page.locator('#loginPass').fill(password);
    await page.locator('#loginBtn').click();
    await page.waitForSelector('#v-runs.active');
    if (await page.locator('#welcome').isVisible()) await page.locator('#welcomeBack').click();
    await page.waitForFunction(() => document.querySelector('#syncLabel').textContent === 'Synced');
  };
  await login();
  await page.getByRole('button', { name: 'Build a run' }).click();
  await page.locator('#runName').fill('Server test run');
  await page.locator('[data-click="addHabit"]').click();
  await page.locator('#builderHabits [aria-label="Habit name"]').fill('Server test habit');
  await page.getByRole('button', { name: 'Create run' }).click();
  await page.waitForFunction(() => document.querySelector('#syncLabel').textContent === 'Synced');
  await page.getByRole('button', { name: 'Edit run' }).click();
  await page.locator('#editBody [aria-label="Habit name"]').fill('Edited server habit');
  await page.getByRole('button', { name: 'Save changes' }).click();
  await page.waitForFunction(() => document.querySelector('#syncLabel').textContent === 'Synced');
  await page.context().close();
  page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  await login();
  assert.equal(await page.locator('#runsBody').getByText('Edited server habit').count(), 1);
  console.log('PASS local server: registration, sign-in, creation, edit, sync and fresh-browser recovery');
} finally { await browser.close(); }
