import React, { useState } from 'react';
import './Dashboard.css';
import { trackingService } from '../services/tracking';

/**
 * Control dashboard.
 *
 * Start/stop now drive the on-device capture loop. Status is supplied by the
 * parent, which subscribes to the tracking service, so the panel no longer needs
 * its own polling timer.
 */
function Dashboard({ status }) {
  const [objects, setObjects] = useState([]);
  const [loading, setLoading] = useState(false);
  const [message, setMessage] = useState(null);

  const isTracking = Boolean(status?.running);

  const handleStartTracking = async () => {
    setLoading(true);
    setMessage(null);
    try {
      const result = await trackingService.start();
      if (result.status === 'error') setMessage(result.message);
    } catch (error) {
      setMessage(error?.message ?? 'Failed to start tracking');
    } finally {
      setLoading(false);
    }
  };

  const handleStopTracking = async () => {
    setLoading(true);
    setMessage(null);
    try {
      await trackingService.stop();
      setObjects([]);
    } catch (error) {
      setMessage(error?.message ?? 'Failed to stop tracking');
    } finally {
      setLoading(false);
    }
  };

  const handleGetObjects = () => {
    const result = trackingService.getTrackedObjects();
    setObjects(result.objects ?? []);
  };

  return (
    <div className="dashboard">
      <div className="dashboard-header">
        <h2>🎮 Control Dashboard</h2>
      </div>

      <div className="controls-panel">
        <div className="control-section">
          <h3>Tracking Controls</h3>
          <button
            className={`control-btn ${isTracking ? 'active' : ''}`}
            onClick={handleStartTracking}
            disabled={isTracking || loading}
          >
            ▶️ Start Tracking
          </button>
          <button
            className={`control-btn stop ${!isTracking ? 'disabled' : ''}`}
            onClick={handleStopTracking}
            disabled={!isTracking || loading}
          >
            ⏹️ Stop Tracking
          </button>
        </div>

        <div className="status-panel">
          <h3>Status Information</h3>
          {status && (
            <div className="status-info">
              <p><strong>Running:</strong> {isTracking ? '✅ Yes' : '❌ No'}</p>
              <p><strong>FPS:</strong> {(status.fps ?? 0).toFixed(1)}</p>
              <p><strong>Frames Captured:</strong> {status.frame_count ?? 0}</p>
              <p><strong>Tracked Objects:</strong> {status.tracking_data_count ?? 0}</p>
              <p><strong>Capture Source:</strong> {status.capture_source ?? 'none'}</p>
            </div>
          )}
          {message && <p className="status-message">{message}</p>}
        </div>
      </div>

      <div className="objects-section">
        <div className="objects-header">
          <h3>Detected Objects</h3>
          <button className="refresh-btn" onClick={handleGetObjects}>
            🔄 Refresh
          </button>
        </div>

        <div className="objects-list">
          {objects.length > 0 ? (
            objects.map((obj) => (
              <div key={obj.id} className="object-card">
                <div className="object-info">
                  <p><strong>ID:</strong> {obj.id}</p>
                  <p><strong>Color:</strong> {obj.color}</p>
                  <p><strong>Confidence:</strong> {obj.confidence?.toFixed(1)}%</p>
                  <p><strong>Position:</strong> ({obj.centroid?.x}, {obj.centroid?.y})</p>
                  <p><strong>Area:</strong> {obj.area?.toFixed(0)} px²</p>
                </div>
              </div>
            ))
          ) : (
            <p className="empty-message">No objects detected yet</p>
          )}
        </div>
      </div>
    </div>
  );
}

export default Dashboard;