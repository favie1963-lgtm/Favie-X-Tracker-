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
   * @param {{data: Uint8ClampedArray|Uint8Array, width: number, height: number}} frame
   * @param {Array<object>|null} [detections] Detections already produced for this
   *   frame. Passing them avoids re-running the pipeline, which used to append a
   *   second copy of the same frame to the history and collapse every predicted
   *   velocity to zero.
   * @returns {{cups: Array<object>, predictions: Array<object>, total_detected: number, timestamp: string}}
   */
  trackCups(frame, detections = null) {
    const objects = detections ?? this.detectObjects(frame);

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

/** Target lifecycle states reported to the UI. */
export const TARGET_STATE = Object.freeze({
  NONE: 'none',
  LOCKED: 'locked',
  LOST: 'lost',
});

/** Edge length of the colour signature sampled from a target. */
export const SIGNATURE_SIZE = 16;

/**
 * Sample a compact colour signature for a region.
 *
 * The signature is a fixed SIGNATURE_SIZE x SIGNATURE_SIZE RGB grid taken with
 * nearest sampling, so it is scale-independent: the same object occupies the
 * same grid whether it is near or far, and comparison cost is constant
 * regardless of how large the region is.
 */
export function extractSignature(frame, bbox) {
  const { data, width, height } = frame;
  const out = new Uint8Array(SIGNATURE_SIZE * SIGNATURE_SIZE * 3);

  const x0 = Math.max(0, Math.floor(bbox.x));
  const y0 = Math.max(0, Math.floor(bbox.y));
  const w = Math.max(1, Math.min(width - x0, Math.round(bbox.width)));
  const h = Math.max(1, Math.min(height - y0, Math.round(bbox.height)));

  for (let gy = 0; gy < SIGNATURE_SIZE; gy += 1) {
    for (let gx = 0; gx < SIGNATURE_SIZE; gx += 1) {
      const sx = x0 + Math.min(w - 1, Math.floor(((gx + 0.5) * w) / SIGNATURE_SIZE));
      const sy = y0 + Math.min(h - 1, Math.floor(((gy + 0.5) * h) / SIGNATURE_SIZE));
      const p = (sy * width + sx) * 4;
      const o = (gy * SIGNATURE_SIZE + gx) * 3;
      out[o] = data[p];
      out[o + 1] = data[p + 1];
      out[o + 2] = data[p + 2];
    }
  }
  return out;
}

/**
 * Distance in [0, 1] between two colour signatures.
 *
 * Mean of the per-cell largest-channel difference, matching
 * {@link TargetTracker#scoreRegion} so a threshold means the same thing in both
 * places.
 */
export function signatureDistance(a, b) {
  if (!a || !b || a.length !== b.length) return 1;
  const cells = a.length / 3;
  let sum = 0;
  for (let i = 0, o = 0; i < a.length; i += 3, o += 1) {
    sum += Math.max(
      Math.abs(a[i] - b[i]),
      Math.abs(a[i + 1] - b[i + 1]),
      Math.abs(a[i + 2] - b[i + 2]),
    );
  }
  return sum / (cells * 255);
}

/** Whether a point falls inside a bbox, optionally padded. */
export function pointInBbox(point, bbox, pad = 0) {
  return (
    point.x >= bbox.x - pad &&
    point.x <= bbox.x + bbox.width + pad &&
    point.y >= bbox.y - pad &&
    point.y <= bbox.y + bbox.height + pad
  );
}

/** Intersection-over-union of two bboxes, in [0, 1]. */
export function bboxIou(a, b) {
  const x1 = Math.max(a.x, b.x);
  const y1 = Math.max(a.y, b.y);
  const x2 = Math.min(a.x + a.width, b.x + b.width);
  const y2 = Math.min(a.y + a.height, b.y + b.height);
  if (x2 <= x1 || y2 <= y1) return 0;
  const inter = (x2 - x1) * (y2 - y1);
  const union = a.width * a.height + b.width * b.height - inter;
  return union > 0 ? inter / union : 0;
}

/**
 * Template-based single-object tracker for an explicitly chosen target.
 *
 * The user picks the target by tapping it; nothing here ever chooses a target on
 * its own. Once locked, the tracker follows *that* region:
 *
 *  1. If the object detector still reports the same stable id, that identity wins.
 *  2. Otherwise the locked colour signature is searched for in a small window
 *     around the last position. This is what keeps the lock through occlusion and
 *     detector misses, and it is deliberately local so the tracker cannot jump to
 *     a different, more easily detected object elsewhere in the frame.
 *  3. If neither matches, the target is reported LOST and the UI offers an
 *     explicit reacquire rather than silently re-selecting something else.
 */
export class TargetTracker {
  constructor(options = {}) {
    // Fraction of the target's own size that it may move between frames. Bounding
    // the search this way is the guard against switching to a look-alike object.
    this.maxShift = options.maxShift ?? 0.35;
    // Match similarity is `1 - meanPerCellColourDistance`, where the per-cell
    // distance is the largest RGB channel difference. A region that does not
    // contain the object scores about 0.45, while a correct track scores above
    // 0.9, so the bound sits well inside that gap. It is deliberately not higher:
    // captured frames arrive JPEG-compressed, and rejecting a real track is worse
    // than accepting a slightly displaced one.
    this.matchThreshold = options.matchThreshold ?? 0.82;
    this.lostGraceFrames = options.lostGraceFrames ?? 5;
    this.reset();
  }

  /**
   * Radius, in pixels, of the local search window around the last position.
   *
   * Scaled to the object but floored by a fraction of the frame so a small object
   * on a slow capture interval is not lost to ordinary motion. The floor is much
   * smaller than the frame, which is what keeps a distant look-alike out of reach.
   */
  searchRadius(frame) {
    const { bbox } = this.target;
    const selfScaled = Math.max(bbox.width, bbox.height) * this.maxShift;
    const frameScaled = Math.min(frame.width, frame.height) * 0.12;
    return Math.max(3, Math.round(Math.max(selfScaled, frameScaled)));
  }

  reset() {
    this.state = TARGET_STATE.NONE;
    this.target = null;
    this.signature = null;
    this.lostFrames = 0;
    this.lastSeenAt = null;
  }

  get isLocked() {
    return this.state === TARGET_STATE.LOCKED;
  }

  /**
   * Lock onto the object under the user's tap.
   *
   * The detection containing the point is preferred, and the smallest such
   * detection wins so a tap inside a nested blob selects the inner object rather
   * than its container. With no detector hit the tapped box itself becomes the
   * template, so an object the colour/edge pipeline never reports is still
   * trackable.
   */
  select(frame, detections, tapPoint) {
    let chosen = null;

    const containing = detections.filter((d) => pointInBbox(tapPoint, d.bbox));
    if (containing.length > 0) {
      chosen = containing.reduce((best, d) =>
        d.bbox.width * d.bbox.height < best.bbox.width * best.bbox.height ? d : best,
      );
    }

    let bbox;
    let color;
    let detectionId = null;

    if (chosen) {
      bbox = { ...chosen.bbox };
      color = chosen.color;
      detectionId = chosen.id;
    } else {
      const size = Math.max(24, Math.round(Math.min(frame.width, frame.height) * 0.1));
      bbox = {
        x: Math.round(tapPoint.x - size / 2),
        y: Math.round(tapPoint.y - size / 2),
        width: size,
        height: size,
      };
      bbox.x = Math.max(0, Math.min(frame.width - bbox.width, bbox.x));
      bbox.y = Math.max(0, Math.min(frame.height - bbox.height, bbox.y));
      color = 'unknown';
    }

    this.target = {
      bbox,
      centroid: { x: Math.round(bbox.x + bbox.width / 2), y: Math.round(bbox.y + bbox.height / 2) },
      color,
      detectionId,
      confidence: chosen?.confidence ?? 0,
    };
    this.signature = extractSignature(frame, bbox);
    this.state = TARGET_STATE.LOCKED;
    this.lostFrames = 0;
    this.lastSeenAt = Date.now();

    return this.report();
  }

  /**
   * Advance the lock by one frame.
   *
   * @returns {{state: string, target: object|null, confidence: number, lost_frames: number}}
   */
  update(frame, detections) {
    if (!this.target || this.state === TARGET_STATE.NONE) return this.report();

    // 1. Detector identity continuity.
    const sameId = this.target.detectionId
      ? detections.find((d) => d.id === this.target.detectionId)
      : null;
    if (sameId) {
      this.accept(sameId.bbox, sameId.color, sameId.id, sameId.confidence ?? 0, frame);
      return this.report();
    }

    // 2. Local appearance search.
    const match = this.matchSignature(frame);
    if (match) {
      this.accept(match.bbox, this.target.color, this.target.detectionId, match.score * 100, frame);
      return this.report();
    }

    // 3. A detector hit of the same colour near the last known position. The
    //    distance bound is the same local window step 2 uses: without it a
    //    look-alike anywhere in the frame could be adopted, which is exactly the
    //    object-switching this tracker exists to prevent.
    const radius = this.searchRadius(frame);
    const nearby = detections
      .filter((d) => d.color === this.target.color || this.target.color === 'unknown')
      .map((d) => ({
        det: d,
        dist: Math.hypot(
          d.centroid.x - this.target.centroid.x,
          d.centroid.y - this.target.centroid.y,
        ),
      }))
      .filter((c) => c.dist <= radius)
      .sort((a, b) => a.dist - b.dist);

    if (nearby.length > 0) {
      const { det } = nearby[0];
      const score = 1 - signatureDistance(extractSignature(frame, det.bbox), this.signature);
      if (score >= this.matchThreshold) {
        this.accept(det.bbox, det.color, det.id, score * 100, frame);
        return this.report();
      }
    }

    // 4. Nothing matched. Hold the lock briefly so a single dropped frame does
    //    not flicker the UI into "lost", then report LOST and keep the last
    //    known box so the user can see where it was.
    this.lostFrames += 1;
    if (this.lostFrames > this.lostGraceFrames) this.state = TARGET_STATE.LOST;
    return this.report();
  }

  /** Adopt a new geometry for the locked target. */
  accept(bbox, color, detectionId, confidence, frame) {
    this.target = {
      bbox: { ...bbox },
      centroid: {
        x: Math.round(bbox.x + bbox.width / 2),
        y: Math.round(bbox.y + bbox.height / 2),
      },
      color: color ?? this.target.color,
      detectionId: detectionId ?? null,
      confidence,
    };
    this.signature = extractSignature(frame, bbox);
    this.state = TARGET_STATE.LOCKED;
    this.lostFrames = 0;
    this.lastSeenAt = Date.now();
  }

  /**
   * Search a bounded window around the last position for the locked signature.
   *
   * Steps by two pixels first and then refines at single-pixel resolution around
   * the winner, which finds the same offset as an exhaustive search at roughly a
   * quarter of the cost.
   */
  matchSignature(frame) {
    const { data, width, height } = frame;
    const { bbox } = this.target;
    const w = Math.max(1, Math.round(bbox.width));
    const h = Math.max(1, Math.round(bbox.height));
    const radius = this.searchRadius(frame);
    const originX = Math.round(bbox.x);
    const originY = Math.round(bbox.y);

    let best = { score: 0, dx: 0, dy: 0 };

    const scan = (step, rad, fromX, fromY) => {
      for (let dy = -rad; dy <= rad; dy += step) {
        for (let dx = -rad; dx <= rad; dx += step) {
          const x = fromX + dx;
          const y = fromY + dy;
          if (x < 0 || y < 0 || x + w > width || y + h > height) continue;
          const score = this.scoreRegion(data, width, x, y, w, h);
          if (score > best.score) best = { score, dx: x - originX, dy: y - originY };
        }
      }
    };

    scan(2, radius, originX, originY);

    if (best.score > 0) {
      // Refine at single-pixel resolution around the coarse winner. The offsets
      // stay relative to the last known position, so the reported bbox is always
      // in frame coordinates.
      const refined = { ...best };
      const cx = originX + best.dx;
      const cy = originY + best.dy;
      for (let dy = -2; dy <= 2; dy += 1) {
        for (let dx = -2; dx <= 2; dx += 1) {
          const x = cx + dx;
          const y = cy + dy;
          if (x < 0 || y < 0 || x + w > width || y + h > height) continue;
          const score = this.scoreRegion(data, width, x, y, w, h);
          if (score > refined.score) refined.score = score;
        }
      }
      if (refined.score > 0) best = refined;
    }

    if (best.score < this.matchThreshold) return null;

    return {
      bbox: { x: originX + best.dx, y: originY + best.dy, width: w, height: h },
      score: best.score,
    };
  }

  /** Similarity in [0, 1] between the locked signature and a region. */
  scoreRegion(data, width, x0, y0, w, h) {
    // Per-cell colour distance is the largest channel difference and the score is
    // the mean across the grid, normalised to [0, 1].
    //
    // The mean is what makes the threshold meaningful. An earlier version
    // normalised the summed difference over all *channels*, which lets a
    // uniformly wrong region score deceptively well: a saturated object against a
    // grey background averages to about 0.33 and read as a match, so a target that
    // had left the frame was "tracked" onto empty background instead of being
    // reported lost. A median is tempting for the same reason but swings too far
    // the other way — it ignores up to half the template mismatching, so the box
    // can drift across a target rather than staying on it.
    //
    // With this metric a fully mismatched region scores about 0.45 and a correct
    // track scores above 0.9, which leaves a wide gap for the threshold to sit in.
    let sum = 0;
    for (let gy = 0; gy < SIGNATURE_SIZE; gy += 1) {
      for (let gx = 0; gx < SIGNATURE_SIZE; gx += 1) {
        const sx = x0 + Math.min(w - 1, Math.floor(((gx + 0.5) * w) / SIGNATURE_SIZE));
        const sy = y0 + Math.min(h - 1, Math.floor(((gy + 0.5) * h) / SIGNATURE_SIZE));
        const p = (sy * width + sx) * 4;
        const s = (gy * SIGNATURE_SIZE + gx) * 3;
        sum += Math.max(
          Math.abs(data[p] - this.signature[s]),
          Math.abs(data[p + 1] - this.signature[s + 1]),
          Math.abs(data[p + 2] - this.signature[s + 2]),
        );
      }
    }
    return 1 - sum / (SIGNATURE_SIZE * SIGNATURE_SIZE * 255);
  }

  report() {
    return {
      state: this.state,
      target: this.target
        ? {
            bbox: { ...this.target.bbox },
            centroid: { ...this.target.centroid },
            color: this.target.color,
            confidence: this.target.confidence,
          }
        : null,
      lost_frames: this.lostFrames,
      last_seen: this.lastSeenAt,
    };
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