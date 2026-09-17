package com.favie.xtracker;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
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
import android.util.Base64;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import androidx.activity.result.ActivityResult;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/**
 * Screen capture backed by MediaProjection.
 *
 * The consent dialog is itself an activity result, so {@code start} launches it
 * and the projection is only created in the callback. A frame is grabbed by
 * asking the ImageReader for its most recent image, which is why the callback
 * keeps the reader open rather than tearing it down per frame.
 */
@CapacitorPlugin(
        name = "ScreenCapture",
        permissions = {
                @Permission(alias = "notifications", strings = { android.Manifest.permission.POST_NOTIFICATIONS })
        })
public class ScreenCapturePlugin extends Plugin {

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
            startCapture(result.getResultCode(), result.getData());
            call.resolve(runningResult());
        } catch (Exception e) {
            call.reject("Unable to start screen capture: " + e.getMessage(), e);
        }
    }

    private void startCapture(int resultCode, Intent data) {
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

        // Android 14+ requires the foreground service to be running before the
        // projection is created, otherwise getMediaProjection throws.
        startCaptureService();

        projection = projectionManager.getMediaProjection(resultCode, data);
        projection.registerCallback(projectionCallback, handler);

        recreateVirtualDisplay();
        running = true;
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
        getContext().startService(intent);
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