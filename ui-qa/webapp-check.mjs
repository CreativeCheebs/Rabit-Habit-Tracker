import { chromium } from 'playwright';

const BASE = process.env.HABITLAB_WEB || 'http://192.168.0.8:8788';
const failures = [];
const check = (name, ok, extra) => {
  console.log((ok ? 'PASS ' : 'FAIL ') + name + (extra ? ' — ' + extra : ''));
  if (!ok) failures.push(name);
};

const browser = await chromium.launch({ headless: true });
const page = await browser.newPage({ viewport: { width: 393, height: 852 }, deviceScaleFactor: 2 });
const pageErrors = [];
page.on('pageerror', (e) => pageErrors.push(String(e)));

// Static + API surface intact.
const health = await page.request.get(BASE + '/health');
check('health ok', health.ok());
const css = await page.request.get(BASE + '/styles.css');
check('styles.css served', css.ok());
const js = await page.request.get(BASE + '/app.js');
check('app.js served', js.ok());
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
check('stays on login screen', await page.locator('#login.active').count() === 1);

// Authed flow when a test account is provided.
const user = process.env.HABITLAB_TEST_USER, pass = process.env.HABITLAB_TEST_PASS;
if (user && pass) {
  await page.fill('#loginUser', user);
  await page.fill('#loginPass', pass);
  await page.click('#loginBtn');
  await page.waitForSelector('#runs.active', { timeout: 20000 });
  await page.waitForTimeout(2500);
  check('runs screen after login', true);
  check('nav visible', await page.locator('#mainNav').isVisible());
  check('no page errors after sync', pageErrors.length === 0, pageErrors.join(' | '));
  const label = await page.locator('#syncLabel').textContent();
  console.log('sync label: ' + label);
  await page.screenshot({ path: 'web-runs.png' });
  await page.locator('#mainNav [data-screen="quicklog"]').click();
  await page.waitForTimeout(600);
  await page.screenshot({ path: 'web-quicklog.png' });
  check('quick log renders', await page.locator('#quickBody').isVisible());
} else {
  console.log('SKIP authed flow (set HABITLAB_TEST_USER/HABITLAB_TEST_PASS)');
}

await browser.close();
if (failures.length) { console.error('FAILURES: ' + failures.join(', ')); process.exit(1); }
console.log('ALL CHECKS PASSED');
