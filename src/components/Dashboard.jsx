import React, { useCallback, useEffect, useState } from 'react';
import './Dashboard.css';
import Icon from './Icon';
import { trackingService } from '../services/tracking';
import { overlayToolbar } from '../services/capture/android/toolbar';

/**
 * Configuration dashboard.
 *
 * This screen sets the app up; it does not drive tracking. On Android the
 * on-screen toolbar owns start/stop/target controls, because those have to be
 * reachable while the user is looking at another app. What lives here is the one
 * decision the app itself has to make — turn the toolbar and capture on — plus a
 * read-out of what the service is doing.
 *
 * On the web build the floating toolbar does not exist, so the same button falls
 * back to the in-page capture loop and the note explains the difference.
 */
const MODE_LABELS = {
  ready: 'Ready',
  selecting: 'Select a target',
  tracking: 'Tracking target',
  lost: 'Target lost',
};

const MODE_TONES = {
  ready: 'idle',
  selecting: 'warn',
  tracking: 'ok',
  lost: 'warn',
};

function StatusRow({ label, value, tone = 'neutral', hint, action }) {
  return (
    <div className="status-row">
      <div className="status-row-main">
        <span className="status-row-label">{label}</span>
        <span className="status-row-right">
          <span className={`status-row-value tone-${tone}`}>
            <span className="status-dot" aria-hidden="true" />
            {value}
          </span>
          {action}
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

/** Description for the toolbar switch, which is the app's primary control. */
function toolbarDescription(supported, captureOn, toolbarOn) {
  if (!supported) return 'Only available in the Android app.';
  if (!captureOn) {
    return 'Requests the two permissions, then shows the floating control bar.';
  }
  return toolbarOn
    ? 'Hides the bar without stopping capture or losing the target.'
    : 'Shows the floating control bar over other apps.';
}

/**
 * Normalise the two target report shapes into one the panel can render.
 *
 * The native toolbar reports a flat target ({@code {state, confidence}}) and only
 * when a box exists, while the in-page tracker reports its whole state
 * ({@code {state, target, lost_frames}}) whether or not a target is locked. Without
 * this, the nested wrapper reads as a target and the row claims a lock that does
 * not exist.
 */
function readTarget(supported, native, jsReport) {
  if (supported) {
    if (!native?.target) return null;
    return { state: native.target.state, confidence: native.target.confidence ?? 0 };
  }
  if (!jsReport?.target) return null;
  return { state: jsReport.state, confidence: jsReport.target.confidence ?? 0 };
}

/**
 * Tracking mode, which also differs between the two report shapes.
 *
 * The native service names its states ready/selecting/tracking/lost, while the
 * in-page tracker reports none/locked/lost. They are mapped onto the native
 * vocabulary here so the panel has one set of labels to render.
 */
function readMode(supported, native, jsReport) {
  if (supported) return native?.mode ?? 'ready';

  const state = jsReport?.target ? jsReport.state : 'none';
  if (state === 'locked') return 'tracking';
  if (state === 'lost') return 'lost';
  return 'ready';
}

function Dashboard({ status }) {
  const [message, setMessage] = useState(null);
  const [busy, setBusy] = useState(false);
  const [busyKey, setBusyKey] = useState(null);
  const [overlayPermission, setOverlayPermission] = useState(null);

  const supported = trackingService.isToolbarSupported();
  const native = status?.native;
  const jsReport = status?.target;
  const toolbarOn = Boolean(native?.overlay);
  const captureOn = Boolean(native?.capture) || Boolean(status?.running);
  const running = captureOn || toolbarOn;
  const mode = readMode(supported, native, jsReport);
  const target = readTarget(supported, native, jsReport);

  /** Overlay permission is only reported by the native plugin. */
  const refreshPermission = useCallback(async () => {
    if (!supported) return;
    const result = await overlayToolbar.hasOverlayPermission();
    setOverlayPermission(Boolean(result?.granted));
  }, [supported]);

  /**
   * Pull the toolbar's own state, which reaches the panel through the service's
   * emitted status rather than being stored here.
   */
  const refresh = useCallback(async () => {
    if (!supported) return;
    await trackingService.syncNativeStatus();
  }, [supported]);

  useEffect(() => {
    refresh();
  }, [refresh, status?.frame_count, status?.running]);

  useEffect(() => {
    refreshPermission();
  }, [refreshPermission, running]);

  const run = async (key, action) => {
    setBusy(true);
    setBusyKey(key);
    setMessage(null);
    try {
      const result = await action();
      if (result?.status === 'error' || result?.status === 'unsupported') {
        setMessage(result.message ?? 'That action could not be completed.');
      }
      await refresh();
      await refreshPermission();
    } catch (error) {
      setMessage(error?.message ?? 'Something went wrong.');
    } finally {
      setBusy(false);
      setBusyKey(null);
    }
  };

  /**
   * Master switch for the whole feature.
   *
   * On Android this collects the overlay and MediaProjection consents and starts
   * the foreground service that owns capture, the toolbar and the target lock, so
   * it keeps working after the user leaves this screen. In the browser it starts
   * the in-page capture loop.
   */
  const handleScreenTrackingToggle = (next) => {
    if (!supported) {
      run('capture', () => (next ? trackingService.start() : trackingService.stop()));
      return;
    }
    run('capture', () =>
      next ? trackingService.enableToolbar() : trackingService.disableToolbar(),
    );
  };

  /**
   * Show or hide the toolbar.
   *
   * When nothing is running yet this is the switch the user is told to flip, so
   * it boots the service (collecting the overlay and capture consents on the way)
   * rather than refusing until capture is on. Once the service is up it only
   * hides or shows the bar, so toggling it never drops the target.
   */
  const handleToolbarVisibleToggle = (next) => {
    if (!supported) return;
    if (!captureOn) {
      run('toolbar', () => trackingService.enableToolbar());
      return;
    }
    run('toolbar', () => trackingService.setToolbarVisible(next));
  };

  const handleGrantOverlay = () => {
    run('permission', async () => {
      await overlayToolbar.requestOverlayPermission();
      return { status: 'ok' };
    });
  };

  /**
   * Drop the target.
   *
   * Routed through the service so the Android path clears the overlay's tracker
   * (which owns the marker) rather than a parallel app-side lock.
   */
  const handleClearTarget = () => {
    run('target', async () => {
      const result = await trackingService.clearTarget();
      return result?.status === 'error' ? result : { status: 'ok' };
    });
  };

  const handleReacquire = () => {
    run('target', async () => trackingService.reacquireTarget());
  };

  const targetLabel = target
    ? `${target.state === 'lost' ? 'Lost' : 'Locked'} · ${Math.round(target.confidence ?? 0)}%`
    : 'None selected';

  return (
    <div className="dashboard">
      <section className="hero card">
        <div className="hero-top">
          <div>
            <h2>Tracker</h2>
            <p className="hero-sub">
              Authorised on-device object tracking. You choose the target; the marker
              follows that object and nothing else.
            </p>
          </div>
          <span className={`state-pill ${running ? 'on' : 'off'}`}>
            {running ? 'Enabled' : 'Idle'}
          </span>
        </div>

        <p className="hero-note">
          {supported
            ? 'Capture and the toolbar run as a foreground service, so they keep working while you use other apps.'
            : 'This build has no floating toolbar; capture comes from the page itself.'}
        </p>

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
        <h3 className="card-title">Status</h3>

        <StatusRow
          label="Screen capture"
          value={captureOn ? 'Enabled' : 'Inactive'}
          tone={captureOn ? 'ok' : 'idle'}
          hint={
            captureOn
              ? 'Runs in a foreground service, so it continues while you use other apps.'
              : 'Nothing is being captured.'
          }
        />
        <StatusRow
          label="Overlay toolbar"
          value={toolbarOn ? 'Visible' : supported ? 'Hidden' : 'Unavailable'}
          tone={toolbarOn ? 'ok' : 'idle'}
          hint={
            supported && captureOn && !toolbarOn
              ? 'Capture is running; switch the toolbar back on under Controls to show the bar again.'
              : undefined
          }
        />
        <StatusRow
          label="Tracking"
          value={MODE_LABELS[mode] ?? 'Ready'}
          tone={MODE_TONES[mode] ?? 'idle'}
        />
        <StatusRow
          label="Target"
          value={targetLabel}
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
          <p className="card-note card-note--error">
            <Icon name="alert" size={15} />
            {message}
          </p>
        )}
      </section>

      {supported && (
        <section className="card">
          <h3 className="card-title">Permissions</h3>

          <StatusRow
            label="Display over other apps"
            value={
              overlayPermission === null
                ? 'Checking…'
                : overlayPermission
                  ? 'Granted'
                  : 'Not granted'
            }
            tone={overlayPermission ? 'ok' : 'warn'}
            hint="Required before the floating toolbar can appear over another app."
            action={
              overlayPermission ? null : (
                <button
                  type="button"
                  className="btn btn-ghost"
                  onClick={handleGrantOverlay}
                  disabled={busy}
                >
                  Grant
                </button>
              )
            }
          />
          <StatusRow
            label="Screen capture"
            value={captureOn ? 'Granted' : 'Asked when you enable the toolbar'}
            tone={captureOn ? 'ok' : 'idle'}
            hint="Android asks for capture consent every session; it cannot be remembered for you."
          />
        </section>
      )}

      <section className="card">
        <h3 className="card-title">Controls</h3>

        <Toggle
          id="toggle-capture"
          label="Enable screen tracking"
          description={
            supported
              ? 'Requests capture consent and starts the tracking service, then becomes a status switch.'
              : 'Captures this page. The floating toolbar needs the Android app.'
          }
          checked={captureOn}
          busy={busy && busyKey === 'capture'}
          onChange={handleScreenTrackingToggle}
        />

        <Toggle
          id="toggle-toolbar"
          label="Enable on-screen toolbar"
          description={toolbarDescription(supported, captureOn, toolbarOn)}
          checked={toolbarOn}
          disabled={!supported}
          busy={busy && busyKey === 'toolbar'}
          onChange={handleToolbarVisibleToggle}
        />

        {!supported && (
          <p className="card-note">
            The floating toolbar requires the Android app. In this environment capture
            comes from the page itself.
          </p>
        )}
      </section>

      <section className="card">
        <h3 className="card-title">How to track an object</h3>
        <ol className="steps">
          <li>Enable the on-screen toolbar and approve the two prompts.</li>
          <li>Switch to the app holding the object you want to track.</li>
          <li>
            Tap <strong>Select target</strong> on the toolbar, then tap the object once.
          </li>
          <li>
            The marker follows that object. If it is hidden for too long the toolbar offers{' '}
            <strong>Reacquire</strong> rather than switching to a different object.
          </li>
        </ol>
      </section>
    </div>
  );
}

export default Dashboard;
