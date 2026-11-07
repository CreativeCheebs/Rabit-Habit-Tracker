import fs from 'node:fs';
import { PNG } from 'pngjs';

const image = PNG.sync.read(fs.readFileSync('docs/screenshots/runs-screen-final.png'));
const cropHeight = Math.min(160, image.height);
const cropY = image.height - cropHeight;
const crop = new PNG({ width: image.width, height: cropHeight });
PNG.bitblt(image, crop, 0, cropY, image.width, cropHeight, 0, 0);
fs.writeFileSync('ui-qa/dock-crop.png', PNG.sync.write(crop));
console.log(`Wrote dock crop ${crop.width}×${crop.height}.`);
