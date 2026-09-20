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
     * the captured content changes, and the buffers only match it once the virtual
     * display is rebuilt.
     */
    private final android.media.projection.MediaProjection.Callback projectionCallback =
            new android.media.projection.MediaProjection.Callback() {
                @Override
                public void onStop() {
                    mainHandler.post(() -> {
                        stopCapture();
                        stopSelf();
                    });
                }

                @Override
                public void onCapturedContentResize(int width, int height) {
                    if (width <= 0 || height <= 0) return;
                    engine.resize(width, height);
                }
            };

    private volatile boolean active = false;
    private volatile boolean selecting = false;

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

    public boolean isCaptureActive() {
        return active && engine.isRunning();
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
        // session — so there would be nothing to capture. Rather than linger as a
        // foreground service posting an indefinite notification for a session that
        // cannot work, shut down and let the user start again from the app.
        if (intent == null) {
            // Enter the foreground first purely to satisfy the platform's
            // foreground-service contract, then leave: a service started via
            // startForegroundService that stops without ever calling
            // startForeground is killed with ForegroundServiceDidNotStartInTime.
            enterForeground();
            stopCapture();
            stopSelf();
            return START_NOT_STICKY;
        }

        if (intent.getBooleanExtra(EXTRA_STOP, false)) {
            enterForeground();
            stopCapture();
            stopSelf();
            return START_NOT_STICKY;
        }

        // Reaching the foreground is what lets the projection exist, so the
        // notification is posted before anything else can fail. This is the only
        // path that announces readiness: a projection can be attached from here on.
        enterForeground();
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

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.capture_notification_text))
                .setSmallIcon(R.drawable.ic_stat_capture)
                .setOngoing(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(contentIntent)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        running = true;
    }

    /**
     * Attach a projection granted through the plugin.
     *
     * Must be called on the main thread. The engine is created here rather than in
     * the Activity so the projection's lifetime is the service's, not the
     * Activity's.
     */
    public void attachProjection(android.media.projection.MediaProjection projection,
                                 int width, int height, int densityDpi) {
        try {
            projection.registerCallback(projectionCallback, mainHandler);
        } catch (Exception ignored) {
            // Some platforms reject a second callback; capture still works.
        }
        engine.start(projection, width, height, densityDpi);
        active = true;
        showOverlays();
        startLoop();
        broadcastState();
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
                    tracker.getState() == NativeTargetTracker.STATE_LOST);
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

    private void showOverlays() {
        if (!hasOverlayPermission()) return;
        if (toolbarView == null) addToolbarWindow();
        if (markerView == null) addMarkerWindow();
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
            case OverlayToolbarView.ACTION_STOP:
            case OverlayToolbarView.ACTION_CLOSE:
                stopCapture();
                stopSelf();
                break;
            default:
                break;
        }
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

        if (!tracker.select(frame.argb, frame.width, frame.height,
                Math.round(frameX), Math.round(frameY))) {
            tracker.reset();
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
        tracker.select(frame.argb, frame.width, frame.height, Math.round(cx), Math.round(cy));
        applyMarkerTouchability();
        broadcastState();
    }

    /** Stop capture and hide the overlays, then take the service down. */
    private void stopCapture() {
        active = false;
        selecting = false;
        mainHandler.removeCallbacks(captureLoop);
        tracker.reset();
        engine.release();
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