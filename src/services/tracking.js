/**
 * Tracking session orchestration.
 *
 * Owns the capture loop, the vision pipeline and the analytics engine, and
 * exposes the same surface the previous backend offered (`/api/status`,
 * `/api/tracked-objects`, `/api/analytics/*`) so the existing UI components and
 * documented API remain valid.
 */

import { ObjectTracker, TargetTracker, TARGET_STATE } from './vision/tracker.js';
import { AnalyticsEngine } from './analytics.js';
import { storage } from './storage.js';
import { createCaptureSource } from './capture/index.js';
import { isToolbarAvailable, overlayToolbar } from './capture/android/toolbar.js';
import { CUP_TRACKING_MAX_FPS } from './vision/config.js';

const DEFAULT_CONFIG = {
  capture_interval: 500,
  enable_ai_analysis: true,
  max_cups: 3,
  cup_tracking_max_fps: CUP_TRACKING_MAX_FPS,
  analysis_scale: 1,
  // Frame transport settings. `maxWidth` is read by both the native plugin and
  // the browser capture source, so both keys have to exist here: passing
  // undefined would silently fall back to each layer's own default and the
  // config would stop being the single source of truth.
  max_frame_width: 960,
  jpeg_quality: 70,
  debug_mode: false,
  // Whether the native floating toolbar should be shown. The toolbar is the
  // primary control surface on Android; the preference is persisted here so the
  // foreground service can restore it after a restart.
  enable_overlay_toolbar: false,
};

export class TrackingService {
  constructor() {
    this.config = { ...DEFAULT_CONFIG };
    this.tracker = new ObjectTracker({ analysisScale: this.config.analysis_scale });
    this.targetTracker = new TargetTracker();
    this.analytics = new AnalyticsEngine();

    this.running = false;
    this.source = null;
    this.loopTimer = null;
    this.frameCount = 0;
    this.lastFrame = null;
    /** Lightweight view of the newest frame, safe to hand to React. */
    this.preview = null;
    this.lastDetections = [];
    this.lastCupResult = null;
    this.lastError = null;
    this.cupLastRun = 0;
    this.listeners = new Set();
    this.sessions = [];
    /** Last native toolbar report, if the toolbar is running. */
    this.nativeStatus = null;
  }

  async init() {
    const saved = await storage.loadConfig();
    if (saved) this.config = { ...this.config, ...saved };
    this.tracker.analysisScale = this.config.analysis_scale;
    this.sessions = await storage.loadSessions();
    // The native toolbar outlives the Activity, so its report is the source of
    // truth for whether it is showing after a process restart.
    await this.refreshNativeStatus();
    return this.config;
  }

  /** Pull the native toolbar's state, if the plugin is present. */
  async refreshNativeStatus() {
    if (!isToolbarAvailable()) {
      this.nativeStatus = null;
      return null;
    }
    try {
      this.nativeStatus = await overlayToolbar.isOverlayRunning();
    } catch {
      this.nativeStatus = null;
    }
    return this.nativeStatus;
  }

  onChange(listener) {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  emit() {
    const status = this.getStatus();
    this.listeners.forEach((fn) => fn(status));
  }

  /**
   * Frame-transport options handed to whichever capture source is chosen.
   *
   * Both settings are read from config rather than hard-coded here, so a
   * caller changing `max_frame_width` or `jpeg_quality` actually takes effect
   * instead of being overridden by a per-layer fallback.
   */
  captureOptions() {
    return {
      maxWidth: this.config.max_frame_width,
      quality: this.config.jpeg_quality,
    };
  }

  async start() {
    if (this.running) return { status: 'already-running' };

    this.source = await createCaptureSource(this.captureOptions());

    if (this.source.kind === 'none') {
      this.lastError =
        'No screen capture source available. On Android the MediaProjection permission is required; in a browser getDisplayMedia must be granted.';
      this.emit();
      return { status: 'error', message: this.lastError };
    }

    this.running = true;
    this.frameCount = 0;
    this.lastError = null;
    this.tracker.reset();
    this.analytics.start();

    const interval = Math.max(50, this.config.capture_interval);
    this.loopTimer = setInterval(() => {
      this.tick().catch((err) => {
        this.lastError = err?.message ?? String(err);
        this.emit();
      });
    }, interval);

    this.emit();
    return { status: 'started', source: this.source.kind };
  }

  async stop() {
    if (!this.running) return { status: 'stopped' };

    clearInterval(this.loopTimer);
    this.loopTimer = null;
    this.running = false;
    await this.source?.stop();
    this.source = null;
    this.analytics.stop();

    const session = {
      started_at: this.analytics.sessionStart,
      ended_at: this.analytics.sessionEnd,
      summary: this.analytics.getSummary(),
    };
    this.sessions = await storage.saveSession(session);
    this.emit();

    return { status: 'stopped', session };
  }

  /** One capture+analyse cycle. */
  async tick() {
    const frame = await this.source?.grab();
    if (!frame) return;

    this.lastFrame = frame;
    this.frameCount += 1;

    // Preview state is kept separate from the raw frame: the raw pixel buffer is
    // large and would be copied into every status notification.
    this.preview = {
      dataUrl: frame.dataUrl,
      width: frame.width,
      height: frame.height,
      timestamp: frame.timestamp,
    };

    if (!this.config.enable_ai_analysis) {
      this.emit();
      return;
    }

    this.lastDetections = this.tracker.detectObjects(frame);
    this.analytics.recordFrame(frame, this.lastDetections);

    // The target lock is advanced before cup tracking so a frame that the user is
    // actively tracking is never the one whose detection is skipped.
    if (this.targetTracker.state !== TARGET_STATE.NONE) {
      this.targetTracker.update(frame, this.lastDetections);
    }

    const now = Date.now();
    const cupInterval = 1000 / Math.max(1, this.config.cup_tracking_max_fps);
    if (now - this.cupLastRun >= cupInterval) {
      this.cupLastRun = now;
      // Reuse the detections already computed above: running the pipeline again
      // both doubled the cost and appended a duplicate frame to the history.
      this.lastCupResult = this.tracker.trackCups(frame, this.lastDetections);
    }

    this.emit();
  }

  /** Matches the documented `GET /api/status`. */
  getStatus() {
    return {
      running: this.running,
      fps: this.analytics.currentFps(),
      frame_count: this.frameCount,
      tracking_data_count: this.tracker.trackedObjects.size,
      capture_source: this.source?.kind ?? 'none',
      error: this.lastError,
      config: { ...this.config },
      lastFrame: this.preview,
      overlay: this.lastDetections,
      target: this.targetTracker.report(),
      native: this.nativeStatus,
      toolbar: isToolbarAvailable(),
    };
  }

  /** Raw latest frame, for consumers that need the pixel buffer. */
  getLatestFrame() {
    return this.lastFrame;
  }

  /** Matches the documented `GET /api/tracked-objects`. */
  getTrackedObjects() {
    return {
      objects: this.tracker.getAllObjects(),
      timestamp: new Date().toISOString(),
    };
  }

  getCupTracking() {
    return this.lastCupResult;
  }

  getAnalyticsSummary() {
    return this.analytics.getSummary();
  }

  getAnalyticsReport() {
    const report = this.analytics.getReport();
    storage.saveReport(report).catch(() => {});
    return report;
  }

  getSessions() {
    return this.sessions;
  }

  async updateConfig(patch) {
    this.config = { ...this.config, ...patch };
    this.tracker.analysisScale = this.config.analysis_scale;
    await storage.saveConfig(this.config);
    this.emit();
    return this.config;
  }

  // --- Target selection -----------------------------------------------------

  /**
   * Lock the tracker onto the object under a tap.
   *
   * @param {{x:number,y:number}} point Coordinates in the captured frame's own
   *   pixel space, which is what the native toolbar reports.
   */
  selectTarget(point) {
    const frame = this.lastFrame;
    if (!frame) {
      return { status: 'error', message: 'No frame available to select from' };
    }
    // Mirror the tap onto the native overlay when it is running. The overlay owns
    // its own tracker and draws the marker itself, so without this the app-side
    // lock and the on-screen marker would disagree about what is selected.
    if (this.isToolbarSupported()) {
      overlayToolbar.selectTarget(point);
    }
    const report = this.targetTracker.select(frame, this.lastDetections, point);
    this.emit();
    return { status: 'selected', target: report.target };
  }

  /**
   * Drop the lock and try to re-acquire at the last known position.
   *
   * Reacquisition is deliberately explicit: the user asks for it, and it either
   * succeeds at the tracked location or fails visibly. It never substitutes a
   * different object.
   */
  reacquireTarget() {
    if (!this.targetTracker.target) {
      return { status: 'error', message: 'No target to reacquire' };
    }
    const frame = this.lastFrame;
    if (!frame) {
      return { status: 'error', message: 'No frame available to reacquire from' };
    }

    const { centroid, bbox } = this.targetTracker.target;
    const report = this.targetTracker.select(frame, this.lastDetections, {
      x: centroid.x,
      y: centroid.y,
    });
    void bbox;
    this.emit();
    return { status: 'reacquired', target: report.target };
  }

  /** Clear the target entirely. */
  clearTarget() {
    this.targetTracker.reset();
    this.emit();
    return { status: 'cleared' };
  }

  /** Current target report. */
  getTarget() {
    return this.targetTracker.report();
  }

  // --- On-screen toolbar ----------------------------------------------------

  /** Whether this build can show the floating toolbar. */
  isToolbarSupported() {
    return isToolbarAvailable();
  }

  /**
   * Show the floating toolbar, requesting overlay and capture consent as needed.
   *
   * The native service owns the projection and the overlay, so this only has to
   * start it and persist the preference.
   */
  async enableToolbar() {
    if (!isToolbarAvailable()) {
      return {
        status: 'unsupported',
        message: 'The on-screen toolbar is only available in the Android app.',
      };
    }

    const result = await overlayToolbar.startOverlay({
      maxWidth: this.config.max_frame_width,
      quality: this.config.jpeg_quality,
    });

    if (result?.status === 'started' || result?.running) {
      await this.updateConfig({ enable_overlay_toolbar: true });
      await this.refreshNativeStatus();
      this.emit();
      return { status: 'started', native: this.nativeStatus };
    }

    return result ?? { status: 'error', message: 'Unable to start the toolbar' };
  }

  /** Hide the floating toolbar and stop the native capture service. */
  async disableToolbar() {
    if (!isToolbarAvailable()) return { status: 'unsupported' };

    await overlayToolbar.stopOverlay();
    await this.updateConfig({ enable_overlay_toolbar: false });
    await this.refreshNativeStatus();
    this.emit();
    return { status: 'stopped' };
  }

  /** Re-read the native toolbar state, for UI polling. */
  async syncNativeStatus() {
    await this.refreshNativeStatus();
    this.emit();
    return this.nativeStatus;
  }

  /** Show or hide the floating toolbar without stopping capture. */
  async setToolbarVisible(visible) {
    if (!isToolbarAvailable()) {
      return { status: 'unsupported', message: 'The toolbar is only available on Android.' };
    }
    const result = await overlayToolbar.setToolbarVisible(visible);
    await this.refreshNativeStatus();
    this.emit();
    return result ?? { status: 'ok' };
  }
}

export const trackingService = new TrackingService();
export { DEFAULT_CONFIG };
export default trackingService;
