import React, { useEffect, useRef, useState } from 'react';
import './TrackingDisplay.css';
import Icon from './Icon';

/**
 * Live feed.
 *
 * Frames are captured and rendered entirely on-device, so the preview is pushed
 * from the tracking service rather than polled over HTTP as it was when a
 * backend served `/api/latest-frame`.
 *
 * The detection boxes are drawn in the captured frame's own pixel coordinates,
 * so the SVG is pinned to the image's rendered box rather than to the container.
 * The image is letterboxed with `object-fit: contain`, which means the rendered
 * box is usually smaller than the container; overlaying the container would
 * scale the boxes away from the objects they describe.
 */
function TrackingDisplay({ status }) {
  const lastFrame = status?.lastFrame;
  const overlay = status?.overlay ?? [];
  const hasFrame = Boolean(lastFrame?.dataUrl);

  const imageRef = useRef(null);
  const [scale, setScale] = useState(1);

  /**
   * The SVG is stretched from the frame's pixel space to the image's rendered
   * box, so one SVG user unit is worth `renderedWidth / frameWidth` screen
   * pixels. Label sizes are authored in screen pixels for legibility, so they
   * have to be divided by that ratio or a 960px-wide frame shown at 340px
   * renders its labels at roughly a third of their intended size.
   */
  useEffect(() => {
    const node = imageRef.current;
    if (!node) return undefined;

    const measure = () => {
      const frameWidth = lastFrame?.width;
      const rendered = node.getBoundingClientRect().width;
      if (!frameWidth || !rendered) return;
      setScale(rendered / frameWidth);
    };

    measure();
    if (typeof ResizeObserver === 'undefined') {
      window.addEventListener('resize', measure);
      return () => window.removeEventListener('resize', measure);
    }
    const observer = new ResizeObserver(measure);
    observer.observe(node);
    return () => observer.disconnect();
  }, [lastFrame?.width, lastFrame?.dataUrl]);

  return (
    <div className="tracking-display">
      <header className="section-header">
        <h2>
          <Icon name="video" />
          Live screen tracking
        </h2>
        {lastFrame?.timestamp && (
          <p className="section-meta">
            Updated {new Date(lastFrame.timestamp).toLocaleTimeString()}
          </p>
        )}
      </header>

      <div className={`frame-container ${hasFrame ? 'has-frame' : ''}`}>
        {hasFrame ? (
          <div className="frame-wrapper">
            <img
              ref={imageRef}
              src={lastFrame.dataUrl}
              alt="Live screen capture"
              className="frame-image"
            />
            {overlay.length > 0 && (
              <svg
                className="frame-overlay"
                viewBox={`0 0 ${lastFrame.width} ${lastFrame.height}`}
                preserveAspectRatio="none"
                aria-hidden="true"
              >
                {overlay.map((det, idx) => {
                  // Screen-space sizes, converted back to user units.
                  const fontSize = 12 / scale;
                  const labelGap = 6 / scale;
                  const pad = 4 / scale;
                  const label = `${det.color} ${(det.confidence ?? 0).toFixed(0)}%`;
                  // Prefer the label above the box, but keep it inside the frame
                  // when the box is against the top edge.
                  const above = det.bbox.y - labelGap - fontSize >= 0;
                  const labelY = above
                    ? det.bbox.y - labelGap
                    : det.bbox.y + fontSize + labelGap;
                  return (
                    <g key={det.id ?? idx}>
                      <rect
                        x={det.bbox.x}
                        y={det.bbox.y}
                        width={det.bbox.width}
                        height={det.bbox.height}
                        fill="none"
                        stroke="#ffffff"
                        strokeWidth="2"
                        vectorEffect="non-scaling-stroke"
                      />
                      {/* Captured frames are arbitrarily bright, so the label
                          gets a dark plate rather than relying on white text
                          alone to stand out. */}
                      <rect
                        x={det.bbox.x}
                        y={labelY - fontSize + pad}
                        width={label.length * fontSize * 0.62 + pad * 2}
                        height={fontSize + pad}
                        fill="rgba(0, 0, 0, 0.72)"
                        rx={2 / scale}
                      />
                      <text
                        x={det.bbox.x + pad}
                        y={labelY}
                        fill="#ffffff"
                        fontSize={fontSize}
                        fontWeight="600"
                      >
                        {label}
                      </text>
                    </g>
                  );
                })}
              </svg>
            )}
          </div>
        ) : status?.error ? (
          <div className="frame-state error-message">
            <Icon name="alert" size={26} />
            <p className="state-title">{status.error}</p>
            <p className="state-hint">
              Open the Dashboard tab and start tracking to grant capture permission.
            </p>
          </div>
        ) : (
          <div className="frame-state">
            <Icon name="crosshair" size={26} />
            <p className="state-title">
              {status?.running ? 'Waiting for the first frame' : 'Tracking is stopped'}
            </p>
            <p className="state-hint">
              {status?.running
                ? 'Frames appear here as soon as capture produces one.'
                : 'Start tracking from the Dashboard tab to see a live feed.'}
            </p>
          </div>
        )}
      </div>

      <div className="info-panel">
        <p>
          Live feed updates every {status?.config?.capture_interval ?? 500}ms while tracking is
          active
        </p>
        {status?.running && (
          <p>
            Source: {status.capture_source} · Objects in frame: {status.tracking_data_count ?? 0}
          </p>
        )}
      </div>
    </div>
  );
}

export default TrackingDisplay;