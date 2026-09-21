/**
 * Bridge to the native overlay toolbar plugin.
 *
 * The toolbar is the primary control surface on Android: it floats over other
 * apps and owns the capture service, so the WebView is only ever a
 * configuration/dashboard screen. Every call is feature-detected so the browser
 * build keeps working without the plugin.
 */

import ScreenCapture, { isNativeCaptureAvailable } from './plugin.js';

export { isNativeCaptureAvailable };

/** Whether the native overlay toolbar can be used on this platform. */
export function isToolbarAvailable() {
  return isNativeCaptureAvailable();
}

export const overlayToolbar = {
  /**
   * Show the toolbar, prompting for overlay permission and screen-capture
   * consent as required.
   *
   * @param {{maxWidth?: number, quality?: number}} options
   * @returns {Promise<{status: string, running?: boolean, message?: string}>}
   */
  async startOverlay(options = {}) {
    try {
      return await ScreenCapture.startOverlay(options);
    } catch (error) {
      return { status: 'error', message: error?.message ?? 'Unable to start the toolbar' };
    }
  },

  /** Hide the toolbar and stop capture. */
  async stopOverlay() {
    try {
      return await ScreenCapture.stopOverlay();
    } catch {
      return { status: 'error' };
    }
  },

  /** Whether the toolbar service is currently running. */
  async isOverlayRunning() {
    try {
      return await ScreenCapture.isOverlayRunning();
    } catch {
      return { running: false, overlay: false, capture: false };
    }
  },

  /**
   * Report a tap in captured-frame coordinates.
   *
   * Invoked by the user through the toolbar rather than by this app: the toolbar
   * asks the user to tap the object, then hands the point back for locking.
   */
  async selectTarget(point) {
    try {
      return await ScreenCapture.selectTarget({ x: point.x, y: point.y });
    } catch (error) {
      return { status: 'error', message: error?.message ?? 'Unable to select target' };
    }
  },

  /** Ask the toolbar to enter or leave target-selection mode. */
  async setMode(mode) {
    try {
      return await ScreenCapture.setMode({ mode });
    } catch {
      return { status: 'error' };
    }
  },

  /**
   * Reacquire the locked target at its last known position.
   *
   * The toolbar's own Reacquire button is the normal path; this lets the
   * dashboard's copy drive the same service call.
   */
  async reacquireTarget() {
    try {
      return await ScreenCapture.reacquireTarget();
    } catch (error) {
      return { status: 'error', message: error?.message ?? 'Unable to reacquire the target' };
    }
  },

  /** Drop the current target. */
  async clearTarget() {
    try {
      return await ScreenCapture.clearTarget();
    } catch (error) {
      return { status: 'error', message: error?.message ?? 'Unable to clear the target' };
    }
  },

  /**
   * Blank the screen down to the tracked object, or show everything again.
   *
   * Mirrors the toolbar's Isolate/Show all control. The veil is drawn by the
   * native overlay window, so this only sets service state.
   */
  async setFocusMode(enabled) {
    try {
      return await ScreenCapture.setFocusMode({ enabled });
    } catch (error) {
      return { status: 'error', message: error?.message ?? 'Unable to change focus mode' };
    }
  },

  /** Whether overlay permission has been granted. */
  async hasOverlayPermission() {
    try {
      return await ScreenCapture.hasOverlayPermission();
    } catch {
      return { granted: false };
    }
  },

  /** Open the system overlay-permission screen for this app. */
  async requestOverlayPermission() {
    try {
      return await ScreenCapture.requestOverlayPermission();
    } catch {
      return { granted: false };
    }
  },

  /** Show or hide the toolbar without stopping capture. */
  async setToolbarVisible(visible) {
    try {
      return await ScreenCapture.setToolbarVisible({ visible });
    } catch (error) {
      return { status: 'error', message: error?.message ?? 'Unable to change the toolbar' };
    }
  },

  /** Full status report: capture, overlay, mode, selection and target. */
  async getStatus() {
    try {
      return await ScreenCapture.getInfo();
    } catch {
      return null;
    }
  },
};

export default overlayToolbar;