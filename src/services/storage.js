/**
 * Local persistence.
 *
 * Sessions, reports and runtime configuration are written to device storage so
 * the app keeps working offline, as documented. Uses the Capacitor Preferences
 * plugin when running natively and falls back to localStorage in a browser.
 */

import { Capacitor } from '@capacitor/core';

const KEY_SESSIONS = 'favie.sessions';
const KEY_CONFIG = 'favie.config';
const KEY_LAST_REPORT = 'favie.lastReport';

const MAX_SESSIONS = 20;

async function backend() {
  try {
    if (Capacitor.isNativePlatform()) {
      const { Preferences } = await import('@capacitor/preferences');
      return {
        async get(key) {
          const { value } = await Preferences.get({ key });
          return value ?? null;
        },
        async set(key, value) {
          await Preferences.set({ key, value });
        },
        async remove(key) {
          await Preferences.remove({ key });
        },
      };
    }
  } catch {
    // Plugin unavailable (web build); fall through to localStorage.
  }

  return {
    async get(key) {
      return window.localStorage.getItem(key);
    },
    async set(key, value) {
      window.localStorage.setItem(key, value);
    },
    async remove(key) {
      window.localStorage.removeItem(key);
    },
  };
}

async function readJson(key, fallback) {
  try {
    const store = await backend();
    const raw = await store.get(key);
    if (!raw) return fallback;
    return JSON.parse(raw);
  } catch {
    return fallback;
  }
}

async function writeJson(key, value) {
  const store = await backend();
  await store.set(key, JSON.stringify(value));
}

export const storage = {
  async loadSessions() {
    const sessions = await readJson(KEY_SESSIONS, []);
    return Array.isArray(sessions) ? sessions : [];
  },

  /** Persist a completed session and return the retained history. */
  async saveSession(session) {
    const sessions = await storage.loadSessions();
    sessions.unshift(session);
    const trimmed = sessions.slice(0, MAX_SESSIONS);
    await writeJson(KEY_SESSIONS, trimmed);
    return trimmed;
  },

  async saveReport(report) {
    await writeJson(KEY_LAST_REPORT, report);
  },

  async loadLastReport() {
    return readJson(KEY_LAST_REPORT, null);
  },

  async loadConfig() {
    return readJson(KEY_CONFIG, null);
  },

  async saveConfig(config) {
    await writeJson(KEY_CONFIG, config);
  },

  async listStorageKeys() {
    const store = await backend();
    return [KEY_SESSIONS, KEY_CONFIG, KEY_LAST_REPORT].map(async (key) => ({
      key,
      value: await store.get(key),
    }));
  },

  async clear() {
    const store = await backend();
    await Promise.all([store.remove(KEY_SESSIONS), store.remove(KEY_LAST_REPORT)]);
  },
};

export default storage;