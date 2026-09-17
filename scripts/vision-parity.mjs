/**
 * Vision parity check.
 *
 * Runs the JavaScript tracker against the same synthetic frame the original
 * Python pipeline processed, and asserts that every geometric field matches the
 * recorded reference within a tight tolerance. This is the guard that keeps the
 * port honest: the detection thresholds in config.js are only meaningful if
 * these numbers agree.
 *
 * Usage:
 *   python3 scripts/reference_tracker.py .parity      # regenerate the oracle
 *   node scripts/vision-parity.mjs                    # compare
 */

import { readFileSync, existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { ObjectTracker } from '../src/services/vision/tracker.js';

const here = dirname(fileURLToPath(import.meta.url));
const fixtureDir = process.argv[2] ?? join(here, '..', '.parity');

const FIXTURE = join(fixtureDir, 'fixture.bgr');
const META = join(fixtureDir, 'meta.json');
const REFERENCE = join(fixtureDir, 'reference.json');

if (!existsSync(FIXTURE) || !existsSync(REFERENCE)) {
  console.error(
    `Missing parity fixture in ${fixtureDir}.\n` +
      'Generate it first with: python3 scripts/reference_tracker.py ' +
      fixtureDir,
  );
  process.exit(2);
}

const { width, height } = JSON.parse(readFileSync(META, 'utf8'));
const reference = JSON.parse(readFileSync(REFERENCE, 'utf8'));
const bgr = new Uint8Array(readFileSync(FIXTURE));
const rgba = new Uint8ClampedArray(width * height * 4);

for (let i = 0, o = 0; i < width * height; i += 1, o += 3) {
  const p = i * 4;
  rgba[p] = bgr[o + 2];
  rgba[p + 1] = bgr[o + 1];
  rgba[p + 2] = bgr[o];
  rgba[p + 3] = 255;
}

const tracker = new ObjectTracker();
const actual = tracker.detectObjects({ data: rgba, width, height });

const failures = [];
const near = (a, b, tol) => Math.abs(a - b) <= tol;

/**
 * Match detections by colour and nearest centroid, then compare geometry.
 *
 * Matching by centroid rather than by index keeps the check meaningful when the
 * two implementations enumerate contours in a different order.
 */
const remaining = [...reference];
const matched = [];

for (const det of actual) {
  let bestIdx = -1;
  let bestDist = Infinity;
  for (let i = 0; i < remaining.length; i += 1) {
    if (remaining[i].color !== det.color) continue;
    const dx = remaining[i].centroid.x - det.centroid.x;
    const dy = remaining[i].centroid.y - det.centroid.y;
    const dist = Math.hypot(dx, dy);
    if (dist < bestDist) {
      bestDist = dist;
      bestIdx = i;
    }
  }
  if (bestIdx >= 0 && bestDist <= 3) {
    matched.push({ expected: remaining[bestIdx], actual: det });
    remaining.splice(bestIdx, 1);
  } else {
    failures.push(
      `unmatched JS detection: ${det.color} @ (${det.centroid.x},${det.centroid.y})` +
        ` nearest ref distance=${bestDist === Infinity ? 'n/a' : bestDist.toFixed(1)}`,
    );
  }
}

for (const missing of remaining) {
  failures.push(
    `reference detection not reproduced: ${missing.color} @ ` +
      `(${missing.centroid.x},${missing.centroid.y})`,
  );
}

const TOL_AREA = 0.05;
const TOL_CIRC = 0.05;
const TOL_CONF = 0.05;
const TOL_BOX = 1;
const TOL_CENTROID = 1;

for (const { expected, actual: got } of matched) {
  const label = `${expected.color} @ (${expected.centroid.x},${expected.centroid.y})`;

  if (!near(got.bbox.x, expected.bbox.x, TOL_BOX)) {
    failures.push(`${label}: bbox.x ${got.bbox.x} != ${expected.bbox.x}`);
  }
  if (!near(got.bbox.y, expected.bbox.y, TOL_BOX)) {
    failures.push(`${label}: bbox.y ${got.bbox.y} != ${expected.bbox.y}`);
  }
  if (!near(got.bbox.width, expected.bbox.width, TOL_BOX)) {
    failures.push(`${label}: bbox.width ${got.bbox.width} != ${expected.bbox.width}`);
  }
  if (!near(got.bbox.height, expected.bbox.height, TOL_BOX)) {
    failures.push(`${label}: bbox.height ${got.bbox.height} != ${expected.bbox.height}`);
  }
  if (!near(got.centroid.x, expected.centroid.x, TOL_CENTROID)) {
    failures.push(`${label}: centroid.x ${got.centroid.x} != ${expected.centroid.x}`);
  }
  if (!near(got.centroid.y, expected.centroid.y, TOL_CENTROID)) {
    failures.push(`${label}: centroid.y ${got.centroid.y} != ${expected.centroid.y}`);
  }

  const rel = (a, b) => (b === 0 ? Math.abs(a) : Math.abs(a - b) / Math.abs(b));
  if (rel(got.area, expected.area) > TOL_AREA) {
    failures.push(`${label}: area ${got.area.toFixed(2)} != ${expected.area.toFixed(2)}`);
  }
  if (expected.circularity !== undefined && rel(got.circularity, expected.circularity) > TOL_CIRC) {
    failures.push(
      `${label}: circularity ${got.circularity.toFixed(4)} != ${expected.circularity.toFixed(4)}`,
    );
  }
  if (rel(got.confidence, expected.confidence) > TOL_CONF) {
    failures.push(
      `${label}: confidence ${got.confidence.toFixed(2)} != ${expected.confidence.toFixed(2)}`,
    );
  }
}

/**
 * Every detection is held to the same tight tolerance. Edge-derived contours are
 * included deliberately: Canny's output near a threshold is not unique, so if a
 * future change makes the edge stage diverge, this is where it should surface
 * rather than being masked by a separate, looser budget.
 */
console.log(`reference detections : ${reference.length}`);
console.log(`JS detections        : ${actual.length}`);
console.log(`matched              : ${matched.length}`);

if (failures.length) {
  console.error(`\nPARITY FAILED (${failures.length} mismatches):`);
  for (const f of failures.slice(0, 40)) console.error(`  - ${f}`);
  if (failures.length > 40) console.error(`  ... and ${failures.length - 40} more`);
  process.exit(1);
}

console.log('\nPARITY OK: every detection matched the reference within tolerance.');
