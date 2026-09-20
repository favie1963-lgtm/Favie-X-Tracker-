package com.favie.xtracker;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import androidx.activity.result.ActivityResult;
import androidx.core.content.ContextCompat;

import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

/**
 * Thin control surface over {@link ScreenCaptureService}.
 *
 * The plugin no longer owns the projection, the virtual display or the frame
 * reader: those live in the foreground service so they survive the Activity. The
 * plugin's job is only the interaction that must happen in an Activity context —
 * the MediaProjection consent dialog and the "draw over other apps" settings
 * screen — and forwarding the granted projection into the service.
 *
 * Calls are grouped so the WebView can drive the same workflow as the on-screen
 * toolbar:
 *  - {@code startOverlay} / {@code stopOverlay} — enable/disable the toolbar and
 *    capture together, collecting the two consents as needed;
 *  - {@code setMode} / {@code selectTarget} — mirror the toolbar's selection flow
 *    from the app UI;
 *  - {@code isOverlayRunning} / {@code hasOverlayPermission} — status for the
 *    dashboard.
 */
@CapacitorPlugin(
        name = "ScreenCapture",
        permissions = {
                @Permission(alias = "notifications", strings = { android.Manifest.permission.POST_NOTIFICATIONS })
        })
public class ScreenCapturePlugin extends Plugin {

    private static final long SERVICE_READY_TIMEOUT_MS = 3000;

    private MediaProjectionManager projectionManager;

    /** Pending consent callback, held so the overlay permission detour can resume it. */
    private PluginCall pendingStartCall;

    @Override
    public void load() {
        projectionManager =
                (MediaProjectionManager) getContext().getSystemService(Context.MEDIA_PROJECTION_SERVICE);
    }

    // --- Overlay toolbar ------------------------------------------------------

    /**
     * Show the on-screen toolbar and start capture.
     *
     * Order matters: overlay permission first (so the toolbar can be added the
     * moment the service starts), then the foreground service, then the
     * MediaProjection consent, then the hand-off. Each step can suspend and be
     * resumed from its callback.
     */
    @PluginMethod
    public void startOverlay(PluginCall call) {
        if (!hasOverlayPermission()) {
            pendingStartCall = call;
            openOverlaySettings();
            return;
        }
        requestCaptureConsent(call);
    }

    @PluginMethod
    public void stopOverlay(PluginCall call) {
        ScreenCaptureService service = ScreenCaptureService.getInstance();
        if (service != null) {
            service.requestStopFromUi();
        } else {
            // No live instance in this process, so there is nothing to tear down;
            // stopService is used as a belt-and-braces call because it is safe from
            // the background, unlike startService, which throws on API 26+ when the
            // app is not in the foreground.
            getContext().stopService(new Intent(getContext(), ScreenCaptureService.class));
        }
        call.resolve(overlayStatus());
    }

    @PluginMethod
    public void isOverlayRunning(PluginCall call) {
        call.resolve(overlayStatus());
    }

    @PluginMethod
    public void hasOverlayPermission(PluginCall call) {
        JSObject result = new JSObject();
        result.put("granted", hasOverlayPermission());
        call.resolve(result);
    }

    /** Open the system "draw over other apps" screen for this app. */
    @PluginMethod
    public void requestOverlayPermission(PluginCall call) {
        pendingStartCall = call;
        openOverlaySettings();
    }

    /** Show or hide the toolbar without stopping capture. */
    @PluginMethod
    public void setToolbarVisible(PluginCall call) {
        ScreenCaptureService service = ScreenCaptureService.getInstance();
        if (service == null) {
            call.reject("Screen capture is not running");
            return;
        }
        service.setToolbarVisible(call.getBoolean("visible", true));
        call.resolve(overlayStatus());
    }

    /** Enter or leave target-selection mode on the toolbar. */
    @PluginMethod
    public void setMode(PluginCall call) {
        String mode = call.getString("mode", "select");
        ScreenCaptureService service = ScreenCaptureService.getInstance();
        if (service == null) {
            call.reject("The tracking toolbar is not running");
            return;
        }
        if ("select".equals(mode)) {
            service.requestSelectionFromUi();
        }
        call.resolve(overlayStatus());
    }

    /**
     * Reacquire the locked target at its last known position.
     *
     * Mirrors the toolbar's own Reacquire button so the dashboard's copy of the
     * control can drive the same service code.
     */
    @PluginMethod
    public void reacquireTarget(PluginCall call) {
        ScreenCaptureService service = ScreenCaptureService.getInstance();
        if (service == null) {
            call.reject("The tracking toolbar is not running");
            return;
        }
        service.reacquireFromUi();
        call.resolve(overlayStatus());
    }

    /** Drop the current target. */
    @PluginMethod
    public void clearTarget(PluginCall call) {
        ScreenCaptureService service = ScreenCaptureService.getInstance();
        if (service == null) {
            call.reject("The tracking toolbar is not running");
            return;
        }
        service.clearTargetFromUi();
        call.resolve(overlayStatus());
    }

    /**
     * Lock the target at a point the app supplied.
     *
     * Used by the in-app mirror of the toolbar. The normal path is the overlay's
     * own tap handling, which does not involve the WebView at all.
     */
    @PluginMethod
    public void selectTarget(PluginCall call) {
        ScreenCaptureService service = ScreenCaptureService.getInstance();
        if (service == null) {
            call.reject("The tracking toolbar is not running");
            return;
        }
        Double x = call.getDouble("x");
        Double y = call.getDouble("y");
        if (x == null || y == null) {
            call.reject("selectTarget requires x and y");
            return;
        }
        service.selectTargetFromUi(x.floatValue(), y.floatValue());
        call.resolve(overlayStatus());
    }

    // --- Compat surface -------------------------------------------------------

    /**
     * Start capture without showing the toolbar.
     *
     * Retained so the documented {@code ScreenCapture.start} contract keeps
     * working; the toolbar path is {@link #startOverlay}.
     */
    @PluginMethod
    public void start(PluginCall call) {
        startOverlay(call);
    }

    @PluginMethod
    public void stop(PluginCall call) {
        stopOverlay(call);
    }

    @PluginMethod
    public void isRunning(PluginCall call) {
        call.resolve(overlayStatus());
    }

    @PluginMethod
    public void getInfo(PluginCall call) {
        JSObject result = overlayStatus();
        ScreenCaptureService service = ScreenCaptureService.getInstance();
        if (service != null) {
            result.put("frameCount", service.getFrameCount());
            result.put("fps", service.getFps());
            result.put("state", service.getStateName());
        }
        call.resolve(result);
    }

    /** Push the WebView's frame-transport settings to the running service. */
    @PluginMethod
    public void setTransport(PluginCall call) {
        ScreenCaptureService service = ScreenCaptureService.getInstance();
        if (service != null) {
            service.setTransport(call.getInt("maxWidth", 960), call.getInt("quality", 70));
        }
        call.resolve();
    }

    /**
     * Grab a frame as a JPEG data URL.
     *
     * The overlay workflow does not need this — the service analyses frames in
     * process — but the dashboard's live preview and the existing pipeline tests
     * do, so the contract is preserved.
     */
    @PluginMethod
    public void grabFrame(PluginCall call) {
        ScreenCaptureService service = ScreenCaptureService.getInstance();
        if (service == null || !service.isCaptureActive()) {
            call.reject("Screen capture is not running");
            return;
        }

        int maxWidth = call.getInt("maxWidth", 960);
        int quality = call.getInt("quality", 70);

        CaptureEngine.Frame frame = service.captureFrameForBridge(maxWidth, quality);
        if (frame == null) {
            call.resolve(new JSObject().put("dataUrl", (String) null));
            return;
        }

        JSObject result = new JSObject();
        result.put("dataUrl", frame.dataUrl);
        result.put("width", frame.width);
        result.put("height", frame.height);
        call.resolve(result);
    }

    // --- Consent plumbing -----------------------------------------------------

    private void requestCaptureConsent(PluginCall call) {
        if (projectionManager == null) {
            call.reject("MediaProjection is unavailable on this device");
            return;
        }

        // The notification only affects whether the foreground service's
        // notification is visible, so a denial is not treated as failure.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission()) {
            requestPermissionForAlias("notifications", call, "notificationsResult");
            return;
        }
        launchProjectionDialog(call);
    }

    private boolean hasNotificationPermission() {
        return PermissionState.GRANTED.equals(getPermissionState("notifications"));
    }

    @PermissionCallback
    private void notificationsResult(PluginCall call) {
        if (call == null) return;
        launchProjectionDialog(call);
    }

    private void launchProjectionDialog(PluginCall call) {
        Intent intent = projectionManager.createScreenCaptureIntent();
        startActivityForResult(call, intent, "handleProjectionResult");
    }

    @ActivityCallback
    private void handleProjectionResult(PluginCall call, ActivityResult result) {
        if (call == null) return;

        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) {
            call.reject("Screen capture permission was denied");
            return;
        }

        try {
            startCaptureWithProjection(result.getResultCode(), result.getData(), call);
        } catch (Exception e) {
            call.reject("Unable to start screen capture: " + e.getMessage(), e);
        }
    }

    private void startCaptureWithProjection(int resultCode, Intent data, PluginCall call) {
        if (ScreenCaptureService.isRunning()) {
            handOffProjection(resultCode, data, call);
            return;
        }

        startService();
        awaitServiceReady(() -> handOffProjection(resultCode, data, call), call);
    }

    /**
     * Create the projection and give it to the service, which owns it from here.
     */
    private void handOffProjection(int resultCode, Intent data, PluginCall call) {
        ScreenCaptureService service = ScreenCaptureService.getInstance();
        if (service == null) {
            call.reject("The screen capture service did not start");
            return;
        }

        MediaProjection projection = projectionManager.getMediaProjection(resultCode, data);
        if (projection == null) {
            call.reject("Unable to obtain a MediaProjection instance");
            return;
        }

        DisplayMetrics metrics = new DisplayMetrics();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && getActivity() != null) {
            getActivity().getDisplay().getRealMetrics(metrics);
        } else {
            WindowManager wm = (WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE);
            //noinspection deprecation
            wm.getDefaultDisplay().getRealMetrics(metrics);
        }

        service.attachProjection(projection, metrics.widthPixels, metrics.heightPixels,
                metrics.densityDpi);

        JSObject result = overlayStatus();
        result.put("status", "started");
        call.resolve(result);
    }

    private void startService() {
        Intent intent = new Intent(getContext(), ScreenCaptureService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getContext().startForegroundService(intent);
        } else {
            getContext().startService(intent);
        }
    }

    /**
     * Wait for the service to reach the foreground.
     *
     * Android 14+ refuses a projection unless a foreground service of type
     * mediaProjection is already running, so the callback fires on the service's
     * ready broadcast rather than on the assumption that the start call has taken
     * effect. The timeout prevents a service that fails to start from hanging the
     * call forever.
     */
    private void awaitServiceReady(Runnable onReady, PluginCall call) {
        Handler main = new Handler(Looper.getMainLooper());
        BroadcastReceiver[] holder = new BroadcastReceiver[1];
        boolean[] settled = { false };

        Runnable cleanup = () -> {
            if (holder[0] == null) return;
            try {
                getContext().unregisterReceiver(holder[0]);
            } catch (IllegalArgumentException ignored) {
                // Already unregistered by the other branch.
            }
            holder[0] = null;
        };

        holder[0] = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (settled[0]) return;
                settled[0] = true;
                cleanup.run();
                onReady.run();
            }
        };

        ContextCompat.registerReceiver(
                getContext(),
                holder[0],
                new IntentFilter(ScreenCaptureService.ACTION_READY),
                ContextCompat.RECEIVER_NOT_EXPORTED);

        main.postDelayed(() -> {
            if (settled[0]) return;
            settled[0] = true;
            cleanup.run();
            call.reject("The screen capture service did not start");
        }, SERVICE_READY_TIMEOUT_MS);
    }

    private void openOverlaySettings() {
        if (getActivity() == null) return;
        Intent intent = new Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getContext().getPackageName()));
        getActivity().startActivity(intent);
    }

    private boolean hasOverlayPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        return Settings.canDrawOverlays(getContext());
    }

    /**
     * Resume a start call once the user returns from the overlay settings screen.
     *
     * The settings screen is an Activity, so the result arrives here rather than
     * through the normal plugin lifecycle.
     */
    @Override
    public void handleOnResume() {
        super.handleOnResume();
        PluginCall pending = pendingStartCall;
        if (pending == null || !hasOverlayPermission()) return;
        pendingStartCall = null;
        requestCaptureConsent(pending);
    }

    private JSObject overlayStatus() {
        ScreenCaptureService service = ScreenCaptureService.getInstance();
        JSObject result = new JSObject();
        result.put("running", service != null && service.isCaptureActive());
        result.put("overlay", service != null && service.isToolbarShowing());
        result.put("capture", service != null && service.isCaptureActive());
        result.put("mode", service != null ? service.getStateName() : "ready");
        result.put("selecting", service != null && service.isSelecting());
        result.put("overlayPermission", hasOverlayPermission());
        JSObject target = service != null ? targetJson(service) : null;
        if (target != null) {
            result.put("target", target);
        }
        result.put("frameCount", service != null ? service.getFrameCount() : 0);
        result.put("fps", service != null ? service.getFps() : 0);
        return result;
    }

    private JSObject targetJson(ScreenCaptureService service) {
        NativeTargetTracker tracker = service.getTracker();
        if (tracker.getBoxW() <= 0) return null;

        JSObject target = new JSObject();
        target.put("x", tracker.getBoxX());
        target.put("y", tracker.getBoxY());
        target.put("width", tracker.getBoxW());
        target.put("height", tracker.getBoxH());
        target.put("confidence", tracker.getConfidence());
        target.put("state", tracker.getState() == NativeTargetTracker.STATE_LOST ? "lost" : "locked");
        return target;
    }
}