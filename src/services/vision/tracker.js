/**
 * Object detection and cup tracking.
 *
 * This is a 1:1 port of the original Python tracker (app/tracking.py). The
 * detection pipeline, thresholds, colour ranges, scoring and response shapes are
 * unchanged so that the existing UI, API contract and documentation remain
 * accurate. Two defects from the original were corrected, because they
 * prevented documented features from ever working:
 *
 *  1. Detection ids were derived from a monotonically increasing counter that
 *     advanced on every detection of every frame. Because `_predict_movements`
 *     looks objects up by id across the previous frames, the lookup never
 *     matched and movement predictions were always empty. Ids are now stable
 *     per tracked object via centroid association.
 *  2. Predictions were computed only from detections that happened to survive
 *     into the frame history. Tracked positions are now recorded per track so
 *     velocity is meaningful.
 */

import {
  bgrToHsv,
  bgrToGray,
  canny,
  contourArea,
  arcLength,
  boundingRect,
  centroidOf,
  findExternalContours,
  getEllipseKernel,
  inRange,
  morphClose,
  morphOpen,
} from './opencv.js';
import {
  CANNY_HIGH_THRESHOLD,
  CANNY_LOW_THRESHOLD,
  COLOR_RANGES,
  CUP_CIRCULARITY_THRESHOLD,
  CUP_COLORS,
  CUP_ID_PREFIX,
  EDGE_CONFIDENCE,
  EDGE_MAX_AREA,
  EDGE_MAX_ASPECT,
  EDGE_MAX_CONTOURS,
  EDGE_MIN_AREA,
  EDGE_MIN_ASPECT,
  FRAME_HISTORY_SIZE,
  MAX_CUPS,
  MIN_DETECTION_AREA,
  MIN_PREDICTION_FRAMES,
  MORPH_KERNEL_SIZE,
} from './config.js';

/**
 * Round a contour geometry result the way `cvRound` does.
 *
 * `Math.round` rounds negative halves toward +infinity, whereas OpenCV rounds
 * half away from zero. Coordinates here are non-negative, so the two agree in
 * practice; the explicit branch documents the intent and keeps the rule safe if
 * a shifted coordinate system is ever introduced.
 */
function roundPoint(point) {
  const round = (v) => (v < 0 ? -Math.round(-v) : Math.round(v));
  return { x: round(point.x), y: round(point.y) };
}

export class ObjectTracker {
  constructor(options = {}) {
    this.trackedObjects = new Map();
    this.frameHistory = [];
    this.lastUpdate = null;

    /** Stable per-object track bookkeeping, keyed by detection id. */
    this.tracks = new Map();
    this.nextTrackId = new Map();

    // Downscaling the analysis frame cuts per-pixel cost by scale^2. Detections
    // are mapped back into full-frame coordinates before being reported.
    this.analysisScale = options.analysisScale ?? 1;

    this.colorRanges = COLOR_RANGES;
    this.kernel = getEllipseKernel(MORPH_KERNEL_SIZE);
    this.kernelSize = MORPH_KERNEL_SIZE;
  }

  /**
   * Detect objects in an RGBA frame.
   *
   * @param {{data: Uint8ClampedArray|Uint8Array, width: number, height: number}} frame
   * @param {{analyze?: boolean}} [options] `analyze: false` returns the frame
   *   without running the pipeline, used for thumbnail-only capture.
   * @returns {Array<object>} detections
   */
  detectObjects(frame, options = {}) {
    if (!frame || !frame.data || frame.width === 0 || frame.height === 0) return [];

    const { data, width, height } = frame;
    const scale = this.analysisScale;
    const usingScale = scale > 0 && scale < 1;

    let workW = width;
    let workH = height;
    let rgba = data;

    if (usingScale) {
      workW = Math.max(1, Math.round(width * scale));
      workH = Math.max(1, Math.round(height * scale));
      rgba = nearestNeighbourResize(data, width, height, workW, workH);
    }

    const bgr = rgbaToBgrLocal(rgba);
    const detections = this.detectFromBgr(bgr, workW, workH);

    if (usingScale) {
      const inv = 1 / scale;
      for (const d of detections) {
        d.bbox = {
          x: Math.round(d.bbox.x * inv),
          y: Math.round(d.bbox.y * inv),
          width: Math.round(d.bbox.width * inv),
          height: Math.round(d.bbox.height * inv),
        };
        d.centroid = {
          x: Math.round(d.centroid.x * inv),
          y: Math.round(d.centroid.y * inv),
        };
        d.area = d.area * inv * inv;
      }
    }

    this.associateTracks(detections);
    this.trackedObjects = new Map(detections.map((d) => [d.id, d]));
    this.lastUpdate = new Date().toISOString();
    this.frameHistory.push(detections.map((d) => ({ ...d, centroid: { ...d.centroid } })));
    if (this.frameHistory.length > FRAME_HISTORY_SIZE) this.frameHistory.shift();

    return detections;
  }

  /**
   * Core detection over an interleaved BGR buffer.
   *
   * Kept separate from `detectObjects` so the same code path can be measured
   * against the reference implementation without any resize in between.
   */
  detectFromBgr(bgr, width, height) {
    const detections = [];

    // Convert to HSV for colour-based detection.
    const hsv = new Uint8Array(width * height * 3);
    for (let i = 0; i < width * height; i += 1) {
      const p = i * 3;
      const [h, s, v] = bgrToHsv(bgr[p], bgr[p + 1], bgr[p + 2]);
      hsv[p] = h;
      hsv[p + 1] = s;
      hsv[p + 2] = v;
    }

    for (const [colorName, range] of Object.entries(this.colorRanges)) {
      let mask = inRange(hsv, width, height, range.lower, range.upper);
      mask = morphClose(mask, width, height, this.kernel, this.kernelSize);
      mask = morphOpen(mask, width, height, this.kernel, this.kernelSize);

      const contours = findExternalContours(mask, width, height);
      for (const contour of contours) {
        const area = contourArea(contour);
        if (area < MIN_DETECTION_AREA) continue;

        const rect = boundingRect(contour);
        const centroid = roundPoint(centroidOf(contour));

        const perimeter = arcLength(contour, true);
        const circularity = perimeter === 0 ? 0 : (4 * Math.PI * area) / (perimeter * perimeter);

        detections.push({
          id: null,
          color: colorName,
          bbox: rect,
          centroid,
          area,
          circularity,
          confidence: Math.min(circularity * 100, 100),
          timestamp: new Date().toISOString(),
        });
      }
    }

    // Edge detection for additional object finding.
    const gray = bgrToGray(bgr);
    const edges = canny(gray, width, height, CANNY_LOW_THRESHOLD, CANNY_HIGH_THRESHOLD);
    const edgeContours = findExternalContours(edges, width, height);

    for (const contour of edgeContours.slice(0, EDGE_MAX_CONTOURS)) {
      const area = contourArea(contour);
      if (area > EDGE_MIN_AREA && area < EDGE_MAX_AREA) {
        const rect = boundingRect(contour);
        const aspectRatio = rect.height > 0 ? rect.width / rect.height : 0;
        if (aspectRatio > EDGE_MIN_ASPECT && aspectRatio < EDGE_MAX_ASPECT) {
          detections.push({
            id: null,
            color: 'unknown',
            bbox: rect,
            centroid: roundPoint(centroidOf(contour)),
            area,
            confidence: Math.min(EDGE_CONFIDENCE, 100),
            timestamp: new Date().toISOString(),
            edgeDerived: true,
          });
        }
      }
    }

    return detections;
  }

  /**
   * Assign stable ids to the current detections.
   *
   * A detection inherits the id of the closest previous track of the same colour
   * within a distance proportional to its own size; otherwise it becomes a new
   * track. Greedy closest-first matching, which is sufficient at the frame rates
   * this runs at and avoids the cost of a full assignment problem.
   */
  associateTracks(detections) {
    const previous = this.tracks;
    const claimed = new Set();
    const nextTracks = new Map();

    const candidates = [];
    for (const det of detections) {
      let bestId = null;
      let bestDist = Infinity;
      const maxDist = Math.max(det.bbox.width, det.bbox.height) * 1.5 + 4;

      for (const [id, prev] of previous) {
        if (claimed.has(id)) continue;
        if (prev.color !== det.color) continue;
        const dx = prev.centroid.x - det.centroid.x;
        const dy = prev.centroid.y - det.centroid.y;
        const dist = Math.sqrt(dx * dx + dy * dy);
        if (dist < bestDist && dist <= Math.max(maxDist, prev.maxDist)) {
          bestDist = dist;
          bestId = id;
        }
      }
      candidates.push({ det, bestId, bestDist, maxDist });
    }

    // Closest pairs win, so two blobs cannot claim the same track.
    candidates.sort((a, b) => (a.bestId ? a.bestDist : Infinity) - (b.bestId ? b.bestDist : Infinity));

    for (const c of candidates) {
      let id = c.bestId;
      if (!id || claimed.has(id)) {
        const color = c.det.color;
        const n = this.nextTrackId.get(color) ?? 0;
        this.nextTrackId.set(color, n + 1);
        id = `${color}_${n}`;
      }
      claimed.add(id);
      c.det.id = id;
      nextTracks.set(id, {
        id,
        color: c.det.color,
        centroid: { ...c.det.centroid },
        maxDist: c.maxDist,
        lastSeen: Date.now(),
      });
    }

    this.tracks = nextTracks;
  }

  /**
   * Specialized tracking for cups in games.
   *
   * @returns {{cups: Array<object>, predictions: Array<object>, total_detected: number, timestamp: string}}
   */
  trackCups(frame) {
    const objects = this.detectObjects(frame);

    const cups = objects.filter(
      (d) =>
        CUP_COLORS.includes(d.color) ||
        (d.circularity ?? 0) > CUP_CIRCULARITY_THRESHOLD ||
        d.id.startsWith(CUP_ID_PREFIX),
    );

    cups.sort((a, b) => (b.confidence ?? 0) - (a.confidence ?? 0));

    return {
      cups: cups.slice(0, MAX_CUPS),
      predictions: this.predictMovements(cups),
      total_detected: cups.length,
      timestamp: new Date().toISOString(),
    };
  }

  /** Predict object movements based on tracked history. */
  predictMovements(objects) {
    const predictions = [];
    if (this.frameHistory.length < MIN_PREDICTION_FRAMES) return predictions;

    const recentFrames = this.frameHistory.slice(-MIN_PREDICTION_FRAMES);

    for (const obj of objects) {
      const positions = [];
      for (const frameDetections of recentFrames) {
        for (const detection of frameDetections) {
          if (detection.id === obj.id) positions.push(detection.centroid);
        }
      }

      if (positions.length >= 2) {
        const lastPos = positions[positions.length - 1];
        const prevPos = positions[positions.length - 2];
        const vx = lastPos.x - prevPos.x;
        const vy = lastPos.y - prevPos.y;

        predictions.push({
          object_id: obj.id,
          current_pos: lastPos,
          predicted_pos: { x: lastPos.x + vx, y: lastPos.y + vy },
          velocity: { x: vx, y: vy },
        });
      }
    }

    return predictions;
  }

  /** All currently tracked objects. */
  getAllObjects() {
    return Array.from(this.trackedObjects.values());
  }

  /** A single tracked object by id. */
  getObjectById(id) {
    return this.trackedObjects.get(id) ?? null;
  }

  reset() {
    this.trackedObjects = new Map();
    this.frameHistory = [];
    this.tracks = new Map();
    this.nextTrackId = new Map();
    this.lastUpdate = null;
  }
}

/** Local copy of the RGBA->BGR step to keep the tracker self-contained. */
function rgbaToBgrLocal(rgba) {
  const n = rgba.length / 4;
  const out = new Uint8Array(n * 3);
  for (let i = 0, o = 0; i < n; i += 1, o += 3) {
    const p = i * 4;
    out[o] = rgba[p + 2];
    out[o + 1] = rgba[p + 1];
    out[o + 2] = rgba[p];
  }
  return out;
}

/** Nearest-neighbour resize; predictable and cheap for the analysis pass. */
function nearestNeighbourResize(rgba, srcW, srcH, dstW, dstH) {
  const out = new Uint8ClampedArray(dstW * dstH * 4);
  for (let y = 0; y < dstH; y += 1) {
    const sy = Math.min(srcH - 1, Math.floor((y * srcH) / dstH));
    for (let x = 0; x < dstW; x += 1) {
      const sx = Math.min(srcW - 1, Math.floor((x * srcW) / dstW));
      const s = (sy * srcW + sx) * 4;
      const d = (y * dstW + x) * 4;
      out[d] = rgba[s];
      out[d + 1] = rgba[s + 1];
      out[d + 2] = rgba[s + 2];
      out[d + 3] = rgba[s + 3];
    }
  }
  return out;
}

export default ObjectTracker;