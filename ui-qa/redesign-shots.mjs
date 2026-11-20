// Captures the redesigned browser views (Today, run editor, habit sheet, empty state) against a
// disposable in-memory mirror. No server or account is used. Output: $SHOT_DIR (default ui-qa/shots).
import { chromium } from 'playwright';
import { readFile, mkdir } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, '../sync-server/src/main/resources/webapp');
const out = process.env.SHOT_DIR || path.join(here, 'shots');
await mkdir(out, { recursive: true });
// Entrance motion runs on every view switch; wait until it settles so shots are deterministic.
async function settle(page) {
  try { await page.waitForFunction(() => !document.querySelector('.view.enter'), null, { timeout: 8000 }); } catch { /* animations disabled or already settled */ }
}

const iso = (d) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
const day = (n) => { const d = new Date(); d.setDate(d.getDate() + n); return iso(d); };
function seed() {
  const db = { habit: {}, run: {}, goal: {}, tracker: {}, run_habit: {}, check_in: {}, tracker_entry: {} };
  const put = (t, v) => { db[t][v.uid] = { v, s: {} }; };
  put('run', { uid: 'r1', name: 'Reset 21', start_date: day(-3), duration_days: 21, status: 'active' });
  const habits = [
    ['h1', 'Morning light', 'Wellbeing', 2, 'weekly', 3], ['h2', 'Plan the day', 'Focus', 1, 'weekly', 7],
    ['h3', 'Drink 2 L water', 'Health', 1, 'weekly', 7], ['h4', 'Focused work block', 'Focus', 4, 'weekly', 3],
    ['h5', 'Read 20 pages', 'Learning', 2, 'interval', 2], ['h6', 'Sleep before 11:30', 'Sleep', 2, 'weekly', 7],
  ];
  for (const [uid, title, group_name, impact_points, recurrence_type, recurrence_value] of habits) {
    put('habit', { uid, title, group_name, impact_points, recurrence_type, recurrence_value, iteration: 1, archived: 0 });
    put('run_habit', { uid: 'r1:' + uid, run_uid: 'r1', habit_uid: uid });
  }
  const logs = [[-3, 'h1', 'done'], [-3, 'h2', 'done'], [-3, 'h3', 'done'], [-3, 'h4', 'done'], [-3, 'h5', 'done'], [-3, 'h6', 'done'],
    [-2, 'h1', 'done'], [-2, 'h2', 'partial'], [-2, 'h3', 'done'], [-2, 'h4', 'skipped'], [-2, 'h6', 'done'],
    [-1, 'h2', 'done'], [-1, 'h3', 'done'], [-1, 'h4', 'done'], [-1, 'h5', 'done'], [-1, 'h6', 'done'], [-1, 'h1', 'done'],
    [0, 'h1', 'done'], [0, 'h2', 'partial'], [0, 'h4', 'skipped']];
  for (const [n, h, state] of logs) put('check_in', { uid: `${h}:${day(n)}`, habit_uid: h, day: day(n), state });
  return db;
}
const browser = await chromium.launch({ headless: true });
async function open(viewport, prefs, data) {
  const page = await browser.newPage({ viewport });
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
  await page.addInitScript(([p, d]) => {
    localStorage.setItem('habitlab.key', 'disposable');
    localStorage.setItem('habitlab.account', 'test');
    localStorage.setItem('habitlab.welcome.test', '1');
    localStorage.setItem('habitlab.prefs', JSON.stringify(p));
    if (d) localStorage.setItem('habitlab.db', JSON.stringify(d));
  }, [prefs, data]);
  await page.goto('http://rabit.test/');
  await page.evaluate(() => document.fonts.ready);
  return page;
}
const desktop = { width: 1280, height: 900 }, phone = { width: 390, height: 844 };
const variants = [['classic-light', { mode: 'light' }], ['classic-dark', { mode: 'dark' }], ['wa-light', { mode: 'light', style: 'wa', accent: 'blue' }], ['wa-dark', { mode: 'dark', style: 'wa', accent: 'blue' }]];
try {
  for (const [label, prefs] of variants) {
    const page = await open(desktop, prefs, seed());
    await settle(page); await page.screenshot({ path: path.join(out, `today-desktop-${label}.png`), fullPage: true });
    await page.close();
  }
  let page = await open(phone, { mode: 'light' }, seed());
  await settle(page); await page.screenshot({ path: path.join(out, 'today-phone.png'), fullPage: true });
  await page.getByRole('button', { name: 'Edit run' }).click();
  await settle(page); await page.screenshot({ path: path.join(out, 'editor-edit-phone.png'), fullPage: true });
  await page.getByRole('button', { name: 'Edit Morning light' }).click();
  await settle(page); await page.screenshot({ path: path.join(out, 'sheet-phone.png') });
  await page.close();
  page = await open(desktop, { mode: 'light' }, seed());
  await page.getByRole('button', { name: 'New run' }).click();
  await settle(page); await page.screenshot({ path: path.join(out, 'editor-new-desktop.png'), fullPage: true });
  await page.getByRole('button', { name: 'Paste YAML' }).click();
  await settle(page); await page.screenshot({ path: path.join(out, 'editor-yaml-desktop.png'), fullPage: true });
  await page.evaluate(() => window.showView('settings'));
  await settle(page); await page.screenshot({ path: path.join(out, 'settings-desktop.png'), fullPage: true });
  await page.close();
  for (const mode of ['light', 'dark']) {
    page = await open(desktop, { mode }, seed());
    for (const view of ['analytics', 'goals', 'trackers', 'quicklog']) {
      await page.evaluate((v) => window.showView(v), view);
      await settle(page); await page.screenshot({ path: path.join(out, `${view}-desktop-${mode}.png`), fullPage: true });
    }
    await page.evaluate(() => localStorage.removeItem('habitlab.key'));
    await page.addInitScript(() => localStorage.removeItem('habitlab.key'));
    await page.reload();
    await settle(page); await page.screenshot({ path: path.join(out, `login-${mode}.png`) });
    await page.close();
  }
  page = await open(phone, { mode: 'dark' }, null);
  await settle(page); await page.screenshot({ path: path.join(out, 'empty-phone-dark.png'), fullPage: true });
  await page.close();
  console.log('Screenshots written to ' + out);
} finally { await browser.close(); }
