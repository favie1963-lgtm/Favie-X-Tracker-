"""Emit the Android vector drawables used by the splash screen.

Two assets are produced:

  splash_icon.xml   the mark, sized for the Android 12+ system splash slot
  splash_mark.xml   the mark plus a baseline rule, used as the launch background so
                    pre-Android-12 launches show the same composed screen rather
                    than a bare mark

The mark is the faceless mask: a dark rounded square with two glowing red eyes, a
white grin and a tear under each eye. It is drawn with the same geometry as
scripts/generate_brand_assets.py and src/components/BrandMark.jsx, so the launch
screen, the launcher icon and the in-app header all show the same face.

The system masks the splash icon to a circle, so the mark is scaled to 80% to stay
well inside that mask.

Prints the splash icon on stdout; regenerate both with:
    python3 scripts/gen_splash_vector.py > android/app/src/main/res/drawable/splash_icon.xml
    python3 scripts/gen_splash_vector.py --mark > android/app/src/main/res/drawable/splash_mark.xml
"""

import math
import sys

VIEW = 108.0

MARK = 32.0
CORNER = 7.0

EYE_R = 3.7
EYE_CY = 12.8
EYE_DX = 7.2

TEAR_TOP_GAP = 2.6
TEAR_LEN = 7.4
TEAR_W = 1.05
TEAR_TIP_SHRINK = 0.55

GRIN_TOP = 20.0
GRIN_CTRL = 26.4
GRIN_BOTTOM = 22.4
GRIN_HALF = 9.0
GRIN_WIDTH = 1.9

SCALE = 0.8  # keeps the mark inside the circular mask the system applies

RULE_GAP = 16.0  # between the foot of the mark and the rule
RULE_H = 1.5

FACE = "#FF141416"
EYE = "#FFFF2D3F"
GRIN = "#FFFFFFFF"
TEAR = "#FFFFFFFF"
RULE = "#66FF2D3F"


def _round_rect(ox, oy, scale):
    """The face plate as a rounded rectangle.

    VectorDrawable has no rounded-rect primitive, so the corners are four
    quarter-circle arcs joined by straight edges.
    """
    size = MARK * scale
    c = CORNER * scale
    x0, y0 = ox, oy
    x1, y1 = ox + size, oy + size
    parts = [f"M{round(x0 + c, 2)},{round(y0, 2)}"]
    parts.append(f"L{round(x1 - c, 2)},{round(y0, 2)}")
    parts.append(f"A{round(c, 2)},{round(c, 2)} 0 0 1 {round(x1, 2)},{round(y0 + c, 2)}")
    parts.append(f"L{round(x1, 2)},{round(y1 - c, 2)}")
    parts.append(f"A{round(c, 2)},{round(c, 2)} 0 0 1 {round(x1 - c, 2)},{round(y1, 2)}")
    parts.append(f"L{round(x0 + c, 2)},{round(y1, 2)}")
    parts.append(f"A{round(c, 2)},{round(c, 2)} 0 0 1 {round(x0, 2)},{round(y1 - c, 2)}")
    parts.append(f"L{round(x0, 2)},{round(y0 + c, 2)}")
    parts.append(f"A{round(c, 2)},{round(c, 2)} 0 0 1 {round(x0 + c, 2)},{round(y0, 2)}")
    parts.append("Z")
    return " ".join(parts)


def mark_paths(ox, oy, scale):
    """The mark's drawable paths, placed at (ox, oy) and scaled.

    Split out so both the bare splash icon and the composed background can reuse
    exactly the same geometry as the launcher art and the in-app BrandMark.
    """
    def t(x, y):
        return round(ox + x * scale, 2), round(oy + y * scale, 2)

    paths = [
        f'    <path android:fillColor="{FACE}" '
        f'android:pathData="{_round_rect(ox, oy, scale)}"/>'
    ]

    # Tears hang below the eyes, so they are emitted before the eyes and their
    # tails are covered by the eye circles where they meet.
    cx_left = MARK / 2 - EYE_DX
    cx_right = MARK / 2 + EYE_DX
    tear_top = EYE_CY + EYE_R + TEAR_TOP_GAP
    tear_bot = tear_top + TEAR_LEN
    half_top = TEAR_W / 2
    half_bot = half_top * TEAR_TIP_SHRINK
    for cx in (cx_left, cx_right):
        x1, y1 = t(cx - half_top, tear_top)
        x2, y2 = t(cx + half_top, tear_top)
        x3, y3 = t(cx + half_bot, tear_bot)
        x4, y4 = t(cx - half_bot, tear_bot)
        paths.append(
            f'    <path android:fillColor="{TEAR}" '
            f'android:pathData="M{x1},{y1} L{x2},{y2} L{x3},{y3} L{x4},{y4} Z"/>'
        )

    for dx in (-EYE_DX, EYE_DX):
        cx, cy = t(MARK / 2 + dx, EYE_CY)
        r = round(EYE_R * scale, 2)
        paths.append(
            f'    <path android:fillColor="{EYE}" '
            f'android:pathData="M{round(cx - r, 2)},{cy} '
            f'A{r},{r} 0 1 0 {round(cx + r, 2)},{cy} '
            f'A{r},{r} 0 1 0 {round(cx - r, 2)},{cy} Z"/>'
        )

    # The grin is the region between two upward-curving quads, which gives it flat
    # ends; a stroked curve would have round caps and read as a pipe.
    gx0, gy0 = t(MARK / 2 - GRIN_HALF, GRIN_TOP)
    gx1, gy1 = t(MARK / 2, GRIN_CTRL)
    gx2, gy2 = t(MARK / 2 + GRIN_HALF, GRIN_TOP)
    gx3, gy3 = t(MARK / 2 + GRIN_HALF, GRIN_BOTTOM)
    gx4, gy4 = t(MARK / 2, GRIN_CTRL + GRIN_WIDTH)
    gx5, gy5 = t(MARK / 2 - GRIN_HALF, GRIN_BOTTOM)
    paths.append(
        f'    <path android:fillColor="{GRIN}" '
        f'android:pathData="M{gx0},{gy0} Q{gx1},{gy1} {gx2},{gy2} '
        f"L{gx3},{gy3} Q{gx4},{gy4} {gx5},{gy5} Z\"/>"
    )

    return paths


def splash_icon():
    ox = (VIEW - MARK * SCALE) / 2
    oy = (VIEW - MARK * SCALE) / 2
    body = mark_paths(ox, oy, SCALE)
    return [
        '<?xml version="1.0" encoding="utf-8"?>',
        '<!-- Generated by scripts/gen_splash_vector.py; do not edit by hand. -->',
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
        f'    android:width="{VIEW}dp"',
        f'    android:height="{VIEW}dp"',
        f'    android:viewportWidth="{VIEW}"',
        f'    android:viewportHeight="{VIEW}">',
        *body,
        '</vector>',
    ]


def splash_mark():
    """Face plus a baseline rule, on the 108dp splash canvas.

    Rendered as vectors rather than a bitmap so it stays crisp at every density
    without shipping one raster per bucket. A short low-opacity rule under the
    mark reads as a deliberate composition; faking lettering with stroked paths
    would not.

    The mark and the rule are centred *as a block*, not the mark alone: the
    drawable is placed at a fixed size in the launch background, so a mark pinned
    near the top left the lower third of the canvas empty and the launch screen
    read as top-heavy.
    """
    block_h = MARK * SCALE + RULE_GAP + RULE_H
    oy = (VIEW - block_h) / 2.0
    ox = (VIEW - MARK * SCALE) / 2
    body = mark_paths(ox, oy, SCALE)

    rule_y = oy + MARK * SCALE + RULE_GAP
    body.append(
        f'    <path android:fillColor="{RULE}" '
        f'android:pathData="M34,{round(rule_y, 2)} L74,{round(rule_y, 2)} '
        f'L74,{round(rule_y + RULE_H, 2)} L34,{round(rule_y + RULE_H, 2)} Z"/>'
    )

    return [
        '<?xml version="1.0" encoding="utf-8"?>',
        '<!-- Generated by scripts/gen_splash_vector.py (mark mode); do not edit by hand. -->',
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
        f'    android:width="{VIEW}dp"',
        f'    android:height="{VIEW}dp"',
        f'    android:viewportWidth="{VIEW}"',
        f'    android:viewportHeight="{VIEW}">',
        *body,
        '</vector>',
    ]


def main():
    mark_mode = "--mark" in sys.argv
    lines = splash_mark() if mark_mode else splash_icon()
    print("\n".join(lines))


if __name__ == "__main__":
    main()