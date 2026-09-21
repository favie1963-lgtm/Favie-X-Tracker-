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
import Icon from './Icon';
import { trackingService } from '../services/tracking';

/**
 * Analytics and reports.
 *
 * Reads the on-device analytics engine through the tracking service on an
 * interval, replacing the HTTP calls the components previously made to the
 * local Flask backend.
 *
 * Chart chrome is passed as props rather than left to Recharts' defaults: the
 * defaults are light-theme blue and near-white grid lines, which are unreadable
 * on the dark surface this app uses.
 */
const AXIS_COLOR = '#a3a3a3';
const GRID_COLOR = '#2c2c2c';
const SERIES_COLOR = '#ff2d3f';

const tooltipStyles = {
  contentStyle: {
    background: '#161616',
    border: '1px solid #3d3d3d',
    borderRadius: '7px',
    color: '#f5f5f5',
    fontSize: '12px',
  },
  labelStyle: { color: '#a3a3a3' },
  itemStyle: { color: '#f5f5f5' },
  cursor: { fill: 'rgba(255,255,255,0.06)' },
};

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

  const summaryCards = summary
    ? [
        ['Session duration', `${Math.floor(summary.session_duration_seconds)}s`],
        ['Frames analyzed', summary.total_frames_analyzed],
        ['Objects detected', summary.total_objects_detected],
        ['Average FPS', (summary.fps ?? 0).toFixed(1)],
        ['Avg objects/frame', (summary.avg_objects_per_frame ?? 0).toFixed(2)],
        ['Avg confidence', `${(summary.avg_confidence ?? 0).toFixed(1)}%`],
      ]
    : [];

  return (
    <div className="analytics">
      <header className="section-header">
        <h2>
          <Icon name="chart" />
          Analytics &amp; reports
        </h2>
        <button type="button" className="btn btn-primary" onClick={handleGenerateReport} disabled={loading}>
          <Icon name="report" size={16} />
          Generate report
        </button>
      </header>

      {summary && (
        <div className="summary-cards">
          {summaryCards.map(([label, value]) => (
            <div className="card" key={label}>
              <h4>{label}</h4>
              <p className="big-number">{value}</p>
            </div>
          ))}
        </div>
      )}

      {cupTracking && cupTracking.cups?.length > 0 && (
        <section className="panel cup-section">
          <h3>Cup tracking</h3>
          <p className="cup-count">
            {cupTracking.total_detected} cup candidate{cupTracking.total_detected === 1 ? '' : 's'} in
            frame
          </p>
          <ul className="cup-list">
            {cupTracking.cups.map((cup) => (
              <li key={cup.id}>
                <span className="object-id">{cup.id}</span>
                <span>
                  {cup.color} · {(cup.confidence ?? 0).toFixed(1)}% · ({cup.centroid?.x},{' '}
                  {cup.centroid?.y})
                </span>
              </li>
            ))}
          </ul>
          {cupTracking.predictions?.length > 0 && (
            <>
              <h4 className="sub-heading">Movement predictions</h4>
              <ul className="cup-list">
                {cupTracking.predictions.map((pred) => (
                  <li key={pred.object_id}>
                    <span className="object-id">{pred.object_id}</span>
                    <span>
                      next ({pred.predicted_pos.x}, {pred.predicted_pos.y}) · velocity (
                      {pred.velocity.x}, {pred.velocity.y})
                    </span>
                  </li>
                ))}
              </ul>
            </>
          )}
        </section>
      )}

      {report && (
        <section className="report-section">
          <div className="panel-header">
            <h3>Detailed report</h3>
            <p className="section-meta">
              Generated {new Date(report.report_generated).toLocaleTimeString()}
            </p>
          </div>

          <div className="metrics-grid">
            <div className="metric">
              <h4>Average motion level</h4>
              <p>{(report.average_motion ?? 0).toFixed(1)}</p>
            </div>
            <div className="metric">
              <h4>Average brightness</h4>
              <p>{(report.average_brightness ?? 0).toFixed(1)}%</p>
            </div>
          </div>

          {trendData.length > 0 && (
            <div className="chart-container">
              <h4>Detection trend</h4>
              <ResponsiveContainer width="100%" height={260}>
                <LineChart data={trendData} margin={{ top: 8, right: 12, bottom: 4, left: -18 }}>
                  <CartesianGrid stroke={GRID_COLOR} strokeDasharray="3 3" vertical={false} />
                  <XAxis
                    dataKey="index"
                    stroke={GRID_COLOR}
                    tick={{ fill: AXIS_COLOR, fontSize: 11 }}
                  />
                  <YAxis stroke={GRID_COLOR} tick={{ fill: AXIS_COLOR, fontSize: 11 }} allowDecimals={false} />
                  <Tooltip {...tooltipStyles} />
                  <Line
                    type="monotone"
                    dataKey="value"
                    name="Objects"
                    stroke={SERIES_COLOR}
                    strokeWidth={2}
                    dot={false}
                  />
                </LineChart>
              </ResponsiveContainer>
            </div>
          )}

          {colorData.length > 0 && (
            <div className="chart-container">
              <h4>Colour distribution</h4>
              <ResponsiveContainer width="100%" height={260}>
                <BarChart data={colorData} margin={{ top: 8, right: 12, bottom: 4, left: -18 }}>
                  <CartesianGrid stroke={GRID_COLOR} strokeDasharray="3 3" vertical={false} />
                  <XAxis
                    dataKey="color"
                    stroke={GRID_COLOR}
                    tick={{ fill: AXIS_COLOR, fontSize: 11 }}
                  />
                  <YAxis stroke={GRID_COLOR} tick={{ fill: AXIS_COLOR, fontSize: 11 }} allowDecimals={false} />
                  <Tooltip {...tooltipStyles} />
                  <Bar dataKey="count" name="Frames" fill={SERIES_COLOR} radius={[3, 3, 0, 0]} />
                </BarChart>
              </ResponsiveContainer>
            </div>
          )}
        </section>
      )}

      {!summary && !report && (
        <p className="empty-message">Start tracking to collect analytics for this session.</p>
      )}
    </div>
  );
}

export default Analytics;