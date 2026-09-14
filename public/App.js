import React, { useState, useEffect } from 'react';
import './App.css';
import Dashboard from './components/Dashboard';
import TrackingDisplay from './components/TrackingDisplay';
import Analytics from './components/Analytics';

function App() {
  const [currentTab, setCurrentTab] = useState('tracking');
  const [isConnected, setIsConnected] = useState(false);

  useEffect(() => {
    // Check backend connection
    const checkConnection = async () => {
      try {
        const result = await window.api.call('GET', '/api/health', {});
        setIsConnected(result.success);
      } catch (error) {
        setIsConnected(false);
      }
    };

    checkConnection();
    const interval = setInterval(checkConnection, 5000);

    return () => clearInterval(interval);
  }, []);

  return (
    <div className="App">
      <header className="app-header">
        <h1>🎯 Favie X Tracker</h1>
        <div className="status-indicator">
          <span className={`status ${isConnected ? 'connected' : 'disconnected'}`}></span>
          {isConnected ? 'Connected' : 'Disconnected'}
        </div>
      </header>

      <nav className="app-nav">
        <button 
          className={`nav-btn ${currentTab === 'tracking' ? 'active' : ''}`}
          onClick={() => setCurrentTab('tracking')}
        >
          📹 Tracking
        </button>
        <button 
          className={`nav-btn ${currentTab === 'dashboard' ? 'active' : ''}`}
          onClick={() => setCurrentTab('dashboard')}
        >
          🎮 Dashboard
        </button>
        <button 
          className={`nav-btn ${currentTab === 'analytics' ? 'active' : ''}`}
          onClick={() => setCurrentTab('analytics')}
        >
          📊 Analytics
        </button>
      </nav>

      <main className="app-main">
        {currentTab === 'tracking' && <TrackingDisplay />}
        {currentTab === 'dashboard' && <Dashboard />}
        {currentTab === 'analytics' && <Analytics />}
      </main>

      <footer className="app-footer">
        <p>Favie X Tracker v1.0.0 | Screen Tracking with AI Analysis</p>
      </footer>
    </div>
  );
}

export default App;
