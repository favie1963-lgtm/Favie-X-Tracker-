#!/usr/bin/env python3
"""Generate the Favie X Tracker launcher icon and splash assets.

The mark is a capital F beside a crosshair X. The X is two diagonals clipped
to their box with a circular hole knocked out at the crossing, which reads as
a reticle and echoes what the app does (screen capture, object detection).
Everything is black and white.

The mark is built once in its own 56x44 coordinate space and then placed with
a transform, so one geometry serves four contexts that need different sizes:

  adaptive foreground  the mark must fit the 72dp safe zone, and must also
                       fit that zone's inscribed circle, or a round launcher
                       mask clips its corners
  monochrome           reuses the foreground art; the platform tints it
  legacy icon          no mask is applied, so the mark gets its own inset plate
  splash               a vector, emitted by scripts/gen_splash_vector.py

Body text in the app uses src/components/BrandMark.jsx, which mirrors this
geometry.

The splash is a vector rather than a set of PNGs. Generating one bitmap per
density produced five rasters that were byte-identical, drew a warning for
inconsistent density-independent sizing, and added ~120KB to the APK for an
image the user sees for a fraction of a second.

Usage: python3 scripts/generate_brand_assets.py
"""

import math
import os

import cairosvg

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "android", "app", "src", "main", "res")

CANVAS = 108.0
STROKE = 9.0

# The mark, in its own coordinate space.
MARK_W, MARK_H = 56.0, 44.0
F_W = 24.0
X_X, X_W = 28.0, 28.0
X_CX, X_CY = X_X + X_W / 2, MARK_H / 2
KNOB_R = 6.0

# Adaptive: 56x44 centred at 54. Half-diagonal hypot(28,22)=35.6 is under the
# safe circle's radius of 36, so no round mask clips it.
ADAPTIVE_SCALE = 1.0
LEGACY_FRACTION = 0.78  # of the 108 tile

INK = "#FFFFFF"
BG = "#000000"


def _clip(r=KNOB_R):
    """Clip that holds the X inside its box and punches the reticle hole.

    The outer subpath is the box and the inner is the circle, with
    clip-rule=evenodd so the circle subtracts. A mask element is not honoured
    by the SVG rasteriser used here, and a background-coloured disc would show
    through on the transparent adaptive foreground, so a clip path is the only
    option that works in every context.
    """
    return (
        f'<clipPath id="reticle" clipPathUnits="userSpaceOnUse">'
        f'<path clip-rule="evenodd" '
        f'd="M{X_X} 0 H{X_X + X_W} V{MARK_H} H{X_X} Z '
        f"M{X_CX + r} {X_CY} "
        f"A{r} {r} 0 1 0 {X_CX - r} {X_CY} "
        f"A{r} {r} 0 1 0 {X_CX + r} {X_CY} Z\"/>"
        f"</clipPath>"
    )


def _mark(color=INK):
    """The mark, in its own coordinate space."""
    f = (
        f'<rect x="0" y="0" width="{STROKE}" height="{MARK_H}" fill="{color}"/>'
        f'<rect x="0" y="0" width="{F_W}" height="{STROKE}" fill="{color}"/>'
        f'<rect x="0" y="{MARK_H / 2 - STROKE / 2}" width="{F_W - 9}" '
        f'height="{STROKE}" fill="{color}"/>'
    )
    x = (
        f'<g clip-path="url(#reticle)">'
        f'<line x1="{X_X}" y1="0" x2="{X_X + X_W}" y2="{MARK_H}" '
        f'stroke="{color}" stroke-width="{STROKE}"/>'
        f'<line x1="{X_X + X_W}" y1="0" x2="{X_X}" y2="{MARK_H}" '
        f'stroke="{color}" stroke-width="{STROKE}"/>'
        f"</g>"
    )
    return f + x


def _wrap(w, h, body, defs):
    return (
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {w} {h}" '
        f'width="{w}" height="{h}"><defs>{defs}</defs>{body}</svg>'
    )


def _place(scale, cx, cy, color=INK):
    """Scale the mark and centre it on (cx, cy)."""
    ox, oy = cx - MARK_W * scale / 2, cy - MARK_H * scale / 2
    return (
        f'<g transform="translate({round(ox, 3)},{round(oy, 3)}) '
        f'scale({round(scale, 4)})">{_mark(color)}</g>'
    )


def svg_icon(shape, size):
    """Legacy launcher icon: the mark on a black plate inset in the tile.

    The plate is inset rather than filling the tile. Legacy icons are shown
    unmasked, and one that fills every pixel of its square region reads as a
    blocky tile next to the adaptive icons on the same launcher, so the plate
    carries its own rounded (or circular) silhouette with transparent margins.
    """
    inset = 6.0
    span = CANVAS - inset * 2
    if shape == "circle":
        ground = f'<circle cx="{CANVAS / 2}" cy="{CANVAS / 2}" r="{span / 2}" fill="{BG}"/>'
        # The X reaches the mark's bounding-box corners, so a circle needs a
        # smaller mark than a square plate: half-diagonal hypot(28,22)=35.6 must
        # stay inside the radius.
        half_diagonal = math.hypot(MARK_W / 2, MARK_H / 2)
        scale = (span / 2) * 0.94 / half_diagonal
    else:
        ground = (
            f'<rect x="{inset}" y="{inset}" width="{span}" height="{span}" '
            f'rx="{round(span * 0.22, 2)}" ry="{round(span * 0.22, 2)}" fill="{BG}"/>'
        )
        scale = LEGACY_FRACTION * span / MARK_W
    return _wrap(CANVAS, CANVAS, ground + _place(scale, CANVAS / 2, CANVAS / 2), _clip())


def svg_foreground(size):
    """Adaptive foreground: the mark only, on a transparent ground."""
    return _wrap(CANVAS, CANVAS, _place(ADAPTIVE_SCALE, 54, 54), _clip())


def write_png(path, svg, w, h):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    cairosvg.svg2png(
        bytestring=svg.encode(), write_to=path, output_width=w, output_height=h
    )


# density dir -> (legacy px, adaptive px)
MIPMAPS = [
    ("mipmap-mdpi", 48, 108),
    ("mipmap-hdpi", 72, 162),
    ("mipmap-xhdpi", 96, 216),
    ("mipmap-xxhdpi", 144, 324),
    ("mipmap-xxxhdpi", 192, 432),
]


def main():
    count = 0
    for d, legacy, adaptive in MIPMAPS:
        for name, svg, px in (
            ("ic_launcher.png", svg_icon("square", legacy), legacy),
            ("ic_launcher_round.png", svg_icon("circle", legacy), legacy),
            ("ic_launcher_foreground.png", svg_foreground(adaptive), adaptive),
        ):
            write_png(os.path.join(RES, d, name), svg, px, px)
            count += 1
    print(f"wrote {count} assets under {os.path.relpath(RES, ROOT)}")


if __name__ == "__main__":
    main()