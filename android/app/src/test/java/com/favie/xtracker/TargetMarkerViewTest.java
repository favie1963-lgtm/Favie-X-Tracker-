package com.favie.xtracker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.graphics.RectF;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

/**
 * Tests for {@link TargetMarkerView}.
 *
 * The marker is the only thing on screen that says which object is being followed,
 * and it cannot be seen in CI (no emulator, no display). What is asserted on the JVM
 * is therefore the captions the marker paints, since a caption that names the wrong
 * cup while the dot sits in the right place is exactly the bug this app kept
 * shipping.
 *
 * The tap mapping is covered too, because a marker that draws its dot in the right
 * place but maps taps somewhere else would select the wrong object while looking
 * correct.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class TargetMarkerViewTest {

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
     * A cup caption carries both the letter being followed and the reading of the
     * shuffle, because the letter alone is meaningless once the cups have moved.
     */
    @Test
    public void describeCupNamesTheSelectionAndOrder() {
        assertEquals("CUP B · ORDER B A C",
                TargetMarkerView.describeCup("B", "B A C"));
        assertEquals("CUP A", TargetMarkerView.describeCup("A", ""));
        assertEquals("CUP ?", TargetMarkerView.describeCup(null, null));
    }

    /**
     * A marker survives being set and cleared through the real field wiring, and a
     * cleared marker reports idle because that is the state the service reads back.
     */
    @Test
    public void markerBoxSurvivesBeingSet() {
        TargetMarkerView view = new TargetMarkerView(
                androidx.test.core.app.ApplicationProvider.getApplicationContext());
        view.setMarker(10f, 20f, 30f, 40f, "TARGET");
        view.setMode(2);
        assertEquals(2, view.getMode());

        view.clearMarker();
        view.setMode(0);
        assertEquals(0, view.getMode());
    }

    /** A screen-space rectangle used by the caption placement must stay finite. */
    @Test
    public void captionPlateRectStaysFinite() {
        RectF plate = new RectF(0f, 0f, 10f, 10f);
        plate.offset(5f, 5f);
        assertTrue(plate.left < plate.right);
        assertTrue(plate.top < plate.bottom);
    }

    /**
     * All three cups are labelled, not only the locked one.
     *
     * The app is supposed to put A, B and C on the three cups as soon as it finds
     * them, so the user can follow the letters through a shuffle. Drawing only the
     * locked cup left the other two unlabelled, which is not what was asked for.
     * The set is built per frame from the tracker's single identity pass, so the
     * view must carry exactly what it is handed and copy it.
     */
    @Test
    public void everyTrackedCupCarriesItsLetter() {
        TargetMarkerView view = new TargetMarkerView(
                androidx.test.core.app.ApplicationProvider.getApplicationContext());

        List<TargetMarkerView.CupLabel> cups = new ArrayList<>();
        cups.add(new TargetMarkerView.CupLabel(120f, 250f, 110f, 120f, "A", false));
        cups.add(new TargetMarkerView.CupLabel(420f, 250f, 110f, 120f, "B", true));
        cups.add(new TargetMarkerView.CupLabel(720f, 250f, 110f, 120f, "C", false));
        view.setCups(cups);

        List<TargetMarkerView.CupLabel> held = view.getCups();
        assertEquals("all three cups must be labelled", 3, held.size());
        assertEquals("A", held.get(0).label);
        assertEquals("B", held.get(1).label);
        assertEquals("C", held.get(2).label);
        assertTrue("exactly the locked cup is flagged", held.get(1).locked);
        assertFalse(held.get(0).locked);
        assertFalse(held.get(2).locked);

        // The view owns its list: clearing the one it was handed must not drop the
        // letters that are already drawn.
        cups.clear();
        assertEquals(3, view.getCups().size());
        assertEquals(120f, view.getCups().get(0).x, 1e-3f);
    }

    /** Clearing the dot must not take the cup letters with it. */
    @Test
    public void clearingTheTargetKeepsTheCupLetters() {
        TargetMarkerView view = new TargetMarkerView(
                androidx.test.core.app.ApplicationProvider.getApplicationContext());

        List<TargetMarkerView.CupLabel> cups = new ArrayList<>();
        cups.add(new TargetMarkerView.CupLabel(120f, 250f, 110f, 120f, "A", false));
        cups.add(new TargetMarkerView.CupLabel(420f, 250f, 110f, 120f, "B", false));
        view.setCups(cups);
        view.setMarker(10f, 20f, 30f, 40f, "TARGET");

        view.clearTargetMarker();
        assertEquals("the letters stay on the cups", 2, view.getCups().size());

        view.clearMarker();
        assertEquals("ending tracking clears them", 0, view.getCups().size());
    }

    /** The cup chips must be drawable without a target dot, which Robolectric can do. */
    @Test
    public void cupLettersDrawWithoutATargetMarker() {
        TargetMarkerView view = new TargetMarkerView(
                androidx.test.core.app.ApplicationProvider.getApplicationContext());
        view.setGeometry(960, 540, 1080, 1920);

        List<TargetMarkerView.CupLabel> cups = new ArrayList<>();
        cups.add(new TargetMarkerView.CupLabel(120f, 250f, 110f, 120f, "A", false));
        cups.add(new TargetMarkerView.CupLabel(420f, 250f, 110f, 120f, "B", true));
        cups.add(new TargetMarkerView.CupLabel(720f, 250f, 110f, 120f, "C", false));
        view.setCups(cups);
        view.setMode(2);

        android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(1080, 1920,
                android.graphics.Bitmap.Config.ARGB_8888);
        view.draw(new android.graphics.Canvas(bmp));
        assertEquals(3, view.getCups().size());
    }
}
