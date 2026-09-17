/**
 * Analytics engine.
 *
 * Reproduces the response shapes that were documented for the original backend
 * (see API.md): a session summary, a detailed report with detection trend,
 * average motion, average brightness, colour distribution and recent activity.
 * All figures are derived on-device from the captured frames.
 */

const MAX_TREND_SAMPLES = 120;
const MAX_ACTIVITY = 50;

export class AnalyticsEngine {
  constructor() {
    this.sessionStart = null;
    this.sessionEnd = null;
    this.totalFrames = 0;
    this.totalObjects = 0;
    this.confidenceSum = 0;
    this.confidenceCount = 0;
    this.colorDistribution = {};
    this.detectionTrend = [];
    this.motionSum = 0;
    this.motionCount = 0;
    this.brightnessSum = 0;
    this.brightnessCount = 0;
    this.recentActivity = [];
    this.previousGray = null;
    this.frameTimestamps = [];
  }

  start() {
    this.sessionStart = new Date().toISOString();
    this.sessionEnd = null;
    this.previousGray = null;
  }

  stop() {
    this.sessionEnd = new Date().toISOString();
  }

  /**
   * Record one analysed frame.
   *
   * @param {{width:number,height:number,data:Uint8ClampedArray|Uint8Array}} frame
   * @param {Array<object>} detections
   */
  recordFrame(frame, detections) {
    if (!this.sessionStart) this.start();

    this.totalFrames += 1;
    this.totalObjects += detections.length;
    this.detectionTrend.push(detections.length);
    if (this.detectionTrend.length > MAX_TREND_SAMPLES) this.detectionTrend.shift();

    this.frameTimestamps.push(Date.now());
    if (this.frameTimestamps.length > 30) this.frameTimestamps.shift();

    for (const det of detections) {
      if (typeof det.confidence === 'number' && Number.isFinite(det.confidence)) {
        this.confidenceSum += det.confidence;
        this.confidenceCount += 1;
      }
      const color = det.color ?? 'unknown';
      this.colorDistribution[color] = (this.colorDistribution[color] ?? 0) + 1;
    }

    this.measureFrame(frame);

    this.recentActivity.push({
      timestamp: new Date().toISOString(),
      objects: detections.length,
      fps: this.currentFps(),
    });
    if (this.recentActivity.length > MAX_ACTIVITY) this.recentActivity.shift();
  }

  /** Brightness plus inter-frame motion from a grayscale downscale. */
  measureFrame(frame) {
    const { width, height } = frame;
    const step = Math.max(1, Math.floor(Math.sqrt((width * height) / 4096)));
    const sampleW = Math.max(1, Math.floor(width / step));
    const sampleH = Math.max(1, Math.floor(height / step));

    const gray = new Uint8Array(sampleW * sampleH);
    const data = frame.data;
    let brightness = 0;

    for (let y = 0; y < sampleH; y += 1) {
      for (let x = 0; x < sampleW; x += 1) {
        const sx = Math.min(width - 1, x * step);
        const sy = Math.min(height - 1, y * step);
        const p = (sy * width + sx) * 4;
        const g =
          data[p] * 0.299 + data[p + 1] * 0.587 + data[p + 2] * 0.114;
        gray[y * sampleW + x] = g;
        brightness += g;
      }
    }

    const pixels = sampleW * sampleH;
    this.brightnessSum += (brightness / pixels / 255) * 100;
    this.brightnessCount += 1;

    if (this.previousGray && this.previousGray.length === gray.length) {
      let diff = 0;
      for (let i = 0; i < gray.length; i += 1) diff += Math.abs(gray[i] - this.previousGray[i]);
      this.motionSum += (diff / gray.length / 255) * 100;
      this.motionCount += 1;
    }
    this.previousGray = gray;
  }

  currentFps() {
    const ts = this.frameTimestamps;
    if (ts.length < 2) return 0;
    const elapsed = (ts[ts.length - 1] - ts[0]) / 1000;
    if (elapsed <= 0) return 0;
    return (ts.length - 1) / elapsed;
  }

  sessionDurationSeconds() {
    if (!this.sessionStart) return 0;
    const end = this.sessionEnd ? new Date(this.sessionEnd) : new Date();
    return (end - new Date(this.sessionStart)) / 1000;
  }

  /** Matches the documented `GET /api/analytics/summary`. */
  getSummary() {
    return {
      session_start: this.sessionStart,
      session_duration_seconds: this.sessionDurationSeconds(),
      total_frames_analyzed: this.totalFrames,
      total_objects_detected: this.totalObjects,
      avg_objects_per_frame: this.totalFrames ? this.totalObjects / this.totalFrames : 0,
      avg_confidence: this.confidenceCount ? this.confidenceSum / this.confidenceCount : 0,
      fps: this.currentFps(),
    };
  }

  /** Matches the documented `GET /api/analytics/report`. */
  getReport() {
    return {
      summary: this.getSummary(),
      detection_trend: [...this.detectionTrend],
      average_motion: this.motionCount ? this.motionSum / this.motionCount : 0,
      average_brightness: this.brightnessCount ? this.brightnessSum / this.brightnessCount : 0,
      color_distribution: { ...this.colorDistribution },
      recent_activity: [...this.recentActivity].reverse(),
      report_generated: new Date().toISOString(),
    };
  }

  reset() {
    Object.assign(this, new AnalyticsEngine());
  }
}

export default AnalyticsEngine;