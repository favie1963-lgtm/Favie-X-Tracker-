import React, { useId } from 'react';

/**
 * The Favie X Tracker mark: a faceless mask with two red eyes and a white grin.
 *
 * Dark tears run down from each eye so the face reads as menacing rather than
 * friendly. There is no nose and no outline — the eyes and the grin are the whole
 * face, which is what keeps the shape legible at 20px where fine detail would
 * disappear.
 *
 * Geometry and colours are mirrored from scripts/generate_brand_assets.py, which
 * draws the launcher art, and scripts/gen_splash_vector.py, which emits the splash
 * vectors, so the in-app mark, the launcher icon and the launch screen stay
 * identical.
 *
 * The mark is a rounded square rather than a circle because a circle would read as
 * a smiley; the squared-off silhouette is what makes the same eyes and grin look
 * like a mask.
 */
const MARK = 32;
const CORNER = 7;

const EYE_R = 3.7;
const EYE_CY = 12.8;
const EYE_DX = 7.2;

const TEAR_TOP_GAP = 2.6;
const TEAR_LEN = 7.4;
const TEAR_W = 1.05;
const TEAR_TIP_SHRINK = 0.55;

const GRIN_TOP = 20;
const GRIN_CTRL = 26.4;
const GRIN_BOTTOM = 22.4;
const GRIN_HALF = 9;
const GRIN_WIDTH = 1.9;

const FACE = '#141416';
const EYE = '#ff2d3f';
const GRIN = '#ffffff';

/** One tapering tear streak hanging out of an eye's lower lid. */
function tearPath(cx) {
  const top = EYE_CY + EYE_R + TEAR_TOP_GAP;
  const bottom = top + TEAR_LEN;
  const halfTop = TEAR_W / 2;
  const halfBottom = halfTop * TEAR_TIP_SHRINK;
  return (
    `M${cx - halfTop},${top} L${cx + halfTop},${top} ` +
    `L${cx + halfBottom},${bottom} L${cx - halfBottom},${bottom} Z`
  );
}

/**
 * The grin: the region between two upward-curving quads.
 *
 * Filled rather than stroked, so the ends are square. A stroked path gets round
 * caps on most renderers and the grin reads as a pipe instead of a mouth.
 */
function grinPath() {
  const cx = MARK / 2;
  return (
    `M${cx - GRIN_HALF},${GRIN_TOP} Q${cx},${GRIN_CTRL} ${cx + GRIN_HALF},${GRIN_TOP} ` +
    `L${cx + GRIN_HALF},${GRIN_BOTTOM} Q${cx},${GRIN_CTRL + GRIN_WIDTH} ` +
    `${cx - GRIN_HALF},${GRIN_BOTTOM} Z`
  );
}

export default function BrandMark({ size = 40, className = '', title = 'Favie X Tracker' }) {
  const id = useId().replace(/:/g, '');
  const clipId = `fx-mask-${id}`;

  return (
    <svg
      className={className}
      width={size}
      height={size}
      viewBox={`0 0 ${MARK} ${MARK}`}
      role="img"
      aria-label={title}
      focusable="false"
    >
      <defs>
        {/* Clipping to the plate keeps the tear tails inside the face if the
            geometry is ever nudged outwards. */}
        <clipPath id={clipId}>
          <rect x="0" y="0" width={MARK} height={MARK} rx={CORNER} ry={CORNER} />
        </clipPath>
      </defs>

      <rect x="0" y="0" width={MARK} height={MARK} rx={CORNER} ry={CORNER} fill={FACE} />

      <g clipPath={`url(#${clipId})`}>
        <path d={tearPath(MARK / 2 - EYE_DX)} fill={GRIN} />
        <path d={tearPath(MARK / 2 + EYE_DX)} fill={GRIN} />
      </g>

      <circle cx={MARK / 2 - EYE_DX} cy={EYE_CY} r={EYE_R} fill={EYE} />
      <circle cx={MARK / 2 + EYE_DX} cy={EYE_CY} r={EYE_R} fill={EYE} />

      <path d={grinPath()} fill={GRIN} />
    </svg>
  );
}