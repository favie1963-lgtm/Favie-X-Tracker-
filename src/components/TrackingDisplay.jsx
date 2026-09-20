import React, { useEffect, useMemo, useRef, useState } from 'react';
import './TrackingDisplay.css';
import Icon from './Icon';
import { trackingService } from '../services/tracking';

/**
 * Live view.
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
 *
 * The preview also acts as a selection surface: when the tracking service is in
 * selection mode, a tap here is mapped back into frame coordinates and locks the
 * target. That is the fallback for the rare case where the floating marker window
 * could not be added (for example overlay permission was revoked after capture
 * started), so selection is never impossible.
 */
function TrackingDisplay({ status }) {
  const lastFrame = status?.lastFrame;
  const overlay = status?.overlay ?? [];
  const target = status?.target;
  const targetBox = target?.target;
  const selecting = status?.native?.selecting ?? false;
  const hasFrame = Boolean(lastFrame?.dataUrl);

  const imageRef = useRef(null);
  const [scale, setScale] = useState(1);
  const [tapMessage, setTapMessage] = useState(null);

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

  const marker = useMemo(() => {
    if (!targetBox) return null;
    const lost = target?.state === 'lost';
    return {
      ...targetBox,
      lost,
      label: lost
        ? 'TARGET LOST'
        : `TARGET · ${targetBox.color ?? 'target'} · ${Math.round(targetBox.confidence ?? 0)}%`,
    };
  }, [targetBox, target?.state]);

  /** Map a tap on the rendered image into frame pixels and lock the target. */
  const handleImageTap = (event) => {
    const node = imageRef.current;
    if (!node || !lastFrame?.width) return;

    const rect = node.getBoundingClientRect();
    // object-fit: contain letterboxes the image, so the drawn area is inset from
    // the element's box. Taps in the letterbox margin are ignored rather than
    // mapped to a distorted coordinate.
    const frameAspect = lastFrame.width / lastFrame.height;
    const boxAspect = rect.width / rect.height;
    let drawW = rect.width;
    let drawH = rect.height;
    let offsetX = 0;
    let offsetY = 0;
    if (boxAspect > frameAspect) {
      drawW = rect.height * frameAspect;
      offsetX = (rect.width - drawW) / 2;
    } else {
      drawH = rect.width / frameAspect;
      offsetY = (rect.height - drawH) / 2;
    }

    const x = event.clientX - rect.left - offsetX;
    const y = event.clientY - rect.top - offsetY;
    if (x < 0 || y < 0 || x > drawW || y > drawH) return;

    const frameX = (x / drawW) * lastFrame.width;
    const frameY = (y / drawH) * lastFrame.height;

    const result = trackingService.selectTarget({ x: frameX, y: frameY });
    if (result.status === 'error') {
      setTapMessage(result.message ?? 'Could not select a target from this frame.');
    } else {
      setTapMessage('Target locked. The marker follows this object.');
    }
  };

  return (
    <div className="tracking-display">
      <header className="section-header">
        <h2>
          <Icon name="video" />
          Live view
        </h2>
        {lastFrame?.timestamp && (
          <p className="section-meta">
            Updated {new Date(lastFrame.timestamp).toLocaleTimeString()}
          </p>
        )}
      </header>

      {(target || selecting) && (
        <div className={`live-banner ${target?.state === 'lost' ? 'lost' : ''}`}>
          <span className="status-dot" aria-hidden="true" />
          {selecting
            ? 'Select a target: tap the object on the toolbar overlay, or tap the preview below.'
            : target?.state === 'lost'
              ? 'Target lost. Use Reacquire on the toolbar, or select the object again.'
              : `Tracking target · ${Math.round(target?.target?.confidence ?? 0)}% confidence`}
        </div>
      )}

      <div className={`frame-container ${hasFrame ? 'has-frame' : ''}`}>
        {hasFrame ? (
          <div className="frame-wrapper">
            <img
              ref={imageRef}
              src={lastFrame.dataUrl}
              alt="Live screen capture"
              className={`frame-image ${selecting ? 'selectable' : ''}`}
              onClick={selecting ? handleImageTap : undefined}
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
                        stroke="rgba(255,255,255,0.45)"
                        strokeWidth="1.5"
                        vectorEffect="non-scaling-stroke"
                      />
                      <rect
                        x={det.bbox.x}
                        y={labelY - fontSize + pad}
                        width={label.length * fontSize * 0.62 + pad * 2}
                        height={fontSize + pad}
                        fill="rgba(0, 0, 0, 0.6)"
                        rx={2 / scale}
                      />
                      <text
                        x={det.bbox.x + pad}
                        y={labelY}
                        fill="rgba(255,255,255,0.85)"
                        fontSize={fontSize}
                        fontWeight="600"
                      >
                        {label}
                      </text>
                    </g>
                  );
                })}

                {marker && (
                  <g>
                    <rect
                      x={marker.x}
                      y={marker.y}
                      width={marker.width}
                      height={marker.height}
                      fill="none"
                      stroke={marker.lost ? '#f87171' : '#4ade80'}
                      strokeWidth="2.5"
                      vectorEffect="non-scaling-stroke"
                    />
                    <rect
                      x={marker.x}
                      y={marker.y - 16 / scale}
                      width={marker.label.length * (11 / scale) * 0.62 + 8 / scale}
                      height={14 / scale}
                      fill="rgba(0,0,0,0.75)"
                      rx={2 / scale}
                    />
                    <text
                      x={marker.x + 4 / scale}
                      y={marker.y - 5 / scale}
                      fill={marker.lost ? '#f87171' : '#4ade80'}
                      fontSize={11 / scale}
                      fontWeight="700"
                    >
                      {marker.label}
                    </text>
                  </g>
                )}
              </svg>
            )}
          </div>
        ) : status?.error ? (
          <div className="frame-state error-message">
            <Icon name="alert" size={26} />
            <p className="state-title">{status.error}</p>
            <p className="state-hint">
              Open the Dashboard tab and enable screen tracking to grant capture permission.
            </p>
          </div>
        ) : (
          <div className="frame-state">
            <Icon name="crosshair" size={26} />
            <p className="state-title">
              {status?.running ? 'Waiting for the first frame' : 'Capture is off'}
            </p>
            <p className="state-hint">
              {status?.running
                ? 'Frames appear here as soon as capture produces one.'
                : 'Enable screen tracking from the Dashboard tab to see a live feed.'}
            </p>
          </div>
        )}
      </div>

      {tapMessage && <p className="live-note">{tapMessage}</p>}

      <div className="info-panel">
        <p>
          Live feed updates every {status?.config?.capture_interval ?? 500}ms while capture is
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