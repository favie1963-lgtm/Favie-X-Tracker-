package com.favie.xtracker;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

/**
 * Owns the whole capture-side feature set: the MediaProjection, the frame loop,
 * the target tracker and both overlay windows.
 *
 * Why this is a service and not part of the plugin: a Capacitor plugin (and the
 * Activity it is attached to) can be destroyed as soon as the user leaves the app,
 * taking the MediaProjection with it. That is exactly why capture used to stop
 * when the user switched apps. Everything that must survive that transition now
 * lives here, held at foreground priority for as long as the user wants capture
 * active.
 *
 * Windows owned by this service:
 *  - the toolbar ({@link OverlayToolbarView}), draggable, always present while
 *    capture is enabled;
 *  - the marker ({@link TargetMarkerView}), full-screen, draw-only except while
 *    the user is choosing a target.
 *
 * The marker window is created with {@code FLAG_NOT_FOCUSABLE} so it never takes
 * input focus from the app underneath; its touchability is switched per mode
 * instead, which is the supported way to capture taps without becoming a
 * blocking overlay.
 */
public class ScreenCaptureService extends Service implements OverlayToolbarView.DragListener {

    public static final String EXTRA_STOP = "com.favie.xtracker.STOP";

    /**
     * Broadcast once {@link #onStartCommand} has reached {@code startForeground}.
     * The plugin waits for this before creating the projection, because Android 14+
     * rejects a projection created while no foreground service of
     * {@code mediaProjection} type is running yet.
     */
    public static final String ACTION_READY = "com.favie.xtracker.CAPTURE_READY";

    /** Broadcast whenever the tracker state changes, for the WebView dashboard. */
    public static final String ACTION_STATE = "com.favie.xtracker.STATE";

    public static final String STATE_SELECTING = "selecting";
    public static final String STATE_TRACKING = "tracking";
    public static final String STATE_LOST = "lost";
    public static final String STATE_READY = "ready";

    public static final String EXTRA_TARGET_X = "target_x";
    public static final String EXTRA_TARGET_Y = "target_y";
    public static final String EXTRA_TARGET_W = "target_w";
    public static final String EXTRA_TARGET_H = "target_h";
    public static final String EXTRA_TARGET_LABEL = "target_label";
    public static final String EXTRA_STATE = "state";

    private static final String CHANNEL_ID = "favie-screen-capture";
    private static final int NOTIFICATION_ID = 4201;

    /** Preference file recording whether a capture session is meant to be running. */
    private static final String PREFS = "favie.capture";
    private static final String PREF_SESSION_ACTIVE = "session_active";

    private static final int MODE_IDLE = 0;
    private static final int MODE_SELECT = 1;
    private static final int MODE_TRACKING = 2;
    private static final int MODE_LOST = 3;

    private static volatile ScreenCaptureService instance;
    private static volatile boolean running = false;

    private final CaptureEngine engine = new CaptureEngine();
    private final NativeTargetTracker tracker = new NativeTargetTracker();

    private WindowManager windowManager;
    private OverlayToolbarView toolbarView;
    private TargetMarkerView markerView;
    private WindowManager.LayoutParams toolbarParams;
    private WindowManager.LayoutParams markerParams;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /**
     * Keeps the engine in step with the system.
     *
     * {@code onStop} fires when the user revokes capture or the system reclaims the
     * token, so the engine is released rather than left pointing at a dead
     * projection. {@code onCapturedContentResize} (API 34+) reports a new size when
     * the captured content changes, and the buffers only match it once they follow
     * that size.
     *
     * Nothing here stops the service. The projection ending is not the same event as
     * the user asking for the session to end: the platform can reclaim a token and
     * then deliver a fresh one, and stopping on the callback would also tear the
     * notification and the toolbar down. Only an explicit user action stops the
     * service.
     */
    private final android.media.projection.MediaProjection.Callback projectionCallback =
            new android.media.projection.MediaProjection.Callback() {
                @Override
                public void onStop() {
                    mainHandler.post(() -> {
                        teardownProjection();
                        markSessionActive(false);
                        broadcastState();
                    });
                }

                @Override
                public void onCapturedContentResize(int width, int height) {
                    if (width <= 0 || height <= 0) return;
                    mainHandler.post(() -> {
                        try {
                            engine.resize(width, height);
                        } catch (Exception ignored) {
                            // A resize that the platform refuses leaves the previous
                            // geometry in place; the next frame is then simply the
                            // old size rather than a crash.
                        }
                    });
                }
            };

    private volatile boolean active = false;
    private volatile boolean selecting = false;

    /** Whether the service has reached {@code startForeground} in this process. */
    private volatile boolean inForeground = false;

    /** Frame transport settings, mirrored from the WebView config. */
    private volatile int maxFrameWidth = 960;
    private volatile int jpegQuality = 70;

    private long frameCount = 0L;
    private long lastFpsTime = 0L;
    private long lastFpsFrame = 0L;
    private float fps = 0f;

    private final Runnable captureLoop = new Runnable() {
        @Override
        public void run() {
            tick();
            if (active) mainHandler.postDelayed(this, 250);
        }
    };

    /** Whether the service has reached the foreground on this process. */
    public static boolean isRunning() {
        return running;
    }

    /** The live instance, or null. Used by the plugin to hand over a projection. */
    public static ScreenCaptureService getInstance() {
        return instance;
    }

    public boolean isToolbarShowing() {
        return toolbarView != null;
    }

    /**
     * Show or hide just the toolbar, leaving capture and the target lock intact.
     *
     * The dashboard offers this as its own switch, so a user who wants the marker
     * and capture without a toolbar can still have both.
     */
    public void setToolbarVisible(boolean visible) {
        mainHandler.post(() -> {
            if (visible) {
                if (toolbarView == null && hasOverlayPermission()) addToolbarWindow();
            } else if (toolbarView != null) {
                try {
                    windowManager.removeView(toolbarView);
                } catch (Exception ignored) {
                    // Already detached.
                }
                toolbarView = null;
                toolbarParams = null;
            }
            broadcastState();
        });
    }

    /**
     * Make sure both overlay windows are attached, without touching the session.
     *
     * Called when the Activity resumes and after the task is removed, because the
     * platform can take overlay windows down with the task even though the service
     * keeps running. Idempotent: an existing window is left alone.
     */
    public void ensureOverlaysRestored() {
        mainHandler.post(() -> {
            if (!hasOverlayPermission()) return;
            showOverlays();
        });
    }

    /** The live tracker, for the plugin to attach taps and report state. */
    public NativeTargetTracker getTracker() {
        return tracker;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
    }

    /**
     * Re-anchor the toolbar after a rotation or a display-size change.
     *
     * The Activity declares {@code configChanges} so it is not recreated, but the
     * service is not an Activity: the window manager keeps the overlay window
     * alive across a rotation and its stored x/y then refer to a screen that no
     * longer exists, which can leave the toolbar off-screen.
     *
     * The marker needs no handling here: the capture loop refreshes its geometry
     * from every frame, including a reused one, so it re-scales on the next tick.
     * A rotation also changes the captured display size, which the platform
     * reports through {@code onCapturedContentResize}; that path rebuilds the
     * virtual display and is left untouched.
     */
    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        mainHandler.post(() -> {
            if (toolbarView == null || toolbarParams == null) return;
            clampToolbar();
            try {
                windowManager.updateViewLayout(toolbarView, toolbarParams);
            } catch (Exception ignored) {
                // Window already gone.
            }
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // A null intent means the system re-created the service on its own, not
        // that the user (or the plugin) asked for capture. A MediaProjection
        // cannot be restored — the platform requires fresh consent for every
        // session — so there would be nothing to capture. The overlays are still
        // restored when the user left a session enabled, because the toolbar is the
        // only way back to the two controls that need an Activity: retargeting via
        // consent and stopping. Reaching the foreground first also satisfies the
        // platform's foreground-service contract.
        enterForeground();

        if (intent == null) {
            if (isSessionActive()) {
                restoreOverlaysAfterRecreate();
                broadcastState();
                // Stay alive so the restored toolbar can be used. A projection can
                // be attached to this same instance, because the plugin sees the
                // live service and hands the new consent straight over.
                return START_NOT_STICKY;
            }
            stopCapture();
            stopSelf();
            return START_NOT_STICKY;
        }

        // An explicit user request to stop. This is the only path that ends a
        // session; a minimised Activity never reaches it.
        if (intent.getBooleanExtra(EXTRA_STOP, false)) {
            stopCapture();
            markSessionActive(false);
            stopSelf();
            return START_NOT_STICKY;
        }

        // This is the only path that announces readiness: a projection can be
        // attached from here on.
        sendBroadcast(new Intent(ACTION_READY).setPackage(getPackageName()));

        // If a projection is already attached (the user re-opened the app, or the
        // Activity restarted) this is a no-op rather than a restart, so the
        // toolbar keeps its session.
        if (hasOverlayPermission()) {
            showOverlays();
        }
        broadcastState();

        return START_NOT_STICKY;
    }

    /**
     * Record that the user wants a capture session to be running.
     *
     * Written on start and cleared on stop. Android will not let the app restart a
     * projection without fresh consent, but it does let the process be recreated
     * after the user swipes the app away, and this flag is what distinguishes "the
     * user wants the toolbar" from "a stray service start".
     */
    private void markSessionActive(boolean value) {
        getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_SESSION_ACTIVE, value)
                .apply();
    }

    private boolean isSessionActive() {
        return getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean(PREF_SESSION_ACTIVE, false);
    }

    /**
     * Put the overlays back after the process was recreated.
     *
     * There is no projection yet, so capture is not running and the toolbar shows
     * its ready state. It is still the toolbar the user asked to keep, and the
     * Select target button is what leads back to a consent prompt.
     */
    private void restoreOverlaysAfterRecreate() {
        if (!hasOverlayPermission()) return;
        showOverlays();
        if (toolbarView != null) {
            toolbarView.syncState(false, false, false);
        }
        applyMarkerTouchability();
    }

    /**
     * Release capture after the system took the projection away.
     *
     * Deliberately does not stop the service or remove the overlays: the user may
     * simply retarget, which asks for a fresh consent, and the toolbar has to still
     * be there for that. The marker is dropped because the box it described was
     * measured against a projection that no longer exists.
     */
    private void teardownProjection() {
        active = false;
        selecting = false;
        mainHandler.removeCallbacks(captureLoop);
        tracker.reset();
        if (markerView != null) {
            markerView.clearMarker();
            markerView.setMode(MODE_IDLE);
        }
        if (toolbarView != null) {
            toolbarView.syncState(false, false, false);
        }
        // Release the engine outright rather than only the display: the projection
        // is already invalid once onStop has been delivered, and dropping the
        // reader, the pixel buffers and the decode thread is what frees the memory
        // the dead session was holding. A later consent builds a fresh engine state.
        engine.release();
        applyMarkerTouchability();
    }

    /** Whether the service has reached the foreground in this process. */
    public boolean isInForeground() {
        return inForeground;
    }

    /**
     * The system's time limit for a foreground service was reached.
     *
     * {@code mediaProjection} is not one of the time-limited types, so this should
     * not fire; if it ever does, stopping immediately is required or the platform
     * raises {@code RemoteServiceException} and kills the app.
     */
    @Override
    public void onTimeout(int startId) {
        stopCapture();
        markSessionActive(false);
        stopSelf();
    }

    /**
     * The user swiped the app out of the recents list.
     *
     * {@code stopWithTask="false"} keeps the service alive so capture survives the
     * swipe, and the overlays are moved back on screen because the platform can
     * take them down with the task. The service is only stopped when the session was
     * not actually recording anything, so a swipe cannot silently kill a live
     * session.
     */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        if (!isSessionActive()) {
            stopCapture();
            stopSelf();
            return;
        }
        mainHandler.post(() -> {
            if (isCaptureActive()) {
                showOverlays();
                broadcastState();
            } else {
                // Nothing to capture (the projection was never attached, or has
                // ended). Leave the user in the state where retargeting works.
                restoreOverlaysAfterRecreate();
            }
        });
    }

    /**
     * Post the foreground notification and declare the service type.
     *
     * Deliberately separate from {@link #onStartCommand}: it sets {@code running}
     * so a later start can tell the service is alive, but never announces
     * readiness. The ready broadcast is sent only once a session is actually
     * being started, so a teardown that also has to pass through the foreground
     * cannot make the plugin think a projection can be attached.
     */
    private void enterForeground() {
        createChannel();

        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this,
                0,
                open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // The notification stays posted for as long as the session can capture,
        // which is what makes the capture visible to the user — the platform
        // requires it, and the user can end the session from the notification.
        Intent stop = new Intent(this, ScreenCaptureService.class).putExtra(EXTRA_STOP, true);
        PendingIntent stopIntent = PendingIntent.getService(
                this,
                1,
                stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.capture_notification_text))
                .setSmallIcon(R.drawable.ic_stat_capture)
                .setOngoing(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(contentIntent)
                .addAction(0, getString(R.string.capture_notification_stop), stopIntent)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        running = true;
        inForeground = true;
    }

    /**
     * Attach a projection granted through the plugin.
     *
     * Must be called on the main thread. The engine is created here rather than in
     * the Activity so the projection's lifetime is the service's, not the
     * Activity's.
     *
     * Throws if the projection cannot be registered or the display cannot be
     * created, so the plugin can report a failure instead of leaving a session that
     * looks alive and captures nothing.
     */
    public void attachProjection(android.media.projection.MediaProjection projection,
                                 int width, int height, int densityDpi) {
        boolean callbackRegistered = false;
        try {
            projection.registerCallback(projectionCallback, mainHandler);
            callbackRegistered = true;
        } catch (Exception ignored) {
            // Some platforms reject a second callback; capture still works, but
            // Android 14+ requires one to exist before a display may be created.
        }

        try {
            engine.start(projection, width, height, densityDpi);
        } catch (RuntimeException e) {
            if (callbackRegistered) {
                try {
                    projection.unregisterCallback(projectionCallback);
                } catch (Exception ignored) {
                    // Best effort.
                }
            }
            throw e;
        }

        active = true;
        markSessionActive(true);
        showOverlays();
        startLoop();
        broadcastState();
    }

    /**
     * Whether the projection is live.
     *
     * The engine is the authority rather than the {@code active} flag, because a
     * projection the system ended leaves {@code active} set until the callback is
     * delivered.
     */
    public boolean isCaptureActive() {
        return active && engine.isRunning();
    }

    public boolean isSelecting() {
        return selecting;
    }

    private void startLoop() {
        mainHandler.removeCallbacks(captureLoop);
        lastFpsTime = System.currentTimeMillis();
        lastFpsFrame = frameCount;
        mainHandler.post(captureLoop);
    }

    /** One capture/analysis cycle. */
    private void tick() {
        if (!active) return;

        CaptureEngine.Frame frame = engine.captureFrame(maxFrameWidth, jpegQuality, false);
        if (frame == null) return;

        // A reused frame means the display delivered nothing new (a static screen
        // is the normal case while the user lines up a target). It is still the
        // current picture, so the marker geometry is refreshed from it, but it
        // must not advance the frame counter or the tracker: feeding the tracker
        // the same pixels over and over would burn CPU and could only ever confirm
        // a lock it already has.
        if (!frame.fresh) {
            if (markerView != null) {
                markerView.setGeometry(frame.width, frame.height,
                        engine.getWidth(), engine.getHeight());
            }
            return;
        }

        frameCount++;
        long now = System.currentTimeMillis();
        if (now - lastFpsTime >= 1000) {
            fps = (frameCount - lastFpsFrame) * 1000f / (now - lastFpsTime);
            lastFpsTime = now;
            lastFpsFrame = frameCount;
        }

        if (tracker.isLocked() || tracker.getState() == NativeTargetTracker.STATE_LOST) {
            tracker.update(frame.argb, frame.width, frame.height);
        }

        // The marker is positioned from analysis space; the view scales it to the
        // screen, so a downscaled frame still lines up with the real object.
        if (markerView != null) {
            markerView.setGeometry(frame.width, frame.height, engine.getWidth(), engine.getHeight());
            if (selecting) {
                markerView.setMode(MODE_SELECT);
            } else if (tracker.getState() == NativeTargetTracker.STATE_LOST) {
                markerView.setMode(MODE_LOST);
                markerView.setMarker(tracker.getBoxX(), tracker.getBoxY(),
                        tracker.getBoxW(), tracker.getBoxH(),
                        TargetMarkerView.describe(colorName(tracker.getColor()),
                                tracker.getConfidence(), true));
            } else if (tracker.isLocked()) {
                markerView.setMode(MODE_TRACKING);
                markerView.setMarker(tracker.getBoxX(), tracker.getBoxY(),
                        tracker.getBoxW(), tracker.getBoxH(),
                        TargetMarkerView.describe(colorName(tracker.getColor()),
                                tracker.getConfidence(), false));
            } else {
                markerView.setMode(MODE_IDLE);
                markerView.clearMarker();
            }
        }

        if (toolbarView != null) {
            toolbarView.setStats((int) frameCount, fps);
            toolbarView.syncState(selecting, tracker.isLocked(),
                    tracker.getState() == NativeTargetTracker.STATE_LOST,
                    markerView != null && markerView.isFocusMode());
        }
    }

    /** Map an ARGB colour to the same names the web pipeline reports. */
    private String colorName(int argb) {
        if (argb == 0) return "target";
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        if (r > g + 40 && r > b + 40) return "red";
        if (g > r + 40 && g > b + 40) return "green";
        if (b > r + 40 && b > g + 40) return "blue";
        if (r > 160 && g > 160 && b < 120) return "yellow";
        return "target";
    }

    // --- Overlay windows ------------------------------------------------------

    private boolean hasOverlayPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true;
        return Settings.canDrawOverlays(this);
    }

    /**
     * Add the marker window first, then the toolbar.
     *
     * Window order is creation order: later windows sit on top. The marker is
     * full-screen with {@code FLAG_LAYOUT_IN_SCREEN}, so if it were added after the
     * toolbar it would cover the toolbar's buttons and leave the bar visible but
     * untappable the moment the user selected a target. Adding the marker first
     * keeps the toolbar above it and reachable.
     */
    private void showOverlays() {
        if (!hasOverlayPermission()) return;
        if (markerView == null) addMarkerWindow();
        if (toolbarView == null) addToolbarWindow();
        applyMarkerTouchability();
    }

    private void addToolbarWindow() {
        toolbarView = new OverlayToolbarView(this, this);
        toolbarParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        toolbarParams.gravity = Gravity.TOP | Gravity.START;
        toolbarParams.x = Math.round(getResources().getDisplayMetrics().widthPixels * 0.06f);
        toolbarParams.y = Math.round(getResources().getDisplayMetrics().heightPixels * 0.12f);
        try {
            windowManager.addView(toolbarView, toolbarParams);
        } catch (Exception e) {
            toolbarView = null;
        }
    }

    private void addMarkerWindow() {
        markerView = new TargetMarkerView(this);
        markerView.setTapListener(new TargetMarkerView.TapListener() {
            @Override
            public void onTargetTap(float frameX, float frameY) {
                handleTargetTap(frameX, frameY);
            }
        });

        // Full-screen, but not touchable by default: it must never intercept input
        // from the app underneath except while the user is choosing a target.
        markerParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        markerParams.gravity = Gravity.TOP | Gravity.START;
        try {
            windowManager.addView(markerView, markerParams);
        } catch (Exception e) {
            markerView = null;
        }
    }

    /**
     * Window type for the overlays.
     *
     * {@code TYPE_APPLICATION_OVERLAY} is the only type an app may add from the
     * background on API 26+; earlier releases need the deprecated
     * {@code TYPE_PHONE}. There is no way to place a window above other apps
     * without the user granting "draw over other apps", which is why that
     * permission is requested explicitly.
     */
    private int overlayType() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        }
        //noinspection deprecation
        return WindowManager.LayoutParams.TYPE_PHONE;
    }

    /**
     * Switch the marker window between draw-only and tap-capturing.
     *
     * Adding/removing {@code FLAG_NOT_TOUCHABLE} is the supported way to receive
     * taps only while selecting: outside selection the window passes every touch
     * straight through to the app underneath.
     *
     * The window is MATCH_PARENT and positioned at the top-left, so while selecting
     * it covers the display and every tap lands here. If the window could not be
     * added at all (markerView == null, for example overlay permission was revoked
     * after capture started) selection still works through the in-app preview,
     * which calls the same handler with a point the user tapped there.
     */
    private void applyMarkerTouchability() {
        if (markerView == null || markerParams == null) return;

        int base = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        markerParams.flags = selecting ? base : base | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;

        if (selecting) {
            markerView.setMode(MODE_SELECT);
        } else if (tracker.getState() == NativeTargetTracker.STATE_LOST) {
            markerView.setMode(MODE_LOST);
        } else if (tracker.isLocked()) {
            markerView.setMode(MODE_TRACKING);
        } else {
            markerView.setMode(MODE_IDLE);
        }

        try {
            windowManager.updateViewLayout(markerView, markerParams);
        } catch (Exception ignored) {
            // Window already gone.
        }
    }

    private void removeOverlays() {
        if (toolbarView != null) {
            try {
                windowManager.removeView(toolbarView);
            } catch (Exception ignored) {
                // Already detached.
            }
            toolbarView = null;
        }
        if (markerView != null) {
            try {
                windowManager.removeView(markerView);
            } catch (Exception ignored) {
                // Already detached.
            }
            markerView = null;
        }
    }

    // --- Toolbar actions ------------------------------------------------------

    @Override
    public void onAction(String action) {
        switch (action) {
            case OverlayToolbarView.ACTION_SELECT:
            case OverlayToolbarView.ACTION_CHANGE:
                beginSelection();
                break;
            case OverlayToolbarView.ACTION_CANCEL_SELECT:
                cancelSelection();
                break;
            case OverlayToolbarView.ACTION_REACQUIRE:
                reacquire();
                break;
            case OverlayToolbarView.ACTION_CLEAR:
                tracker.reset();
                if (markerView != null) markerView.clearMarker();
                applyMarkerTouchability();
                broadcastState();
                break;
            case OverlayToolbarView.ACTION_ISOLATE:
                setFocusMode(true);
                break;
            case OverlayToolbarView.ACTION_SHOW_ALL:
                setFocusMode(false);
                break;
            case OverlayToolbarView.ACTION_STOP:
            case OverlayToolbarView.ACTION_CLOSE:
                stopCapture();
                stopSelf();
                break;
            default:
                break;
        }
    }

    /**
     * Blank the screen down to the tracked object, or show everything again.
     *
     * The veil lives in the marker window, so this only has to set the flag and let
     * the next tick repaint. It is offered as an explicit toggle rather than being
     * forced on: a user who wants to watch the whole table can turn it off, and the
     * marker and tracker behave identically either way.
     */
    public void setFocusMode(boolean enabled) {
        mainHandler.post(() -> {
            if (markerView == null) return;
            // The veil is only meaningful once there is a target to reveal.
            markerView.setFocusMode(enabled && tracker.isLocked());
            broadcastState();
        });
    }

    /** Whether the screen is currently blanked to the tracked object. */
    public boolean isFocusMode() {
        return markerView != null && markerView.isFocusMode();
    }

    @Override
    public void onDrag(float dx, float dy) {
        if (toolbarParams == null || toolbarView == null) return;
        toolbarParams.x += Math.round(dx);
        toolbarParams.y += Math.round(dy);
        clampToolbar();
        try {
            windowManager.updateViewLayout(toolbarView, toolbarParams);
        } catch (Exception ignored) {
            // Window already gone.
        }
    }

    /**
     * Keep the toolbar inside the screen.
     *
     * A drag can otherwise push the bar past an edge, and because the window is
     * added with {@code WRAP_CONTENT} its size is only known after layout. The
     * view's measured size is used when available and the display bounds as the
     * fallback, and the vertical range leaves the status and navigation bars
     * reachable rather than parking the bar underneath them.
     */
    private void clampToolbar() {
        if (toolbarParams == null || toolbarView == null) return;

        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int viewW = toolbarView.getWidth() > 0 ? toolbarView.getWidth() : toolbarView.getMeasuredWidth();
        int viewH = toolbarView.getHeight() > 0 ? toolbarView.getHeight() : toolbarView.getMeasuredHeight();
        if (viewW <= 0) viewW = Math.round(236 * metrics.density);
        if (viewH <= 0) viewH = Math.round(96 * metrics.density);

        int maxX = Math.max(0, metrics.widthPixels - viewW);
        int maxY = Math.max(0, metrics.heightPixels - viewH);
        toolbarParams.x = Math.max(0, Math.min(maxX, toolbarParams.x));
        toolbarParams.y = Math.max(0, Math.min(maxY, toolbarParams.y));
    }

    private void beginSelection() {
        selecting = true;
        // Drop any frame captured before the user started looking, so the tap is
        // resolved against fresh pixels.
        engine.resetTapPeak();
        applyMarkerTouchability();
        if (toolbarView != null) {
            toolbarView.syncState(true, tracker.isLocked(), false);
        }
        broadcastState();
    }

    private void cancelSelection() {
        selecting = false;
        applyMarkerTouchability();
        if (toolbarView != null) {
            toolbarView.syncState(false, tracker.isLocked(),
                    tracker.getState() == NativeTargetTracker.STATE_LOST);
        }
        broadcastState();
    }

    /**
     * Handle a tap in the selection overlay.
     *
     * The frame comes from the engine's remembered latest decode rather than a
     * fresh grab: the capture loop has already decoded the newest frame, and
     * re-grabbing here would both cost a second decode and risk the reader having
     * nothing new. That last case is what used to make selection fail on a static
     * screen, so the engine now serves its most recent picture instead.
     *
     * A tap never falls back to "pick something similar". If there is genuinely
     * nothing decoded yet the selection stays active and the user can tap again,
     * rather than a different object being chosen behind their back.
     */
    private void handleTargetTap(float frameX, float frameY) {
        if (frameX < 0 || frameY < 0) return;

        CaptureEngine.Frame frame = engine.frameForTap();
        if (frame == null) {
            // Nothing has been captured yet. Keep selection active for a retry.
            broadcastState();
            return;
        }

        boolean locked = tracker.select(frame.argb, frame.width, frame.height,
                Math.round(frameX), Math.round(frameY));
        if (!locked) {
            tracker.reset();
        } else if (markerView != null) {
            // Focus follows a successful selection. The requested behaviour is that
            // picking a target clears the rest of the screen and leaves only that
            // object visible, and the user keeps the toolbar's "Show all" control to
            // stop isolating — so the veil is armed here rather than waiting for a
            // second tap.
            markerView.setFocusMode(true);
        }

        selecting = false;
        applyMarkerTouchability();
        broadcastState();
    }

    /** Reacquire explicitly at the last known position; never picks a new object. */
    private void reacquire() {
        CaptureEngine.Frame frame = engine.frameForTap();
        if (frame == null) return;

        float cx = tracker.getBoxX() + tracker.getBoxW() / 2f;
        float cy = tracker.getBoxY() + tracker.getBoxH() / 2f;
        boolean locked =
                tracker.select(frame.argb, frame.width, frame.height, Math.round(cx), Math.round(cy));
        if (locked && markerView != null) markerView.setFocusMode(true);
        applyMarkerTouchability();
        broadcastState();
    }

    /**
     * Stop capture and hide the overlays, then take the service down.
     *
     * Reached only from an explicit user action: the toolbar's Stop/Close control,
     * the notification's Stop action, the dashboard's switch, or the service's own
     * timeout. Merely minimising the Activity, rotating the device or opening
     * another application never reaches this, which is what keeps tracking alive in
     * the background.
     */
    private void stopCapture() {
        active = false;
        selecting = false;
        mainHandler.removeCallbacks(captureLoop);
        tracker.reset();
        engine.release();
        markSessionActive(false);
        removeOverlays();
        broadcastState();
    }

    private void broadcastState() {
        Intent intent = new Intent(ACTION_STATE).setPackage(getPackageName());
        intent.putExtra(EXTRA_STATE, currentStateName());
        if (tracker.getBoxW() > 0) {
            intent.putExtra(EXTRA_TARGET_X, tracker.getBoxX());
            intent.putExtra(EXTRA_TARGET_Y, tracker.getBoxY());
            intent.putExtra(EXTRA_TARGET_W, tracker.getBoxW());
            intent.putExtra(EXTRA_TARGET_H, tracker.getBoxH());
            intent.putExtra(EXTRA_TARGET_LABEL,
                    TargetMarkerView.describe(colorName(tracker.getColor()),
                            tracker.getConfidence(), false));
        }
        sendBroadcast(intent);
    }

    private String currentStateName() {
        if (selecting) return STATE_SELECTING;
        if (tracker.getState() == NativeTargetTracker.STATE_LOST) return STATE_LOST;
        if (tracker.isLocked()) return STATE_TRACKING;
        return STATE_READY;
    }

    /** Overlay permission state for the plugin/UI. */
    public boolean overlayPermissionGranted() {
        return hasOverlayPermission();
    }

    /** Push frame transport settings from the WebView config. */
    public void setTransport(int maxWidth, int quality) {
        if (maxWidth > 0) maxFrameWidth = maxWidth;
        if (quality > 0) jpegQuality = quality;
    }

    /** Lock the target at a frame-space point supplied by the app UI. */
    public void selectTargetFromUi(float frameX, float frameY) {
        mainHandler.post(() -> handleTargetTap(frameX, frameY));
    }

    /** One frame for the WebView dashboard's live preview. */
    public CaptureEngine.Frame captureFrameForBridge(int maxWidth, int quality) {
        return engine.captureFrame(maxWidth, quality, true);
    }

    /**
     * Latest frame as a JPEG data URL for a preview consumer, or null.
     *
     * Serves the retained picture when the display has produced nothing new, so a
     * static screen still gives the dashboard a preview. The base64 string is
     * produced for this call only and is not retained by the service.
     */
    public String capturePreviewForBridge(int maxWidth, int quality) {
        CaptureEngine.Frame frame = engine.captureFrame(maxWidth, quality, true);
        return frame == null ? null : frame.dataUrl;
    }

    public long getFrameCount() {
        return frameCount;
    }

    public float getFps() {
        return fps;
    }

    public String getStateName() {
        return currentStateName();
    }

    /** Enter selection mode from the Activity's mirror UI. */
    public void requestSelectionFromUi() {
        mainHandler.post(this::beginSelection);
    }

    /**
     * Reacquire the current target from the Activity's mirror UI.
     *
     * The normal path is the toolbar's own Reacquire button; this exists so the
     * dashboard's copy of the control drives the same code instead of a second
     * implementation.
     */
    public void reacquireFromUi() {
        mainHandler.post(this::reacquire);
    }

    /** Drop the target from the Activity's mirror UI. */
    public void clearTargetFromUi() {
        mainHandler.post(() -> {
            tracker.reset();
            selecting = false;
            applyMarkerTouchability();
            broadcastState();
        });
    }

    /** Stop everything from the Activity's mirror UI. */
    public void requestStopFromUi() {
        mainHandler.post(() -> {
            stopCapture();
            stopSelf();
        });
    }

    @Override
    public void onDestroy() {
        active = false;
        running = false;
        inForeground = false;
        instance = null;
        mainHandler.removeCallbacks(captureLoop);
        removeOverlays();
        engine.release();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null || manager.getNotificationChannel(CHANNEL_ID) != null) return;

        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.capture_channel_name),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.capture_channel_description));
        manager.createNotificationChannel(channel);
    }
}