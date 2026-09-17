/**
 * Tracking session orchestration.
 *
 * Owns the capture loop, the vision pipeline and the analytics engine, and
 * exposes the same surface the previous backend offered (`/api/status`,
 * `/api/tracked-objects`, `/api/analytics/*`) so the existing UI components and
 * documented API remain valid.
 */

import { ObjectTracker } from './vision/tracker.js';
import { AnalyticsEngine } from './analytics.js';
import { storage } from './storage.js';
import { createCaptureSource } from './capture/index.js';
import { CUP_TRACKING_MAX_FPS } from './vision/config.js';

const DEFAULT_CONFIG = {
  capture_interval: 500,
  enable_ai_analysis: true,
  max_cups: 3,
  cup_tracking_max_fps: CUP_TRACKING_MAX_FPS,
  analysis_scale: 1,
  debug_mode: false,
};

export class TrackingService {
  constructor() {
    this.config = { ...DEFAULT_CONFIG };
    this.tracker = new ObjectTracker({ analysisScale: this.config.analysis_scale });
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
    this.frameTimestamps = [];
    this.cupLastRun = 0;
    this.listeners = new Set();
    this.sessions = [];
  }

  async init() {
    const saved = await storage.loadConfig();
    if (saved) this.config = { ...this.config, ...saved };
    this.tracker.analysisScale = this.config.analysis_scale;
    this.sessions = await storage.loadSessions();
    return this.config;
  }

  onChange(listener) {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  emit() {
    const status = this.getStatus();
    this.listeners.forEach((fn) => fn(status));
  }

  async start() {
    if (this.running) return { status: 'already-running' };

    this.source = await createCaptureSource({
      maxWidth: this.config.max_frame_width,
      quality: this.config.jpeg_quality,
    });

    if (this.source.kind === 'none') {
      this.lastError =
        'No screen capture source available. On Android the MediaProjection permission is required; in a browser getDisplayMedia must be granted.';
      this.emit();
      return { status: 'error', message: this.lastError };
    }

    this.running = true;
    this.frameCount = 0;
    this.frameTimestamps = [];
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
    this.frameTimestamps.push(Date.now());
    if (this.frameTimestamps.length > 60) this.frameTimestamps.shift();

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

    const now = Date.now();
    const cupInterval = 1000 / Math.max(1, this.config.cup_tracking_max_fps);
    if (now - this.cupLastRun >= cupInterval) {
      this.cupLastRun = now;
      this.lastCupResult = this.tracker.trackCups(frame);
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
}

export const trackingService = new TrackingService();
export { DEFAULT_CONFIG };
export default trackingService;