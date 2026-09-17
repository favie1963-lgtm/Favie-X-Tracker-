import React, { useEffect, useState } from 'react';
import BrandMark from './BrandMark';
import './LaunchOverlay.css';

/**
 * Brand intro shown over the app while it boots.
 *
 * The native splash is a static image, so this layer is what carries the
 * animation: the F and X settle in from a slight overscale, breathe once, and
 * the whole overlay then fades to reveal the app. It renders on the first
 * frame, so it covers the gap between the native splash going away and React
 * painting the real UI.
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
      <div className="launch-mark">
        <BrandMark size={168} />
      </div>
    </div>
  );
}