// Regression check: a change to ONE field of a row made on another device must
// merge into the web app's copy instead of replacing the row.
// Needs a test server and its throwaway account (never run against a real account):
//   HABITLAB_TEST_USER=webtest HABITLAB_TEST_PASS=... node web-sync-merge-check.mjs
import { chromium } from 'playwright';

const BASE = process.env.HABITLAB_WEB || 'http://192.168.0.8:8788';
const USER = process.env.HABITLAB_TEST_USER, PASS = process.env.HABITLAB_TEST_PASS;
if (!USER || !PASS) { console.error('Set HABITLAB_TEST_USER and HABITLAB_TEST_PASS'); process.exit(2); }

const failures = [];
const check = (name, ok, extra) => {
  console.log((ok ? 'PASS ' : 'FAIL ') + name + (extra ? ' — ' + extra : ''));
  if (!ok) failures.push(name);
};

// A scripted second device talking to the same /api/sync.
const DEVICE = 'bbbbbbbb-0000-0000-0000-' + String(Date.now()).padStart(12, '0').slice(-12);
const signIn = await (await fetch(BASE + '/api/sign-in', {
  method: 'POST', headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ username: USER, password: PASS }),
})).json();
const headers = { 'Content-Type': 'application/json', 'X-Account-Id': signIn.account, Authorization: 'Bearer ' + signIn.accessToken };
let token = 0;
async function deviceSync(changes = []) {
  const res = await (await fetch(BASE + '/api/sync', { method: 'POST', headers, body: JSON.stringify({ device: DEVICE, token, changes }) })).json();
  token = res.token;
  return res.changes;
}

const browser = await chromium.launch({ headless: true });
const page = await browser.newPage({ viewport: { width: 1100, height: 900 } });
await page.goto(BASE + '/', { waitUntil: 'networkidle' });
await page.fill('#loginUser', USER);
await page.fill('#loginPass', PASS);
await page.click('#loginBtn');
await page.waitForSelector('.sidenav');
await page.waitForTimeout(1500);

const reload = async () => { await page.reload({ waitUntil: 'networkidle' }); await page.waitForTimeout(3000); };
const mirrorRow = (uid) => page.evaluate((u) => (JSON.parse(localStorage.getItem('habitlab.db')).check_in || {})[u], uid);

const day = new Date().toISOString().slice(0, 10);
const uid = `merge-check:${day}:${Date.now()}`;
const base = Date.now() + 2000;
const values = { uid, habit_uid: 'merge-check', day, state: 'done', comment: 'first note' };
const stamps = Object.fromEntries(Object.keys(values).map((k) => [k, { c: base, d: DEVICE }]));

await deviceSync([{ t: 'check_in', u: uid, v: values, s: stamps }]);
await reload();
let row = await mirrorRow(uid);
check('row from other device arrives with its comment', row?.v?.comment === 'first note');

// The other device changes only `state`; the server answers with just that field.
await deviceSync();
await deviceSync([{ t: 'check_in', u: uid, v: { ...values, state: 'partial' }, s: { ...stamps, state: { c: base + 5000, d: DEVICE } } }]);
await reload();
row = await mirrorRow(uid);
check('single-field update keeps the rest of the row', row?.v?.habit_uid === 'merge-check' && row?.v?.day === day && row?.v?.comment === 'first note', JSON.stringify(row?.v));
check('single-field update applies the new state', row?.v?.state === 'partial');

// A delete from the other device hides the row, and a late live field must not revive a partial row.
await deviceSync();
await deviceSync([{ t: 'check_in', u: uid, v: { _deleted: true }, s: { _deleted: { c: base + 20000, d: DEVICE } } }]);
await reload();
row = await mirrorRow(uid);
check('incoming delete hides the row', row?.v?._deleted === true);
await deviceSync();
await deviceSync([{ t: 'check_in', u: uid, v: { state: 'done' }, s: { state: { c: base + 30000, d: DEVICE } } }]);
await reload();
row = await mirrorRow(uid);
check('late field after a delete does not revive the row', row?.v?._deleted === true && row?.v?.state === undefined, JSON.stringify(row?.v));

// The server refuses ids that could carry markup or script.
const bad = await fetch(BASE + '/api/sync', {
  method: 'POST', headers,
  body: JSON.stringify({ device: DEVICE, token, changes: [{ t: 'check_in', u: "x');alert(1);('", v: { state: 'done' }, s: { state: { c: base, d: DEVICE } } }] }),
});
check('server rejects an unsafe row id', bad.status === 400);
const badRef = await fetch(BASE + '/api/sync', {
  method: 'POST', headers,
  body: JSON.stringify({ device: DEVICE, token, changes: [{ t: 'check_in', u: 'ok-id', v: { habit_uid: '"><img src=x>' }, s: { habit_uid: { c: base, d: DEVICE } } }] }),
});
check('server rejects an unsafe reference field', badRef.status === 400);

// Security headers on the page and API.
const home = await fetch(BASE + '/');
check('nosniff header', home.headers.get('x-content-type-options') === 'nosniff');
const csp = home.headers.get('content-security-policy') || '';
check('CSP forbids framing', /frame-ancestors 'none'/.test(csp));
check('CSP allows only our own scripts (no inline, no external)', /script-src 'self'(;|$)/.test(csp));
check('API responses are not cached', (await fetch(BASE + '/api/config')).headers.get('cache-control') === 'no-store');

await browser.close();
if (failures.length) { console.error(String.fromCharCode(10) + failures.length + ' check(s) failed'); process.exit(1); }
console.log(String.fromCharCode(10) + 'All sync merge checks passed');
