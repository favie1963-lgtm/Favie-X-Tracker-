import React, { useState, useEffect } from 'react';
import './Dashboard.css';

function Dashboard() {
  const [status, setStatus] = useState(null);
  const [isTracking, setIsTracking] = useState(false);
  const [objects, setObjects] = useState([]);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    const fetchStatus = async () => {
      try {
        const result = await window.api.getStatus();
        if (result.success) {
          setStatus(result.data);
          setIsTracking(result.data.running);
        }
      } catch (error) {
        console.error('Error fetching status:', error);
      }
    };

    const interval = setInterval(fetchStatus, 1000);
    return () => clearInterval(interval);
  }, []);

  const handleStartTracking = async () => {
    setLoading(true);
    try {
      const result = await window.api.startTracking();
      if (result.success) {
        setIsTracking(true);
      }
    } catch (error) {
      console.error('Error starting tracking:', error);
    } finally {
      setLoading(false);
    }
  };

  const handleStopTracking = async () => {
    setLoading(true);
    try {
      const result = await window.api.stopTracking();
      if (result.success) {
        setIsTracking(false);
      }
    } catch (error) {
      console.error('Error stopping tracking:', error);
    } finally {
      setLoading(false);
    }
  };

  const handleGetObjects = async () => {
    try {
      const result = await window.api.getTrackedObjects();
      if (result.success) {
        setObjects(result.data.objects || []);
      }
    } catch (error) {
      console.error('Error fetching objects:', error);
    }
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
              <p><strong>Running:</strong> {status.running ? '✅ Yes' : '❌ No'}</p>
              <p><strong>FPS:</strong> {status.fps.toFixed(1)}</p>
              <p><strong>Frames Captured:</strong> {status.frame_count}</p>
              <p><strong>Tracked Objects:</strong> {status.tracking_data_count}</p>
            </div>
          )}
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
            objects.map((obj, idx) => (
              <div key={idx} className="object-card">
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