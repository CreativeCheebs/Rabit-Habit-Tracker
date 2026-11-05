import { chromium } from 'playwright';
import { pathToFileURL } from 'node:url';
import { resolve } from 'node:path';

const target = resolve('../habit-tracker-mobile-mockups.html');
const browser = await chromium.launch({ headless: true });
const page = await browser.newPage({ viewport: { width: 360, height: 800 }, deviceScaleFactor: 2 });
await page.goto(pathToFileURL(target).href);
await page.screenshot({ path: 'mock-runs.png' });
await page.locator('[data-screen="quicklog"]').click();
await page.waitForTimeout(300);
await page.screenshot({ path: 'mock-quicklog.png' });
await browser.close();
