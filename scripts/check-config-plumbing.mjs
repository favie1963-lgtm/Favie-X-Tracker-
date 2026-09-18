/**
 * Verifies the frame-transport config is plumbed through to the capture layer.
 *
 * The bug this guards: TrackingService.start() read `max_frame_width` and
 * `jpeg_quality` off its config, but neither key existed in DEFAULT_CONFIG, so
 * both were undefined. Every layer below then quietly substituted its own
 * default, which meant the config object was not the single source of truth it
 * claims to be and any override was ignored.
 *
 * Run with: node scripts/check-config-plumbing.mjs
 */
import { DEFAULT_CONFIG, TrackingService } from '../src/services/tracking.js';

let failures = 0;
function check(label, actual, expected) {
  const ok = actual === expected;
  if (!ok) failures += 1;
  console.log(
    `${ok ? 'PASS' : 'FAIL'} ${label}: ${JSON.stringify(actual)}` +
      (ok ? '' : ` (expected ${JSON.stringify(expected)})`)
  );
}

check('DEFAULT_CONFIG.max_frame_width', DEFAULT_CONFIG.max_frame_width, 960);
check('DEFAULT_CONFIG.jpeg_quality', DEFAULT_CONFIG.jpeg_quality, 70);

// captureOptions() is the single place the settings are read, and start() hands
// its result to the capture source, so asserting on it covers the real path.
const service = new TrackingService();
const defaults = service.captureOptions();
check('defaults forwarded maxWidth', defaults.maxWidth, DEFAULT_CONFIG.max_frame_width);
check('defaults forwarded quality', defaults.quality, DEFAULT_CONFIG.jpeg_quality);

service.config = { ...service.config, max_frame_width: 480, jpeg_quality: 55 };
const overridden = service.captureOptions();
check('override maxWidth', overridden.maxWidth, 480);
check('override quality', overridden.quality, 55);

console.log(failures === 0 ? '\nCONFIG PLUMBING OK' : `\n${failures} FAILURE(S)`);
process.exit(failures === 0 ? 0 : 1);