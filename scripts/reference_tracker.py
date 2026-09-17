"""
Reference implementation of the original detection pipeline (app/tracking.py).

Used only as an offline oracle for the JavaScript port. It emits the same
geometric fields the tracker reports so `scripts/vision-parity.mjs` can compare
the two implementations on an identical frame.

Usage:
    python3 scripts/reference_tracker.py <output-dir>

Writes:
    <output-dir>/fixture.bgr   raw interleaved BGR bytes of the test frame
    <output-dir>/meta.json     { width, height }
    <output-dir>/reference.json  list of detections (geometry only, no ids)
"""

import json
import os
import sys

import cv2
import numpy as np

COLOR_RANGES = {
    "red": ((0, 100, 100), (10, 255, 255)),
    "blue": ((100, 100, 100), (130, 255, 255)),
    "yellow": ((20, 100, 100), (30, 255, 255)),
    "green": ((40, 100, 100), (80, 255, 255)),
}

WIDTH, HEIGHT = 320, 240


def build_fixture():
    """Deterministic synthetic scene exercising colour blobs and edges."""
    rng = np.random.default_rng(20260917)
    frame = np.zeros((HEIGHT, WIDTH, 3), np.uint8)

    # Vertical gradient background so Canny has a smooth ramp to work with.
    for y in range(HEIGHT):
        frame[y, :] = 30 + int(120 * y / HEIGHT)

    # Colour blobs (BGR) that sit squarely inside the default HSV windows.
    cv2.circle(frame, (60, 60), 24, (0, 0, 220), -1)      # red
    cv2.circle(frame, (170, 70), 28, (220, 60, 0), -1)    # blue
    cv2.circle(frame, (255, 130), 22, (0, 220, 230), -1)  # yellow
    cv2.circle(frame, (95, 180), 26, (40, 200, 40), -1)   # green
    cv2.rectangle(frame, (200, 180), (290, 225), (0, 0, 220), -1)  # red square

    # Edge-only structure: a light square outline on the dark background.
    cv2.rectangle(frame, (20, 130), (70, 170), (240, 240, 240), 2)

    noise = rng.integers(-6, 7, size=frame.shape, dtype=np.int16)
    frame = np.clip(frame.astype(np.int16) + noise, 0, 255).astype(np.uint8)
    return frame


def detect(frame):
    """The original detection pipeline, geometry only."""
    detections = []
    hsv = cv2.cvtColor(frame, cv2.COLOR_BGR2HSV)
    kernel = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (5, 5))

    for name, (lower, upper) in COLOR_RANGES.items():
        mask = cv2.inRange(hsv, np.array(lower), np.array(upper))
        mask = cv2.morphologyEx(mask, cv2.MORPH_CLOSE, kernel)
        mask = cv2.morphologyEx(mask, cv2.MORPH_OPEN, kernel)
        contours, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)

        for contour in contours:
            area = cv2.contourArea(contour)
            if area < 100:
                continue
            x, y, w, h = cv2.boundingRect(contour)
            m = cv2.moments(contour)
            if m["m00"] != 0:
                cx = int(m["m10"] / m["m00"])
                cy = int(m["m01"] / m["m00"])
            else:
                cx, cy = x + w // 2, y + h // 2
            perimeter = cv2.arcLength(contour, True)
            circularity = 0.0 if perimeter == 0 else 4 * np.pi * area / (perimeter * perimeter)
            detections.append({
                "color": name,
                "bbox": {"x": x, "y": y, "width": w, "height": h},
                "centroid": {"x": cx, "y": cy},
                "area": float(area),
                "circularity": float(circularity),
                "confidence": float(min(circularity * 100, 100)),
            })

    gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
    edges = cv2.Canny(gray, 50, 150)
    contours, _ = cv2.findContours(edges, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)

    for contour in contours[:5]:
        area = cv2.contourArea(contour)
        if 500 < area < 50000:
            x, y, w, h = cv2.boundingRect(contour)
            aspect = float(w) / h if h > 0 else 0
            if 0.7 < aspect < 1.3:
                m = cv2.moments(contour)
                if m["m00"] != 0:
                    cx = int(m["m10"] / m["m00"])
                    cy = int(m["m01"] / m["m00"])
                else:
                    cx, cy = x + w // 2, y + h // 2
                detections.append({
                    "color": "unknown",
                    "bbox": {"x": x, "y": y, "width": w, "height": h},
                    "centroid": {"x": cx, "y": cy},
                    "area": float(area),
                    "confidence": float(min(80, 100)),
                })

    return detections


def main():
    out_dir = sys.argv[1] if len(sys.argv) > 1 else "."
    os.makedirs(out_dir, exist_ok=True)

    frame = build_fixture()
    detect(frame)

    with open(os.path.join(out_dir, "fixture.bgr"), "wb") as fh:
        fh.write(frame.tobytes())
    with open(os.path.join(out_dir, "meta.json"), "w") as fh:
        json.dump({"width": WIDTH, "height": HEIGHT}, fh)
    with open(os.path.join(out_dir, "reference.json"), "w") as fh:
        json.dump(detect(frame), fh, indent=2)

    print(f"wrote fixture + {len(detect(frame))} reference detections to {out_dir}")


if __name__ == "__main__":
    main()