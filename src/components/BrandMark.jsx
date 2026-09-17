import React, { useId } from 'react';

/**
 * The Favie X Tracker mark: a capital F beside a crosshair X.
 *
 * The X is two diagonals clipped to their box with a circular hole knocked out
 * at the crossing, so it reads as a reticle. Geometry is mirrored from
 * scripts/generate_brand_assets.py, which draws the launcher and splash art,
 * so the in-app mark and the icon stay identical.
 *
 * The knockout is an evenodd clip path rather than a background-coloured
 * circle, so the mark also renders correctly on transparent or light grounds.
 */
const MARK_W = 56;
const MARK_H = 44;
const STROKE = 9;

export default function BrandMark({ size = 40, className = '', title = 'Favie X Tracker' }) {
  const id = useId().replace(/:/g, '');
  const clipId = `fx-reticle-${id}`;
  const height = (size * MARK_H) / MARK_W;

  return (
    <svg
      className={className}
      width={size}
      height={height}
      viewBox={`0 0 ${MARK_W} ${MARK_H}`}
      role="img"
      aria-label={title}
      focusable="false"
    >
      <defs>
        <clipPath id={clipId} clipPathUnits="userSpaceOnUse">
          <path
            clipRule="evenodd"
            d={
              'M28 0 H56 V44 H28 Z ' +
              'M48 22 A6 6 0 1 0 36 22 A6 6 0 1 0 48 22 Z'
            }
          />
        </clipPath>
      </defs>

      {/* F */}
      <rect x="0" y="0" width={STROKE} height={MARK_H} fill="currentColor" />
      <rect x="0" y="0" width="24" height={STROKE} fill="currentColor" />
      <rect x="0" y={MARK_H / 2 - STROKE / 2} width="15" height={STROKE} fill="currentColor" />

      {/* Crosshair X */}
      <g clipPath={`url(#${clipId})`}>
        <line
          x1="28"
          y1="0"
          x2="56"
          y2={MARK_H}
          stroke="currentColor"
          strokeWidth={STROKE}
        />
        <line
          x1="56"
          y1="0"
          x2="28"
          y2={MARK_H}
          stroke="currentColor"
          strokeWidth={STROKE}
        />
      </g>
    </svg>
  );
}