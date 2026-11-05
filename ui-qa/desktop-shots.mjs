import { chromium } from 'playwright';
import { pathToFileURL } from 'node:url';
import { resolve } from 'node:path';

const browser = await chromium.launch({ headless: true });
const page = await browser.newPage({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 1 });
const errors = [];
page.on('pageerror', (e) => errors.push(String(e)));
await page.goto(pathToFileURL(resolve('../habit-tracker-desktop-mockups.html')).href, { waitUntil: 'networkidle' });
for (const v of ['runs', 'goals', 'builder', 'edit']) {
  await page.locator(`.sidenav button[data-view="${v}"]`).click();
  await page.waitForTimeout(350);
  await page.screenshot({ path: `desk-${v}.png` });
}
await page.locator('.sidenav button[data-view="runs"]').click();
await page.locator('#acctBtn').click();
await page.waitForTimeout(300);
await page.screenshot({ path: 'desk-account.png' });
console.log('page errors: ' + (errors.length ? errors.join(' | ') : 'none'));
await browser.close();
