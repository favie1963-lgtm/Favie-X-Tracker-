import React from 'react';

/**
 * Monochrome chrome icons.
 *
 * The app previously used emoji for its headings and buttons. Emoji are drawn by
 * the platform's colour font, so the same glyph renders differently on every
 * device and injects colour into an otherwise black-and-white brand. These are
 * single-colour strokes that inherit `currentColor`, which keeps the shell on
 * one palette and the alignment predictable.
 *
 * All icons share a 24x24 viewBox and a 1.75 stroke so they optically match.
 */
const PATHS = {
  crosshair: (
    <>
      <circle cx="12" cy="12" r="8" />
      <path d="M12 1.5v4M12 18.5v4M1.5 12h4M18.5 12h4" />
    </>
  ),
  video: (
    <>
      <rect x="2.5" y="6" width="13" height="12" rx="2.5" />
      <path d="M15.5 11l6-3.5v9l-6-3.5z" />
    </>
  ),
  play: <path d="M7.5 4.8v14.4l12-7.2z" />,
  stop: <rect x="6.5" y="6.5" width="11" height="11" rx="1.5" />,
  refresh: (
    <>
      <path d="M20 12a8 8 0 1 1-2.6-5.9" />
      <path d="M20 3.5V9h-5.5" />
    </>
  ),
  chart: (
    <>
      <path d="M3.5 20.5h17" />
      <path d="M6.5 20.5v-6M11 20.5V7.5M15.5 20.5v-9M20 20.5V4.5" />
    </>
  ),
  report: (
    <>
      <path d="M6 2.5h8.5L20 8v13.5H6z" />
      <path d="M14 2.5V8h5.5" />
      <path d="M9.5 13h6M9.5 17h6" />
    </>
  ),
  alert: (
    <>
      <path d="M12 3.2 21.5 20H2.5z" />
      <path d="M12 9.5v5M12 17.4v.1" />
    </>
  ),
  gauge: (
    <>
      <path d="M3.6 18a9 9 0 1 1 16.8 0" />
      <path d="M12 14.5l4-4.5" />
      <circle cx="12" cy="15" r="1.6" />
    </>
  ),
  check: <path d="M4.5 12.8l5 5 10-11" />,
};

export default function Icon({ name, size = 18, className = '', title }) {
  const d = PATHS[name] ?? PATHS.crosshair;
  return (
    <svg
      className={`icon ${className}`.trim()}
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.75"
      strokeLinecap="round"
      strokeLinejoin="round"
      role={title ? 'img' : 'presentation'}
      aria-label={title}
      aria-hidden={title ? undefined : 'true'}
      focusable="false"
    >
      {d}
    </svg>
  );
}