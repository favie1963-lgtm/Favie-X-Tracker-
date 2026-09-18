import React, { useEffect, useState } from 'react';
import './App.css';
import Dashboard from './components/Dashboard';
import TrackingDisplay from './components/TrackingDisplay';
import Analytics from './components/Analytics';
import BrandMark from './components/BrandMark';
import Icon from './components/Icon';
import LaunchOverlay from './components/LaunchOverlay';
import { trackingService } from './services/tracking';

function App() {
  const [currentTab, setCurrentTab] = useState('tracking');
  const [status, setStatus] = useState(trackingService.getStatus());

  useEffect(() => {
    let unsubscribe = () => {};
    trackingService
      .init()
      .then(() => {
        setStatus(trackingService.getStatus());
        unsubscribe = trackingService.onChange(setStatus);
      })
      .catch(() => {});
    return () => unsubscribe();
  }, []);

  const isConnected = status.running;

  return (
    <div className="App">
      <LaunchOverlay />

      <header className="app-header">
        <div className="brand">
          <BrandMark size={34} className="brand-mark" />
          <h1>
            Favie <span className="brand-x">X</span> Tracker
          </h1>
        </div>
        <div className="status-indicator">
          <span className={`status ${isConnected ? 'connected' : 'disconnected'}`}></span>
          {isConnected ? `Tracking · ${status.fps?.toFixed(1) ?? '0.0'} FPS` : 'Idle'}
        </div>
      </header>

      <nav className="app-nav">
        <button
          type="button"
          className={`nav-btn ${currentTab === 'tracking' ? 'active' : ''}`}
          onClick={() => setCurrentTab('tracking')}
        >
          <Icon name="crosshair" />
          Tracking
        </button>
        <button
          type="button"
          className={`nav-btn ${currentTab === 'dashboard' ? 'active' : ''}`}
          onClick={() => setCurrentTab('dashboard')}
        >
          <Icon name="gauge" />
          Dashboard
        </button>
        <button
          type="button"
          className={`nav-btn ${currentTab === 'analytics' ? 'active' : ''}`}
          onClick={() => setCurrentTab('analytics')}
        >
          <Icon name="chart" />
          Analytics
        </button>
      </nav>

      <main className="app-main">
        {currentTab === 'tracking' && <TrackingDisplay status={status} />}
        {currentTab === 'dashboard' && <Dashboard status={status} />}
        {currentTab === 'analytics' && <Analytics status={status} />}
      </main>

      <footer className="app-footer">
        <p>
          Favie X Tracker v1.0.0 &nbsp;|&nbsp; On-device tracking and analysis
          {status.capture_source && status.capture_source !== 'none'
            ? ` · ${status.capture_source}`
            : ''}
        </p>
      </footer>
    </div>
  );
}

export default App;