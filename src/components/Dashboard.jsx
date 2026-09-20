import React, { useCallback, useEffect, useState } from 'react';
import './Dashboard.css';
import Icon from './Icon';
import { trackingService } from '../services/tracking';

/**
 * Configuration dashboard.
 *
 * This screen sets the app up; it does not drive tracking. On Android the
 * on-screen toolbar owns start/stop/target controls, because those have to be
 * reachable while the user is looking at another app. The switches here turn the
 * capture service and the toolbar on and off, which is the only thing the app
 * itself needs to control.
 *
 * On the web build the toolbar is unavailable, so the capture switch falls back
 * to the in-page capture loop and the note explains the difference.
 */
const MODE_LABELS = {
  ready: 'Ready',
  selecting: 'Select a target',
  tracking: 'Tracking target',
  lost: 'Target lost',
};

function StatusRow({ label, value, tone = 'neutral', hint }) {
  return (
    <div className="status-row">
      <div className="status-row-main">
        <span className="status-row-label">{label}</span>
        <span className={`status-row-value tone-${tone}`}>
          <span className="status-dot" aria-hidden="true" />
          {value}
        </span>
      </div>
      {hint && <p className="status-row-hint">{hint}</p>}
    </div>
  );
}

function Toggle({ id, label, description, checked, disabled, busy, onChange }) {
  return (
    <div className={`toggle-row ${disabled ? 'is-disabled' : ''}`}>
      <div className="toggle-text">
        <label htmlFor={id}>{label}</label>
        <p>{description}</p>
      </div>
      <button
        id={id}
        type="button"
        role="switch"
        aria-checked={checked}
        aria-label={label}
        className={`switch ${checked ? 'on' : ''}`}
        disabled={disabled || busy}
        onClick={() => onChange(!checked)}
      >
        <span className="switch-thumb" />
      </button>
    </div>
  );
}

function Dashboard({ status }) {
  const [message, setMessage] = useState(null);
  const [messageTone, setMessageTone] = useState('error');
  const [busy, setBusy] = useState(false);
  const [busyKey, setBusyKey] = useState(null);
  const [target, setTarget] = useState(null);

  const native = status?.native;
  const toolbarSupported = Boolean(status?.toolbar);
  const toolbarOn = Boolean(native?.overlay);
  const captureOn = Boolean(native?.capture) || Boolean(status?.running);
  const mode = native?.mode ?? 'ready';

  const supported = trackingService.isToolbarSupported();

  /** Merge the toolbar's own report with the local frame count for the panel. */
  const refresh = useCallback(async () => {
    if (!supported) {
      setTarget(status?.target ?? null);
      return;
    }
    const report = await trackingService.syncNativeStatus();
    setTarget(report?.target ?? null);
  }, [supported, status?.target]);

  useEffect(() => {
    refresh();
  }, [refresh, status?.frame_count, status?.running]);

  const run = async (key, action) => {
    setBusy(true);
    setBusyKey(key);
    setMessage(null);
    try {
      const result = await action();
      if (result?.status === 'error' || result?.status === 'unsupported') {
        setMessageTone('error');
        setMessage(result.message ?? 'That action could not be completed.');
      } else if (result?.message) {
        setMessageTone('info');
        setMessage(result.message);
      }
      await refresh();
    } catch (error) {
      setMessageTone('error');
      setMessage(error?.message ?? 'Something went wrong.');
    } finally {
      setBusy(false);
      setBusyKey(null);
    }
  };

  const handleCaptureToggle = (next) => {
    if (!supported) {
      // Web build: the in-page pipeline is the capture source.
      run('capture', () => (next ? trackingService.start() : trackingService.stop()));
      return;
    }
    // The capture switch is the master control: turning it on grants consent and
    // starts the foreground service (which owns the projection and the overlay),
    // turning it off stops the service outright.
    run('capture', () =>
      next ? trackingService.enableToolbar() : trackingService.disableToolbar(),
    );
  };

  const handleToolbarToggle = (next) => {
    if (!supported) return;
    // The toolbar switch only shows or hides the floating window; capture keeps
    // running either way, so hiding the bar is not a way to stop tracking.
    run('toolbar', () => trackingService.setToolbarVisible(next));
  };

  const handleClearTarget = () => {
    setMessage(null);
    trackingService.clearTarget();
    refresh();
  };

  const handleReacquire = () => {
    run('target', async () => {
      const result = trackingService.reacquireTarget();
      if (supported) await trackingService.refreshNativeStatus();
      return result;
    });
  };

  const captureDescription = supported
    ? 'Requests screen-capture consent and starts the tracking service.'
    : 'Uses this page as the capture source. The floating toolbar needs the Android app.';

  return (
    <div className="dashboard">
      <section className="hero card">
        <div className="hero-top">
          <div>
            <h2>Tracker</h2>
            <p className="hero-sub">
              Authorised on-device object tracking with an on-screen control toolbar.
            </p>
          </div>
          <span className={`state-pill ${captureOn || toolbarOn ? 'on' : 'off'}`}>
            {captureOn || toolbarOn ? 'Enabled' : 'Idle'}
          </span>
        </div>

        <div className="hero-stats">
          <div className="hero-stat">
            <span className="hero-stat-label">Frames</span>
            <span className="hero-stat-value">{status?.frame_count ?? 0}</span>
          </div>
          <div className="hero-stat">
            <span className="hero-stat-label">Rate</span>
            <span className="hero-stat-value">
              {(native?.fps ?? status?.fps ?? 0).toFixed(1)}
              <em>fps</em>
            </span>
          </div>
          <div className="hero-stat">
            <span className="hero-stat-label">Source</span>
            <span className="hero-stat-value hero-stat-value--text">
              {status?.capture_source && status.capture_source !== 'none'
                ? status.capture_source
                : 'none'}
            </span>
          </div>
        </div>
      </section>

      <section className="card">
        <h3 className="card-title">Controls</h3>

        <Toggle
          id="toggle-capture"
          label="Enable screen tracking"
          description={captureDescription}
          checked={captureOn}
          busy={busy && busyKey === 'capture'}
          onChange={handleCaptureToggle}
        />

        <Toggle
          id="toggle-toolbar"
          label="Enable on-screen toolbar"
          description={
            toolbarSupported
              ? 'Floating control bar, visible over other apps.'
              : 'Only available in the Android app.'
          }
          checked={toolbarOn}
          disabled={!toolbarSupported}
          busy={busy && busyKey === 'toolbar'}
          onChange={handleToolbarToggle}
        />

        {!toolbarSupported && (
          <p className="card-note">
            The on-screen toolbar requires the Android build. In this environment capture comes
            from the page itself.
          </p>
        )}
      </section>

      <section className="card">
        <h3 className="card-title">Status</h3>

        <StatusRow
          label="Screen capture"
          value={captureOn ? 'Active' : 'Inactive'}
          tone={captureOn ? 'ok' : 'idle'}
          hint={
            captureOn
              ? 'Runs in a foreground service, so it continues while you use other apps.'
              : undefined
          }
        />
        <StatusRow
          label="Overlay toolbar"
          value={toolbarOn ? 'Visible' : toolbarSupported ? 'Hidden' : 'Unavailable'}
          tone={toolbarOn ? 'ok' : 'idle'}
        />
        <StatusRow
          label="Tracking"
          value={MODE_LABELS[mode] ?? 'Ready'}
          tone={mode === 'tracking' ? 'ok' : mode === 'lost' ? 'warn' : 'idle'}
        />
        <StatusRow
          label="Target"
          value={
            target
              ? `${target.state === 'lost' ? 'Lost' : 'Locked'} · ${Math.round(
                  target.confidence ?? 0,
                )}%`
              : 'None selected'
          }
          tone={target ? (target.state === 'lost' ? 'warn' : 'ok') : 'idle'}
        />

        {target && (
          <div className="target-actions">
            <button
              type="button"
              className="btn btn-secondary"
              onClick={handleReacquire}
              disabled={busy}
            >
              <Icon name="refresh" size={15} />
              Reacquire target
            </button>
            <button type="button" className="btn btn-ghost" onClick={handleClearTarget}>
              Clear
            </button>
          </div>
        )}

        {status?.error && (
          <p className="card-note card-note--error">
            <Icon name="alert" size={15} />
            {status.error}
          </p>
        )}

        {message && (
          <p className={`card-note ${messageTone === 'error' ? 'card-note--error' : ''}`}>
            <Icon name="alert" size={15} />
            {message}
          </p>
        )}
      </section>

      <section className="card">
        <h3 className="card-title">How to track an object</h3>
        <ol className="steps">
          <li>Enable the on-screen toolbar above and approve the two prompts.</li>
          <li>Switch to the app whose object you want to track.</li>
          <li>
            Tap <strong>Select target</strong> on the toolbar, then tap the object once.
          </li>
          <li>
            The marker follows that object. If it is occluded for too long the toolbar offers{' '}
            <strong>Reacquire</strong> rather than switching to another object.
          </li>
        </ol>
      </section>
    </div>
  );
}

export default Dashboard;