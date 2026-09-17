import React from 'react';
import './TrackingDisplay.css';

/**
 * Live feed.
 *
 * Frames are captured and rendered entirely on-device, so the preview is pushed
 * from the tracking service rather than polled over HTTP as it was when a
 * backend served `/api/latest-frame`.
 */
function TrackingDisplay({ status }) {
  const lastFrame = status?.lastFrame;
  const overlay = status?.overlay;

  return (
    <div className="tracking-display">
      <div className="display-header">
        <h2>📹 Live Screen Tracking</h2>
        {lastFrame?.timestamp && (
          <p className="timestamp">Last Updated: {new Date(lastFrame.timestamp).toLocaleTimeString()}</p>
        )}
      </div>

      <div className="frame-container">
        {lastFrame?.dataUrl ? (
          <div className="frame-wrapper">
            <img src={lastFrame.dataUrl} alt="Screen capture" className="frame-image" />
            {overlay?.length > 0 && (
              <svg
                className="frame-overlay"
                viewBox={`0 0 ${lastFrame.width} ${lastFrame.height}`}
                preserveAspectRatio="none"
              >
                {overlay.map((det, idx) => (
                  <g key={det.id ?? idx}>
                    <rect
                      x={det.bbox.x}
                      y={det.bbox.y}
                      width={det.bbox.width}
                      height={det.bbox.height}
                      fill="none"
                      stroke="#00e5ff"
                      strokeWidth="2"
                    />
                    <text
                      x={det.bbox.x}
                      y={Math.max(12, det.bbox.y - 4)}
                      fill="#00e5ff"
                      fontSize="12"
                    >
                      {det.color} {(det.confidence ?? 0).toFixed(0)}%
                    </text>
                  </g>
                ))}
              </svg>
            )}
          </div>
        ) : status?.error ? (
          <div className="error-message">
            <p>⚠️ {status.error}</p>
            <p>Start tracking from the Dashboard tab and grant capture permission.</p>
          </div>
        ) : (
          <div className="loading-message">
            <p>{status?.running ? 'Waiting for first frame…' : 'Tracking is stopped'}</p>
          </div>
        )}
      </div>

      <div className="info-panel">
        <p>
          Live feed updates every {status?.config?.capture_interval ?? 500}ms while tracking is active
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