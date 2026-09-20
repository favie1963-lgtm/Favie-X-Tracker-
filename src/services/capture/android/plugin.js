/**
 * Single handle to the native Android screen-capture plugin.
 *
 * `registerPlugin` warns (and is wasteful) if the same plugin name is registered
 * more than once, so both the capture and toolbar wrappers import this instance
 * rather than each registering their own.
 *
 * The plugin lives at
 * android/app/src/main/java/com/favie/xtracker/ScreenCapturePlugin.java.
 */

import { registerPlugin, Capacitor } from '@capacitor/core';

const ScreenCapture = registerPlugin('ScreenCapture');

/** Whether the native plugin is present on this platform. */
export function isNativeCaptureAvailable() {
  return Capacitor.isNativePlatform() && Capacitor.isPluginAvailable('ScreenCapture');
}

/** Whether the app is running on Android. */
export function isAndroid() {
  return Capacitor.getPlatform() === 'android';
}

export default ScreenCapture;