package com.favie.xtracker;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import java.util.Locale;

/**
 * Full-screen overlay that marks the tracked object, optionally blanks the rest of
 * the screen, and captures the selection tap.
 *
 * Three responsibilities that have to live in the same window because they must
 * line up pixel-for-pixel:
 *
 * <ul>
 *   <li><b>Marking the target.</b> A filled red dot centred on the tracked object.
 *       A dot reads as "this one" at a glance without drawing a box the size of
 *       whatever region growing happened to find, and it leaves the object itself
 *       visible rather than framing it.</li>
 *   <li><b>Focus mode.</b> When enabled, everything except a generous radius around
 *       the target is painted opaque black, so the only thing on screen is the
 *       object being followed. This is a pure overlay effect — the app underneath
 *       is untouched and the captured pixels are not modified — so it needs no extra
 *       permission beyond the overlay this window already has. The cleared radius
 *       is the object's own half-size plus a margin, which is what keeps the whole
 *       object visible while its surroundings are hidden.</li>
 *   <li><b>Selection.</b> Outside selection mode the window carries
 *       {@code FLAG_NOT_TOUCHABLE} and passes every touch through to the app
 *       underneath. During selection the flag is dropped and a tap is mapped from
 *       screen space into captured-frame space.</li>
 * </ul>
 *
 * Coordinates arrive in analysis-frame space and are scaled to the screen here, so
 * the dot sits on the object no matter how the frame was downscaled or the device
 * rotated. The frame and screen sizes are read on every draw rather than cached
 * from construction, so a rotation repaints correctly immediately.
 */
public class TargetMarkerView extends View {

    public interface TapListener {
        /** @param frameX/frameY the tap mapped into captured-frame coordinates */
        void onTargetTap(float frameX, float frameY);
    }

    private static final int MODE_IDLE = 0;
    private static final int MODE_SELECT = 1;
    private static final int MODE_TRACKING = 2;
    private static final int MODE_LOST = 3;

    /** Radius of the marker dot, in dp. */
    private static final float DOT_RADIUS_DP = 11f;

    /** White ring drawn around the dot so it reads on a dark object. */
    private static final float DOT_RING_DP = 2.5f;

    /** Padding added around the target's own half-size to form the focus hole. */
    private static final float FOCUS_MARGIN_DP = 26f;

    /** Smallest focus hole, so a tiny target is not cleared to a pinprick. */
    private static final float FOCUS_MIN_RADIUS_DP = 52f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint veilPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** The hollow shape that clears the focus hole out of the veil. */
    private final Path holePath = new Path();
    private final RectF holeRect = new RectF();

    private final float density;
    private TapListener tapListener;

    private int mode = MODE_IDLE;

    /** Last selection tap, in frame coordinates, forwarded by {@link #performClick}. */
    private float lastTapX;
    private float lastTapY;

    private final RectF markerRect = new RectF();
    private boolean hasMarker = false;
    private boolean focusMode = false;
    private String label = "";

    private int frameWidth;
    private int frameHeight;
    private int screenWidth;
    private int screenHeight;

    public TargetMarkerView(Context context) {
        super(context);
        this.density = getResources().getDisplayMetrics().density;
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        setWillNotDraw(false);

        veilPaint.setColor(Color.BLACK);
        veilPaint.setStyle(Paint.Style.FILL);
        holePath.setFillType(Path.FillType.EVEN_ODD);
    }

    public void setTapListener(TapListener listener) {
        this.tapListener = listener;
    }

    private float dp(float v) {
        return v * density;
    }

    /** Frame and screen geometry used by the draw and tap mappings. */
    public void setGeometry(int frameW, int frameH, int screenW, int screenH) {
        frameWidth = frameW;
        frameHeight = frameH;
        screenWidth = screenW;
        screenHeight = screenH;
        invalidate();
    }

    public void setMode(int newMode) {
        if (mode == newMode) return;
        mode = newMode;
        setClickable(newMode == MODE_SELECT);
        setFocusable(newMode == MODE_SELECT);
        invalidate();
    }

    public int getMode() {
        return mode;
    }

    /**
     * Blank the screen except for the tracked object.
     *
     * Only has an effect once a target is marked; with nothing to follow the veil
     * would leave the user looking at a black screen with no way to tell why, so it
     * is suppressed until a marker exists.
     */
    public void setFocusMode(boolean enabled) {
        if (focusMode == enabled) return;
        focusMode = enabled;
        invalidate();
    }

    public boolean isFocusMode() {
        return focusMode;
    }

    public void setMarker(float x, float y, float w, float h, String text) {
        markerRect.set(x, y, x + w, y + h);
        hasMarker = true;
        label = text == null ? "" : text;
        invalidate();
    }

    public void clearMarker() {
        hasMarker = false;
        label = "";
        // A focus veil with nothing to reveal would black the screen out
        // completely, so dropping the marker drops the veil with it.
        focusMode = false;
        invalidate();
    }

    /** Screen x for a frame-space x. */
    private float scaleX(float frameX) {
        if (frameWidth <= 0 || screenWidth <= 0) return frameX;
        return frameX * (screenWidth / (float) frameWidth);
    }

    /** Screen y for a frame-space y. */
    private float scaleY(float frameY) {
        if (frameHeight <= 0 || screenHeight <= 0) return frameY;
        return frameY * (screenHeight / (float) frameHeight);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (mode == MODE_SELECT) {
            drawSelectionHint(canvas);
            return;
        }
        if (mode == MODE_IDLE || !hasMarker) return;

        float left = scaleX(markerRect.left);
        float top = scaleY(markerRect.top);
        float right = scaleX(markerRect.right);
        float bottom = scaleY(markerRect.bottom);

        float cx = (left + right) / 2f;
        float cy = (top + bottom) / 2f;

        if (focusMode) drawFocusVeil(canvas, cx, cy, right - left, bottom - top);

        drawDot(canvas, cx, cy);
        drawLabel(canvas, cx, cy, bottom);
    }

    /**
     * Paint everything black except a circle over the target.
     *
     * Using {@code Path.Op.DIFFERENCE} against the view bounds keeps the cleared
     * region a true circle regardless of the view's size, and the even-odd fill is a
     * second guarantee that the hole stays hollow if the op is unavailable on an
     * older platform.
     */
    private void drawFocusVeil(Canvas canvas, float cx, float cy, float w, float h) {
        float radius = focusRadius(w, h, density);

        holeRect.set(cx - radius, cy - radius, cx + radius, cy + radius);
        holePath.reset();
        holePath.setFillType(Path.FillType.EVEN_ODD);
        holePath.addRect(0f, 0f, getWidth(), getHeight(), Path.Direction.CW);
        holePath.addCircle(cx, cy, radius, Path.Direction.CW);
        holePath.setFillType(Path.FillType.EVEN_ODD);

        canvas.drawPath(holePath, veilPaint);
    }

    /**
     * Radius of the cleared region around the target, in pixels.
     *
     * The hole follows the object: it is half of the object's longer side plus a
     * margin, so a wide or tall object is revealed entirely rather than clipped by a
     * fixed circle. A floor keeps a very small target from being cleared to a
     * pinprick the user cannot see through, and the margin is the same padding in
     * every case so the object never touches the edge of the hole.
     *
     * Pulled out as a static so the geometry can be asserted on the JVM, where no
     * overlay window or display exists.
     */
    static float focusRadius(float w, float h, float density) {
        float half = Math.max(w, h) / 2f + FOCUS_MARGIN_DP * density;
        return Math.max(half, FOCUS_MIN_RADIUS_DP * density);
    }

    /**
     * The marker: a red dot with a thin white ring.
     *
     * Red is the accent the toolbar and the notification already use for the
     * tracked state, and it stays legible against both the black veil and an
     * unmasked screen. The ring is what keeps the dot visible when the object
     * underneath happens to be red.
     */
    private void drawDot(Canvas canvas, float cx, float cy) {
        float radius = dp(DOT_RADIUS_DP);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(DOT_RING_DP));
        paint.setColor(0xCCFFFFFF);
        canvas.drawCircle(cx, cy, radius, paint);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFFF2D3F);
        canvas.drawCircle(cx, cy, radius - dp(DOT_RING_DP) / 2f, paint);

        // A soft halo makes the dot findable on a busy background without
        // covering the object the user is following.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2f));
        paint.setColor(0x66FF2D3F);
        canvas.drawCircle(cx, cy, radius + dp(4f), paint);
    }

    /** Caption under the dot, kept inside the screen at the bottom edge. */
    private void drawLabel(Canvas canvas, float cx, float cy, float bottom) {
        if (label == null || label.isEmpty()) return;

        paint.setStyle(Paint.Style.FILL);
        paint.setTextSize(dp(11));
        paint.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.BOLD));
        paint.setTextAlign(Paint.Align.CENTER);

        float padH = dp(8);
        float padV = dp(4);
        float textWidth = paint.measureText(label);
        float plateW = textWidth + padH * 2;
        float plateH = dp(11) + padV * 2;

        float plateCx = cx;
        float plateCxMax = getWidth() - plateW / 2f - dp(4);
        if (plateCx < plateW / 2f + dp(4)) plateCx = plateW / 2f + dp(4);
        if (plateCx > plateCxMax) plateCx = Math.max(dp(4) + plateW / 2f, plateCxMax);

        float plateTop = bottom + dp(DOT_RADIUS_DP) + dp(10);
        if (plateTop + plateH > getHeight() - dp(8)) {
            plateTop = bottom - dp(DOT_RADIUS_DP) - dp(10) - plateH;
        }
        if (plateTop < dp(4)) plateTop = dp(4);

        paint.setColor(0xE6101010);
        canvas.drawRoundRect(new RectF(plateCx - plateW / 2f, plateTop,
                plateCx + plateW / 2f, plateTop + plateH), dp(5), dp(5), paint);

        paint.setColor(Color.WHITE);
        canvas.drawText(label, plateCx, plateTop + plateH - padV - dp(1), paint);
    }

    private void drawSelectionHint(Canvas canvas) {
        // A dim veil makes the "tap an object" affordance unmistakable and shows
        // that the overlay, not the app below, is currently receiving input.
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x33000000);
        canvas.drawRect(0, 0, getWidth(), getHeight(), paint);

        paint.setTextSize(dp(15));
        paint.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.BOLD));
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setColor(Color.WHITE);

        float cx = getWidth() / 2f;
        float cy = dp(96);
        String text = getResources().getString(R.string.marker_selecting);
        float textWidth = paint.measureText(text);
        float padH = dp(18);
        float plateH = dp(40);
        RectF plate = new RectF(cx - textWidth / 2f - padH, cy - plateH / 2f,
                cx + textWidth / 2f + padH, cy + plateH / 2f);
        paint.setColor(0xE6101010);
        canvas.drawRoundRect(plate, dp(20), dp(20), paint);

        paint.setColor(Color.WHITE);
        float baseline = cy - (paint.descent() + paint.ascent()) / 2f;
        canvas.drawText(text, cx, baseline, paint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (mode != MODE_SELECT) return false;

        if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            // getRawX/Y are screen coordinates, which is what the frame maps from.
            // getX/Y are window-relative and would be wrong when the window is
            // inset by system bars.
            float rawX = event.getRawX();
            float rawY = event.getRawY();

            float frameX = rawX;
            float frameY = rawY;
            if (screenWidth > 0 && screenHeight > 0 && frameWidth > 0 && frameHeight > 0) {
                frameX = rawX * (frameWidth / (float) screenWidth);
                frameY = rawY * (frameHeight / (float) screenHeight);
            }

            lastTapX = frameX;
            lastTapY = frameY;
            // Route the gesture through performClick so accessibility services,
            // which invoke it directly rather than synthesising a touch, select
            // the target too.
            performClick();
            return true;
        }

        return true;
    }

    @Override
    public boolean performClick() {
        super.performClick();
        if (tapListener != null) {
            tapListener.onTargetTap(lastTapX, lastTapY);
        }
        return true;
    }

    /** Human-readable marker caption for the current state. */
    public static String describe(String colorName, float confidence, boolean lost) {
        if (lost) return "TARGET LOST";
        // A tap on an object the colour detector does not classify still locks, and
        // reporting that as "target" is honest; inventing a colour would not be.
        String name = colorName == null || colorName.isEmpty() ? "target" : colorName;
        return String.format(Locale.US, "TARGET · %s · %d%%", name, Math.round(confidence));
    }
}
