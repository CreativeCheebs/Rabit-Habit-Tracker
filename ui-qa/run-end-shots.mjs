// Captures the redesigned browser views for an ENDED run (and one stopped run)
// against a disposable in-memory mirror. No server or account is used.
// Output: $SHOT_DIR (default ui-qa/shots). Companion to redesign-shots.mjs.
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
  // Ended 21-day run: started 30 days ago, so it ended 10 days ago.
  put('run', { uid: 'r-end', name: 'Reset 21', start_date: day(-30), duration_days: 21, status: 'active' });
  // Stopped run for the history list.
  put('run', { uid: 'r-old', name: 'Spring reset', start_date: day(-60), duration_days: 14, status: 'stopped' });
  const habits = [
    ['h1', 'Morning light', 'Wellbeing', 2, 'weekly', 3], ['h2', 'Plan the day', 'Focus', 1, 'weekly', 7],
    ['h3', 'Drink 2 L water', 'Health', 1, 'weekly', 7], ['h4', 'Focused work block', 'Focus', 4, 'weekly', 3],
  ];
  for (const [uid, title, group_name, impact_points, recurrence_type, recurrence_value] of habits) {
    put('habit', { uid, title, group_name, impact_points, recurrence_type, recurrence_value, iteration: 1, archived: 0 });
    put('run_habit', { uid: 'r-end:' + uid, run_uid: 'r-end', habit_uid: uid });
  }
  const logs = [[-30, 'h1', 'done'], [-30, 'h2', 'done'], [-29, 'h1', 'done'], [-29, 'h3', 'partial'],
    [-28, 'h2', 'done'], [-28, 'h4', 'skipped'], [-27, 'h1', 'done'], [-12, 'h2', 'done'], [-11, 'h1', 'done']];
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
try {
  for (const mode of ['light', 'dark']) {
    const page = await open(desktop, { mode }, seed());
    await settle(page); await page.screenshot({ path: path.join(out, `ended-today-desktop-${mode}.png`), fullPage: true });
    await page.evaluate(() => window.showView('quicklog'));
    await settle(page); await page.screenshot({ path: path.join(out, `ended-quicklog-desktop-${mode}.png`), fullPage: true });
    await page.close();
  }
  const page = await open(phone, { mode: 'light' }, seed());
  await settle(page); await page.screenshot({ path: path.join(out, 'ended-today-phone.png'), fullPage: true });
  await page.close();
  console.log('Run-end screenshots written to ' + out);
} finally { await browser.close(); }
