/**
 * Vision tuning constants.
 *
 * Single source of truth for detection behaviour. These match the tuning that
 * was previously hard-coded in the Python backend (app/tracking.py),
 * .env.example and CONFIG.md.
 */

export const COLOR_RANGES = Object.freeze({
  // OpenCV HSV: H in [0, 179], S/V in [0, 255].
  red: Object.freeze({
    lower: Object.freeze([0, 100, 100]),
    upper: Object.freeze([10, 255, 255]),
  }),
  blue: Object.freeze({
    lower: Object.freeze([100, 100, 100]),
    upper: Object.freeze([130, 255, 255]),
  }),
  yellow: Object.freeze({
    lower: Object.freeze([20, 100, 100]),
    upper: Object.freeze([30, 255, 255]),
  }),
  green: Object.freeze({
    lower: Object.freeze([40, 100, 100]),
    upper: Object.freeze([80, 255, 255]),
  }),
});

/** Minimum contour area (px^2) for a colour blob to count as a detection. */
export const MIN_DETECTION_AREA = 100;

/** Morphology kernel size used by the close/open pass. */
export const MORPH_KERNEL_SIZE = 5;

/** Canny thresholds, applied to the 8-bit Sobel gradient magnitude. */
export const CANNY_LOW_THRESHOLD = 50;
export const CANNY_HIGH_THRESHOLD = 150;

/** Edge-derived candidate contours: accepted area range. */
export const EDGE_MIN_AREA = 500;
export const EDGE_MAX_AREA = 50000;
export const EDGE_MAX_CONTOURS = 5;

/** Aspect ratio window for an edge contour to be considered "cup-like". */
export const EDGE_MIN_ASPECT = 0.7;
export const EDGE_MAX_ASPECT = 1.3;

/** Fixed confidence assigned to edge-derived detections. */
export const EDGE_CONFIDENCE = 80;

/** Circularity above which a detection is treated as a cup. */
export const CUP_CIRCULARITY_THRESHOLD = 0.6;

/** Colours that are always treated as cup candidates. */
export const CUP_COLORS = Object.freeze(['red', 'blue', 'yellow']);

/** Maximum cups reported by the cup tracker. */
export const MAX_CUPS = 3;

/** Number of frames retained for movement prediction. */
export const FRAME_HISTORY_SIZE = 30;

/** Minimum frames required before movement predictions are produced. */
export const MIN_PREDICTION_FRAMES = 3;

/** Cup candidate id prefix. */
export const CUP_ID_PREFIX = 'cup_';

/** Minimum milliseconds between analysed cup-tracking frames. */
export const CUP_TRACKING_MAX_FPS = 4;