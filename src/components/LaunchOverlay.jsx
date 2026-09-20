import React, { useEffect, useState } from 'react';
import BrandMark from './BrandMark';
import './LaunchOverlay.css';

/**
 * Brand intro shown over the app while it boots.
 *
 * The native splash is a static image, so this layer is what carries the
 * animation: the mark settles in, the name follows, and the whole overlay then
 * fades to reveal the app. It renders on the first frame, so it covers the gap
 * between the native splash going away and React painting the real UI.
 *
 * The composition deliberately mirrors the native splash (mark above a baseline
 * rule) so the two read as one continuous screen rather than a cut.
 *
 * The overlay is skipped entirely when the user has asked for reduced motion.
 */
const HOLD_MS = 1500;

export default function LaunchOverlay() {
  const [done, setDone] = useState(false);

  useEffect(() => {
    const reduced =
      typeof window !== 'undefined' &&
      window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;
    if (reduced) {
      setDone(true);
      return undefined;
    }
    const t = setTimeout(() => setDone(true), HOLD_MS);
    return () => clearTimeout(t);
  }, []);

  if (done) return null;

  return (
    <div
      className="launch-overlay"
      aria-hidden="true"
      // A tap during the intro should skip it rather than be swallowed.
      onPointerDown={() => setDone(true)}
    >
      <div className="launch-content">
        <div className="launch-mark">
          <BrandMark size={132} />
        </div>
        <p className="launch-name">Favie X Tracker</p>
        <span className="launch-rule" />
      </div>
    </div>
  );
}