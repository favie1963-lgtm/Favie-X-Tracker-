package com.favie.xtracker;

import android.os.Bundle;

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
 * The service is deliberately not started here. MediaProjection consent cannot be
 * persisted — Android requires the user to re-approve capture for each new
 * projection — so auto-starting would either fail or post a persistent
 * notification for a session that cannot actually capture. The user enables the
 * toolbar explicitly, and that flow requests consent.
 */
public class MainActivity extends BridgeActivity {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        // Must run before super.onCreate so the plugin is present when the
        // bridge loads the web bundle that calls into it.
        registerPlugin(ScreenCapturePlugin.class);
        super.onCreate(savedInstanceState);
    }
}
