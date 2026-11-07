import fs from 'node:fs';
import { execFileSync } from 'node:child_process';
import { setTimeout as delay } from 'node:timers/promises';

const adb = (...args) => execFileSync('adb', args, { encoding: args[0] === 'exec-out' ? 'buffer' : 'utf8' });
const sizeText = adb('shell', 'wm', 'size');
const match = sizeText.match(/(\d+)x(\d+)/);
if (!match) throw new Error(`Could not read device screen size: ${sizeText}`);
const width = Number(match[1]);
const height = Number(match[2]);

adb('shell', 'am', 'force-stop', 'app.habitlab');
adb('shell', 'monkey', '-p', 'app.habitlab', '1');
await delay(1400);

async function tapAndCapture(name, xRatio) {
  adb('shell', 'input', 'tap', String(Math.round(width * xRatio)), String(Math.round(height * .93)));
  await delay(500);
  fs.writeFileSync(`../docs/screenshots/${name}`, adb('exec-out', 'screencap', '-p'));
}

await tapAndCapture('runs-screen-final.png', .10);
await tapAndCapture('quicklog-final.png', .50);
console.log(`Captured Runs and Quick Log from the ${width}×${height} Android display.`);
