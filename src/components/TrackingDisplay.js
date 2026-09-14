import React, { useState, useEffect } from 'react';
import './TrackingDisplay.css';

function TrackingDisplay() {
  const [frameUrl, setFrameUrl] = useState(null);
  const [timestamp, setTimestamp] = useState(null);
  const [error, setError] = useState(null);

  useEffect(() => {
    const fetchFrame = async () => {
      try {
        const response = await window.api.getLatestFrame();
        if (response.ok) {
          const blob = await response.blob();
          const url = URL.createObjectURL(blob);
          setFrameUrl(url);
          setTimestamp(new Date().toLocaleTimeString());
          setError(null);
        }
      } catch (err) {
        setError('Failed to fetch frame');
      }
    };

    const interval = setInterval(fetchFrame, 500);
    fetchFrame();

    return () => clearInterval(interval);
  }, []);

  return (
    <div className="tracking-display">
      <div className="display-header">
        <h2>📹 Live Screen Tracking</h2>
        {timestamp && <p className="timestamp">Last Updated: {timestamp}</p>}
      </div>

      <div className="frame-container">
        {frameUrl ? (
          <img src={frameUrl} alt="Screen capture" className="frame-image" />
        ) : error ? (
          <div className="error-message">
            <p>⚠️ {error}</p>
            <p>Make sure the backend is running</p>
          </div>
        ) : (
          <div className="loading-message">
            <p>Loading...</p>
          </div>
        )}
      </div>

      <div className="info-panel">
        <p>The live feed updates every 500ms while tracking is active</p>
      </div>
    </div>
  );
}

export default TrackingDisplay;