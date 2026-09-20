/**
 * Thin wrapper around the native Android screen-capture plugin.
 *
 * The plugin is registered as `ScreenCapture` from
 * android/app/src/main/java/com/favie/xtracker/ScreenCapturePlugin.java.
 * Every call is feature-detected so the web build keeps working without it.
 */

import ScreenCapture, { isNativeCaptureAvailable, isAndroid } from './plugin.js';

export { isNativeCaptureAvailable, isAndroid };

export const nativeCapture = {
  /** Prompt the user for consent and start the foreground capture service. */
  async start(options = {}) {
    return ScreenCapture.start(options);
  },

  /** Stop capture and tear down the foreground service. */
  async stop() {
    return ScreenCapture.stop();
  },

  /** Whether the media projection and service are currently active. */
  async isRunning() {
    try {
      const result = await ScreenCapture.isRunning();
      return Boolean(result?.running);
    } catch {
      return false;
    }
  },

  /**
   * Latest captured frame as a data URL.
   *
   * @param {{maxWidth?: number, quality?: number}} options
   * @returns {Promise<string|null>}
   */
  async grabFrame(options = {}) {
    const result = await ScreenCapture.grabFrame(options);
    return result?.dataUrl ?? null;
  },

  /** Reports the capture display size, useful for diagnostics. */
  async getInfo() {
    try {
      return await ScreenCapture.getInfo();
    } catch {
      return null;
    }
  },
};

export default nativeCapture;