package com.favie.xtracker;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Base64;
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

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/**
 * Screen capture backed by MediaProjection.
 *
 * The consent dialog is itself an activity result, so {@code start} launches it
 * and the projection is only created in the callback. A frame is grabbed by
 * asking the ImageReader for its most recent image, which is why the callback
 * keeps the reader open rather than tearing it down per frame.
 *
 * Two ordering constraints are handled explicitly:
 *
 *  - Android 14+ refuses to create a projection unless a foreground service of
 *    type mediaProjection is already running, so the service is started first
 *    and the plugin waits for its ready broadcast rather than assuming the
 *    start call has taken effect.
 *  - The notification permission only affects whether the service notification
 *    is visible, so a denial is not treated as a capture failure.
 */
@CapacitorPlugin(
        name = "ScreenCapture",
        permissions = {
                @Permission(alias = "notifications", strings = { android.Manifest.permission.POST_NOTIFICATIONS })
        })
public class ScreenCapturePlugin extends Plugin {

    /** How long to wait for the capture service before giving up. */
    private static final long SERVICE_READY_TIMEOUT_MS = 3000;

    private MediaProjection projection;
    private MediaProjectionManager projectionManager;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread handlerThread;
    private Handler handler;

    private int frameWidth;
    private int frameHeight;
    private int densityDpi;
    private volatile boolean running = false;

    @Override
    public void load() {
        projectionManager =
                (MediaProjectionManager) getContext().getSystemService(Context.MEDIA_PROJECTION_SERVICE);
    }

    /** Prompt for consent and start capturing. */
    @PluginMethod
    public void start(PluginCall call) {
        if (running) {
            call.resolve(runningResult());
            return;
        }
        if (projectionManager == null) {
            call.reject("MediaProjection is unavailable on this device");
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission()) {
            requestPermissionForAlias("notifications", call, "notificationsResult");
            return;
        }
        requestProjectionConsent(call);
    }

    /**
     * Whether the capture notification will be visible.
     *
     * The permission is declared through the plugin annotation, so Capacitor
     * resolves the alias and its API-level differences; the raw
     * {@code POST_NOTIFICATIONS} constant is only defined from API 33, and
     * referencing it directly trips InlinedApi below that.
     */
    private boolean hasNotificationPermission() {
        return PermissionState.GRANTED.equals(getPermissionState("notifications"));
    }

    /** Capture still works without the notification, so a denial is not fatal. */
    @PermissionCallback
    private void notificationsResult(PluginCall call) {
        if (call == null) return;
        requestProjectionConsent(call);
    }

    private void requestProjectionConsent(PluginCall call) {
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
            startCapture(result.getResultCode(), result.getData(), call);
        } catch (Exception e) {
            call.reject("Unable to start screen capture: " + e.getMessage(), e);
        }
    }

    private void startCapture(int resultCode, Intent data, PluginCall call) {
        DisplayMetrics metrics = new DisplayMetrics();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getActivity().getDisplay().getRealMetrics(metrics);
        } else {
            WindowManager wm = (WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE);
            //noinspection deprecation
            wm.getDefaultDisplay().getRealMetrics(metrics);
        }
        frameWidth = metrics.widthPixels;
        frameHeight = metrics.heightPixels;
        densityDpi = metrics.densityDpi;

        // MediaProjection callbacks must be delivered on a thread with a Looper.
        handlerThread = new HandlerThread("favie-screen-capture");
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper());

        if (ScreenCaptureService.isRunning()) {
            createProjection(resultCode, data, call);
            return;
        }

        startCaptureService();
        awaitCaptureServiceReady(() -> createProjection(resultCode, data, call), call);
    }

    private void createProjection(int resultCode, Intent data, PluginCall call) {
        try {
            projection = projectionManager.getMediaProjection(resultCode, data);
            if (projection == null) {
                call.reject("Unable to obtain a MediaProjection instance");
                releaseCapture();
                return;
            }
            projection.registerCallback(projectionCallback, handler);
            recreateVirtualDisplay();
            running = true;
            call.resolve(runningResult());
        } catch (Exception e) {
            releaseCapture();
            call.reject("Unable to start screen capture: " + e.getMessage(), e);
        }
    }

    /**
     * Wait for {@link ScreenCaptureService} to reach the foreground.
     *
     * The service broadcasts {@code ACTION_READY} after {@code startForeground}
     * returns; this registers for it on the main looper and falls back to the
     * timeout so a service that fails to start surfaces an error instead of
     * hanging the call forever.
     */
    private void awaitCaptureServiceReady(Runnable onReady, PluginCall call) {
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

        // ContextCompat's overload handles the API split internally: pre-33
        // releases have no receiver-export flag, and the flag constant itself is
        // only defined from 33.
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

    private void startCaptureService() {
        Intent intent = new Intent(getContext(), ScreenCaptureService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getContext().startForegroundService(intent);
        } else {
            getContext().startService(intent);
        }
    }

    private void stopCaptureService() {
        Intent intent = new Intent(getContext(), ScreenCaptureService.class);
        intent.putExtra(ScreenCaptureService.EXTRA_STOP, true);
        try {
            getContext().startService(intent);
        } catch (IllegalStateException e) {
            // The app is backgrounded and the OS will not accept a plain service
            // start. The projection is already stopped at this point, so the
            // notification is all that is left; stopping it is best-effort.
            getContext().stopService(intent);
        }
    }

    /**
     * Keeps the projection object in step with the system.
     *
     * `onStop` fires when the user revokes capture or the system reclaims the
     * token, so the reader and display are released here rather than leaking.
     * `onCapturedContentResize` (API 34+) reports a new size when the captured
     * app or display changes, and the buffers only match the content once the
     * virtual display is rebuilt.
     */
    private final MediaProjection.Callback projectionCallback = new MediaProjection.Callback() {
        @Override
        public void onStop() {
            releaseCapture();
        }

        @Override
        public void onCapturedContentResize(int width, int height) {
            if (width <= 0 || height <= 0) return;
            frameWidth = width;
            frameHeight = height;
            recreateVirtualDisplay();
        }
    };

    private void recreateVirtualDisplay() {
        if (projection == null) return;

        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }

        imageReader = ImageReader.newInstance(frameWidth, frameHeight, PixelFormat.RGBA_8888, 2);
        virtualDisplay = projection.createVirtualDisplay(
                "favie-x-tracker",
                frameWidth,
                frameHeight,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(),
                null,
                handler);
    }

    @PluginMethod
    public void stop(PluginCall call) {
        releaseCapture();
        call.resolve(runningResult());
    }

    @PluginMethod
    public void isRunning(PluginCall call) {
        call.resolve(runningResult());
    }

    @PluginMethod
    public void getInfo(PluginCall call) {
        JSObject result = runningResult();
        result.put("densityDpi", densityDpi);
        call.resolve(result);
    }

    /**
     * Encode the newest captured frame as a JPEG data URL.
     *
     * The bitmap is scaled down before encoding: the tracker analyses at a few
     * hundred pixels wide, and shipping a full-resolution PNG across the bridge
     * would dominate the frame budget.
     */
    @PluginMethod
    public void grabFrame(PluginCall call) {
        if (!running || imageReader == null) {
            call.reject("Screen capture is not running");
            return;
        }

        Integer maxWidth = call.getInt("maxWidth", 960);
        Integer quality = call.getInt("quality", 70);

        Image image = imageReader.acquireLatestImage();
        if (image == null) {
            // No new frame since the last grab; the caller simply retries.
            call.resolve(new JSObject().put("dataUrl", (String) null));
            return;
        }

        Bitmap bitmap = null;
        try {
            bitmap = imageToBitmap(image);
            if (bitmap == null) {
                call.resolve(new JSObject().put("dataUrl", (String) null));
                return;
            }

            if (maxWidth > 0 && bitmap.getWidth() > maxWidth) {
                int targetHeight = Math.max(1, Math.round(bitmap.getHeight() * (maxWidth / (float) bitmap.getWidth())));
                Bitmap scaled = Bitmap.createScaledBitmap(bitmap, maxWidth, targetHeight, true);
                if (scaled != bitmap) {
                    bitmap.recycle();
                    bitmap = scaled;
                }
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out);
            String dataUrl = "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);

            JSObject result = new JSObject();
            result.put("dataUrl", dataUrl);
            result.put("width", bitmap.getWidth());
            result.put("height", bitmap.getHeight());
            call.resolve(result);
        } catch (Exception e) {
            call.reject("Unable to encode frame: " + e.getMessage(), e);
        } finally {
            if (bitmap != null) bitmap.recycle();
            image.close();
        }
    }

    /**
     * Copy an RGBA_8888 image into a Bitmap.
     *
     * The image row stride can exceed {@code width * 4}; using the raw buffer
     * directly would shear the picture, so rows are copied individually.
     */
    private Bitmap imageToBitmap(Image image) {
        Image.Plane[] planes = image.getPlanes();
        if (planes.length == 0) return null;

        ByteBuffer buffer = planes[0].getBuffer();
        int pixelStride = planes[0].getPixelStride();
        int rowStride = planes[0].getRowStride();
        int rowPadding = rowStride - pixelStride * image.getWidth();

        int bitmapWidth = image.getWidth() + rowPadding / pixelStride;
        Bitmap bitmap = Bitmap.createBitmap(bitmapWidth, image.getHeight(), Bitmap.Config.ARGB_8888);
        bitmap.copyPixelsFromBuffer(buffer);

        if (bitmapWidth != image.getWidth()) {
            Bitmap cropped = Bitmap.createBitmap(bitmap, 0, 0, image.getWidth(), image.getHeight());
            bitmap.recycle();
            return cropped;
        }
        return bitmap;
    }

    private JSObject runningResult() {
        JSObject result = new JSObject();
        result.put("running", running);
        result.put("width", frameWidth);
        result.put("height", frameHeight);
        return result;
    }

    private void releaseCapture() {
        running = false;
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
        if (projection != null) {
            projection.stop();
            projection = null;
        }
        if (handlerThread != null) {
            handlerThread.quitSafely();
            handlerThread = null;
        }
        handler = null;
        stopCaptureService();
    }
}