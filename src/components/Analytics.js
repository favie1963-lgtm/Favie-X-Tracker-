import React, { useState, useEffect } from 'react';
import { LineChart, Line, BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, Legend, ResponsiveContainer } from 'recharts';
import './Analytics.css';

function Analytics() {
  const [summary, setSummary] = useState(null);
  const [report, setReport] = useState(null);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    const fetchAnalytics = async () => {
      setLoading(true);
      try {
        const result = await window.api.getAnalytics();
        if (result.success) {
          setSummary(result.data);
        }
      } catch (error) {
        console.error('Error fetching analytics:', error);
      } finally {
        setLoading(false);
      }
    };

    const interval = setInterval(fetchAnalytics, 5000);
    fetchAnalytics();

    return () => clearInterval(interval);
  }, []);

  const handleGenerateReport = async () => {
    setLoading(true);
    try {
      const result = await window.api.getReport();
      if (result.success) {
        setReport(result.data);
      }
    } catch (error) {
      console.error('Error generating report:', error);
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="analytics">
      <div className="analytics-header">
        <h2>📊 Analytics & Reports</h2>
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

      {report && (
        <div className="report-section">
          <h3>Detailed Report</h3>
          
          {report.detection_trend && report.detection_trend.length > 0 && (
            <div className="chart-container">
              <h4>Detection Trend</h4>
              <ResponsiveContainer width="100%" height={300}>
                <LineChart data={report.detection_trend.map((val, idx) => ({ index: idx, value: val }))}>
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

          {report.color_distribution && Object.keys(report.color_distribution).length > 0 && (
            <div className="chart-container">
              <h4>Color Distribution</h4>
              <ResponsiveContainer width="100%" height={300}>
                <BarChart data={Object.entries(report.color_distribution).map(([color, count]) => ({ color, count }))}>
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