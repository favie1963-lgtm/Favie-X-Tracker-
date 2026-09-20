package com.favie.xtracker;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import java.util.Locale;

/**
 * Full-screen overlay that draws the target marker and captures the selection tap.
 *
 * Two responsibilities that have to live in the same window, because they must
 * line up pixel-for-pixel:
 *
 *  - Drawing the locked target's box, crosshair and label. Coordinates arrive in
 *    analysis-frame space and are scaled to the screen here, so the marker sits
 *    on the object no matter how the frame was downscaled or the device rotated.
 *  - In selection mode, turning a screen tap into a frame-space point. Measured
 *    against the *real* display size rather than the window, because a floating
 *    overlay window can be inset by system bars depending on the device.
 *
 * Outside selection mode the window is not touchable, so it never steals input
 * from the app underneath.
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

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** Reused across frames; the label plate is drawn on every capture tick. */
    private final RectF plateRect = new RectF();
    private final float density;
    private TapListener tapListener;

    private int mode = MODE_IDLE;

    /** Last selection tap, in frame coordinates, forwarded by {@link #performClick}. */
    private float lastTapX;
    private float lastTapY;

    private final RectF markerRect = new RectF();
    private boolean hasMarker = false;
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

    public void setMarker(float x, float y, float w, float h, String text) {
        markerRect.set(x, y, x + w, y + h);
        hasMarker = true;
        label = text == null ? "" : text;
        invalidate();
    }

    public void clearMarker() {
        hasMarker = false;
        label = "";
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
        if (mode == MODE_IDLE) return;

        if (!hasMarker) return;

        float left = scaleX(markerRect.left);
        float top = scaleY(markerRect.top);
        float right = scaleX(markerRect.right);
        float bottom = scaleY(markerRect.bottom);

        int accent = mode == MODE_LOST ? 0xFFF87171 : 0xFF4ADE80;

        // Box with corner brackets, which reads as a tracking reticle without
        // hiding the object behind a filled shape.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2));
        paint.setColor(accent);
        canvas.drawRect(left, top, right, bottom, paint);

        float arm = Math.min(dp(18), Math.min(right - left, bottom - top) * 0.3f);
        paint.setStrokeWidth(dp(3.5f));
        float x0 = left;
        float y0 = top;
        float x1 = right;
        float y1 = bottom;
        // Corners
        canvas.drawLine(x0, y0, x0 + arm, y0, paint);
        canvas.drawLine(x0, y0, x0, y0 + arm, paint);
        canvas.drawLine(x1, y0, x1 - arm, y0, paint);
        canvas.drawLine(x1, y0, x1, y0 + arm, paint);
        canvas.drawLine(x0, y1, x0 + arm, y1, paint);
        canvas.drawLine(x0, y1, x0, y1 - arm, paint);
        canvas.drawLine(x1, y1, x1 - arm, y1, paint);
        canvas.drawLine(x1, y1, x1, y1 - arm, paint);
        paint.setStrokeWidth(dp(2));

        // Centre crosshair.
        float cx = (left + right) / 2f;
        float cy = (top + bottom) / 2f;
        float tick = dp(7);
        paint.setStrokeWidth(dp(1.5f));
        canvas.drawLine(cx - tick, cy, cx + tick, cy, paint);
        canvas.drawLine(cx, cy - tick, cx, cy + tick, paint);

        // Label plate above the box, kept inside the screen at the top edge.
        if (!label.isEmpty()) {
            paint.setStyle(Paint.Style.FILL);
            paint.setTextSize(dp(11));
            paint.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                    android.graphics.Typeface.BOLD));
            paint.setTextAlign(Paint.Align.LEFT);
            float padH = dp(7);
            float padV = dp(4);
            float textWidth = paint.measureText(label);
            float plateW = textWidth + padH * 2;
            float plateH = dp(11) + padV * 2;
            float plateX = left;
            float plateY = top - plateH - dp(4);
            if (plateY < dp(4)) plateY = bottom + dp(4);

            paint.setColor(0xE6101010);
            plateRect.set(plateX, plateY, plateX + plateW, plateY + plateH);
            canvas.drawRoundRect(plateRect, dp(5), dp(5), paint);

            paint.setColor(Color.WHITE);
            canvas.drawText(label, plateX + padH, plateY + plateH - padV - dp(1), paint);
        }
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