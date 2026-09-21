package com.favie.xtracker;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The floating tracking toolbar.
 *
 * Drawn with plain Canvas primitives rather than inflated from a layout: an
 * overlay window has no theme context to inflate against, and a hand-drawn
 * surface keeps the toolbar a single small, self-contained view with predictable
 * sizing across devices.
 *
 * The toolbar is the only place tracking is started, stopped and re-targeted. The
 * Activity's own UI configures the app; it never drives a session.
 *
 * Layout is two rows inside one card: a header carrying the wordmark and a status
 * pill, and a row of controls whose set depends on the tracker's state. Buttons
 * are laid out from the same rectangles the touch handler hit-tests against, so
 * drawing and hit-testing can never disagree about where a control is.
 */
public class OverlayToolbarView extends View {

    public interface Listener {
        /** A control was tapped. */
        void onAction(String action);
    }

    /** Optional extra callback so the service can reposition the window. */
    public interface DragListener extends Listener {
        void onDrag(float dx, float dy);
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

    // Surfaces. The card is a near-black gradient so it separates from both a
    // light and a dark app behind it without needing a hard outline.
    private static final int COLOR_CARD_TOP = 0xF21E1E22;
    private static final int COLOR_CARD_BOTTOM = 0xF2141416;
    private static final int COLOR_BORDER = 0x26FFFFFF;
    private static final int COLOR_DIVIDER = 0x1FFFFFFF;
    private static final int COLOR_GRIP = 0x33FFFFFF;
    private static final int COLOR_PILL = 0x1FFFFFFF;
    /** Chip behind each cup letter in the strip: the brand red, dimmed. */
    private static final int COLOR_CUP_CHIP = 0x59FF2D3F;

    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_INK_INVERSE = 0xFFFFFFFF;

    private static final int COLOR_BUTTON = 0x1FFFFFFF;
    private static final int COLOR_BUTTON_PRESSED = 0x3DFFFFFF;
    /** Pressed shade of the red primary; the resting shade is COLOR_ACCENT. */
    private static final int COLOR_ACCENT_PRESSED = 0xFFFF5566;
    /** Brand red: every primary action on the bar and the live state accent. */
    private static final int COLOR_ACCENT = 0xFFFF2D3F;

    // State accents, shared with TargetMarkerView so the pill and the marker agree.
    // The ladder is red-family for the two live states and grey for ready, matching
    // the app UI: green would be a second accent the black-and-red scheme has not got.
    private static final int COLOR_READY = 0xFF8A8A8A;
    private static final int COLOR_SELECTING = 0xFFFF2D3F;
    private static final int COLOR_LOST = 0xFFFF6B6B;

    private static final float CARD_WIDTH_DP = 244f;
    private static final float CARD_HEIGHT_DP = 138f;
    private static final float HEADER_BASELINE_DP = 34f;
    private static final float DIVIDER_Y_DP = 46f;
    private static final float BUTTON_TOP_DP = 56f;
    private static final float BUTTON_HEIGHT_DP = 36f;
    // The letters strip sits below the buttons and mirrors where the cups are.
    private static final float STRIP_TOP_DP = 102f;
    private static final float STRIP_HEIGHT_DP = 24f;
    private static final float STRIP_MARGIN_DP = 12f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Typeface boldFace = Typeface.create("sans-serif-medium", Typeface.BOLD);
    private final Typeface regularFace = Typeface.create("sans-serif", Typeface.NORMAL);

    private final float density;
    private final Listener listener;

    private final List<Button> buttons = new ArrayList<>();

    private final RectF cardRect = new RectF();
    /**
     * Cached card gradient.
     *
     * The card's bounds are fixed by the measure spec, so the shader is built on
     * the first draw and reused. Allocating it per frame showed up as a
     * DrawAllocation lint warning and is real work the draw pass does not need.
     */
    private LinearGradient cardShader;
    private float shaderTop = Float.NaN;
    private float shaderBottom = Float.NaN;
    private final RectF gripRect = new RectF();
    private final RectF pillRect = new RectF();

    private int state = STATE_READY;
    private int frameCount = 0;
    private float fps = 0f;

    /** Current left-to-right cup letters, e.g. {@code "B A C"}. */
    private String cupOrder = "";

    /**
     * Horizontal position of each cup, 0..1 across the analysed frame.
     *
     * This is what the letters strip is drawn from. It is deliberately a separate
     * input from {@link #cupOrder}: the strip has to show where the cups actually
     * are, so it is driven by the cups' tracked positions and never by the order
     * string — the string only says which letter is left of which.
     */
    private float[] cupPositions = new float[0];

    /** Drag bookkeeping: a press that moves beyond the slop drags the window. */
    private float downX;
    private float downY;
    private float lastTouchX;
    private float lastTouchY;
    private int pressedIndex = -1;
    private boolean dragging = false;
    private final float touchSlop;

    /** Action queued by the touch handler, dispatched by {@link #performClick}. */
    private String pendingAction;

    public OverlayToolbarView(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        this.density = getResources().getDisplayMetrics().density;
        this.touchSlop = 8f * density;
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        // The card paints its own drop shadow, so the view keeps a margin for it
        // to land in; the padding is what reserves that room.
        int pad = Math.round(dp(8));
        setPadding(pad, pad, pad, pad);
        rebuildButtons();
    }

    /**
     * Inflater-shaped constructor.
     *
     * The toolbar is always built in code, because an overlay window has no theme
     * context to inflate against. This exists so layout tooling can instantiate
     * the view; {@code listener} stays null and {@link #performClick} is a no-op.
     */
    public OverlayToolbarView(Context context) {
        this(context, null);
    }

    private float dp(float value) {
        return value * density;
    }

    public void setStats(int frames, float currentFps) {
        frameCount = frames;
        fps = currentFps;
        invalidate();
    }

    /**
     * Show the shuffle's current left-to-right order.
     *
     * This is the readout the user follows during a shuffle: the letters are bound
     * to the cups' identities, so the order changing is the shuffle, and it stays
     * readable even when the cups move faster than the eye can follow.
     */
    public void setCupOrder(String order) {
        String next = order == null ? "" : order;
        // Positions belong to the cups the order names, so dropping the order drops
        // them too; a later frame repopulates both together.
        if (next.isEmpty()) cupPositions = new float[0];
        if (next.equals(cupOrder)) return;
        cupOrder = next;
        invalidate();
    }

    public String getCupOrder() {
        return cupOrder;
    }

    /**
     * Set each tracked cup's horizontal position, 0..1 across the analysed frame.
     *
     * Passed independently of {@link #setCupOrder} because the strip draws the cups
     * where they are, not the letters in a fixed row. The two are fed from the same
     * detection pass in the service, so they cannot describe different frames.
     */
    public void setCupPositions(float[] positions) {
        cupPositions = positions == null ? new float[0] : positions.clone();
        invalidate();
    }

    /** The positions last set, for tests. */
    float[] getCupPositions() {
        return cupPositions.clone();
    }

    public int getToolbarState() {
        return state;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(
                Math.round(dp(CARD_WIDTH_DP)) + getPaddingLeft() + getPaddingRight(),
                Math.round(dp(CARD_HEIGHT_DP)) + getPaddingTop() + getPaddingBottom());
    }

    /**
     * Rebuild the per-state control set.
     *
     * Kept in one place so hit-testing and drawing can never disagree about which
     * buttons exist. The set is deliberately short: two controls at most, so the
     * bar stays small over another app. The destructive action is only offered
     * while a target is locked, leaving the idle bar with one obvious call to
     * action.
     */
    private void rebuildButtons() {
        buttons.clear();
        switch (state) {
            case STATE_SELECTING:
                buttons.add(new Button(ACTION_CANCEL_SELECT, str(R.string.toolbar_cancel), false));
                buttons.add(new Button(ACTION_CLOSE, str(R.string.toolbar_close), false));
                break;
            case STATE_TRACKING:
                // Two controls while locked: retarget and stop. The black-out
                // toggle was removed with the veil mode.
                buttons.add(new Button(ACTION_CHANGE, str(R.string.toolbar_change), false));
                buttons.add(new Button(ACTION_STOP, str(R.string.toolbar_stop), true));
                break;
            case STATE_LOST:
                buttons.add(new Button(ACTION_REACQUIRE, str(R.string.toolbar_reacquire), true));
                buttons.add(new Button(ACTION_CLEAR, str(R.string.toolbar_clear), false));
                break;
            case STATE_READY:
            default:
                buttons.add(new Button(ACTION_SELECT, str(R.string.toolbar_select), true));
                buttons.add(new Button(ACTION_CLOSE, str(R.string.toolbar_close), false));
                break;
        }
        invalidate();
    }

    private String str(int resId) {
        return getResources().getString(resId);
    }

    /** Called by the service so the controls reflect tracker state. */
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

        float left = getPaddingLeft();
        float top = getPaddingTop();
        float right = getWidth() - getPaddingRight();
        float bottom = getHeight() - getPaddingBottom();
        float radius = dp(16);

        cardRect.set(left, top, right, bottom);

        // Soft drop shadow so the bar reads as floating above the app rather than
        // pasted onto it. A separate paint is used because a shader and a shadow
        // layer on the same paint fight over the layer.
        shadowPaint.setStyle(Paint.Style.FILL);
        shadowPaint.setColor(Color.TRANSPARENT);
        shadowPaint.setShadowLayer(dp(9), 0f, dp(3), 0x73000000);
        canvas.drawRoundRect(cardRect, radius, radius, shadowPaint);

        paint.setStyle(Paint.Style.FILL);
        if (cardShader == null || shaderTop != top || shaderBottom != bottom) {
            cardShader = new LinearGradient(0f, top, 0f, bottom,
                    COLOR_CARD_TOP, COLOR_CARD_BOTTOM, Shader.TileMode.CLAMP);
            shaderTop = top;
            shaderBottom = bottom;
        }
        paint.setShader(cardShader);
        canvas.drawRoundRect(cardRect, radius, radius, paint);
        paint.setShader(null);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor(COLOR_BORDER);
        canvas.drawRoundRect(cardRect, radius, radius, paint);

        drawGrip(canvas, left, right, top);
        drawHeader(canvas, left, right);
        drawButtons(canvas, left, right);
        drawCupStrip(canvas, left, right);
    }

    /**
     * The letters strip: one letter per tracked cup, placed where that cup is.
     *
     * This is the readout that lets the user follow a shuffle without watching the
     * cups themselves. It is driven purely by the cups' tracked positions — the
     * order string only says which letter belongs to which cup — so it cannot agree
     * with a stale label drawn over a cup, and it keeps reporting the cups' movement
     * after the user has switched away from the tracked app and the cups are no
     * longer visible at all.
     */
    private void drawCupStrip(Canvas canvas, float left, float right) {
        // Shown whenever cups are tracked, not only once one is locked: the app
        // letters the cups as soon as it finds them, and the strip is the only
        // readout of that. Hidden only while the user is picking a target, when the
        // card is telling them to tap instead.
        if (state == STATE_SELECTING || cupPositions.length == 0) return;

        String[] labels = cupOrder.isEmpty() ? new String[0] : cupOrder.split(" ");
        if (labels.length == 0) return;

        float trackLeft = left + dp(STRIP_MARGIN_DP);
        float trackRight = right - dp(STRIP_MARGIN_DP);
        float trackW = trackRight - trackLeft;
        float midY = getPaddingTop() + dp(STRIP_TOP_DP + STRIP_HEIGHT_DP / 2f);
        float pillH = dp(STRIP_HEIGHT_DP);

        // A faint rail so the strip reads as a position line rather than stray
        // letters: the cup's letter slides along it as the cup moves.
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(COLOR_PILL);
        canvas.drawRoundRect(trackLeft, midY - dp(1.5f), trackRight, midY + dp(1.5f),
                dp(1.5f), dp(1.5f), paint);

        paint.setTypeface(boldFace);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(dp(11));

        int n = Math.min(labels.length, cupPositions.length);
        float pillW = Math.max(dp(20), Math.min(dp(34), trackW / Math.max(1, n) - dp(4)));

        for (int i = 0; i < n; i++) {
            float cx = chipCenterX(cupPositions[i], trackLeft, trackW, pillW);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(COLOR_CUP_CHIP);
            canvas.drawRoundRect(cx - pillW / 2f, midY - pillH / 2f,
                    cx + pillW / 2f, midY + pillH / 2f, pillH / 2f, pillH / 2f, paint);
            paint.setColor(COLOR_TEXT);
            canvas.drawText(labels[i], cx,
                    midY - (paint.descent() + paint.ascent()) / 2f, paint);
        }
    }

    /**
     * Where a cup's letter chip is centred, from the cup's position along the frame.
     *
     * The chip is inset by half its own width so a cup at the left edge has its chip
     * fully on the rail rather than half off the card, which is why the mapping is a
     * lerp between half a chip and the rail's width less half a chip rather than a
     * plain multiply. Extracted so the mapping is testable without a drawn bitmap:
     * that the letters actually move with the cups is the whole point of the strip,
     * and a render test on the JVM cannot see it (Robolectric's default graphics
     * returns a blank bitmap).
     */
    static float chipCenterX(float t, float trackLeft, float trackW, float pillW) {
        return trackLeft + pillW / 2f + (trackW - pillW) * clamp01(t);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    /** A short bar at the top centre: the conventional "drag me" affordance. */
    private void drawGrip(Canvas canvas, float left, float right, float top) {
        float handleW = dp(34);
        float handleH = dp(3);
        float cx = (left + right) / 2f;
        gripRect.set(cx - handleW / 2f, top + dp(6), cx + handleW / 2f, top + dp(6) + handleH);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(COLOR_GRIP);
        canvas.drawRoundRect(gripRect, handleH / 2f, handleH / 2f, paint);
    }

    private void drawHeader(Canvas canvas, float left, float right) {
        String statusText;
        int statusColor;
        switch (state) {
            case STATE_SELECTING:
                statusText = str(R.string.toolbar_selecting);
                statusColor = COLOR_SELECTING;
                break;
            case STATE_TRACKING:
                statusText = str(R.string.toolbar_tracking);
                statusColor = COLOR_READY;
                break;
            case STATE_LOST:
                statusText = str(R.string.toolbar_lost);
                statusColor = COLOR_LOST;
                break;
            case STATE_READY:
            default:
                statusText = str(R.string.toolbar_ready);
                statusColor = COLOR_READY;
                break;
        }

        float baseline = getPaddingTop() + dp(HEADER_BASELINE_DP);
        float textX = left + dp(16);

        // Reticle glyph: echoes the app mark and the marker the user will see.
        float glyphR = dp(5.5f);
        float glyphCx = textX + glyphR;
        float glyphCy = baseline - dp(4.5f);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.6f));
        paint.setColor(COLOR_TEXT);
        canvas.drawCircle(glyphCx, glyphCy, glyphR, paint);
        canvas.drawLine(glyphCx - glyphR - dp(0.5f), glyphCy, glyphCx + glyphR + dp(0.5f), glyphCy, paint);
        canvas.drawLine(glyphCx, glyphCy - glyphR - dp(0.5f), glyphCx, glyphCy + glyphR + dp(0.5f), paint);

        // Status pill, right-aligned: dot plus label, with the live rate appended
        // while tracking so the user can see capture is keeping up. The cup order
        // replaces the rate when a shuffle is being tracked, because the letters are
        // what the user is actually reading.
        String meta;
        if (state == STATE_TRACKING && cupOrder != null && !cupOrder.isEmpty()) {
            meta = cupOrder;
        } else if (state == STATE_TRACKING && frameCount > 0) {
            meta = String.format(Locale.US, "%s · %.0f fps", statusText, fps);
        } else {
            meta = statusText;
        }

        String wordmark = str(R.string.toolbar_wordmark);
        float wordmarkX = glyphCx + glyphR + dp(8);

        paint.setTypeface(boldFace);
        paint.setTextSize(dp(12.5f));
        paint.setLetterSpacing(0.16f);
        float wordmarkWidth = paint.measureText(wordmark);
        paint.setLetterSpacing(0f);

        paint.setTypeface(regularFace);
        paint.setTextSize(dp(11));
        float pillTextWidth = paint.measureText(meta);

        float dotR = dp(3.5f);
        float padH = dp(9);
        float pillH = dp(22);
        float pillW = pillTextWidth + padH * 2 + dotR * 2 + dp(6);
        float pillRight = right - dp(12);
        float pillLeft = pillRight - pillW;

        // The pill shares the header row with the wordmark, and the selecting hint
        // is long enough to run into it. Fitting both would shrink the text past
        // legibility, so when they would collide the instruction wins and the pill
        // takes the whole row: at that moment the user needs to read what to do,
        // not the app's name.
        boolean showWordmark = pillLeft >= wordmarkX + wordmarkWidth + dp(10);
        if (!showWordmark && pillLeft < left + dp(16)) {
            pillLeft = left + dp(16);
            pillW = pillRight - pillLeft;
        }
        float pillTop = baseline - pillH + dp(3);

        if (showWordmark) {
            paint.setTypeface(boldFace);
            paint.setTextSize(dp(12.5f));
            paint.setTextAlign(Paint.Align.LEFT);
            paint.setLetterSpacing(0.16f);
            paint.setColor(COLOR_TEXT);
            canvas.drawText(wordmark, wordmarkX, baseline, paint);
            paint.setLetterSpacing(0f);
        }

        pillRect.set(pillLeft, pillTop, pillLeft + pillW, pillTop + pillH);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(COLOR_PILL);
        canvas.drawRoundRect(pillRect, pillH / 2f, pillH / 2f, paint);

        paint.setColor(statusColor);
        if (showWordmark) {
            canvas.drawCircle(pillLeft + padH + dotR, pillRect.centerY(), dotR, paint);
        } else {
            canvas.drawCircle(pillLeft + padH, pillRect.centerY(), dotR, paint);
        }

        paint.setTypeface(regularFace);
        paint.setTextSize(dp(11));
        paint.setColor(COLOR_TEXT);
        paint.setTextAlign(showWordmark ? Paint.Align.LEFT : Paint.Align.CENTER);
        float textY = pillRect.centerY() - (paint.descent() + paint.ascent()) / 2f;
        float metaX = showWordmark
                ? pillLeft + padH + dotR * 2 + dp(6)
                : pillRect.centerX();
        canvas.drawText(meta, metaX, textY, paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor(COLOR_DIVIDER);
        canvas.drawLine(left + dp(12), getPaddingTop() + dp(DIVIDER_Y_DP),
                right - dp(12), getPaddingTop() + dp(DIVIDER_Y_DP), paint);
    }

    private void drawButtons(Canvas canvas, float left, float right) {
        layoutButtons(left, right);

        float radius = dp(BUTTON_HEIGHT_DP) / 2f;
        paint.setTypeface(boldFace);
        paint.setTextAlign(Paint.Align.CENTER);

        for (int i = 0; i < buttons.size(); i++) {
            Button button = buttons.get(i);
            boolean pressed = i == pressedIndex;
            paint.setStyle(Paint.Style.FILL);
            if (button.accent) {
                paint.setColor(pressed ? COLOR_ACCENT_PRESSED : COLOR_ACCENT);
            } else {
                paint.setColor(pressed ? COLOR_BUTTON_PRESSED : COLOR_BUTTON);
            }
            canvas.drawRoundRect(button.rect, radius, radius, paint);

            paint.setColor(button.accent ? COLOR_INK_INVERSE : COLOR_TEXT);
            paint.setTextSize(button.textSize);
            canvas.drawText(button.label, button.rect.centerX(),
                    button.rect.centerY() - (paint.descent() + paint.ascent()) / 2f, paint);
        }
    }

    /**
     * Place the buttons and fit their labels.
     *
     * Split from {@link #drawButtons} so the geometry the touch handler hit-tests
     * against exists independently of a draw pass. A view that has been measured
     * and laid out but never drawn must still route a tap to the right control.
     */
    private void layoutButtons(float left, float right) {
        int count = buttons.size();
        if (count == 0) return;

        float margin = dp(12);
        float gap = dp(8);
        float top = getPaddingTop() + dp(BUTTON_TOP_DP);
        float bottom = top + dp(BUTTON_HEIGHT_DP);
        float available = (right - left) - margin * 2 - gap * (count - 1);
        float step = available / count;

        // Labels shrink to fit their button rather than spilling over the edge: a
        // longer translation would otherwise bleed into the neighbouring control.
        float maxLabelWidth = step - dp(12);
        float baseTextSize = dp(12.5f);

        float x = left + margin;
        for (Button button : buttons) {
            button.rect.set(x, top, x + step, bottom);

            paint.setTypeface(boldFace);
            paint.setTextSize(baseTextSize);
            paint.setLetterSpacing(0f);
            float width = paint.measureText(button.label);
            button.textSize = width <= maxLabelWidth
                    ? baseTextSize
                    : Math.max(dp(9.5f), baseTextSize * (maxLabelWidth / width));

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
                    pendingAction = buttons.get(pressedIndex).action;
                    pressedIndex = -1;
                    invalidate();
                    // Via performClick so accessibility services, which call it
                    // directly instead of synthesising a touch, hit the button too.
                    performClick();
                    return true;
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

    @Override
    public boolean performClick() {
        super.performClick();
        if (pendingAction != null && listener != null) {
            String action = pendingAction;
            pendingAction = null;
            listener.onAction(action);
        }
        return true;
    }

    // --- Test hooks -----------------------------------------------------------

    /**
     * Recompute control geometry and report it, for the unit tests.
     *
     * The toolbar is driven entirely by touch on the device, so these accessors are
     * what let a JVM test assert that a tap lands on the control the user sees. The
     * geometry is produced by the same {@link #layoutButtons} the draw path uses, so
     * a test cannot pass against a stale layout.
     */
    Control[] controlsForTest() {
        layoutButtons(getPaddingLeft(), getWidth() - getPaddingRight());
        Control[] controls = new Control[buttons.size()];
        for (int i = 0; i < buttons.size(); i++) {
            Button b = buttons.get(i);
            controls[i] = new Control(b.action, b.label,
                    new RectF(b.rect), b.textSize, b.accent, paint);
        }
        return controls;
    }

    /** A control's resolved geometry and label metrics, for assertions. */
    static class Control {
        final String action;
        final String label;
        final RectF rect;
        final float textSize;
        final boolean accent;
        final float textWidth;

        Control(String action, String label, RectF rect, float textSize, boolean accent,
                Paint paint) {
            this.action = action;
            this.label = label;
            this.rect = rect;
            this.textSize = textSize;
            this.accent = accent;
            paint.setTextSize(textSize);
            this.textWidth = paint.measureText(label);
        }
    }

    private static class Button {
        final String action;
        final String label;
        final boolean accent;
        final RectF rect = new RectF();
        float textSize = 0f;

        Button(String action, String label, boolean accent) {
            this.action = action;
            this.label = label;
            this.accent = accent;
        }
    }
}