import React, { useEffect, useState } from 'react';
import './App.css';
import Dashboard from './components/Dashboard';
import TrackingDisplay from './components/TrackingDisplay';
import Analytics from './components/Analytics';
import BrandMark from './components/BrandMark';
import Icon from './components/Icon';
import LaunchOverlay from './components/LaunchOverlay';
import { trackingService } from './services/tracking';

/**
 * Application shell.
 *
 * The dashboard is the home tab: it is where the user enables the on-screen
 * toolbar and sees status. Live view and analytics are secondary. Tracking is not
 * started from here — the toolbar owns that on Android — so the shell only
 * reflects state.
 */
function App() {
  const [currentTab, setCurrentTab] = useState('home');
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

  const native = status.native;
  const toolbarLive = Boolean(native?.overlay);
  const captureLive = Boolean(native?.capture) || Boolean(status.running);
  const isActive = captureLive || toolbarLive;

  const statusLabel = toolbarLive
    ? `Toolbar · ${native?.mode ?? 'ready'}`
    : captureLive
      ? `Capturing · ${status.fps?.toFixed(1) ?? '0.0'} FPS`
      : 'Idle';

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
          <span className={`status ${isActive ? 'connected' : 'disconnected'}`}></span>
          {statusLabel}
        </div>
      </header>

      <nav className="app-nav" role="tablist">
        <button
          type="button"
          role="tab"
          aria-selected={currentTab === 'home'}
          className={`nav-btn ${currentTab === 'home' ? 'active' : ''}`}
          onClick={() => setCurrentTab('home')}
        >
          <Icon name="gauge" />
          Dashboard
        </button>
        <button
          type="button"
          role="tab"
          aria-selected={currentTab === 'tracking'}
          className={`nav-btn ${currentTab === 'tracking' ? 'active' : ''}`}
          onClick={() => setCurrentTab('tracking')}
        >
          <Icon name="crosshair" />
          Live
        </button>
        <button
          type="button"
          role="tab"
          aria-selected={currentTab === 'analytics'}
          className={`nav-btn ${currentTab === 'analytics' ? 'active' : ''}`}
          onClick={() => setCurrentTab('analytics')}
        >
          <Icon name="chart" />
          Analytics
        </button>
      </nav>

      <main className="app-main">
        {currentTab === 'home' && <Dashboard status={status} />}
        {currentTab === 'tracking' && <TrackingDisplay status={status} />}
        {currentTab === 'analytics' && <Analytics status={status} />}
      </main>

      <footer className="app-footer">
        <p>
          Favie X Tracker v1.0.0 &nbsp;|&nbsp; On-device tracking
          {status.capture_source && status.capture_source !== 'none'
            ? ` · ${status.capture_source}`
            : ''}
        </p>
      </footer>
    </div>
  );
}

export default App;