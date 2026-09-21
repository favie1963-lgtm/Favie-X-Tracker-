package com.favie.xtracker;

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
    public void trackingStateOffersChangeIsolateAndStop() {
        Recorder recorder = new Recorder();
        OverlayToolbarView view = layout(recorder);

        view.syncState(false, true, false);

        OverlayToolbarView.Control[] controls = view.controlsForTest();
        assertEquals(3, controls.length);
        assertEquals(OverlayToolbarView.ACTION_CHANGE, controls[0].action);
        assertEquals(OverlayToolbarView.ACTION_ISOLATE, controls[1].action);
        assertEquals(OverlayToolbarView.ACTION_STOP, controls[2].action);
    }

    /**
     * While the screen is blanked down to the target, the toggle has to offer the way
     * back out. Leaving it reading "Isolate" would give the user a control that does
     * nothing, and no way to restore the rest of the screen.
     */
    @Test
    public void trackingStateOffersShowAllWhenAlreadyIsolated() {
        Recorder recorder = new Recorder();
        OverlayToolbarView view = layout(recorder);

        view.syncState(false, true, false, true);

        OverlayToolbarView.Control[] controls = view.controlsForTest();
        assertEquals(3, controls.length);
        assertEquals(OverlayToolbarView.ACTION_SHOW_ALL, controls[1].action);
        assertTrue("the bar must report the isolated state", view.isFocusMode());
    }

    /**
     * The black-out toggle changes the button set without any change in tracker
     * state, so the rebuild cannot key off the state alone.
     */
    @Test
    public void togglingFocusRebuildsTheControlSet() {
        Recorder recorder = new Recorder();
        OverlayToolbarView view = layout(recorder);

        view.syncState(false, true, false, false);
        assertEquals(OverlayToolbarView.ACTION_ISOLATE, view.controlsForTest()[1].action);

        view.syncState(false, true, false, true);
        assertEquals(OverlayToolbarView.ACTION_SHOW_ALL, view.controlsForTest()[1].action);
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

        assertEquals(OverlayToolbarView.ACTION_STOP, view.controlsForTest()[2].action);
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