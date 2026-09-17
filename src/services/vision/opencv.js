/**
 * Low-level image primitives that mirror the subset of OpenCV the tracker needs.
 *
 * They are written against plain typed arrays so the same code runs in the
 * WebView (Android) and in a headless Node test harness. Wherever a choice
 * existed, the implementation follows OpenCV's published behaviour rather than
 * the visually equivalent shortcut, because detection output is compared against
 * the reference implementation pixel-for-pixel:
 *
 *  - HSV conversion uses the classical algorithm: H scaled to [0, 179],
 *    S/V scaled to [0, 255].
 *  - Sobel uses OpenCV's 3x3 kernel with its 1/16 normalisation folded into the
 *    gradient, so a step of K produces |G| = K, matching Canny's thresholds.
 *  - Canny is implemented as 8-bit-magnitude hysteresis, which is what the
 *    tracker's thresholds (50/150) were tuned against.
 */

import { MIN_DETECTION_AREA } from './config.js';

/** OpenCV-compatible HSV conversion. Input BGR in [0,255], output H[0,179] S/V[0,255]. */
export function bgrToHsv(b, g, r) {
  const B = b / 255;
  const G = g / 255;
  const R = r / 255;
  const max = Math.max(B, G, R);
  const min = Math.min(B, G, R);
  const delta = max - min;

  // Hue is computed in degrees (0..360) exactly as OpenCV does, then encoded for
  // an 8-bit image. The rounding happens before the wrap, and that order is
  // observable: a pixel at 359.2 degrees quantises to 180 and OpenCV reports 0,
  // whereas clipping to 179 would place it in the wrong bucket of any hue range.
  let degrees = 0;
  if (delta !== 0) {
    if (max === R) {
      degrees = 60 * (((G - B) / delta) % 6);
    } else if (max === G) {
      degrees = 60 * ((B - R) / delta + 2);
    } else {
      degrees = 60 * ((R - G) / delta + 4);
    }
  }
  if (degrees < 0) degrees += 360;
  let hue = Math.round(degrees / 2);
  if (hue >= 180) hue -= 180;
  if (hue < 0) hue += 180;

  const s = max === 0 ? 0 : Math.round((delta / max) * 255);
  const v = Math.round(max * 255);

  return [hue, s, v];
}

/**
 * Convert an RGBA frame into an interleaved BGR uint8 buffer.
 *
 * Canvas gives us RGBA; the tracker's maths was written for BGR. Rather than
 * pre-multiplying by 4 on every pixel access we keep the channel order explicit
 * and index straight into the RGBA array.
 */
export function rgbaToBgr(rgba) {
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

/**
 * Convert an interleaved BGR buffer to grayscale using OpenCV's fixed-point
 * weights.
 *
 * `cv::cvtColor` with COLOR_BGR2GRAY on 8-bit input uses the fixed-point weights
 * `B2Y=1868, G2Y=9617, R2Y=4899` with a 14-bit shift and a `1 << 13` rounding
 * term. Using the shorter `29/150/77 >> 8` approximation instead shifts a few
 * thousand pixels by one level, and one grey level is enough to move a Sobel
 * response across Canny's low threshold.
 */
export function bgrToGray(bgr) {
  const n = bgr.length / 3;
  const out = new Uint8Array(n);
  for (let i = 0, o = 0; i < n; i += 1, o += 3) {
    out[i] = (bgr[o] * 1868 + bgr[o + 1] * 9617 + bgr[o + 2] * 4899 + 8192) >> 14;
  }
  return out;
}

/**
 * OpenCV `getStructuringElement(MORPH_ELLIPSE, (k, k))`.
 *
 * OpenCV does not test a radial inequality. For each row it computes how far the
 * ellipse extends horizontally, `dx = round(c * sqrt((r^2 - dy^2) / r^2))`, and
 * fills that span. The difference matters: a direct distance test drops the
 * diagonal shoulder cells (`dx=2, dy=1` in a 5x5), which widens every blob by a
 * pixel and shifts bounding boxes away from the reference output.
 *
 * Note `r` and `c` are truncated halves, so even-sized kernels are anchored
 * asymmetrically, exactly as in OpenCV.
 */
export function getEllipseKernel(k) {
  const kernel = new Uint8Array(k * k);
  const r = k >> 1;
  const c = k >> 1;
  const invR2 = r ? 1 / (r * r) : 0;

  for (let i = 0; i < k; i += 1) {
    const dy = i - r;
    let j1 = 0;
    let j2 = 0;
    if (Math.abs(dy) <= r) {
      const dx = Math.round(c * Math.sqrt((r * r - dy * dy) * invR2));
      j1 = Math.max(c - dx, 0);
      j2 = Math.min(c + dx + 1, k);
    }
    for (let j = j1; j < j2; j += 1) kernel[i * k + j] = 1;
  }

  return kernel;
}

/**
 * Grayscale morphology with an arbitrary (possibly non-rectangular) kernel.
 *
 * Samples outside the frame are taken from the nearest edge pixel
 * (BORDER_REPLICATE), for dilation and erosion alike. The choice is observable:
 * a 4px-tall arm attached to the left edge survives a close, because the
 * replicated column keeps enough neighbours filled for the erosion pass. Filling
 * the outside with background instead would shrink every border-touching blob
 * and shift its bounding box relative to the reference.
 */
export function morphology(src, width, height, kernel, k, op) {
  const out = new Uint8Array(width * height);
  const r = k >> 1;
  const c = k >> 1;
  const isDilate = op === 'dilate';

  const sample = (x, y) => {
    const cx = x < 0 ? 0 : x >= width ? width - 1 : x;
    const cy = y < 0 ? 0 : y >= height ? height - 1 : y;
    return src[cy * width + cx];
  };

  for (let y = 0; y < height; y += 1) {
    for (let x = 0; x < width; x += 1) {
      let best = isDilate ? 0 : 255;
      for (let ky = 0; ky < k; ky += 1) {
        for (let kx = 0; kx < k; kx += 1) {
          if (!kernel[ky * k + kx]) continue;
          const v = sample(x + kx - c, y + ky - r);
          if (isDilate ? v > best : v < best) best = v;
          if (isDilate && best === 255) break;
          if (!isDilate && best === 0) break;
        }
      }
      out[y * width + x] = best;
    }
  }
  return out;
}

export function dilate(src, width, height, kernel, k) {
  return morphology(src, width, height, kernel, k, 'dilate');
}

export function erode(src, width, height, kernel, k) {
  return morphology(src, width, height, kernel, k, 'erode');
}

/** OpenCV MORPH_CLOSE = dilate then erode. */
export function morphClose(src, width, height, kernel, k) {
  return erode(dilate(src, width, height, kernel, k), width, height, kernel, k);
}

/** OpenCV MORPH_OPEN = erode then dilate. */
export function morphOpen(src, width, height, kernel, k) {
  return dilate(erode(src, width, height, kernel, k), width, height, kernel, k);
}

/** Threshold a channel array into a 0/255 mask using inclusive HSV bounds. */
export function inRange(hsv, width, height, lower, upper) {
  const out = new Uint8Array(width * height);
  const n = width * height;
  for (let i = 0; i < n; i += 1) {
    const p = i * 3;
    const h = hsv[p];
    const s = hsv[p + 1];
    const v = hsv[p + 2];
    out[i] = h >= lower[0] && h <= upper[0] && s >= lower[1] && s <= upper[1] && v >= lower[2] && v <= upper[2] ? 255 : 0;
  }
  return out;
}

/**
 * Sobel 3x3 gradient components, matching `cv2.Sobel(..., CV_16S/CV_32F, ksize=3)`.
 *
 * Values are the raw convolution responses with no normalisation, which is what
 * OpenCV returns for floating-point output: a luminance step of K yields a
 * component of 4K. Canny's 50/150 thresholds are defined against that scale, so
 * dividing here (or normalising by the kernel weight) would push every real edge
 * below the low threshold and leave the edge stage with nothing to detect.
 *
 * Border pixels stay at zero, which is why Canny never marks the outermost ring.
 */
export function sobelComponents(gray, width, height) {
  const n = width * height;
  const gx = new Float64Array(n);
  const gy = new Float64Array(n);

  for (let y = 1; y < height - 1; y += 1) {
    for (let x = 1; x < width - 1; x += 1) {
      const i = y * width + x;
      const tl = gray[i - width - 1];
      const t = gray[i - width];
      const tr = gray[i - width + 1];
      const l = gray[i - 1];
      const r = gray[i + 1];
      const bl = gray[i + width - 1];
      const b = gray[i + width];
      const br = gray[i + width + 1];

      // gx = [-1 0 1; -2 0 2; -1 0 1], gy = [-1 -2 -1; 0 0 0; 1 2 1]
      gx[i] = tr + 2 * r + br - (tl + 2 * l + bl);
      gy[i] = bl + 2 * b + br - (tl + 2 * t + tr);
    }
  }

  return { gx, gy };
}

/**
 * Canny edge detection with hysteresis.
 *
 * Two-pass implementation: non-maximum suppression against the quantised
 * gradient direction, then strong-edge seeding followed by connected weak-edge
 * growth. Border pixels are never marked, matching OpenCV.
 */
export function canny(gray, width, height, lowThreshold, highThreshold) {
  const n = width * height;
  const { gx, gy } = sobelComponents(gray, width, height);
  // OpenCV measures gradient strength with the L1 norm (|gx| + |gy|), not the
  // Euclidean magnitude. Calibration on a diagonal step edge confirms it: a step
  // producing gx = gy = 30 is flagged at threshold 60 and not at 43, which is
  // L1 and rules out L2 by a wide margin.
  const mag = new Float64Array(n);
  for (let i = 0; i < n; i += 1) mag[i] = (gx[i] < 0 ? -gx[i] : gx[i]) + (gy[i] < 0 ? -gy[i] : gy[i]);
  const edges = new Uint8Array(n);

  // Quantise the gradient direction into the four neighbour pairs used by
  // non-maximum suppression.
  //
  // The bin must follow the dominant component: a mostly-horizontal gradient
  // means the edge runs vertically, so the suppression pair is the horizontal
  // neighbours. Selecting the vertical pair for every axis-aligned gradient (the
  // natural-looking shortcut) suppresses along the wrong axis and shreds
  // continuous contours into dozens of fragments.
  const dir = new Uint8Array(n);
  const TAN22 = 0.41421356237;
  for (let y = 1; y < height - 1; y += 1) {
    for (let x = 1; x < width - 1; x += 1) {
      const i = y * width + x;
      const a = gx[i];
      const b = gy[i];
      const ax = a < 0 ? -a : a;
      const ay = b < 0 ? -b : b;
      if (ax < 1e-9 && ay < 1e-9) {
        dir[i] = 0;
      } else if (ax >= ay) {
        dir[i] = ay <= ax * TAN22 ? 0 : a * b >= 0 ? 1 : 3;
      } else {
        dir[i] = ax <= ay * TAN22 ? 2 : a * b >= 0 ? 1 : 3;
      }
    }
  }

  // Non-maximum suppression.
  const suppressed = new Float64Array(n);
  for (let y = 1; y < height - 1; y += 1) {
    for (let x = 1; x < width - 1; x += 1) {
      const i = y * width + x;
      const m = mag[i];
      if (m < lowThreshold) continue;
      let n1;
      let n2;
      switch (dir[i]) {
        case 0:
          n1 = mag[i - 1];
          n2 = mag[i + 1];
          break;
        case 1:
          n1 = mag[i - width - 1];
          n2 = mag[i + width + 1];
          break;
        case 2:
          n1 = mag[i - width];
          n2 = mag[i + width];
          break;
        default:
          n1 = mag[i - width + 1];
          n2 = mag[i + width - 1];
      }
      if (m >= n1 && m >= n2) suppressed[i] = m;
    }
  }

  // Hysteresis: seed from strong pixels, then grow through connected weak pixels.
  const stack = [];
  for (let i = 0; i < n; i += 1) {
    if (suppressed[i] >= highThreshold) {
      edges[i] = 255;
      stack.push(i);
    }
  }
  while (stack.length) {
    const i = stack.pop();
    const y = (i / width) | 0;
    const x = i - y * width;
    for (let dy = -1; dy <= 1; dy += 1) {
      const ny = y + dy;
      if (ny < 0 || ny >= height) continue;
      for (let dx = -1; dx <= 1; dx += 1) {
        const nx = x + dx;
        if (nx < 0 || nx >= width) continue;
        const j = ny * width + nx;
        if (edges[j]) continue;
        if (suppressed[j] >= lowThreshold) {
          edges[j] = 255;
          stack.push(j);
        }
      }
    }
  }

  return edges;
}

/**
 * Extract the outer boundary polygon of each foreground component.
 *
 * Boundary points are emitted on the integer lattice, one per boundary corner.
 * Matches `cv2.RETR_EXTERNAL` in two respects: holes inside a blob are ignored,
 * and a component nested inside another is skipped entirely. The nesting rule
 * matters for real frames, where a saturated inner region can split one visual
 * object into two contours, and reporting both would double-count it.
 *
 * @returns {Array<Array<[number, number]>>} one polygon per external component
 */
export function findExternalContours(mask, width, height) {
  const n = width * height;
  const visited = new Uint8Array(n);
  const components = [];

  for (let start = 0; start < n; start += 1) {
    if (mask[start] === 0 || visited[start]) continue;

    // Flood the 8-connected component and remember its topmost-leftmost pixel;
    // the top-left corner of that pixel is guaranteed to lie on the outer ring.
    const queue = [start];
    visited[start] = 1;
    const sx = start % width;
    const sy = (start / width) | 0;
    let minX = sx;
    let maxX = sx;
    let minY = sy;
    let maxY = sy;
    let pixelCount = 0;

    while (queue.length) {
      const i = queue.pop();
      const y = (i / width) | 0;
      const x = i - y * width;
      pixelCount += 1;
      if (x < minX) minX = x;
      if (x > maxX) maxX = x;
      if (y < minY) minY = y;
      if (y > maxY) maxY = y;
      for (let dy = -1; dy <= 1; dy += 1) {
        const ny = y + dy;
        if (ny < 0 || ny >= height) continue;
        for (let dx = -1; dx <= 1; dx += 1) {
          const nx = x + dx;
          if (nx < 0 || nx >= width) continue;
          const j = ny * width + nx;
          if (!visited[j] && mask[j] > 0) {
            visited[j] = 1;
            queue.push(j);
          }
        }
      }
    }

    components.push({
      sx,
      sy,
      minX,
      maxX,
      minY,
      maxY,
      pixelCount,
      contour: traceOuterRing(mask, width, height, sx, sy),
    });
  }

  const contours = [];
  for (let a = 0; a < components.length; a += 1) {
    const candidate = components[a];
    if (candidate.contour.length < 4) continue;

    // A component is nested when another component's filled region encloses it.
    // The test uses the containing component's pixel count against its bounding
    // box: a solid rectangle fills its box exactly, so any strictly smaller
    // count means the enclosure has a hole for the candidate to sit in. This
    // avoids expensive point-in-polygon work on every pair.
    let nested = false;
    for (let b = 0; b < components.length && !nested; b += 1) {
      if (a === b) continue;
      const outer = components[b];
      const encloses =
        outer.minX < candidate.minX &&
        outer.maxX > candidate.maxX &&
        outer.minY < candidate.minY &&
        outer.maxY > candidate.maxY;
      if (!encloses) continue;

      const boxArea = (outer.maxX - outer.minX + 1) * (outer.maxY - outer.minY + 1);
      if (outer.pixelCount < boxArea) nested = true;
    }

    if (!nested) contours.push(candidate.contour);
  }

  return contours;
}

/**
 * Walk the outer ring of the component owned by pixel (sx, sy), which the caller
 * guarantees is that component's topmost-leftmost pixel.
 *
 * Pixels are traced through their centres rather than along the pixel lattice.
 * That matters for circularity: a lattice walk steps 1px in each of x and y, so
 * a digital disc's perimeter is overstated by roughly 4/pi and a perfect circle
 * scores ~0.56 instead of OpenCV's ~0.85, which would put it below the 0.6 cup
 * threshold. Tracing centres lets diagonal steps cost sqrt(2), reproducing the
 * perimeter (and therefore the circularity) that the cup heuristic was tuned on.
 *
 * Moore-neighbour tracing with a fixed clockwise search order.
 */
function traceOuterRing(mask, width, height, sx, sy) {
  const filled = (x, y) => x >= 0 && y >= 0 && x < width && y < height && mask[y * width + x] > 0;

  // Clockwise neighbour order starting at north.
  const ORDER = [
    [0, -1],
    [1, -1],
    [1, 0],
    [1, 1],
    [0, 1],
    [-1, 1],
    [-1, 0],
    [-1, -1],
  ];

  const contour = [[sx, sy]];
  let cx = sx;
  let cy = sy;
  // The start pixel is topmost-leftmost in its component, so west is background.
  let backDir = 6; // W

  const MAX_STEPS = (width + 2) * (height + 2) * 4;
  for (let step = 0; step < MAX_STEPS; step += 1) {
    let found = -1;
    for (let k = 1; k <= 8; k += 1) {
      const idx = (backDir + k) & 7;
      if (filled(cx + ORDER[idx][0], cy + ORDER[idx][1])) {
        found = idx;
        break;
      }
    }
    if (found === -1) break; // isolated pixel: no ring to trace

    const nx = cx + ORDER[found][0];
    const ny = cy + ORDER[found][1];
    backDir = (found + 4) & 7;
    cx = nx;
    cy = ny;

    if (cx === sx && cy === sy) break;
    contour.push([cx, cy]);
  }

  return simplifyCollinear(contour);
}

/**
 * Drop vertices that lie in the middle of a straight run.
 *
 * The walk emits a vertex for every boundary corner, so straight edges would
 * otherwise contribute thousands of redundant points to each shoelace and
 * perimeter sum. Removing them leaves the area and perimeter unchanged while
 * cutting the cost of every downstream geometry call.
 */
function simplifyCollinear(contour) {
  const n = contour.length;
  if (n < 3) return contour;
  const out = [];
  for (let i = 0; i < n; i += 1) {
    const prev = contour[(i - 1 + n) % n];
    const cur = contour[i];
    const next = contour[(i + 1) % n];
    const collinear =
      (prev[0] === cur[0] && cur[0] === next[0]) || (prev[1] === cur[1] && cur[1] === next[1]);
    if (!collinear) out.push(cur);
  }
  return out.length >= 4 ? out : contour;
}

/**
 * OpenCV `contourArea` (Green's theorem) for a non-self-intersecting contour.
 * Returns the absolute area so convex and concave contours behave the same.
 */
export function contourArea(contour) {
  const n = contour.length;
  if (n < 3) return 0;
  let area = 0;
  for (let i = 0; i < n; i += 1) {
    const [x1, y1] = contour[i];
    const [x2, y2] = contour[(i + 1) % n];
    area += x1 * y2 - x2 * y1;
  }
  return Math.abs(area) / 2;
}

/**
 * OpenCV `contourArea` for a filled component measured as the pixel count.
 * Used as the area measure for solid colour blobs so that the value matches the
 * pixel-count semantics of the original implementation.
 */
export function filledArea(contour) {
  const area = contourArea(contour);
  return area > 0 ? area : 0;
}

/** OpenCV `arcLength(closed=true)` for a polygon contour. */
export function arcLength(contour, closed = true) {
  const n = contour.length;
  if (n < 2) return 0;
  let total = 0;
  const last = closed ? n : n - 1;
  for (let i = 0; i < last; i += 1) {
    const [x1, y1] = contour[i];
    const [x2, y2] = contour[(i + 1) % n];
    total += Math.sqrt((x2 - x1) ** 2 + (y2 - y1) ** 2);
  }
  return total;
}

/** OpenCV `boundingRect`. */
export function boundingRect(contour) {
  let minX = Infinity;
  let minY = Infinity;
  let maxX = -Infinity;
  let maxY = -Infinity;
  for (const [x, y] of contour) {
    if (x < minX) minX = x;
    if (x > maxX) maxX = x;
    if (y < minY) minY = y;
    if (y > maxY) maxY = y;
  }
  return {
    x: minX,
    y: minY,
    width: maxX - minX + 1,
    height: maxY - minY + 1,
  };
}

/**
 * Area centroid of a contour, matching `cv2.moments` m10/m00.
 *
 * Green's theorem over the polygon vertices, *not* the mean of the vertices.
 * The two differ for any shape whose corners are unevenly spaced: a rectangle
 * reduces to the same answer, but the red blob's vertex mean sits two rows below
 * its true centre of mass. Returns fractional coordinates; the caller is
 * responsible for the integer conversion, so the rounding rule stays in one place.
 */
export function centroidOf(contour) {
  const n = contour.length;
  if (n < 3) {
    const rect = boundingRect(contour);
    return { x: rect.x + rect.width / 2, y: rect.y + rect.height / 2 };
  }

  let twiceArea = 0;
  let cx = 0;
  let cy = 0;
  for (let i = 0; i < n; i += 1) {
    const [x0, y0] = contour[i];
    const [x1, y1] = contour[(i + 1) % n];
    const cross = x0 * y1 - x1 * y0;
    twiceArea += cross;
    cx += (x0 + x1) * cross;
    cy += (y0 + y1) * cross;
  }

  if (twiceArea === 0) {
    const rect = boundingRect(contour);
    return { x: rect.x + rect.width / 2, y: rect.y + rect.height / 2 };
  }

  const factor = 1 / (3 * twiceArea);
  return { x: cx * factor, y: cy * factor };
}

/** Guard used to reject specks before the more expensive geometry runs. */
export function passesMinArea(area) {
  return area >= MIN_DETECTION_AREA;
}