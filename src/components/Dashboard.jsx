import React, { useState } from 'react';
import './Dashboard.css';
import Icon from './Icon';
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

  const stats = [
    ['FPS', (status?.fps ?? 0).toFixed(1)],
    ['Frames captured', status?.frame_count ?? 0],
    ['Tracked objects', status?.tracking_data_count ?? 0],
    ['Capture source', status?.capture_source ?? 'none'],
  ];

  return (
    <div className="dashboard">
      <header className="section-header">
        <h2>
          <Icon name="gauge" />
          Control dashboard
        </h2>
        <span className={`state-pill ${isTracking ? 'on' : 'off'}`}>
          {isTracking ? 'Tracking' : 'Idle'}
        </span>
      </header>

      <div className="controls-panel">
        <section className="panel">
          <h3>Tracking controls</h3>
          <div className="control-actions">
            <button
              type="button"
              className="btn btn-primary"
              onClick={handleStartTracking}
              disabled={isTracking || loading}
            >
              <Icon name="play" size={16} />
              Start tracking
            </button>
            <button
              type="button"
              className="btn btn-secondary"
              onClick={handleStopTracking}
              disabled={!isTracking || loading}
            >
              <Icon name="stop" size={16} />
              Stop tracking
            </button>
          </div>
        </section>

        <section className="panel">
          <h3>Status</h3>
          <dl className="stat-grid">
            {stats.map(([label, value]) => (
              <div className="stat" key={label}>
                <dt>{label}</dt>
                <dd>{value}</dd>
              </div>
            ))}
          </dl>
          {message && (
            <p className="status-message">
              <Icon name="alert" size={15} />
              {message}
            </p>
          )}
        </section>
      </div>

      <section className="panel">
        <div className="panel-header">
          <h3>Detected objects</h3>
          <button type="button" className="btn btn-ghost" onClick={handleGetObjects}>
            <Icon name="refresh" size={15} />
            Refresh
          </button>
        </div>

        {objects.length > 0 ? (
          <div className="objects-list">
            {objects.map((obj) => (
              <article className="object-card" key={obj.id}>
                <header>
                  <span className="object-id">{obj.id}</span>
                  <span className="object-color">{obj.color}</span>
                </header>
                <dl>
                  <div>
                    <dt>Confidence</dt>
                    <dd>{obj.confidence?.toFixed(1)}%</dd>
                  </div>
                  <div>
                    <dt>Position</dt>
                    <dd>
                      {obj.centroid?.x}, {obj.centroid?.y}
                    </dd>
                  </div>
                  <div>
                    <dt>Area</dt>
                    <dd>{obj.area?.toFixed(0)} px²</dd>
                  </div>
                </dl>
              </article>
            ))}
          </div>
        ) : (
          <p className="empty-message">
            No objects detected yet. Start tracking, then refresh to list what the tracker sees.
          </p>
        )}
      </section>
    </div>
  );
}

export default Dashboard;