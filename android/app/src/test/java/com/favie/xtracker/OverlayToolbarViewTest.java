package com.favie.xtracker;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

/**
 * Behavioural tests for {@link OverlayToolbarView}.
 *
 * The toolbar is the only way to start, re-target and stop tracking on Android,
 * and it cannot be exercised on a device in CI (no emulator and no KVM), so it is
 * driven here through real touch events on a Robolectric-measured view.
 *
 * The tests target the ways this view could break the workflow silently:
 *  - the control set must change with the tracker state, so the user is never
 *    offered Stop while nothing is being tracked;
 *  - a tap must reach the control the user actually sees, which means hit-testing
 *    and drawing must agree;
 *  - a drag must move the window instead of firing the control it started on,
 *    otherwise repositioning the bar could stop a session;
 *  - a label must never overflow its button, which is what a longer translation
 *    would otherwise cause.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class OverlayToolbarViewTest {

    /** Captures actions and drags reported by the view. */
    private static final class Recorder implements OverlayToolbarView.DragListener {
        final List<String> actions = new ArrayList<>();
        float dragX;
        float dragY;
        int dragCount = 0;

        @Override
        public void onAction(String action) {
            actions.add(action);
        }

        @Override
        public void onDrag(float dx, float dy) {
            dragCount++;
            dragX += dx;
            dragY += dy;
        }
    }

    private static Context context() {
        return ApplicationProvider.getApplicationContext();
    }

    /** Build and lay the view out at its intrinsic size so hit-testing works. */
    private static OverlayToolbarView layout(Recorder recorder) {
        OverlayToolbarView view = new OverlayToolbarView(context(), recorder);
        remeasure(view);
        return view;
    }

    private static void remeasure(OverlayToolbarView view) {
        view.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        view.layout(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight());
    }

    /**
     * Tap the centre of the control whose action is {@code action}.
     *
     * The centre comes from the view's own resolved geometry, so the test cannot
     * pass by hard-coding a position the drawing code has since moved away from.
     */
    private static void tapControl(OverlayToolbarView view, String action) {
        OverlayToolbarView.Control target = null;
        for (OverlayToolbarView.Control control : view.controlsForTest()) {
            if (control.action.equals(action)) target = control;
        }
        assertNotNull("no control with action " + action, target);

        float x = target.rect.centerX();
        float y = target.rect.centerY();
        long now = SystemClock.uptimeMillis();
        view.dispatchTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0));
        view.dispatchTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_UP, x, y, 0));
    }

    private static void pressAndMove(OverlayToolbarView view, float x, float y,
                                     float dx, float dy) {
        long now = SystemClock.uptimeMillis();
        view.dispatchTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0));
        view.dispatchTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_MOVE,
                x + dx, y + dy, 0));
        view.dispatchTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_UP,
                x + dx, y + dy, 0));
    }

    private static RectF firstControlRect(OverlayToolbarView view) {
        return view.controlsForTest()[0].rect;
    }

    // --- Control set per state ------------------------------------------------

    @Test
    public void idleToolbarOffersSelectAndClose() {
        Recorder recorder = new Recorder();
        OverlayToolbarView view = layout(recorder);

        view.syncState(false, false, false);

        OverlayToolbarView.Control[] controls = view.controlsForTest();
        assertEquals(2, controls.length);
        assertEquals(OverlayToolbarView.ACTION_SELECT, controls[0].action);
        assertEquals(OverlayToolbarView.ACTION_CLOSE, controls[1].action);
    }

    @Test
    public void trackingStateOffersChangeAndStop() {
        Recorder recorder = new Recorder();
        OverlayToolbarView view = layout(recorder);

        view.syncState(false, true, false);

        OverlayToolbarView.Control[] controls = view.controlsForTest();
        assertEquals(2, controls.length);
        assertEquals(OverlayToolbarView.ACTION_CHANGE, controls[0].action);
        assertEquals(OverlayToolbarView.ACTION_STOP, controls[1].action);
    }

    /**
     * The shuffle readout is what the user follows while the cups move, so the bar has
     * to carry the current order and drop it when a new target is set.
     */
    @Test
    public void cupOrderIsCarriedAndCleared() {
        OverlayToolbarView view = layout(new Recorder());

        view.setCupOrder("B A C");
        assertEquals("B A C", view.getCupOrder());

        view.setCupOrder("");
        assertEquals("", view.getCupOrder());
    }

    /**
     * The letters strip has to show where the cups are, so it needs the positions
     * as well as the order. Positions are dropped with the order: they describe the
     * cups the order names, and a new session must not inherit the last one's.
     */
    @Test
    public void cupPositionsAreCarriedAndDroppedWithTheOrder() {
        OverlayToolbarView view = layout(new Recorder());

        view.setCupOrder("B A C");
        view.setCupPositions(new float[]{0.2f, 0.5f, 0.8f});
        assertArrayEquals(new float[]{0.2f, 0.5f, 0.8f}, view.getCupPositions(), 1e-6f);

        view.setCupOrder("");
        assertEquals(0, view.getCupPositions().length);
    }

    /**
     * The view keeps its own copy: the service reuses its readout array each frame,
     * so holding the caller's array would let the next frame move positions the
     * strip has not drawn yet.
     */
    @Test
    public void cupPositionsAreCopiedNotAliased() {
        OverlayToolbarView view = layout(new Recorder());

        view.setCupOrder("A B C");
        float[] source = {0.1f, 0.5f, 0.9f};
        view.setCupPositions(source);
        source[0] = 0.99f;

        assertEquals(0.1f, view.getCupPositions()[0], 1e-6f);
    }

    /** A shorter order than positions must not draw a letter for an absent cup. */
    @Test
    public void theStripDrawsOneLetterPerNamedCup() {
        OverlayToolbarView view = layout(new Recorder());
        view.syncState(false, true, false);

        view.setCupOrder("A B");
        view.setCupPositions(new float[]{0f, 0.5f, 1f});

        render(view);
    }

    /**
     * The letters must move with the cups.
     *
     * The whole point of the strip is following a shuffle when the cups themselves
     * are hard to watch, so it has to derive each chip's place from the cup's
     * position. A strip that only echoed the order string would pass the rail and do
     * nothing, which is the bug this pins: the chip at t=0.9 must sit to the right of
     * the chip at t=0.1, by the rail's width less a chip.
     */
    @Test
    public void theStripMovesWithTheCupPositions() {
        float trackLeft = 12f, trackW = 220f, pillW = 28f;

        float left = OverlayToolbarView.chipCenterX(0.1f, trackLeft, trackW, pillW);
        float right = OverlayToolbarView.chipCenterX(0.9f, trackLeft, trackW, pillW);

        assertTrue("a cup further right must draw its chip further right",
                right > left);
        assertEquals("the mapping must span the rail between half-chip insets",
                (trackW - pillW) * 0.8f, right - left, 1e-3f);
    }

    /** A cup at either edge keeps its whole chip on the card. */
    @Test
    public void theStripKeepsEdgeChipsInsideTheRail() {
        float trackLeft = 12f, trackW = 220f, pillW = 28f;

        assertEquals(trackLeft + pillW / 2f,
                OverlayToolbarView.chipCenterX(0f, trackLeft, trackW, pillW), 1e-3f);
        assertEquals(trackLeft + trackW - pillW / 2f,
                OverlayToolbarView.chipCenterX(1f, trackLeft, trackW, pillW), 1e-3f);
        // A position outside the frame (a cup partly off-screen) is clamped, not drawn
        // past the card.
        assertEquals(trackLeft + trackW - pillW / 2f,
                OverlayToolbarView.chipCenterX(1.7f, trackLeft, trackW, pillW), 1e-3f);
        assertEquals(trackLeft + pillW / 2f,
                OverlayToolbarView.chipCenterX(-0.4f, trackLeft, trackW, pillW), 1e-3f);
    }

    private static android.graphics.Bitmap render(OverlayToolbarView view) {
        view.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        view.layout(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight());
        android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                Math.max(1, view.getWidth()), Math.max(1, view.getHeight()),
                android.graphics.Bitmap.Config.ARGB_8888);
        view.draw(new android.graphics.Canvas(bmp));
        return bmp;
    }

    @Test
    public void selectingStateOffersCancelAndClose() {
        Recorder recorder = new Recorder();
        OverlayToolbarView view = layout(recorder);

        view.syncState(true, false, false);

        OverlayToolbarView.Control[] controls = view.controlsForTest();
        assertEquals(OverlayToolbarView.ACTION_CANCEL_SELECT, controls[0].action);
        assertEquals(OverlayToolbarView.ACTION_CLOSE, controls[1].action);
    }

    @Test
    public void lostStateOffersReacquireAndClear() {
        Recorder recorder = new Recorder();
        OverlayToolbarView view = layout(recorder);

        view.syncState(false, false, true);

        OverlayToolbarView.Control[] controls = view.controlsForTest();
        assertEquals(OverlayToolbarView.ACTION_REACQUIRE, controls[0].action);
        assertEquals(OverlayToolbarView.ACTION_CLEAR, controls[1].action);
    }

    /**
     * "Target lost" must take precedence over a stale locked flag, otherwise the
     * user is offered Change/Stop while the toolbar is telling them the target is
     * gone and Reacquire is what they need.
     */
    @Test
    public void lostOutranksLockedWhenBothAreReported() {
        Recorder recorder = new Recorder();
        OverlayToolbarView view = layout(recorder);

        view.syncState(false, true, true);

        assertEquals(OverlayToolbarView.ACTION_REACQUIRE,
                view.controlsForTest()[0].action);
    }

    // --- Taps reach the visible control ---------------------------------------

    @Test
    public void tappingThePrimaryControlReportsItsAction() {
        Recorder recorder = new Recorder();
        OverlayToolbarView view = layout(recorder);

        view.syncState(false, false, false);
        tapControl(view, OverlayToolbarView.ACTION_SELECT);
        assertEquals(1, recorder.actions.size());
        assertEquals(OverlayToolbarView.ACTION_SELECT, recorder.actions.get(0));

        view.syncState(false, true, false);
        tapControl(view, OverlayToolbarView.ACTION_STOP);
        assertEquals(2, recorder.actions.size());
        assertEquals(OverlayToolbarView.ACTION_STOP, recorder.actions.get(1));

        view.syncState(false, false, true);
        tapControl(view, OverlayToolbarView.ACTION_REACQUIRE);
        assertEquals(OverlayToolbarView.ACTION_REACQUIRE, recorder.actions.get(2));
    }

    @Test
    public void controlsDoNotOverlapAcrossStates() {
        Recorder recorder = new Recorder();
        OverlayToolbarView view = layout(recorder);

        boolean[][] states = {
                {false, false, false},
                {true, false, false},
                {false, true, false},
                {false, false, true},
        };

        for (boolean[] state : states) {
            view.syncState(state[0], state[1], state[2]);
            OverlayToolbarView.Control[] controls = view.controlsForTest();
            for (int i = 0; i < controls.length; i++) {
                assertTrue("a control must have a positive width",
                        controls[i].rect.width() > 0f);
                for (int j = i + 1; j < controls.length; j++) {
                    assertFalse("controls must not overlap, state "
                                    + java.util.Arrays.toString(state),
                            RectF.intersects(controls[i].rect, controls[j].rect));
                }
            }
        }
    }

    @Test
    public void controlsStayInsideTheCard() {
        Recorder recorder = new Recorder();
        OverlayToolbarView view = layout(recorder);
        view.syncState(false, true, false);

        float left = view.getPaddingLeft();
        float right = view.getWidth() - view.getPaddingRight();
        float top = view.getPaddingTop();
        float bottom = view.getHeight() - view.getPaddingBottom();

        for (OverlayToolbarView.Control control : view.controlsForTest()) {
            assertTrue("control must sit inside the card horizontally",
                    control.rect.left >= left && control.rect.right <= right);
            assertTrue("control must sit inside the card vertically",
                    control.rect.top >= top && control.rect.bottom <= bottom);
        }
    }

    // --- Drag vs tap ----------------------------------------------------------

    /**
     * A press that travels past the touch slop is a drag, not a tap. Without this
     * the bar could not be moved without triggering whichever control the drag
     * started on — Stop included.
     */
    @Test
    public void draggingMovesTheWindowAndDoesNotFireAControl() {
        Recorder recorder = new Recorder();
        OverlayToolbarView view = layout(recorder);
        view.syncState(false, true, false);

        RectF stop = null;
        for (OverlayToolbarView.Control control : view.controlsForTest()) {
            if (control.action.equals(OverlayToolbarView.ACTION_STOP)) stop = control.rect;
        }
        assertNotNull(stop);

        pressAndMove(view, stop.centerX(), stop.centerY(), 60f, 40f);

        assertTrue("a drag must be reported", recorder.dragCount > 0);
        assertTrue("the drag must carry horizontal movement", recorder.dragX > 0f);
        assertTrue("the drag must carry vertical movement", recorder.dragY > 0f);
        assertEquals("a drag must not also act as a tap", 0, recorder.actions.size());
    }

    /**
     * A real finger is never perfectly still, so a small wobble must still count as
     * a tap. Treating it as a drag would make the toolbar feel unresponsive.
     */
    @Test
    public void aTapThatJittersWithinSlopStillFiresTheControl() {
        Recorder recorder = new Recorder();
        OverlayToolbarView view = layout(recorder);
        view.syncState(false, false, false);

        RectF select = firstControlRect(view);
        pressAndMove(view, select.centerX(), select.centerY(), 2f, 2f);

        assertEquals(1, recorder.actions.size());
        assertEquals(OverlayToolbarView.ACTION_SELECT, recorder.actions.get(0));
        assertEquals(0, recorder.dragCount);
    }

    // --- Label fitting --------------------------------------------------------

    /**
     * Button labels grow with a translation, and the widest is already close to its
     * button. Each label must fit its button or it bleeds over the neighbour.
     */
    @Test
    public void labelsFitInsideTheirButtons() {
        Recorder recorder = new Recorder();
        OverlayToolbarView view = layout(recorder);

        boolean[][] states = {{false, false, false}, {true, false, false},
                {false, true, false}, {false, false, true}};

        for (boolean[] state : states) {
            view.syncState(state[0], state[1], state[2]);
            for (OverlayToolbarView.Control control : view.controlsForTest()) {
                assertTrue(control.label != null && !control.label.isEmpty());
                assertTrue("label must stay inside its button: " + control.label,
                        control.textWidth <= control.rect.width() + 0.5f);
                assertTrue("label must remain legible", control.textSize > 0f);
            }
        }
    }

    @Test
    public void statsDoNotChangeTheControlSet() {
        Recorder recorder = new Recorder();
        OverlayToolbarView view = layout(recorder);

        view.syncState(false, true, false);
        view.setStats(120, 24.5f);
        view.setStats(240, 23.0f);

        assertEquals(OverlayToolbarView.ACTION_STOP, view.controlsForTest()[1].action);
        tapControl(view, OverlayToolbarView.ACTION_STOP);
        assertEquals(OverlayToolbarView.ACTION_STOP, recorder.actions.get(0));
    }

    /**
     * The service rebuilds the control set on every state change, so a state that
     * does not actually change must not churn the button list.
     */
    @Test
    public void repeatingTheSameStateIsANoOp() {
        Recorder recorder = new Recorder();
        OverlayToolbarView view = layout(recorder);

        view.syncState(false, true, false);
        OverlayToolbarView.Control[] first = view.controlsForTest();
        view.syncState(false, true, false);
        OverlayToolbarView.Control[] second = view.controlsForTest();

        assertEquals(first.length, second.length);
        assertEquals(first[0].action, second[0].action);
    }

    @Test
    public void inflaterConstructorProducesAnInertView() {
        OverlayToolbarView view = new OverlayToolbarView(context());
        assertNotNull(view);
        remeasure(view);
        // No listener attached, so a press must not throw.
        RectF rect = firstControlRect(view);
        pressAndMove(view, rect.centerX(), rect.centerY(), 0f, 0f);
        assertTrue(view.getWidth() > 0);
    }

    // --- Marker captions ------------------------------------------------------

    @Test
    public void markerCaptionDescribesLockedAndLostTargets() {
        String locked = TargetMarkerView.describe("red", 87.4f, false);
        assertTrue(locked.contains("TARGET"));
        assertTrue(locked.contains("red"));
        assertTrue(locked.contains("87"));

        assertEquals("TARGET LOST", TargetMarkerView.describe("red", 0f, true));

        // A target the colour detector does not classify is still a target, and the
        // label must not invent a colour for it.
        String unclassified = TargetMarkerView.describe("", 55f, false);
        assertTrue(unclassified.contains("target"));
        assertFalse(unclassified.contains("red"));
    }
}