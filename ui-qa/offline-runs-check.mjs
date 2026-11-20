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
    const name = url.pathname === '/' ? 'index.html' : path.normalize(decodeURIComponent(url.pathname)).replace(/^[\\/]+/, '');
    if (name.startsWith('..')) return route.fulfill({ status: 404 });
    try {
      const body = await readFile(path.join(root, name));
      const type = name.endsWith('.js') ? 'text/javascript' : name.endsWith('.css') ? 'text/css' : name.endsWith('.html') ? 'text/html' : name.endsWith('.ttf') ? 'font/ttf' : 'image/png';
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
  await page.getByRole('button', { name: 'New habit' }).click();
  await page.locator('#habitEditName').fill('Scratch habit');
  await page.getByRole('button', { name: 'Done', exact: true }).click();
  await page.locator('dialog.sheet').waitFor({ state: 'detached' });
  // Focus returns to the new habit's row after the sheet closes.
  assert.equal(await page.evaluate(() => document.activeElement.getAttribute('aria-label')), 'Edit Scratch habit');
  await page.getByRole('button', { name: 'Create run' }).click();
  assert.equal(await page.locator('#runsBody').getByText('Scratch habit').count(), 1);

  // Tap cycle: not done -> done -> partial -> skipped -> not done; re-logging after a clear uses a fresh uid.
  const circle = () => page.locator('#runsBody button.check').first();
  for (const state of ['done', 'partial', 'skipped', 'none']) {
    await circle().click();
    assert.match(await circle().getAttribute('class'), new RegExp(`check ${state}`));
  }
  assert.equal(await page.evaluate(() => document.activeElement.classList.contains('check')), true);
  await circle().click();
  const cycleUids = await page.evaluate(() => Object.entries(JSON.parse(localStorage.getItem('habitlab.db')).check_in)
    .map(([key, entry]) => [key, entry.v._deleted === true]));
  assert.equal(cycleUids.length, 2);
  assert.equal(cycleUids.filter(([, deleted]) => deleted).length, 1);
  assert.match(cycleUids.find(([, deleted]) => !deleted)[0], /#/);
  assert.match(await page.locator('#topActions').textContent(), /1-day streak/);
  assert.equal(await page.locator('#runsBody .pts').first().isVisible(), true);
  await page.evaluate(() => { const p = JSON.parse(localStorage.getItem('habitlab.prefs') || '{}'); p.hidePoints = true; localStorage.setItem('habitlab.prefs', JSON.stringify(p)); });
  await page.reload();
  assert.equal(await page.locator('#runsBody .pts').first().isVisible(), false);
  await page.evaluate(() => { const p = JSON.parse(localStorage.getItem('habitlab.prefs') || '{}'); p.hidePoints = false; localStorage.setItem('habitlab.prefs', JSON.stringify(p)); });
  await page.reload();

  // Only one run may be active at once.
  await page.getByRole('button', { name: 'New run' }).click();
  assert.match(await page.locator('.banner.warn').textContent(), /Scratch run is still running/);
  await page.getByRole('button', { name: 'Create run' }).click();
  assert.match(await page.locator('#runError').textContent(), /Stop the active run/);
  await page.getByRole('button', { name: 'Cancel' }).click();

  await page.getByRole('button', { name: 'Edit run' }).click();
  await page.locator('#runName').fill('Edited run');
  assert.equal(await page.locator('#pvName').textContent(), 'Edited run');
  await page.getByRole('button', { name: 'Edit Scratch habit' }).click();
  await page.locator('#habitEditName').fill('Edited habit');
  await page.getByRole('button', { name: 'Done', exact: true }).click();
  // Esc closes the sheet without saving and returns focus to the row button.
  await page.getByRole('button', { name: 'Edit Edited habit' }).click();
  await page.locator('#habitEditName').fill('Discarded name');
  await page.keyboard.press('Escape');
  await page.locator('dialog.sheet').waitFor({ state: 'detached' });
  assert.equal(await page.evaluate(() => document.activeElement.getAttribute('aria-label')), 'Edit Edited habit');
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

  // A habit can be edited directly without replacing its identity or check-ins.
  await page.getByRole('button', { name: 'Edit Edited habit' }).click();
  await page.locator('#habitEditName').fill('Directly edited habit');
  await page.locator('#habitEditPoints').fill('4');
  await page.getByRole('button', { name: 'Every N days' }).click();
  await page.locator('#habitEditCount').fill('366');
  await page.getByRole('button', { name: 'Done', exact: true }).click();
  assert.equal(await page.locator('#habitEditCount').evaluate((input) => input.validity.rangeOverflow), true);
  await page.locator('#habitEditCount').fill('365');
  await page.getByRole('button', { name: 'Done', exact: true }).click();
  assert.equal(await page.locator('#runsBody').getByText('Directly edited habit').count(), 1);
  await page.getByRole('button', { name: 'Edit Directly edited habit' }).click();
  await page.locator('#habitEditName').fill('Edited habit');
  await page.getByRole('button', { name: 'Done', exact: true }).click();

  // Names are unique inside a run even when case and whitespace differ.
  await page.getByRole('button', { name: 'Edit run' }).click();
  await page.getByRole('button', { name: 'New habit' }).click();
  await page.locator('#habitEditName').fill('  EDITED   habit  ');
  await page.getByRole('button', { name: 'Done', exact: true }).click();
  assert.match(await page.locator('#editorBody .banner.error[role="alert"]').last().textContent(), /Two habits are called/);
  assert.equal(await page.locator('#editorBody .hrow.dup').count(), 2);
  await page.getByRole('button', { name: 'Save changes' }).click();
  assert.match(await page.locator('#runError').textContent(), /appears more than once/);
  await page.locator('#editorBody [data-click="editDraft"]').last().click();
  await page.getByRole('button', { name: 'Remove' }).click();
  assert.equal(await page.locator('#editorBody .hrow.dup').count(), 0);
  await page.getByRole('button', { name: 'Save changes' }).click();

  // Resolve the active run before copying it into a new run.
  page.on('dialog', (dialog) => dialog.accept());
  await page.locator('.run-management summary').click();
  await page.getByRole('button', { name: 'Stop run' }).click();
  assert.match(await page.locator('.run-management').textContent(), /Stopped/);
  assert.equal(await page.locator('#runsBody .hgroup:not(.standalone) button.check').count(), 0);
  const stoppedCheckIns = await page.evaluate(() => Object.values(JSON.parse(localStorage.getItem('habitlab.db')).check_in).length);
  await page.evaluate(() => {
    const habit = Object.values(JSON.parse(localStorage.getItem('habitlab.db')).habit).find((entry) => entry.v.title === 'Edited habit').v;
    window.toggleDay(habit.uid, new Date().toISOString().slice(0, 10));
    window.showView('quicklog');
  });
  assert.equal(await page.locator('#quickBody').getByText('Edited habit').count(), 0);
  assert.equal(await page.evaluate(() => Object.values(JSON.parse(localStorage.getItem('habitlab.db')).check_in).length), stoppedCheckIns);
  await page.evaluate(() => window.showView('runs'));

  await page.getByRole('button', { name: 'New run' }).click();
  assert.equal(await page.locator('#editorHabits .hname').first().textContent(), 'Edited habit');
  await page.locator('#runName').fill('Copied run');
  await page.getByRole('button', { name: 'Create run' }).click();
  assert.equal(await page.locator('#activeRunSelect').count(), 0);
  assert.equal(await page.evaluate(() => Object.values(JSON.parse(localStorage.getItem('habitlab.db')).run)
    .filter((entry) => entry.v.status === 'active' && !entry.v._deleted).length), 1);
  assert.equal(await page.locator('#runsBody').getByText('Edited habit').count(), 1);
  await page.locator('.run-management summary').click();
  await page.locator('.run-management-row').filter({ hasText: 'Edited run' }).getByRole('button', { name: 'Edit', exact: true }).click();
  await page.locator('#runStart').fill('2020-01-02');
  await page.getByRole('button', { name: 'Save changes' }).click();
  await page.locator('.run-management summary').click();
  assert.match(await page.locator('.run-management-row').filter({ hasText: 'Edited run' }).textContent(), /2020-01-02/);
  await page.locator('.run-management-row').filter({ hasText: 'Edited run' }).getByRole('button', { name: 'History' }).click();
  assert.equal(await page.getByRole('button', { name: 'Back to current run' }).count(), 1);
  assert.equal(await page.locator('#runsBody .hgroup:not(.standalone) button.check').count(), 0);
  await page.getByRole('button', { name: 'Back to current run' }).click();

  await page.getByRole('button', { name: 'New run' }).click();
  await page.getByRole('button', { name: 'Cancel' }).click();
  await page.locator('.run-management summary').click();
  await page.getByRole('button', { name: 'Stop run' }).click();
  await page.getByRole('button', { name: 'New run' }).click();
  // Add existing works in New mode too.
  await page.getByRole('button', { name: 'Blank' }).click();
  await page.getByRole('button', { name: 'Add existing' }).click();
  await page.locator('#attachSel').selectOption({ label: 'Edited habit · General' });
  await page.getByRole('button', { name: 'Add', exact: true }).click();
  assert.equal(await page.locator('#editorHabits .hname').first().textContent(), 'Edited habit');
  await page.getByRole('button', { name: 'Paste YAML' }).click();
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
  assert.equal(await page.locator('#activeRunSelect').count(), 0);
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
  assert.equal(await page.evaluate(() => {
    const db = JSON.parse(localStorage.getItem('habitlab.db'));
    return Object.values(db.run).some((entry) => entry.v.name === 'Edited run' && !entry.v._deleted);
  }), false);
  assert.equal(await page.evaluate(() => {
    const db = JSON.parse(localStorage.getItem('habitlab.db'));
    return Object.values(db.habit).some((entry) => entry.v.title === 'Edited habit' && !entry.d);
  }), true);
  const shots = process.env.SHOT_DIR || 'docs/screenshots';
  await page.screenshot({ path: path.join(shots, 'web-runs-desktop.png'), fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth), true);
  await page.screenshot({ path: path.join(shots, 'web-runs-mobile.png'), fullPage: true });
  await page.setViewportSize({ width: 1440, height: 900 });
  // Switching to the Japanese style picks the Ai indigo accent once.
  await page.evaluate(() => window.showView('settings'));
  await page.getByRole('button', { name: 'Japanese' }).click();
  assert.equal(await page.evaluate(() => JSON.parse(localStorage.getItem('habitlab.prefs')).accent), 'blue');
  assert.equal(await page.evaluate(() => getComputedStyle(document.documentElement).getPropertyValue('--bg').trim().toUpperCase()), '#F7F3EA');
  await page.getByRole('button', { name: 'Accent Matcha' }).click();
  assert.equal(await page.evaluate(() => JSON.parse(localStorage.getItem('habitlab.prefs')).accent), 'green');
  await page.getByRole('button', { name: 'Classic' }).click();
  assert.equal(await page.evaluate(() => JSON.parse(localStorage.getItem('habitlab.prefs')).accent), 'green');
  assert.equal(await page.evaluate(() => document.fonts.check('16px "Plus Jakarta Sans"')), true);
  await page.evaluate(() => window.showView('runs'));
  assert.deepEqual(errors, []);
  // A legacy parallel active run is stopped on load; its row and history survive.
  await page.evaluate(() => {
    const db = JSON.parse(localStorage.getItem('habitlab.db'));
    db.run.legacy = { v: { uid: 'legacy', name: 'Legacy run', start_date: '2020-01-01', duration_days: 7, status: 'active' }, s: {} };
    localStorage.setItem('habitlab.db', JSON.stringify(db));
  });
  await page.reload();
  assert.equal(await page.evaluate(() => {
    const db = JSON.parse(localStorage.getItem('habitlab.db'));
    return Object.values(db.run).filter((entry) => entry.v.status === 'active' && !entry.v._deleted).length;
  }), 1);
  assert.equal(await page.evaluate(() => JSON.parse(localStorage.getItem('habitlab.db')).run.legacy.v.status), 'stopped');
  await page.locator('.run-management summary').click();
  await page.locator('.run-management-row').filter({ hasText: 'Copied run' }).getByRole('button', { name: 'Make active' }).click();
  assert.deepEqual(await page.evaluate(() => Object.values(JSON.parse(localStorage.getItem('habitlab.db')).run)
    .filter((entry) => entry.v.status === 'active' && !entry.v._deleted).map((entry) => entry.v.name)), ['Copied run']);
  assert.equal(await page.evaluate(() => Object.values(JSON.parse(localStorage.getItem('habitlab.db')).run)
    .find((entry) => entry.v.name === 'YAML run').v.status), 'stopped');
  assert.equal(await page.locator('#activeRunSelect').count(), 0);
  console.log('PASS offline browser: scratch, edit, YAML download, manual run activation, read-only history, YAML creation and optional rest-day logging');
} finally { await browser.close(); }
