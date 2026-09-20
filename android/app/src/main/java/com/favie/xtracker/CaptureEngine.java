package com.favie.xtracker;

import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/**
 * Owns the MediaProjection, the VirtualDisplay and the frame decoder.
 *
 * This class deliberately holds no Activity or Plugin reference. The projection
 * used to live in {@code ScreenCapturePlugin}, which is bound to the Activity: as
 * soon as the user left the app the plugin (and with it the projection, the
 * display and the reader) could be torn down. Capture is now owned here and this
 * engine is owned by {@link ScreenCaptureService}, which is a foreground service
 * and therefore outlives the Activity.
 *
 * Frames arrive on {@link ImageReader}'s handler thread. {@link #captureFrame}
 * converts the newest image to int ARGB, scales it down and encodes it as a JPEG
 * data URL, all off the main thread so the overlay/UI never blocks on a frame.
 */
public class CaptureEngine {

    /** Which image format the decoder should assume. */
    private static final int FORMAT_ARGB_8888 = 1;
    private static final int FORMAT_RGBA_8888 = 2;

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread thread;
    private Handler handler;

    private int width;
    private int height;
    private int densityDpi;

    /** Sequential frame counter, used to drop frames already consumed by a tap grab. */
    private long frameCounter = 0L;

    private final Object lock = new Object();

    /** Whether a projection, display and reader are all live. */
    public boolean isRunning() {
        synchronized (lock) {
            return projection != null && virtualDisplay != null && imageReader != null;
        }
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    /**
     * Attach to a projection granted by the user.
     *
     * Any previous display and reader are released first, so this is also how the
     * engine follows a {@code onCapturedContentResize} callback.
     */
    public void start(MediaProjection mediaProjection, int displayWidth, int displayHeight,
                      int displayDpi) {
        synchronized (lock) {
            releaseLocked();

            if (thread == null) {
                thread = new HandlerThread("favie-capture");
                thread.start();
                handler = new Handler(thread.getLooper());
            }

            projection = mediaProjection;
            width = Math.max(1, displayWidth);
            height = Math.max(1, displayHeight);
            densityDpi = displayDpi;
            createDisplayLocked();
        }
    }

    /**
     * Rebuild the virtual display after the captured content changes size.
     *
     * API 34+ reports the new size through {@code onCapturedContentResize}; the
     * buffers only match the content once the display and reader are recreated at
     * that size, otherwise every frame is sheared or cropped.
     */
    public void resize(int newWidth, int newHeight) {
        synchronized (lock) {
            if (projection == null || newWidth <= 0 || newHeight <= 0) return;
            width = newWidth;
            height = newHeight;
            createDisplayLocked();
        }
    }

    private void createDisplayLocked() {
        releaseDisplayLocked();

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        virtualDisplay = projection.createVirtualDisplay(
                "favie-x-tracker",
                width,
                height,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(),
                null,
                handler);
        frameCounter = 0L;
    }

    /** Release the display and reader but keep the projection and thread. */
    public void releaseDisplay() {
        synchronized (lock) {
            releaseDisplayLocked();
        }
    }

    private void releaseDisplayLocked() {
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
    }

    /** Release everything. Safe to call repeatedly. */
    public void release() {
        synchronized (lock) {
            releaseLocked();
        }
    }

    private void releaseLocked() {
        releaseDisplayLocked();
        // Drop the remembered picture too: it belongs to the display that just
        // went away, and a later session must not lock onto it.
        resetTapPeak();
        if (projection != null) {
            try {
                projection.stop();
            } catch (Exception ignored) {
                // Already stopped by the system.
            }
            projection = null;
        }
        if (thread != null) {
            thread.quitSafely();
            thread = null;
            handler = null;
        }
    }

    /** A decoded frame in analysis-space, plus its JPEG preview. */
    public static class Frame {
        public final int[] argb;
        public final int width;
        public final int height;
        public final String dataUrl;
        public final long sequence;
        /**
         * Whether this frame was decoded from a newly delivered image.
         *
         * A reused frame ({@code fresh == false}) is the previous decode handed
         * back because the display produced nothing new. Consumers that drive a
         * session forward — frame counters, tracking — ignore the reused copy;
         * consumers that need the current picture, such as tap hit-testing and
         * the preview, still get pixels instead of nothing.
         */
        public final boolean fresh;

        Frame(int[] argb, int width, int height, String dataUrl, long sequence, boolean fresh) {
            this.argb = argb;
            this.width = width;
            this.height = height;
            this.dataUrl = dataUrl;
            this.sequence = sequence;
            this.fresh = fresh;
        }
    }

    /** The most recent decode, kept so a tap or a preview can be served without
     *  waiting for the display to produce a new image. */
    private static class LatestFrame {
        int[] argb;
        int width;
        int height;
        long sequence;
        String dataUrl;

        boolean hasContent() {
            return argb != null && width > 0 && height > 0;
        }
    }

    private final LatestFrame latest = new LatestFrame();
    private final Object latestLock = new Object();

    /**
     * Discard the picture a tap should be tested against.
     *
     * Called when selection mode begins so the user's tap is matched against a
     * frame captured after they started looking. The capture loop refills this
     * within one tick.
     */
    public void resetTapPeak() {
        synchronized (latestLock) {
            latest.argb = null;
            latest.width = 0;
            latest.height = 0;
            latest.sequence = 0L;
            latest.dataUrl = null;
        }
    }

    /** Record the newest decode for later tap hit-testing and preview use. */
    private void rememberForTap(Frame frame, int[] argb, String dataUrl) {
        synchronized (latestLock) {
            latest.argb = argb;
            latest.width = frame.width;
            latest.height = frame.height;
            latest.sequence = frame.sequence;
            latest.dataUrl = dataUrl;
        }
    }

    /**
     * The picture to hit-test a tap against, or null before the first decode.
     *
     * This deliberately hands back the most recent decode rather than insisting on
     * an unseen one. A static screen — the common case when a user is lining up a
     * tap on a paused frame — makes {@link ImageReader} deliver nothing, and the
     * previous "one tap per new frame" rule then silently swallowed the tap: the
     * user tapped an object and nothing happened. The picture is at most one
     * capture interval old, so reusing it is both correct and what makes selection
     * dependable.
     */
    public Frame frameForTap() {
        synchronized (latestLock) {
            if (!latest.hasContent()) return null;
            return new Frame(latest.argb, latest.width, latest.height, latest.dataUrl,
                    latest.sequence, false);
        }
    }

    /**
     * Capture the newest frame, scaled to {@code maxWidth} and JPEG-encoded.
     *
     * When the display has produced nothing since the last call the previous
     * decode is returned marked as not fresh, so callers that only need the
     * current picture still work while callers that advance a session can skip it.
     *
     * @return the frame, or null when no frame has ever been decoded
     */
    public Frame captureFrame(int maxWidth, int quality, boolean wantDataUrl) {
        ImageReader reader;
        synchronized (lock) {
            if (imageReader == null) return null;
            reader = imageReader;
        }

        Image image;
        try {
            image = reader.acquireLatestImage();
        } catch (IllegalStateException e) {
            // The reader was closed by a concurrent resize/teardown.
            return null;
        }
        if (image == null) return reuseLatest(wantDataUrl);

        Bitmap bitmap = null;
        try {
            bitmap = imageToBitmap(image);
            if (bitmap == null) return reuseLatest(wantDataUrl);

            if (maxWidth > 0 && bitmap.getWidth() > maxWidth) {
                int targetHeight = Math.max(1,
                        Math.round(bitmap.getHeight() * (maxWidth / (float) bitmap.getWidth())));
                Bitmap scaled = Bitmap.createScaledBitmap(bitmap, maxWidth, targetHeight, true);
                if (scaled != bitmap) {
                    bitmap.recycle();
                    bitmap = scaled;
                }
            }

            int w = bitmap.getWidth();
            int h = bitmap.getHeight();
            int[] argb = new int[w * h];
            bitmap.getPixels(argb, 0, w, 0, 0, w, h);

            String dataUrl = null;
            if (wantDataUrl) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                bitmap.compress(Bitmap.CompressFormat.JPEG, Math.max(1, Math.min(100, quality)), out);
                dataUrl = "data:image/jpeg;base64,"
                        + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
            }

            long seq;
            synchronized (lock) {
                seq = ++frameCounter;
            }
            Frame frame = new Frame(argb, w, h, dataUrl, seq, true);
            rememberForTap(frame, argb, dataUrl);
            return frame;
        } catch (Exception e) {
            return reuseLatest(wantDataUrl);
        } finally {
            if (bitmap != null) bitmap.recycle();
            image.close();
        }
    }

    /**
     * The previous decode, marked not fresh, or null if nothing has been decoded.
     *
     * The ARGB buffer is shared with the caller rather than copied: the capture
     * loop replaces it wholesale on the next decode, and no consumer mutates it.
     */
    private Frame reuseLatest(boolean wantDataUrl) {
        synchronized (latestLock) {
            if (!latest.hasContent()) return null;
            return new Frame(latest.argb, latest.width, latest.height,
                    wantDataUrl ? latest.dataUrl : null, latest.sequence, false);
        }
    }

    /**
     * Copy an RGBA_8888 image into an ARGB_8888 Bitmap.
     *
     * The row stride can exceed {@code width * 4}; decoding the buffer directly
     * would shear the picture, so rows are copied individually.
     */
    private Bitmap imageToBitmap(Image image) {
        Image.Plane[] planes = image.getPlanes();
        if (planes.length == 0) return null;

        ByteBuffer buffer = planes[0].getBuffer();
        int pixelStride = planes[0].getPixelStride();
        int rowStride = planes[0].getRowStride();
        int rowPadding = rowStride - pixelStride * image.getWidth();

        int paddedWidth = image.getWidth() + rowPadding / pixelStride;
        Bitmap padded = Bitmap.createBitmap(paddedWidth, image.getHeight(), Bitmap.Config.ARGB_8888);
        padded.copyPixelsFromBuffer(buffer);

        if (paddedWidth == image.getWidth()) {
            return normalize(padded, image.getWidth(), image.getHeight(), FORMAT_RGBA_8888);
        }

        Bitmap cropped = Bitmap.createBitmap(padded, 0, 0, image.getWidth(), image.getHeight());
        padded.recycle();
        return normalize(cropped, image.getWidth(), image.getHeight(), FORMAT_RGBA_8888);
    }

    /**
     * Convert the reader's RGBA byte order into ARGB.
     *
     * {@code ImageReader} with {@code PixelFormat.RGBA_8888} yields bytes in R,G,B,A
     * order, and {@code copyPixelsFromBuffer} reads them into an int as
     * {@code 0xBBGGRRAA}. Treating that as ARGB swaps red and blue, so the pixel
     * buffer is rotated once here rather than at every sample site. When the
     * platform is already ARGB (some devices report the native format) the bitmap
     * is returned untouched.
     */
    private Bitmap normalize(Bitmap bitmap, int width, int height, int format) {
        if (format != FORMAT_RGBA_8888) return bitmap;

        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);

        boolean allZeroAlpha = true;
        for (int p : pixels) {
            if ((p & 0xFF) != 0) {
                allZeroAlpha = false;
                break;
            }
        }
        if (!allZeroAlpha) return bitmap;

        for (int i = 0; i < pixels.length; i++) {
            int p = pixels[i];
            int r = p & 0xFF;
            int g = (p >> 8) & 0xFF;
            int b = (p >> 16) & 0xFF;
            pixels[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }

        Bitmap out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        out.setPixels(pixels, 0, width, 0, 0, width, height);
        bitmap.recycle();
        return out;
    }
}