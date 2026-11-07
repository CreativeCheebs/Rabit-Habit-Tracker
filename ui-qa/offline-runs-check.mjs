// Exercises the browser UI against a disposable, in-memory mirror. No server data is touched.
import { chromium } from 'playwright';
import { readFile } from 'node:fs/promises';
import assert from 'node:assert/strict';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../sync-server/src/main/resources/webapp');
const browser = await chromium.launch({ headless: true });
try {
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  const errors = [];
  page.on('pageerror', (error) => errors.push(String(error)));
  await page.route('**/*', async (route) => {
    const url = new URL(route.request().url());
    if (url.pathname.startsWith('/api/')) return route.abort();
    const name = url.pathname === '/' ? 'index.html' : path.basename(url.pathname);
    try {
      const body = await readFile(path.join(root, name));
      const type = name.endsWith('.js') ? 'text/javascript' : name.endsWith('.css') ? 'text/css' : name.endsWith('.html') ? 'text/html' : 'image/png';
      await route.fulfill({ status: 200, contentType: type, body });
    } catch { await route.fulfill({ status: 404 }); }
  });
  await page.addInitScript(() => {
    localStorage.setItem('habitlab.key', 'disposable');
    localStorage.setItem('habitlab.account', 'test');
    localStorage.setItem('habitlab.welcome.test', '1');
  });
  await page.goto('http://rabit.test/');
  await page.getByRole('button', { name: 'Build a run' }).click();
  await page.locator('#runName').fill('Scratch run');
  await page.locator('[data-click="addHabit"]').click();
  await page.locator('#builderHabits [aria-label="Habit name"]').fill('Scratch habit');
  await page.getByRole('button', { name: 'Create run' }).click();
  assert.equal(await page.locator('#runsBody').getByText('Scratch habit').count(), 1);

  // Only one run may be active at once.
  await page.getByRole('button', { name: '＋ New run' }).click();
  await page.getByRole('button', { name: 'Create run' }).click();
  assert.match(await page.locator('#builderRunError').textContent(), /Stop the active run/);
  await page.getByRole('button', { name: 'Cancel' }).click();

  await page.getByRole('button', { name: 'Edit run' }).click();
  await page.locator('#editName').fill('Edited run');
  await page.locator('#editBody [aria-label="Habit name"]').fill('Edited habit');
  await page.evaluate(() => {
    const originalClick = HTMLAnchorElement.prototype.click;
    HTMLAnchorElement.prototype.click = function () {
      if (this.download) {
        fetch(this.href).then((response) => response.text()).then((text) => { window.__downloadedYaml = text; });
        return;
      }
      return originalClick.call(this);
    };
  });
  await page.getByRole('button', { name: 'Download YAML' }).click();
  await page.waitForFunction(() => window.__downloadedYaml);
  const yaml = await page.evaluate(() => window.__downloadedYaml);
  assert.match(yaml, /Edited habit/);
  await page.getByRole('button', { name: 'Save changes' }).click();
  assert.equal(await page.locator('#runsBody').getByText('Edited habit').count(), 1);

  // Names are unique inside a run even when case and whitespace differ.
  await page.getByRole('button', { name: 'Edit run' }).click();
  await page.locator('#editBody [data-click="addHabit"][data-a="edit"]').click();
  await page.locator('#editBody [aria-label="Habit name"]').last().fill('  EDITED   habit  ');
  await page.getByRole('button', { name: 'Save changes' }).click();
  assert.match(await page.locator('#editRunError').textContent(), /appears more than once/);
  await page.locator('#editBody [data-click="removeHabit"][data-a="edit"]').last().click();
  await page.getByRole('button', { name: 'Save changes' }).click();

  // Resolve the active run before copying it into a new run.
  page.on('dialog', (dialog) => dialog.accept());
  await page.locator('.run-management summary').click();
  await page.getByRole('button', { name: 'Stop run' }).click();
  assert.match(await page.locator('.run-management').textContent(), /Stopped/);

  await page.getByRole('button', { name: '＋ New run' }).click();
  assert.equal(await page.locator('#builderHabits [aria-label="Habit name"]').inputValue(), 'Edited habit');
  await page.locator('#runName').fill('Copied run');
  await page.getByRole('button', { name: 'Create run' }).click();
  assert.equal(await page.locator('#activeRunSelect option').count(), 2);
  const originalRun = await page.locator('#activeRunSelect option').filter({ hasText: 'Edited run' }).getAttribute('value');
  await page.locator('#activeRunSelect').selectOption(originalRun);
  assert.equal(await page.locator('#runsBody').getByText('Edited habit').count(), 1);

  await page.getByRole('button', { name: '＋ New run' }).click();
  await page.getByRole('button', { name: 'Cancel' }).click();
  await page.locator('.run-management summary').click();
  await page.getByRole('button', { name: 'Stop run' }).click();
  await page.getByRole('button', { name: '＋ New run' }).click();
  await page.getByRole('button', { name: 'From YAML' }).click();
  await page.locator('#yamlText').fill('name: YAML run\ndays: 7\nhabits:\n  - name: YAML habit\n    every: 2 days\n');
  await page.getByRole('button', { name: 'Load into editor' }).click();
  await page.getByRole('button', { name: 'Create run' }).click();
  assert.equal(await page.locator('#runsBody').getByText('YAML habit').count(), 1);
  await page.evaluate(() => {
    const db = JSON.parse(localStorage.getItem('habitlab.db'));
    const habit = Object.values(db.habit).find((entry) => entry.v.title === 'YAML habit').v;
    const yesterday = new Date(); yesterday.setDate(yesterday.getDate() - 1);
    const day = `${yesterday.getFullYear()}-${String(yesterday.getMonth() + 1).padStart(2, '0')}-${String(yesterday.getDate()).padStart(2, '0')}`;
    const uid = habit.uid + ':' + day;
    db.check_in[uid] = { v: { uid, habit_uid: habit.uid, day, state: 'done' }, s: {} };
    localStorage.setItem('habitlab.db', JSON.stringify(db));
  });
  await page.reload();
  await page.locator('#activeRunSelect').selectOption(await page.locator('#activeRunSelect option').filter({ hasText: 'YAML run' }).getAttribute('value'));
  const resting = page.locator('.not-due');
  assert.equal(await resting.getByText('YAML habit').count(), 1);
  assert.equal(await resting.getAttribute('open'), null);
  await resting.locator('summary').click();
  await resting.getByRole('button', { name: /YAML habit/ }).last().click();
  assert.equal(await page.locator('.not-due').count(), 0);
  assert.equal(await page.locator('#runsBody').getByText('YAML habit').count(), 1);

  // Deleting a stopped run removes only the run and its links, not reusable habits.
  await page.locator('.run-management summary').click();
  const editedRow = page.locator('.run-management-row').filter({ hasText: 'Edited run' });
  await editedRow.getByRole('button', { name: 'Delete run' }).click();
  assert.equal(await page.locator('#activeRunSelect option').filter({ hasText: 'Edited run' }).count(), 0);
  assert.equal(await page.evaluate(() => {
    const db = JSON.parse(localStorage.getItem('habitlab.db'));
    return Object.values(db.habit).some((entry) => entry.v.title === 'Edited habit' && !entry.d);
  }), true);
  await page.screenshot({ path: 'docs/screenshots/web-runs-desktop.png', fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  await page.screenshot({ path: 'docs/screenshots/web-runs-mobile.png', fullPage: true });
  assert.deepEqual(errors, []);
  console.log('PASS offline browser: scratch, edit, YAML download, copy, run selection, YAML creation and optional rest-day logging');
} finally { await browser.close(); }
