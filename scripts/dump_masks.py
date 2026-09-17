import json
import os
import sys

import cv2
import numpy as np

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "scripts"))
from reference_tracker import build_fixture, COLOR_RANGES  # noqa: E402

out_dir = sys.argv[1] if len(sys.argv) > 1 else "/tmp/parity"
os.makedirs(out_dir, exist_ok=True)

frame = build_fixture()
hsv = cv2.cvtColor(frame, cv2.COLOR_BGR2HSV)
kernel = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (5, 5))
meta = {}

for name, (lower, upper) in COLOR_RANGES.items():
    mask = cv2.inRange(hsv, np.array(lower), np.array(upper))
    mask = cv2.morphologyEx(mask, cv2.MORPH_CLOSE, kernel)
    mask = cv2.morphologyEx(mask, cv2.MORPH_OPEN, kernel)
    with open(os.path.join(out_dir, f"mask_{name}.raw"), "wb") as fh:
        fh.write(mask.tobytes())
    n, labels, stats, _ = cv2.connectedComponentsWithStats((mask > 0).astype(np.uint8), connectivity=8)
    comps = []
    for i in range(1, n):
        x, y, w, h, area = stats[i]
        comps.append({"x": int(x), "y": int(y), "w": int(w), "h": int(h), "area": int(area)})
    meta[name] = {
        "pixels": int((mask > 0).sum()),
        "components": comps,
        "contours": len(cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)[0]),
    }

gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
edges = cv2.Canny(gray, 50, 150)
with open(os.path.join(out_dir, "edges.raw"), "wb") as fh:
    fh.write(edges.tobytes())
with open(os.path.join(out_dir, "gray.raw"), "wb") as fh:
    fh.write(gray.tobytes())
meta["edges_pixels"] = int((edges > 0).sum())

# Edge contours after the tracker's filters, for reference
contours, _ = cv2.findContours(edges, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
edge_kept = []
for c in contours[:5]:
    area = cv2.contourArea(c)
    if 500 < area < 50000:
        x, y, w, h = cv2.boundingRect(c)
        asp = float(w) / h if h > 0 else 0
        if 0.7 < asp < 1.3:
            edge_kept.append({"area": float(area), "bbox": [int(x), int(y), int(w), int(h)]})
meta["edge_kept"] = edge_kept
meta["edge_contour_count"] = len(contours)

with open(os.path.join(out_dir, "maskmeta.json"), "w") as fh:
    json.dump(meta, fh, indent=2)

print(json.dumps(meta, indent=2))