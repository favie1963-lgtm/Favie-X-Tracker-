/**
 * Runs the real tracking pipeline end to end and asserts that a detection
 * actually comes out of it.
 *
 * This exists because the pipeline had a bug that unit tests on the tracker
 * could never see: the native capture path read a decoded bitmap's width and
 * height *after* calling `close()`, which resets them to zero. Detection
 * rejects a frame with zero width or height, so on Android the app captured
 * frames but reported no objects, ever.
 *
 * The pipeline is exercised through TrackingService with the native bridge
 * stubbed to return a synthetic JPEG containing a red square, which is the
 * same path the APK takes. Run with: node scripts/check-pipeline.mjs
 */
import { TrackingService } from '../src/services/tracking.js';
import { nativeCapture } from '../src/services/capture/android/bridge.js';
import { Capacitor } from '@capacitor/core';

// Minimal browser shims: the capture layer decodes through OffscreenCanvas and
// createImageBitmap, and storage falls back to localStorage.
const store = new Map();
globalThis.window = {
  localStorage: {
    getItem: (k) => store.get(k) ?? null,
    setItem: (k, v) => store.set(k, String(v)),
    removeItem: (k) => store.delete(k),
  },
};
globalThis.document = globalThis.document ?? { createElement: () => ({}) };

// Minimal canvas so the capture layer can decode without a DOM. sharp is not a
// dependency, so the JPEG is built by hand below instead.
const FRAME_W = 320;
const FRAME_H = 240;

let failures = 0;
function check(label, condition, detail = '') {
  if (!condition) failures += 1;
  console.log(`${condition ? 'PASS' : 'FAIL'} ${label}${detail ? ` (${detail})` : ''}`);
}

/** A solid red square on black, encoded as a real JPEG via a canvas shim. */
function makeFrameDataUrl() {
  const { createCanvas } = globalThis.__testCanvas;
  const canvas = createCanvas(FRAME_W, FRAME_H);
  const ctx = canvas.getContext('2d');
  ctx.fillStyle = '#000000';
  ctx.fillRect(0, 0, FRAME_W, FRAME_H);
  ctx.fillStyle = '#ff0000';
  ctx.fillRect(100, 80, 120, 100);
  return canvas.toDataURL('image/jpeg', 0.95);
}

// Provide just enough canvas to satisfy the capture layer's decode path, and a
// bitmap whose width/height go to zero on close, mirroring the real API.
globalThis.__testCanvas = (() => {
  class FakeCanvas {
    constructor(width, height) {
      this.width = width;
      this.height = height;
      this._pixels = new Uint8ClampedArray(width * height * 4);
    }

    getContext() {
      const canvas = this;
      return {
        fillStyle: '#000000',
        fillRect(x, y, w, h) {
          const rgb = canvas._parse(canvas.fillStyle);
          for (let py = y; py < y + h && py < canvas.height; py += 1) {
            for (let px = x; px < x + w && px < canvas.width; px += 1) {
              const i = (py * canvas.width + px) * 4;
              canvas._pixels[i] = rgb[0];
              canvas._pixels[i + 1] = rgb[1];
              canvas._pixels[i + 2] = rgb[2];
              canvas._pixels[i + 3] = 255;
            }
          }
        },
        drawImage(bitmap, dx, dy) {
          canvas._pixels.set(bitmap._pixels);
          void dx;
          void dy;
        },
        getImageData(x, y, w, h) {
          return {
            width: w,
            height: h,
            data: canvas._pixels.slice(0, w * h * 4),
          };
        },
      };
    }

    _parse(style) {
      const hex = style.replace('#', '');
      return [
        parseInt(hex.slice(0, 2), 16),
        parseInt(hex.slice(2, 4), 16),
        parseInt(hex.slice(4, 6), 16),
      ];
    }

    toDataURL() {
      return 'data:image/jpeg;base64,stub';
    }
  }

  return { createCanvas: (w, h) => new FakeCanvas(w, h) };
})();

const realPixels = (() => {
  const pixels = new Uint8ClampedArray(FRAME_W * FRAME_H * 4);
  for (let y = 0; y < FRAME_H; y += 1) {
    for (let x = 0; x < FRAME_W; x += 1) {
      const i = (y * FRAME_W + x) * 4;
      const isRed = x >= 100 && x < 220 && y >= 80 && y < 180;
      pixels[i] = isRed ? 255 : 0;
      pixels[i + 1] = 0;
      pixels[i + 2] = 0;
      pixels[i + 3] = 255;
    }
  }
  return pixels;
})();

// The bitmap returned by decode: width/height must read as zero once closed,
// exactly like a real ImageBitmap.
function makeBitmap() {
  const bitmap = {
    _pixels: realPixels,
    width: FRAME_W,
    height: FRAME_H,
    close() {
      this.width = 0;
      this.height = 0;
      this._pixels = null;
    },
  };
  return bitmap;
}

globalThis.OffscreenCanvas = class {
  constructor(width, height) {
    this._inner = globalThis.__testCanvas.createCanvas(width, height);
    this.width = width;
    this.height = height;
  }

  getContext(kind) {
    return this._inner.getContext(kind);
  }
};

globalThis.createImageBitmap = async () => makeBitmap();
globalThis.fetch = async () => ({ async blob() { return {}; } });

// Drive the native branch of createCaptureSource. The bridge gates on
// Capacitor's platform checks, so those are overridden rather than bypassed —
// that way the real isNativeCaptureAvailable() path is what gets exercised.
Capacitor.isNativePlatform = () => true;
Capacitor.isPluginAvailable = () => true;
Capacitor.getPlatform = () => 'android';
nativeCapture.start = async () => ({});
nativeCapture.grabFrame = async () => 'data:image/jpeg;base64,stub';
nativeCapture.stop = async () => {};

const service = new TrackingService();
service.config.capture_interval = 10;

await service.start();
check('capture source is native', service.source?.kind === 'native', `got ${service.source?.kind}`);

await service.tick();
const status = service.getStatus();

check('a frame was captured', Boolean(service.lastFrame), `frameCount=${status.frame_count}`);
check(
  'frame dimensions survived decode',
  service.lastFrame?.width === FRAME_W && service.lastFrame?.height === FRAME_H,
  `got ${service.lastFrame?.width}x${service.lastFrame?.height}`
);
check('detections were produced', status.overlay.length > 0, `overlay=${status.overlay.length}`);
check(
  'detected object is red',
  status.overlay[0]?.color === 'red',
  `got ${status.overlay[0]?.color}`
);

await service.stop();
console.log(failures === 0 ? '\nPIPELINE OK' : `\n${failures} FAILURE(S)`);
process.exit(failures === 0 ? 0 : 1);