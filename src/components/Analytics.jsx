import React, { useEffect, useMemo, useState } from 'react';
import {
  LineChart,
  Line,
  BarChart,
  Bar,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  ResponsiveContainer,
} from 'recharts';
import './Analytics.css';
import { trackingService } from '../services/tracking';

/**
 * Analytics and reports.
 *
 * Reads the on-device analytics engine through the tracking service on an
 * interval, replacing the HTTP calls the components previously made to the
 * local Flask backend.
 */
function Analytics({ status }) {
  const [summary, setSummary] = useState(null);
  const [report, setReport] = useState(null);
  const [cupTracking, setCupTracking] = useState(null);
  const [loading, setLoading] = useState(false);
  const refreshKey = `${status?.frame_count ?? 0}:${status?.running ? 1 : 0}`;

  useEffect(() => {
    setSummary(trackingService.getAnalyticsSummary());
    setCupTracking(trackingService.getCupTracking());
  }, [refreshKey]);

  const handleGenerateReport = () => {
    setLoading(true);
    try {
      setReport(trackingService.getAnalyticsReport());
    } finally {
      setLoading(false);
    }
  };

  const trendData = useMemo(
    () => (report?.detection_trend ?? []).map((value, index) => ({ index, value })),
    [report],
  );

  const colorData = useMemo(
    () =>
      Object.entries(report?.color_distribution ?? {}).map(([color, count]) => ({
        color,
        count,
      })),
    [report],
  );

  return (
    <div className="analytics">
      <div className="analytics-header">
        <h2>📊 Analytics &amp; Reports</h2>
        <button className="generate-btn" onClick={handleGenerateReport} disabled={loading}>
          📋 Generate Report
        </button>
      </div>

      {summary && (
        <div className="summary-cards">
          <div className="card">
            <h4>Session Duration</h4>
            <p className="big-number">{Math.floor(summary.session_duration_seconds)}s</p>
          </div>
          <div className="card">
            <h4>Frames Analyzed</h4>
            <p className="big-number">{summary.total_frames_analyzed}</p>
          </div>
          <div className="card">
            <h4>Objects Detected</h4>
            <p className="big-number">{summary.total_objects_detected}</p>
          </div>
          <div className="card">
            <h4>Average FPS</h4>
            <p className="big-number">{summary.fps?.toFixed(1)}</p>
          </div>
          <div className="card">
            <h4>Avg Objects/Frame</h4>
            <p className="big-number">{summary.avg_objects_per_frame?.toFixed(2)}</p>
          </div>
          <div className="card">
            <h4>Avg Confidence</h4>
            <p className="big-number">{summary.avg_confidence?.toFixed(1)}%</p>
          </div>
        </div>
      )}

      {cupTracking && cupTracking.cups?.length > 0 && (
        <div className="cup-section">
          <h3>Cup Tracking</h3>
          <div className="status-info">
            <p><strong>Candidates in frame:</strong> {cupTracking.total_detected}</p>
            {cupTracking.cups.map((cup) => (
              <p key={cup.id}>
                <strong>{cup.id}</strong> — {cup.color}, confidence {(cup.confidence ?? 0).toFixed(1)}%,
                position ({cup.centroid?.x}, {cup.centroid?.y})
              </p>
            ))}
          </div>
          {cupTracking.predictions?.length > 0 && (
            <div className="status-info">
              <p><strong>Movement predictions:</strong></p>
              {cupTracking.predictions.map((pred) => (
                <p key={pred.object_id}>
                  <strong>{pred.object_id}</strong> — next ({pred.predicted_pos.x},{' '}
                  {pred.predicted_pos.y}) at velocity ({pred.velocity.x}, {pred.velocity.y})
                </p>
              ))}
            </div>
          )}
        </div>
      )}

      {report && (
        <div className="report-section">
          <h3>Detailed Report</h3>

          {trendData.length > 0 && (
            <div className="chart-container">
              <h4>Detection Trend</h4>
              <ResponsiveContainer width="100%" height={300}>
                <LineChart data={trendData}>
                  <CartesianGrid strokeDasharray="3 3" />
                  <XAxis dataKey="index" />
                  <YAxis />
                  <Tooltip />
                  <Line type="monotone" dataKey="value" stroke="#8884d8" />
                </LineChart>
              </ResponsiveContainer>
            </div>
          )}

          <div className="metrics-grid">
            <div className="metric">
              <h4>Average Motion Level</h4>
              <p>{report.average_motion?.toFixed(1)}</p>
            </div>
            <div className="metric">
              <h4>Average Brightness</h4>
              <p>{report.average_brightness?.toFixed(1)}%</p>
            </div>
          </div>

          {colorData.length > 0 && (
            <div className="chart-container">
              <h4>Color Distribution</h4>
              <ResponsiveContainer width="100%" height={300}>
                <BarChart data={colorData}>
                  <CartesianGrid strokeDasharray="3 3" />
                  <XAxis dataKey="color" />
                  <YAxis />
                  <Tooltip />
                  <Bar dataKey="count" fill="#82ca9d" />
                </BarChart>
              </ResponsiveContainer>
            </div>
          )}
        </div>
      )}

      {loading && <div className="loading">Loading...</div>}
    </div>
  );
}

export default Analytics;