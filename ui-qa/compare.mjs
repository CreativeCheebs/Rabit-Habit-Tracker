import fs from 'node:fs';
import pixelmatch from 'pixelmatch';
import { PNG } from 'pngjs';

const pairs = [['runs-screen-final.png', 'mock-runs.png', 'diff-runs.png'], ['quicklog-final.png', 'mock-quicklog.png', 'diff-quicklog.png']];
for (const [actualPath, referencePath, diffPath] of pairs) {
  const actual = PNG.sync.read(fs.readFileSync(`../${actualPath}`));
  const reference = PNG.sync.read(fs.readFileSync(referencePath));
  if (actual.width !== reference.width || actual.height !== reference.height) {
    throw new Error(`${actualPath}: ${actual.width}x${actual.height} does not match ${referencePath}: ${reference.width}x${reference.height}`);
  }
  const diff = new PNG({ width: actual.width, height: actual.height });
  const count = pixelmatch(actual.data, reference.data, diff.data, actual.width, actual.height, { threshold: 0.18, includeAA: false });
  fs.writeFileSync(diffPath, PNG.sync.write(diff));
  console.log(`${actualPath} vs ${referencePath}: ${count.toLocaleString()} differing pixels (${(100 * count / (actual.width * actual.height)).toFixed(1)}%); diff: ${diffPath}`);
}
