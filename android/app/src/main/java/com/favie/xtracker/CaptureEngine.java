package com.favie.xtracker;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Base64;
import android.view.Surface;

import java.io.ByteArrayOutputStream;

/**
 * Owns the MediaProjection, the VirtualDisplay and the frame decoder.
 *
 * This class deliberately holds no Activity or Plugin reference. The projection
 * used to live in {@code ScreenCapturePlugin}, which is bound to the Activity: as
 * soon as the user left the app the plugin (and with it the projection, the
 * display and the reader) could be torn down. Capture is owned here and this
 * engine is owned by {@link ScreenCaptureService}, which is a foreground service
 * and therefore outlives the Activity.
 *
 * <h2>One virtual display per consent session</h2>
 *
 * Since Android 14 (API 34) a user consent grants exactly one call to
 * {@code MediaProjection#createVirtualDisplay}. Calling it a second time on the
 * same projection throws, and building a second projection from the same consent
 * result throws too. The previous implementation re-created the display and reader
 * on every {@code onCapturedContentResize}, which the platform delivers when the
 * captured content changes size — including when the user switches to another
 * application. That second creation threw an uncaught framework exception on the
 * main thread and took the process down, which is why capture appeared to stop
 * whenever another app was opened. The display is now built once per session and a
 * later size change is applied with {@code VirtualDisplay#setSurface} and
 * {@code VirtualDisplay#resize}, which is the supported way to follow a
 * configuration change without a second creation.
 *
 * <h2>Frame sources are polled</h2>
 *
 * Frames are pulled with {@link ImageReader#acquireLatestImage} from the service's
 * capture loop rather than pushed by a listener, so a slow tick can never queue
 * work without bound.
 *
 * <h2>Buffers are reused</h2>
 *
 * Decoding an untouched 1080p frame used to allocate four objects per frame (a
 * padded bitmap, a crop, a scaled bitmap and an int array) at up to four frames a
 * second, and it retained a base64 data URL of every frame. Each buffer below is
 * now allocated once per geometry and reused, and only the pixel array of the
 * latest frame is retained — see {@link #decodeToOutput}, {@link #ensureArgbBuffer}
 * and {@link LatestFrame}.
 */
public class CaptureEngine {

    /** Reader queue depth. Two is the smallest value that never stalls a producer. */
    private static final int MAX_IMAGES = 2;

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private Surface surface;
    private HandlerThread thread;
    private Handler handler;

    private int width;
    private int height;
    private int densityDpi;

    /** Sequential frame counter, used to drop frames already consumed by a tap grab. */
    private long frameCounter = 0L;

    private final Object lock = new Object();

    // --- Reused decode scratch -------------------------------------------------

    private Bitmap paddedBitmap;
    private int paddedW;
    private int paddedH;

    private Bitmap outputBitmap;
    private int outputW;
    private int outputH;

    private final Canvas scaleCanvas = new Canvas();
    private final Paint scalePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Rect srcRect = new Rect();
    private final Rect dstRect = new Rect();

    private int[] argb;
    private ByteArrayOutputStream jpegOut;

    /** Quality to use when a preview is re-encoded from the retained picture. */
    private volatile int previewQuality = 70;

    /** Whether a projection, display and reader are all live. */
    public boolean isRunning() {
        synchronized (lock) {
            return projection != null && virtualDisplay != null && imageReader != null;
        }
    }

    public int getWidth() {
        synchronized (lock) {
            return width;
        }
    }

    public int getHeight() {
        synchronized (lock) {
            return height;
        }
    }

    /**
     * Attach to a projection granted by the user.
     *
     * This is the only place the virtual display is ever created for a session,
     * because one consent permits exactly one creation. A later
     * {@code onCapturedContentResize} is applied through {@link #resize}.
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
            frameCounter = 0L;
        }
    }

    /**
     * Follow a change in the captured content's size.
     *
     * API 34+ reports the new size through {@code onCapturedContentResize}. The
     * buffers have to move to that size or every frame is sheared, but the display
     * itself may not be re-created. The order below never leaves the display
     * attached to a closed surface: build the replacement reader, point the
     * display at it, and only then close the old one.
     */
    public void resize(int newWidth, int newHeight) {
        synchronized (lock) {
            if (projection == null || virtualDisplay == null) return;
            if (newWidth <= 0 || newHeight <= 0) return;
            if (newWidth == width && newHeight == height) return;

            width = newWidth;
            height = newHeight;

            ImageReader previous = imageReader;
            ImageReader next = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888,
                    MAX_IMAGES);

            try {
                virtualDisplay.setSurface(next.getSurface());
            } catch (Exception e) {
                // The display has gone; keep the old reader so teardown still works.
                next.close();
                return;
            }

            imageReader = next;
            surface = next.getSurface();
            if (previous != null) previous.close();

            virtualDisplay.resize(width, height, densityDpi);

            // The scratch buffers belong to the previous geometry.
            releaseScratchLocked();
        }
    }

    private void createDisplayLocked() {
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, MAX_IMAGES);
        surface = imageReader.getSurface();
        virtualDisplay = projection.createVirtualDisplay(
                "favie-x-tracker",
                width,
                height,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface,
                null,
                handler);
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
        // Closing the reader also releases the surface it produced.
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
        surface = null;
    }

    /**
     * Drop the scratch bitmaps and the ARGB buffer, keeping the session alive.
     *
     * Called from the service's {@code onTrimMemory}. The decode buffers are the
     * process's largest allocation after the projection itself, and the system asks
     * for them back at exactly the moment a user-switched-to app is competing for
     * memory — the situation where the process used to be reclaimed. The next
     * capture rebuilds them at the current geometry, so the only visible effect is
     * one frame that reallocates instead of reusing.
     *
     * The remembered tap frame is intentionally left alone: a trim can land between
     * the user entering selection mode and their tap, and discarding the picture
     * would make that tap do nothing.
     */
    public void releaseScratchForTrim() {
        synchronized (lock) {
            releaseScratchLocked();
        }
    }

    /**
     * Release the reader, the display and the scratch buffers.
     *
     * Called when the projection ends or the service stops. The next session
     * rebuilds all of it.
     */
    public void release() {
        synchronized (lock) {
            releaseLocked();
        }
    }

    private void releaseLocked() {
        releaseDisplayLocked();
        releaseScratchLocked();
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

    /**
     * Free the decode scratch buffers.
     *
     * Called on teardown and whenever the capture geometry changes, so a rotated
     * or resized display does not leave a full-resolution bitmap and pixel array
     * behind for the rest of the session.
     */
    private void releaseScratchLocked() {
        if (paddedBitmap != null) {
            paddedBitmap.recycle();
            paddedBitmap = null;
        }
        paddedW = 0;
        paddedH = 0;
        if (outputBitmap != null) {
            outputBitmap.recycle();
            outputBitmap = null;
        }
        outputW = 0;
        outputH = 0;
        argb = null;
    }

    /** A decoded frame in analysis-space, plus an optional JPEG preview. */
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

    /**
     * The most recent decode, kept so a tap or a preview can be served without
     * waiting for the display to produce a new image.
     *
     * Only the pixel array and its geometry are retained. The previous version also
     * cached the base64 data URL of every decode; one such string per frame is
     * hundreds of kilobytes of char data that the service had no use for, and it
     * existed purely so a reused preview would not look blank. The output bitmap
     * holds the same picture and re-encoding it on demand covers that case.
     */
    private static class LatestFrame {
        int[] argb;
        int width;
        int height;
        long sequence;

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
        }
    }

    /** Record the newest decode for later tap hit-testing and preview use. */
    private void rememberLatest(int[] pixels, int w, int h, long sequence) {
        synchronized (latestLock) {
            latest.argb = pixels;
            latest.width = w;
            latest.height = h;
            latest.sequence = sequence;
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
            return new Frame(latest.argb, latest.width, latest.height, null, latest.sequence, false);
        }
    }

    /**
     * Capture the newest frame, scaled to {@code maxWidth} and optionally
     * JPEG-encoded.
     *
     * When the display has produced nothing since the last call the previous decode
     * is returned marked as not fresh, so callers that only need the current
     * picture still work while callers that advance a session can skip it.
     *
     * @return the frame, or null when no frame has ever been decoded
     */
    public Frame captureFrame(int maxWidth, int quality, boolean wantDataUrl) {
        previewQuality = Math.max(1, Math.min(100, quality));

        ImageReader reader;
        synchronized (lock) {
            if (imageReader == null) return reuseLatest(wantDataUrl);
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

        try {
            Bitmap decoded = decodeToOutput(image, maxWidth);
            if (decoded == null) return reuseLatest(wantDataUrl);

            int w = decoded.getWidth();
            int h = decoded.getHeight();
            int[] pixels = ensureArgbBuffer(w, h);
            decoded.getPixels(pixels, 0, w, 0, 0, w, h);

            // The rotation is written back into the bitmap so the tracker's pixels
            // and the preview describe the same picture.
            if (rotateRgbaInPlace(pixels)) {
                decoded.setPixels(pixels, 0, w, 0, 0, w, h);
            }

            String dataUrl = wantDataUrl ? encodeJpeg(decoded, previewQuality) : null;

            long seq;
            synchronized (lock) {
                seq = ++frameCounter;
            }
            rememberLatest(pixels, w, h, seq);
            return new Frame(pixels, w, h, dataUrl, seq, true);
        } catch (Exception e) {
            return reuseLatest(wantDataUrl);
        } finally {
            image.close();
        }
    }

    /**
     * The previous decode, marked not fresh, or null if nothing has been decoded.
     *
     * The ARGB buffer is shared with the caller rather than copied: the capture
     * loop replaces it wholesale on the next decode, and no consumer mutates it.
     * A reused frame is only JPEG-encoded when a preview consumer asked for it, so
     * tracking on a static screen costs no encoding work at all.
     */
    private Frame reuseLatest(boolean wantDataUrl) {
        int[] pixels;
        int w;
        int h;
        long sequence;
        synchronized (latestLock) {
            if (!latest.hasContent()) return null;
            pixels = latest.argb;
            w = latest.width;
            h = latest.height;
            sequence = latest.sequence;
        }

        String dataUrl = wantDataUrl ? encodeRetained() : null;
        return new Frame(pixels, w, h, dataUrl, sequence, false);
    }

    /**
     * Encode the retained picture for a preview consumer.
     *
     * The output bitmap still holds the pixels recorded by the last decode, so a
     * static screen — which delivers no new image — still yields a preview instead
     * of a blank one, without the engine keeping a data URL per frame.
     */
    private String encodeRetained() {
        Bitmap retained;
        synchronized (lock) {
            retained = outputBitmap;
        }
        if (retained == null || retained.isRecycled()) return null;
        try {
            String dataUrl = encodeJpeg(retained, previewQuality);
            // Detach the scratch canvas from the retained bitmap: it outlives every
            // other buffer here, and a Canvas left pointing at a recycled bitmap is
            // an illegal state the next decode's drawBitmap would trip over.
            synchronized (lock) {
                if (outputBitmap == retained) scaleCanvas.setBitmap(null);
            }
            return dataUrl;
        } catch (Exception e) {
            return null;
        }
    }

    private String encodeJpeg(Bitmap bitmap, int quality) {
        // The stream is reset rather than reallocated. The base64 string handed to
        // the caller is owned by the caller and dropped when it is done with it.
        if (jpegOut == null) jpegOut = new ByteArrayOutputStream(64 * 1024);
        jpegOut.reset();
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, jpegOut);
        return "data:image/jpeg;base64,"
                + Base64.encodeToString(jpegOut.toByteArray(), Base64.NO_WRAP);
    }

    /**
     * Decode an image into the reused output bitmap, cropped and scaled to fit
     * {@code maxWidth}.
     *
     * One draw does the crop and the scale together: the reader's row stride can
     * exceed {@code width * 4}, so the padded copy has to be reduced to the real
     * width anyway, and doing both in a single filtered draw is what the previous
     * createBitmap/createScaledBitmap pair did with two extra allocations per frame.
     */
    private Bitmap decodeToOutput(Image image, int maxWidth) {
        Image.Plane[] planes = image.getPlanes();
        if (planes.length == 0) return null;

        int imageWidth = image.getWidth();
        int imageHeight = image.getHeight();
        int pixelStride = planes[0].getPixelStride();
        int rowStride = planes[0].getRowStride();
        if (imageWidth <= 0 || imageHeight <= 0 || pixelStride <= 0 || rowStride <= 0) return null;

        int rowPadding = rowStride - pixelStride * imageWidth;
        int paddedWidth = imageWidth + rowPadding / pixelStride;
        if (paddedWidth < imageWidth) paddedWidth = imageWidth;

        ensurePaddedBitmap(paddedWidth, imageHeight);
        if (paddedBitmap == null) return null;
        paddedBitmap.copyPixelsFromBuffer(planes[0].getBuffer());

        int targetW = imageWidth;
        int targetH = imageHeight;
        if (maxWidth > 0 && imageWidth > maxWidth) {
            targetW = maxWidth;
            targetH = Math.max(1, Math.round(imageHeight * (maxWidth / (float) imageWidth)));
        }

        ensureOutputBitmap(targetW, targetH);
        if (outputBitmap == null) return null;

        // The crop and scale always land in the output bitmap rather than being
        // short-circuited to the padded copy when neither is needed: the output
        // bitmap is the retained picture a preview is re-encoded from, so it has
        // to hold the latest pixels in every case.
        srcRect.set(0, 0, imageWidth, imageHeight);
        dstRect.set(0, 0, targetW, targetH);
        scalePaint.setFilterBitmap(targetW != imageWidth || targetH != imageHeight);
        scaleCanvas.setBitmap(outputBitmap);
        scaleCanvas.drawBitmap(paddedBitmap, srcRect, dstRect, scalePaint);
        return outputBitmap;
    }

    private void ensurePaddedBitmap(int w, int h) {
        if (paddedBitmap != null && !paddedBitmap.isRecycled() && paddedW == w && paddedH == h) {
            return;
        }
        if (paddedBitmap != null) paddedBitmap.recycle();
        paddedBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        paddedW = w;
        paddedH = h;
    }

    private void ensureOutputBitmap(int w, int h) {
        if (outputBitmap != null && !outputBitmap.isRecycled() && outputW == w && outputH == h) {
            return;
        }
        if (outputBitmap != null) outputBitmap.recycle();
        outputBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        outputW = w;
        outputH = h;
    }

    private int[] ensureArgbBuffer(int w, int h) {
        int needed = w * h;
        if (argb == null || argb.length < needed) argb = new int[needed];
        return argb;
    }

    /**
     * Convert the reader's RGBA byte order into ARGB, in place.
     *
     * {@code ImageReader} with {@code PixelFormat.RGBA_8888} yields bytes in R,G,B,A
     * order, and {@code copyPixelsFromBuffer} reads them into an int as
     * {@code 0xBBGGRRAA}. Treating that as ARGB swaps red and blue, so the pixel
     * buffer is rotated here rather than at every sample site. When the platform is
     * already ARGB (some devices report the native format) every low byte is
     * non-zero and the buffer is left untouched.
     *
     * @return true when the buffer was rotated and the bitmap must be updated
     */
    private boolean rotateRgbaInPlace(int[] pixels) {
        boolean allZeroLowByte = true;
        for (int p : pixels) {
            if ((p & 0xFF) != 0) {
                allZeroLowByte = false;
                break;
            }
        }
        if (!allZeroLowByte) return false;

        for (int i = 0; i < pixels.length; i++) {
            int p = pixels[i];
            int r = p & 0xFF;
            int g = (p >> 8) & 0xFF;
            int b = (p >> 16) & 0xFF;
            pixels[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }
        return true;
    }
}
