package com.favie.xtracker;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;

import androidx.core.content.ContextCompat;

import com.getcapacitor.BridgeActivity;

/**
 * The app's configuration surface.
 *
 * The Activity is intentionally thin: capture, the tracking loop, the target lock
 * and the overlay windows all belong to {@link ScreenCaptureService}, so the app
 * can be closed while the toolbar and capture keep running. This class exists to
 * host the WebView dashboard and to start that service when the user enables the
 * toolbar.
 *
 * <h2>Lifecycle rules this class follows</h2>
 *
 * Nothing here stops capture. A user who opens another application, rotates the
 * device or lets the system destroy the Activity must still find the toolbar and
 * the session running when they come back, because the toolbar is the only way to
 * retarget and stop on Android. That means:
 *
 * <ul>
 *   <li>{@code onPause}/{@code onStop} do nothing to the service. Losing visibility
 *       is the normal case for this app, not an ending.</li>
 *   <li>{@code onDestroy} does not stop the service either. The Activity and the
 *       service have independent lifetimes by design, and a configuration change or
 *       a low-memory reclaim destroys the Activity while the session is still
 *       valid.</li>
 *   <li>{@code onResume} refreshes the overlays only if the platform took them down
 *       with the Activity, so returning to the app never leaves a session with no
 *       visible controls.</li>
 * </ul>
 *
 * The service is deliberately not started here. MediaProjection consent cannot be
 * persisted — Android requires the user to re-approve capture for each new
 * projection — so auto-starting would either fail or post a persistent
 * notification for a session that cannot actually capture. The user enables the
 * toolbar explicitly, and that flow requests consent. A service that the user left
 * running is restored in {@link ScreenCaptureService#onStartCommand}, not here.
 */
public class MainActivity extends BridgeActivity {

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // The service is the source of truth for the session; the WebView only
            // needs to be told that something changed.
            lastState = intent.getStringExtra(ScreenCaptureService.EXTRA_STATE);
        }
    };

    private volatile String lastState;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        // Must run before super.onCreate so the plugin is present when the
        // bridge loads the web bundle that calls into it.
        registerPlugin(ScreenCapturePlugin.class);
        super.onCreate(savedInstanceState);
        ContextCompat.registerReceiver(this, stateReceiver,
                new IntentFilter(ScreenCaptureService.ACTION_STATE),
                ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    @Override
    public void onResume() {
        super.onResume();
        // If the user left a session enabled and the overlays are missing — the
        // platform removes overlay windows when it tears the task down — put them
        // back. This is a repair, not a restart: the projection, if any, is
        // untouched.
        ScreenCaptureService service = ScreenCaptureService.getInstance();
        if (service != null && service.overlayPermissionGranted()) {
            service.ensureOverlaysRestored();
        }
    }

    @Override
    public void onPause() {
        // Deliberately empty. Pausing is what happens when the user switches to the
        // app they want to track; stopping here was the original cause of tracking
        // ending as soon as another application was opened.
        super.onPause();
    }

    @Override
    public void onStop() {
        // Deliberately empty, for the same reason as onPause. Capture, the toolbar
        // and the target lock all outlive this Activity.
        super.onStop();
    }

    @Override
    public void onDestroy() {
        try {
            unregisterReceiver(stateReceiver);
        } catch (IllegalArgumentException ignored) {
            // Already unregistered.
        }
        // The service is not touched: it owns its own lifetime and keeps the
        // session alive across this Activity being destroyed.
        super.onDestroy();
    }

    /** Last state name reported by the service, for diagnostics. */
    public String getLastState() {
        return lastState;
    }
}
