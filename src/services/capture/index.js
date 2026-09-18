/**
 * Frame acquisition.
 *
 * Provides a single `captureFrame()` call that returns raw RGBA pixels ready for
 * the tracker. Two sources are supported:
 *
 *  - Android: the native MediaProjection plugin, which captures the whole
 *    device display including other apps.
 *  - Browser/desktop: `getDisplayMedia` (a user-chosen screen, window or tab),
 *    used for development and for the desktop build.
 */

import { nativeCapture, isNativeCaptureAvailable } from './android/bridge.js';

/**
 * @typedef {object} CaptureSource
 * @property {"native"|"display-media"|"none"} kind
 * @property {() => Promise<CaptureFrame|null>} grab
 * @property {() => Promise<void>} stop
 */

/**
 * @typedef {object} CaptureFrame
 * @property {number} width
 * @property {number} height
 * @property {Uint8ClampedArray} data  RGBA, top-left origin
 * @property {string} dataUrl          JPEG/PNG preview of the same frame
 * @property {number} timestamp
 */

/** Create the best available capture source for the current platform. */
export async function createCaptureSource(options = {}) {
  if (isNativeCaptureAvailable()) {
    await nativeCapture.start(options);
    return {
      kind: 'native',
      async grab() {
        return grabNativeFrame(options);
      },
      async stop() {
        await nativeCapture.stop();
      },
    };
  }

  const displayMedia = await tryDisplayMedia();
  if (displayMedia) return displayMedia;

  return {
    kind: 'none',
    async grab() {
      return null;
    },
    async stop() {},
  };
}

const NATIVE_FRAME_MAX_WIDTH = 960;

/**
 * Pull one frame from the native plugin and decode it into RGBA.
 *
 * The plugin returns a data URL because it is the cheapest way to move a bitmap
 * across the Capacitor bridge (a raw array would be JSON-encoded). Decoding
 * through an ImageBitmap keeps this off the main thread's image path.
 */
async function grabNativeFrame(options = {}) {
  const maxWidth = options.maxWidth ?? NATIVE_FRAME_MAX_WIDTH;
  const quality = options.quality ?? 70;

  const dataUrl = await nativeCapture.grabFrame({ maxWidth, quality });
  if (!dataUrl) return null;

  const bitmap = await decodeDataUrl(dataUrl);
  // Read the dimensions before closing. `close()` detaches the bitmap and
  // resets width/height to 0, and downstream detection rejects a frame whose
  // dimensions are 0 — so reading them afterwards silently produced a frame
  // that never contained any detections.
  const width = bitmap.width;
  const height = bitmap.height;

  const canvas = createCanvas(width, height);
  const ctx = canvas.getContext('2d', { willReadFrequently: true });
  ctx.drawImage(bitmap, 0, 0, width, height);
  const imageData = ctx.getImageData(0, 0, width, height);
  if (typeof bitmap.close === 'function') bitmap.close();

  return {
    width,
    height,
    data: imageData.data,
    dataUrl,
    timestamp: Date.now(),
  };
}

let displayStream = null;
let displayVideo = null;

/** Attempt to acquire a desktop screen/window/tab capture stream. */
async function tryDisplayMedia() {
  if (typeof navigator === 'undefined' || !navigator.mediaDevices?.getDisplayMedia) {
    return null;
  }

  try {
    displayStream = await navigator.mediaDevices.getDisplayMedia({
      video: { frameRate: 30 },
      audio: false,
    });
  } catch {
    return null;
  }

  displayVideo = document.createElement('video');
  displayVideo.srcObject = displayStream;
  displayVideo.muted = true;
  displayVideo.playsInline = true;
  await displayVideo.play();

  await new Promise((resolve) => {
    if (displayVideo.videoWidth) return resolve();
    displayVideo.onloadedmetadata = () => resolve();
    return undefined;
  });

  displayStream.getVideoTracks()[0]?.addEventListener('ended', () => {
    displayStream = null;
    displayVideo = null;
  });

  return {
    kind: 'display-media',
    async grab() {
      if (!displayVideo || !displayVideo.videoWidth) return null;
      const width = displayVideo.videoWidth;
      const height = displayVideo.videoHeight;
      const canvas = createCanvas(width, height);
      const ctx = canvas.getContext('2d', { willReadFrequently: true });
      ctx.drawImage(displayVideo, 0, 0, width, height);
      const imageData = ctx.getImageData(0, 0, width, height);
      return {
        width,
        height,
        data: imageData.data,
        dataUrl: canvas.toDataURL('image/jpeg', 0.7),
        timestamp: Date.now(),
      };
    },
    async stop() {
      displayStream?.getTracks().forEach((track) => track.stop());
      displayStream = null;
      displayVideo = null;
    },
  };
}

function createCanvas(width, height) {
  if (typeof OffscreenCanvas !== 'undefined') return new OffscreenCanvas(width, height);
  const canvas = document.createElement('canvas');
  canvas.width = width;
  canvas.height = height;
  return canvas;
}

async function decodeDataUrl(dataUrl) {
  if (typeof createImageBitmap === 'function') {
    const response = await fetch(dataUrl);
    const blob = await response.blob();
    return createImageBitmap(blob);
  }

  return new Promise((resolve, reject) => {
    const img = new Image();
    img.onload = () => resolve(img);
    img.onerror = () => reject(new Error('Unable to decode captured frame'));
    img.src = dataUrl;
  });
}

export default createCaptureSource;