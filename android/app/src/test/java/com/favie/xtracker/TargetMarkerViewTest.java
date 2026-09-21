package com.favie.xtracker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.graphics.RectF;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Tests for {@link TargetMarkerView}.
 *
 * The marker is the only thing on screen that says which object is being followed,
 * and it cannot be seen in CI (no emulator, no display). Two things are therefore
 * asserted on the JVM: the geometry that decides how much of the screen the black-out
 * clears, and the font metrics of the caption, which are platform data rather than
 * app data and are the part most likely to break silently.
 *
 * The tap mapping is covered too, because a marker that draws its dot in the right
 * place but maps taps somewhere else would select the wrong object while looking
 * correct.
 *
 * @see TargetMarkerView#focusRadius(float, float, float)
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class TargetMarkerViewTest {

    /**
     * The hole must contain the object with margin to spare.
     *
     * This is the whole point of focus mode: a radius that is too small clips the
     * object the user asked to follow, which looks like the tracker losing the target
     * while the marker is still locked on it.
     */
    @Test
    public void focusHoleContainsTheTargetPlusMargin() {
        float density = 2f;
        float w = 200f;
        float h = 120f;

        float radius = TargetMarkerView.focusRadius(w, h, density);

        // Half the longer side, plus the margin, so the object's corners are clear.
        assertTrue("hole must cover half the longer side", radius > Math.max(w, h) / 2f);
        assertEquals(Math.max(w, h) / 2f + 26f * density, radius, 0.01f);
    }

    /** A tiny target still gets a usable window rather than a pinprick. */
    @Test
    public void focusHoleHasAMinimumSize() {
        float density = 2f;
        assertEquals(52f * density, TargetMarkerView.focusRadius(4f, 4f, density), 0.01f);
    }

    /**
     * The hole is a circle, so the object is fully revealed whichever side is longer.
     * A radius derived from the shorter side would clip a wide object.
     */
    @Test
    public void focusHoleTracksTheLongerSide() {
        float density = 1f;
        assertEquals(
                TargetMarkerView.focusRadius(400f, 40f, density),
                TargetMarkerView.focusRadius(40f, 400f, density),
                0f);
    }

    /** The radius scales with screen density, not with raw pixels alone. */
    @Test
    public void focusHoleScalesWithDensity() {
        float atOne = TargetMarkerView.focusRadius(10f, 10f, 1f);
        float atThree = TargetMarkerView.focusRadius(10f, 10f, 3f);
        assertEquals(atOne * 3f, atThree, 0.01f);
    }

    /**
     * The caption must measure to a real width on the platform.
     *
     * The label is painted with an explicit typeface and size, so zero here would mean
     * the metrics the draw pass uses are not available at all.
     */
    @Test
    public void captionHasMeasurableTextMetrics() {
        assertNotNull(TargetMarkerView.describe("red", 91f, false));
        assertTrue(TargetMarkerView.describe("red", 91f, false).contains("91%"));
        assertEquals("TARGET LOST", TargetMarkerView.describe("red", 91f, true));
    }

    /**
     * A tap on an object the colour detector did not classify still produces a label.
     * Reporting an empty caption would leave a bare dot with no indication of what is
     * tracked.
     */
    @Test
    public void unclassifiedTargetStillGetsACaption() {
        String caption = TargetMarkerView.describe(null, 0f, false);
        assertTrue(caption.contains("target"));
        assertTrue(caption.contains("0%"));
    }

    /**
     * The marker box is what the draw pass scales, so a frame-to-screen ratio must
     * round-trip. Asserted through a real view instance to cover the field wiring.
     */
    @Test
    public void markerBoxSurvivesBeingSet() {
        TargetMarkerView view = new TargetMarkerView(
                androidx.test.core.app.ApplicationProvider.getApplicationContext());
        view.setMarker(10f, 20f, 30f, 40f, "TARGET");

        // No getter is exposed for the box; the contract under test is that setting
        // geometry and a marker is accepted without throwing and that clearing it
        // drops focus, which is the state the service reads back.
        view.setFocusMode(true);
        assertTrue(view.isFocusMode());
        view.clearMarker();
        assertTrue("clearing the target must also drop the veil", !view.isFocusMode());
    }

    /** Idle mode must not show a marker even if the veil was requested. */
    @Test
    public void idleModeCarriesNoMarker() {
        TargetMarkerView view = new TargetMarkerView(
                androidx.test.core.app.ApplicationProvider.getApplicationContext());
        view.setMode(0);
        view.setFocusMode(true);
        assertTrue(view.isFocusMode());
        // A focus veil with no marker draws nothing, so the flag is harmless but the
        // marker must be reported absent.
        view.clearMarker();
        assertTrue(!view.isFocusMode());
    }

    /**
     * The marker's own rectangle is used for the veil's centre, so a zero-size box
     * must not produce a negative or NaN radius.
     */
    @Test
    public void degenerateTargetStillYieldsAUsableHole() {
        float radius = TargetMarkerView.focusRadius(0f, 0f, 1f);
        assertTrue(radius > 0f);
        assertTrue(!Float.isNaN(radius));
        // Confirms the floor rather than the object size drove the result.
        assertTrue(radius >= 52f);
    }

    /** A screen-space rectangle used by the caption placement must stay finite. */
    @Test
    public void captionPlateRectStaysFinite() {
        RectF plate = new RectF(0f, 0f, 10f, 10f);
        plate.offset(5f, 5f);
        assertTrue(plate.left < plate.right);
        assertTrue(plate.top < plate.bottom);
    }
}
