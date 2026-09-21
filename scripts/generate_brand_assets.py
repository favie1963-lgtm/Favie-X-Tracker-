#!/usr/bin/env python3
"""Generate the Favie X Tracker launcher icon and splash raster assets.

The mark is a faceless mask: a dark rounded-square silhouette with two glowing red
eyes and a white grin. Vertical dark tears run down from each eye, so the face reads
as menacing rather than friendly. There is no nose, no outline and no other facial
feature — the eyes and the grin are the whole face, which is what keeps the shape
legible at 48dp where detail would otherwise disappear.

Nothing in this file is anti-aliasing-dependent: every shape is a solid fill or a
stroke over a flat ground, so the rasteriser produces a clean result at every
density.

The mark is built once in its own 32x32 coordinate space and then placed with a
transform, so one geometry serves four contexts that need different sizes:

  adaptive foreground  the mark must fit the 72dp safe zone, and must also fit
                       that zone's inscribed circle, or a round launcher mask clips
                       its corners
  monochrome           reuses the foreground art; the platform tints it to a single
                       colour, so the red eyes and the white grin collapse to one
                       tone — the silhouette is what carries the mark there
  legacy icon          no mask is applied, so the mark gets its own inset plate
  splash               a vector, emitted by scripts/gen_splash_vector.py

Body text in the app uses src/components/BrandMark.jsx, which mirrors this
geometry and keeps the colours, so the in-app header and the icon stay identical.

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

# The mark, in its own coordinate space.
MARK = 32.0
CORNER = 7.0

EYE_R = 3.7
EYE_CY = 12.8
EYE_DX = 7.2

# Tear: a thin tapering streak hanging out of the lower lid.
TEAR_TOP_GAP = 2.6
TEAR_LEN = 7.4
TEAR_W = 1.05
TEAR_TIP_SHRINK = 0.55

GRIN_TOP = 20.0
GRIN_CTRL = 26.4
GRIN_BOTTOM = 22.4
GRIN_HALF = 9.0
GRIN_WIDTH = 1.9

# Adaptive: the mark is 32 wide, and its centre is 19.8 from the tile centre, under
# the 72dp safe zone's inscribed radius of 36, so no round mask clips it.
ADAPTIVE_SCALE = 1.0
LEGACY_FRACTION = 0.72  # of the 108 tile

# Face ground: near-black, dark enough to read as a silhouette against any
# launcher wallpaper but not pure black, so it stays distinct from a black
# background.
FACE = "#141416"
# Adaptive foreground ground. Must be opaque: it is composited over a transparent
# layer, so a translucent face would let the wallpaper through the whole shape.
FACE_SOLID = "#141416"
INK = "#141416"

EYE = "#FF2D3F"
GRIN = "#FFFFFF"
TEAR = "#FFFFFF"


def _face(ground=FACE):
    return (
        f'<rect x="0" y="0" width="{MARK}" height="{MARK}" '
        f'rx="{CORNER}" ry="{CORNER}" fill="{ground}"/>'
    )


def _eyes():
    parts = []
    for dx in (-EYE_DX, EYE_DX):
        parts.append(
            f'<circle cx="{MARK / 2 + dx}" cy="{EYE_CY}" r="{EYE_R}" fill="{EYE}"/>'
        )
    return "".join(parts)


def _tears():
    """A tapering streak out of each eye's lower lid."""
    parts = []
    for dx in (-EYE_DX, EYE_DX):
        cx = MARK / 2 + dx
        top = EYE_CY + EYE_R + TEAR_TOP_GAP
        bot = top + TEAR_LEN
        w_top = TEAR_W / 2
        w_bot = w_top * TEAR_TIP_SHRINK
        parts.append(
            f'<path fill="{TEAR}" d="'
            f"M{cx - w_top},{top} L{cx + w_top},{top} "
            f"L{cx + w_bot},{bot} L{cx - w_bot},{bot} Z\"/>"
        )
    return "".join(parts)


def _grin():
    """An upward-curving grin with square ends.

    A stroked quadratic has round caps on some rasterisers, which made the grin
    read as a pipe. Filling the region between two quads instead gives the ends the
    same flat edge as the drawing in BrandMark.jsx.
    """
    cx = MARK / 2
    return (
        f'<path fill="{GRIN}" d="'
        f"M{cx - GRIN_HALF},{GRIN_TOP} "
        f"Q{cx},{GRIN_CTRL} {cx + GRIN_HALF},{GRIN_TOP} "
        f"L{cx + GRIN_HALF},{GRIN_BOTTOM} "
        f"Q{cx},{GRIN_CTRL + GRIN_WIDTH} {cx - GRIN_HALF},{GRIN_BOTTOM} Z\"/>"
    )


def _mark(ground=FACE):
    return _face(ground) + _tears() + _eyes() + _grin()


def _wrap(w, h, body):
    return (
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {w} {h}" '
        f'width="{w}" height="{h}">{body}</svg>'
    )


def _place(scale, cx, cy, ground=FACE):
    """Scale the mark and centre it on (cx, cy)."""
    ox, oy = cx - MARK * scale / 2, cy - MARK * scale / 2
    return (
        f'<g transform="translate({round(ox, 3)},{round(oy, 3)}) '
        f'scale({round(scale, 4)})">{_mark(ground)}</g>'
    )


def svg_icon(shape, size):
    """Legacy launcher icon: the face inset on a black plate.

    The plate is inset rather than filling the tile. Legacy icons are shown
    unmasked, and one that fills every pixel of its square region reads as a
    blocky tile next to the adaptive icons on the same launcher, so the plate
    carries its own rounded (or circular) silhouette with transparent margins.
    """
    inset = 6.0
    span = CANVAS - inset * 2
    if shape == "circle":
        ground = f'<circle cx="{CANVAS / 2}" cy="{CANVAS / 2}" r="{span / 2}" fill="{INK}"/>'
        scale = (span / 2) * 0.9 / (MARK / 2 * math.sqrt(2))
    else:
        ground = (
            f'<rect x="{inset}" y="{inset}" width="{span}" height="{span}" '
            f'rx="{round(span * 0.22, 2)}" ry="{round(span * 0.22, 2)}" fill="{INK}"/>'
        )
        scale = LEGACY_FRACTION * span / MARK
    return _wrap(CANVAS, CANVAS, ground + _place(scale, CANVAS / 2, CANVAS / 2))


def svg_foreground(size):
    """Adaptive foreground: the face only, centred in the safe zone.

    The ground is opaque here because the adaptive foreground is a separate layer
    composited over the background colour; leaving it transparent would show the
    background through the face and the mark would read as eyes and a grin floating
    on nothing.
    """
    return _wrap(CANVAS, CANVAS, _place(ADAPTIVE_SCALE, 54, 54, FACE_SOLID))


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