package com.favie.xtracker;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * The floating tracking toolbar.
 *
 * Drawn with plain Canvas primitives rather than inflated from a layout: an
 * overlay window has no theme context, and a hand-drawn surface keeps the toolbar
 * a single small, self-contained view with predictable sizing across devices.
 *
 * The toolbar is the only place tracking is started, stopped and re-targeted. The
 * Activity's own UI configures the app; it never drives a session.
 */
public class OverlayToolbarView extends View {

    public interface Listener {
        /** A control was tapped. */
        void onAction(String action);
    }

    public static final String ACTION_SELECT = "select";
    public static final String ACTION_CANCEL_SELECT = "cancel-select";
    public static final String ACTION_CHANGE = "change";
    public static final String ACTION_STOP = "stop";
    public static final String ACTION_REACQUIRE = "reacquire";
    public static final String ACTION_CLEAR = "clear";
    public static final String ACTION_CLOSE = "close";

    private static final int STATE_READY = 0;
    private static final int STATE_SELECTING = 1;
    private static final int STATE_TRACKING = 2;
    private static final int STATE_LOST = 3;

    private static final int COLOR_CARD = 0xF21A1A1A;
    private static final int COLOR_BORDER = 0x33FFFFFF;
    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_MUTED = 0xFF9A9A9A;
    private static final int COLOR_BUTTON = 0x1FFFFFFF;
    private static final int COLOR_BUTTON_PRESSED = 0x3DFFFFFF;
    private static final int COLOR_READY = 0xFF4ADE80;
    private static final int COLOR_SELECTING = 0xFFFBBF24;
    private static final int COLOR_LOST = 0xFFF87171;
    private static final int COLOR_ACCENT_BUTTON = 0xFFFFFFFF;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private final Listener listener;

    private final List<Button> buttons = new ArrayList<>();

    private final RectF cardRect = new RectF();

    private int state = STATE_READY;
    private int frameCount = 0;
    private float fps = 0f;

    /** Drag bookkeeping: a press that moves beyond the slop drags the window. */
    private float downX;
    private float downY;
    private float lastTouchX;
    private float lastTouchY;
    private int pressedIndex = -1;
    private boolean dragging = false;
    private final float touchSlop;

    public OverlayToolbarView(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        this.density = getResources().getDisplayMetrics().density;
        this.touchSlop = 8f * density;
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        rebuildButtons();
    }

    private float dp(float value) {
        return value * density;
    }

    public void setState(int newState) {
        if (state == newState) return;
        state = newState;
        invalidate();
    }

    public void setStats(int frames, float currentFps) {
        frameCount = frames;
        fps = currentFps;
        invalidate();
    }

    public int getToolbarState() {
        return state;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = Math.round(dp(236));
        int height = Math.round(dp(96));
        setMeasuredDimension(width, height);
    }

    /** Rebuild the per-state control set. Kept in one place so hit-testing and
     * drawing can never disagree about which buttons exist. */
    private void rebuildButtons() {
        buttons.clear();
        switch (state) {
            case STATE_SELECTING:
                buttons.add(new Button(ACTION_CANCEL_SELECT, "Cancel", false));
                buttons.add(new Button(ACTION_CLOSE, "Close", false));
                break;
            case STATE_TRACKING:
                buttons.add(new Button(ACTION_CHANGE, "Change target", false));
                buttons.add(new Button(ACTION_STOP, "Stop", true));
                buttons.add(new Button(ACTION_CLOSE, "Close", false));
                break;
            case STATE_LOST:
                buttons.add(new Button(ACTION_REACQUIRE, "Reacquire", true));
                buttons.add(new Button(ACTION_CLEAR, "Clear", false));
                buttons.add(new Button(ACTION_CLOSE, "Close", false));
                break;
            case STATE_READY:
            default:
                buttons.add(new Button(ACTION_SELECT, "Select target", true));
                buttons.add(new Button(ACTION_CLOSE, "Close", false));
                break;
        }
        invalidate();
    }

    /** Called by the service so the select button can reflect tracker state. */
    public void syncState(boolean selecting, boolean locked, boolean lost) {
        int next = STATE_READY;
        if (selecting) next = STATE_SELECTING;
        else if (lost) next = STATE_LOST;
        else if (locked) next = STATE_TRACKING;

        if (next != state) {
            state = next;
            rebuildButtons();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float w = getWidth();
        float h = getHeight();
        float radius = dp(14);

        cardRect.set(dp(1), dp(1), w - dp(1), h - dp(1));
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(COLOR_CARD);
        canvas.drawRoundRect(cardRect, radius, radius, paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor(COLOR_BORDER);
        canvas.drawRoundRect(cardRect, radius, radius, paint);

        drawHeader(canvas, w);
        drawButtons(canvas, w, h);
    }

    private void drawHeader(Canvas canvas, float w) {
        String statusText;
        int statusColor;
        switch (state) {
            case STATE_SELECTING:
                statusText = "Tap an object";
                statusColor = COLOR_SELECTING;
                break;
            case STATE_TRACKING:
                statusText = "Target locked";
                statusColor = COLOR_READY;
                break;
            case STATE_LOST:
                statusText = "Target lost";
                statusColor = COLOR_LOST;
                break;
            case STATE_READY:
            default:
                statusText = "Ready";
                statusColor = COLOR_READY;
                break;
        }

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(COLOR_TEXT);
        paint.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD));
        paint.setTextSize(dp(13));
        paint.setTextAlign(Paint.Align.LEFT);
        paint.setLetterSpacing(0.08f);
        float x = dp(16);
        float baseline = dp(26);
        canvas.drawText("TRACKER", x, baseline, paint);
        paint.setLetterSpacing(0f);

        float titleWidth = paint.measureText("TRACKER");

        // Status dot plus label, right-aligned.
        String meta = state == STATE_TRACKING && frameCount > 0
                ? statusText + " · " + String.format(java.util.Locale.US, "%.1f fps", fps)
                : statusText;
        paint.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL));
        paint.setTextSize(dp(11));
        paint.setColor(COLOR_MUTED);
        paint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(meta, w - dp(16), baseline, paint);

        paint.setColor(statusColor);
        paint.setStyle(Paint.Style.FILL);
        float dotR = dp(3.5f);
        float dotX = x + titleWidth + dp(8) + dotR;
        canvas.drawCircle(dotX, baseline - dp(4), dotR, paint);

        // Hairline under the header.
        paint.setColor(COLOR_BORDER);
        paint.setStrokeWidth(dp(1));
        canvas.drawLine(dp(12), dp(36), w - dp(12), dp(36), paint);
    }

    private void drawButtons(Canvas canvas, float w, float h) {
        int count = buttons.size();
        if (count == 0) return;

        float margin = dp(12);
        float gap = dp(8);
        float top = dp(46);
        float bottom = h - dp(12);
        float available = w - margin * 2 - gap * (count - 1);
        float step = available / count;

        paint.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD));
        paint.setTextSize(dp(12));
        paint.setTextAlign(Paint.Align.CENTER);

        float x = margin;
        for (int i = 0; i < count; i++) {
            Button button = buttons.get(i);
            button.rect.set(x, top, x + step, bottom);

            boolean pressed = i == pressedIndex;
            paint.setStyle(Paint.Style.FILL);
            if (button.accent) {
                paint.setColor(COLOR_ACCENT_BUTTON);
            } else {
                paint.setColor(pressed ? COLOR_BUTTON_PRESSED : COLOR_BUTTON);
            }
            canvas.drawRoundRect(button.rect, dp(9), dp(9), paint);

            paint.setColor(button.accent ? 0xFF101010 : COLOR_TEXT);
            float textY = button.rect.centerY() - (paint.descent() + paint.ascent()) / 2f;
            canvas.drawText(button.label, button.rect.centerX(), textY, paint);

            x += step + gap;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getRawX();
                downY = event.getRawY();
                lastTouchX = downX;
                lastTouchY = downY;
                dragging = false;
                pressedIndex = indexAt(event.getX(), event.getY());
                if (pressedIndex >= 0) invalidate();
                return true;

            case MotionEvent.ACTION_MOVE:
                float rawX = event.getRawX();
                float rawY = event.getRawY();
                if (!dragging) {
                    float dx = Math.abs(rawX - downX);
                    float dy = Math.abs(rawY - downY);
                    if (dx > touchSlop || dy > touchSlop) {
                        dragging = true;
                        pressedIndex = -1;
                        invalidate();
                    }
                }
                if (dragging && listener instanceof DragListener) {
                    ((DragListener) listener).onDrag(rawX - lastTouchX, rawY - lastTouchY);
                }
                lastTouchX = rawX;
                lastTouchY = rawY;
                return true;

            case MotionEvent.ACTION_UP:
                if (!dragging && pressedIndex >= 0 && pressedIndex < buttons.size()) {
                    String action = buttons.get(pressedIndex).action;
                    pressedIndex = -1;
                    invalidate();
                    listener.onAction(action);
                }
                pressedIndex = -1;
                dragging = false;
                invalidate();
                return true;

            case MotionEvent.ACTION_CANCEL:
                pressedIndex = -1;
                dragging = false;
                invalidate();
                return true;

            default:
                return super.onTouchEvent(event);
        }
    }

    private int indexAt(float x, float y) {
        for (int i = 0; i < buttons.size(); i++) {
            if (buttons.get(i).rect.contains(x, y)) return i;
        }
        return -1;
    }

    /** Optional extra callback so the service can reposition the window. */
    public interface DragListener extends Listener {
        void onDrag(float dx, float dy);
    }

    private static class Button {
        final String action;
        final String label;
        final boolean accent;
        final RectF rect = new RectF();

        Button(String action, String label, boolean accent) {
            this.action = action;
            this.label = label;
            this.accent = accent;
        }
    }
}