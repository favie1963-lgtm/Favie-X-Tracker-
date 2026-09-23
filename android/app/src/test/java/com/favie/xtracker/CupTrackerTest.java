package com.favie.xtracker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/**
 * Behavioural tests for {@link CupTracker}.
 *
 * The point of these is the one thing appearance matching cannot do: the three
 * cups are drawn identically, so only position carries identity. If a letter ever
 * jumps to a different cup the readout and the marker would both be wrong, and
 * these tests catch that.
 */
public class CupTrackerTest {

    private static final int W = 960;
    private static final int H = 540;

    private static final int TABLE = argb(0x2A, 0x4A, 0x2E);
    private static final int CUP = argb(0xC8, 0xC4, 0xB8);
    private static final int CUP_RIM = argb(0x8A, 0x86, 0x7C);

    private static final int CUP_W = 110;
    private static final int CUP_H = 120;
    private static final int CUP_Y = 250;

    private static int argb(int r, int g, int b) {
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** Every cup is drawn with the same colours: nothing but position differs. */
    private static int[] scene(int... cupX) {
        int[] px = new int[W * H];
        java.util.Arrays.fill(px, TABLE);
        for (int x : cupX) {
            rect(px, x, CUP_Y, CUP_W, CUP_H, CUP);
            rect(px, x, CUP_Y, CUP_W, 5, CUP_RIM);
            rect(px, x, CUP_Y + CUP_H - 5, CUP_W, 5, CUP_RIM);
            rect(px, x, CUP_Y, 5, CUP_H, CUP_RIM);
            rect(px, x + CUP_W - 5, CUP_Y, 5, CUP_H, CUP_RIM);
        }
        return px;
    }

    private static void rect(int[] px, int x, int y, int w, int h, int c) {
        for (int dy = 0; dy < h; dy++) {
            for (int dx = 0; dx < w; dx++) {
                int ax = x + dx;
                int ay = y + dy;
                if (ax < 0 || ay < 0 || ax >= W || ay >= H) continue;
                px[ay * W + ax] = c;
            }
        }
    }

    private static List<String> lettersLeftToRight(CupTracker t, int[] frame) {
        List<String> labels = new ArrayList<>();
        for (CupTracker.TrackedCup c : t.update(frame, W, H)) labels.add(c.label);
        return labels;
    }

    @Test
    public void detectsThreeCupsAndLabelsThemLeftToRight() {
        CupTracker t = new CupTracker();
        List<String> order = lettersLeftToRight(t, scene(120, 420, 720));
        assertEquals(3, order.size());
        assertEquals("A B C", String.join(" ", order));
    }

    @Test
    public void lettersStayWithTheirCupThroughAFullSwap() {
        CupTracker t = new CupTracker();
        t.update(scene(120, 420, 720), W, H);

        // The left and right cups trade places, passing through each other in
        // small steps so identity has to be carried by motion, not appearance.
        int[] lefts = {240, 360, 480, 600, 720};
        int[] rights = {600, 480, 360, 240, 120};
        for (int i = 0; i < lefts.length; i++) {
            List<CupTracker.TrackedCup> cups = t.update(scene(lefts[i], 420, rights[i]), W, H);
            assertEquals("all three cups must survive the shuffle", 3, cups.size());
        }

        // The cup that started left now sits right, so left-to-right is C B A.
        List<String> order = lettersLeftToRight(t, scene(720, 420, 120));
        assertEquals("C B A", String.join(" ", order));
    }

    @Test
    public void markerStaysOnTheSelectedCupThroughTheShuffle() {
        CupTracker t = new CupTracker();
        t.update(scene(120, 420, 720), W, H);

        // The user picks the middle cup, which is B.
        String locked = t.lockAt(420 + CUP_W / 2f, CUP_Y + CUP_H / 2f);
        assertEquals("B", locked);

        // B shuffles left while the right-hand cup takes its old place. The marker
        // must travel with B, not stay where B used to be.
        int[] bTrack = {300, 240, 180, 120};
        for (int x : bTrack) {
            t.update(scene(x, 600, 540), W, H);
        }

        CupTracker.TrackedCup held = t.getLockedCup();
        assertNotNull("the locked cup must still be tracked", held);
        assertEquals("B", held.label);
        assertTrue("marker must have travelled left with cup B, was "
                        + held.cup.centerX(),
                Math.abs(held.cup.centerX() - (120 + CUP_W / 2f)) < CUP_W);
    }

    @Test
    public void tappingBesideACupStillLocksTheNearestOne() {
        CupTracker t = new CupTracker();
        t.update(scene(120, 420, 720), W, H);

        // Just outside the box, on the table.
        String locked = t.lockAt(120 - 12f, CUP_Y + CUP_H / 2f);
        assertEquals("A", locked);
    }

    @Test
    public void tappingFarFromEveryCupLocksNothing() {
        CupTracker t = new CupTracker();
        t.update(scene(120, 420, 720), W, H);
        assertNull(t.lockAt(20f, 40f));
        assertFalse(t.hasLock());
    }

    @Test
    public void lockedCupReleasesWhenItLeavesTheGame() {
        CupTracker t = new CupTracker();
        t.update(scene(120, 420, 720), W, H);
        t.lockAt(420 + CUP_W / 2f, CUP_Y + CUP_H / 2f);

        // The cup is lifted away; only the other two remain, for longer than the
        // grace period.
        for (int i = 0; i < 20; i++) {
            t.update(scene(120, 720), W, H);
        }

        assertNull("a cup that left the board must not keep a stale box", t.getLockedCup());
        assertEquals("2", String.valueOf(t.orderText().split(" ").length));
    }

    @Test
    public void orderTextTracksTheShuffleForTheToolbar() {
        CupTracker t = new CupTracker();
        t.update(scene(120, 420, 720), W, H);
        assertEquals("A B C", t.orderText());

        // A and B trade places over a realistic shuffle: small steps, identical
        // cups, overlapping in the middle. Reading the letters out left to right
        // must report B then A.
        for (int x = 180; x <= 420; x += 60) {
            t.update(scene(x, 540 - x, 720), W, H);
        }
        assertEquals("B A C", t.orderText());
    }

    @Test
    public void resetClearsEverything() {
        CupTracker t = new CupTracker();
        t.update(scene(120, 420, 720), W, H);
        t.lockAt(120 + CUP_W / 2f, CUP_Y + CUP_H / 2f);
        t.reset();

        assertNull(t.getLockedCup());
        assertEquals("", t.orderText());
    }

    /**
     * A slow swap used to scramble the letters, and only at some speeds.
     *
     * The tracker treats a detection much wider than a cup as two cups merged, using
     * the identity's own width as the reference. That reference was the width it had
     * last adopted, so adopting a slightly-too-wide blob raised the threshold for the
     * next frame: the box grew by about a cup's width every tick until one identity
     * had swallowed all three cups and parked in the middle, while the other two
     * letters ended up on the wrong cups. The failure was speed-dependent — whether
     * the box crossed the ratio depended on how the blob's integer bounds fell — so it
     * survived sampling a couple of speeds and would have shown up as an occasional
     * missed shuffle in play.
     *
     * This walks an outer-cup swap at many step counts, which is what made the ladder
     * reach its threshold, and checks the letters every time.
     */
    @Test
    public void lettersSurviveASwapAtEveryRealisticSpeed() {
        int failures = 0;
        StringBuilder failing = new StringBuilder();
        for (int steps = 5; steps <= 60; steps++) {
            CupTracker t = new CupTracker();
            double[] x = {120, 420, 720};
            t.update(scene((int) x[0], (int) x[1], (int) x[2]), W, H);
            double a0 = x[0], c0 = x[2];
            for (int k = 1; k <= steps; k++) {
                double f = k / (double) steps;
                x[0] = a0 + (c0 - a0) * f;
                x[2] = c0 + (a0 - c0) * f;
                t.update(scene((int) Math.round(x[0]), (int) x[1], (int) Math.round(x[2])), W, H);
            }
            // Settle, then the cup that started left must read right of the other.
            for (int k = 0; k < 3; k++) {
                t.update(scene((int) Math.round(x[0]), (int) x[1], (int) Math.round(x[2])), W, H);
            }
            assertEquals("all three cups must survive the shuffle at " + steps + " steps",
                    3, t.update(scene((int) Math.round(x[0]), (int) x[1],
                            (int) Math.round(x[2])), W, H).size());
            if (!"C B A".equals(t.orderText())) {
                failures++;
                failing.append(steps).append(' ');
            }
        }
        assertEquals("letters must not swap at any realistic speed; failed at steps "
                + failing, 0, failures);
    }

    /**
     * The two readout fields the toolbar draws from must describe one frame.
     *
     * The strip pairs {@code labels[i]} with {@code positions[i]}, so if the order
     * and the positions could come from different frames a letter would be drawn at a
     * cup it does not belong to. They are produced by a single call for that reason,
     * and this pins the pairing: each letter must sit at the cup carrying it, and the
     * positions must be in reading order.
     */
    @Test
    public void readoutPairsEachLetterWithItsOwnCupPosition() {
        CupTracker t = new CupTracker();
        t.update(scene(120, 420, 720), W, H);

        // Shuffle the outer cups in, so the reading order changes.
        for (int k = 1; k <= 6; k++) {
            double f = k / 6.0;
            t.update(scene((int) Math.round(120 + 600 * f), 420,
                    (int) Math.round(720 - 600 * f)), W, H);
        }
        t.update(scene(720, 420, 120), W, H);
        t.update(scene(720, 420, 120), W, H);
        t.update(scene(720, 420, 120), W, H);

        String[] labels = t.readout(W).order.split(" ");
        float[] positions = t.readout(W).positions;
        assertEquals(labels.length, positions.length);

        // Positions must be in reading order (non-decreasing).
        for (int i = 1; i < positions.length; i++) {
            assertTrue("positions must be in reading order",
                    positions[i] >= positions[i - 1] - 1e-4f);
        }

        // Each letter must be at the cup the tracker says carries it.
        for (CupTracker.TrackedCup cup : t.update(scene(720, 420, 120), W, H)) {
            for (int i = 0; i < labels.length; i++) {
                if (!labels[i].equals(cup.label)) continue;
                float expected = cup.cup.centerX() / W;
                assertEquals("letter " + labels[i] + " must carry its own position",
                        expected, positions[i], 1e-3f);
            }
        }
    }

    /**
     * A fast outer swap at only a few ticks used to scramble the letters.
     *
     * The prediction was {@code cup + velocity * missedTicks}, which omitted the tick
     * being predicted: a just-matched cup was predicted back at its last observed
     * position while a cup that had missed a frame was predicted forward by the full
     * miss count. On the frame where all three cups coincide only one can be matched,
     * so the other two froze and, when the cups separated, re-matched whichever cup
     * was nearest their stale position. At four ticks per swap — a fast but entirely
     * ordinary shuffle — the outer cups came out reading A B C instead of C B A, i.e.
     * the two letters had swapped onto each other's cups.
     *
     * The identity of each cup, not just the order, is what has to survive, so this
     * locks each cup in turn and checks where its letter ends up.
     */
    @Test
    public void eachLettersIdentitySurvivesAFastOuterSwap() {
        for (int steps = 4; steps <= 24; steps++) {
            int[] start = {120, 420, 720};
            // The left and right cups trade places; the middle one stays.
            double[] end = {720, 420, 120};
            for (int which = 0; which < 3; which++) {
                CupTracker t = new CupTracker();
                t.update(scene(start[0], start[1], start[2]), W, H);
                t.lockAt(start[which] + CUP_W / 2f, CUP_Y + CUP_H / 2f);

                for (int k = 1; k <= steps; k++) {
                    double f = k / (double) steps;
                    t.update(scene(
                            (int) Math.round(start[0] + (end[0] - start[0]) * f),
                            start[1],
                            (int) Math.round(start[2] + (end[2] - start[2]) * f)), W, H);
                }
                for (int k = 0; k < 3; k++) {
                    t.update(scene((int) end[0], (int) end[1], (int) end[2]), W, H);
                }

                CupTracker.TrackedCup held = t.getLockedCup();
                assertNotNull("cup " + which + " must still be tracked at " + steps
                        + " steps", held);
                float expected = (float) end[which] + CUP_W / 2f;
                assertTrue("cup " + which + " must end on its own destination at "
                                + steps + " steps; expected " + expected + " but the letter "
                                + held.label + " sat at " + held.cup.centerX(),
                        Math.abs(held.cup.centerX() - expected) < CUP_W);
            }
        }
    }

    /**
     * A circular rotation, where every cup moves, must also keep each identity on its
     * own cup. The middle cup travelling too is what the outer-only swap never
     * exercised.
     */
    @Test
    public void eachLettersIdentitySurvivesARotation() {
        for (int steps = 4; steps <= 24; steps++) {
            double[] start = {120, 420, 720};
            double[] end = {420, 720, 120};
            for (int which = 0; which < 3; which++) {
                CupTracker t = new CupTracker();
                t.update(scene((int) start[0], (int) start[1], (int) start[2]), W, H);
                t.lockAt((float) start[which] + CUP_W / 2f, CUP_Y + CUP_H / 2f);

                for (int k = 1; k <= steps; k++) {
                    double f = k / (double) steps;
                    t.update(scene(
                            (int) Math.round(start[0] + (end[0] - start[0]) * f),
                            (int) Math.round(start[1] + (end[1] - start[1]) * f),
                            (int) Math.round(start[2] + (end[2] - start[2]) * f)), W, H);
                }
                for (int k = 0; k < 3; k++) {
                    t.update(scene((int) end[0], (int) end[1], (int) end[2]), W, H);
                }

                CupTracker.TrackedCup held = t.getLockedCup();
                assertNotNull("cup " + which + " must still be tracked at " + steps
                        + " steps", held);
                float expected = (float) end[which] + CUP_W / 2f;
                assertTrue("cup " + which + " must end on its own destination at "
                                + steps + " steps; expected " + expected + " but the letter "
                                + held.label + " sat at " + held.cup.centerX(),
                        Math.abs(held.cup.centerX() - expected) < CUP_W);
            }
        }
    }

    /**
     * The full readout has to carry every cup's own box, not only the order and the
     * positions, because the on-screen letters are drawn from it. Each reported box
     * must be the box of the cup whose letter it names.
     */
    @Test
    public void readoutReportsEachCupsOwnBoxForTheOnScreenLetters() {
        CupTracker t = new CupTracker();
        t.update(scene(120, 420, 720), W, H);

        CupTracker.ShuffleReadout readout = t.readout(W);
        assertEquals(3, readout.cups.size());
        for (int i = 0; i < readout.cups.size(); i++) {
            CupTracker.CupTruth truth = readout.cups.get(i);
            assertEquals("the letters must be in reading order",
                    readout.order.split(" ")[i], truth.label);
            assertEquals("the box must belong to the named cup",
                    readout.positions[i], truth.cup.centerX() / W, 1e-3f);
        }
    }

    /** A frame with no cups must produce an empty readout, not a stale one. */
    @Test
    public void readoutIsEmptyWhenNothingIsTracked() {
        CupTracker t = new CupTracker();
        CupTracker.ShuffleReadout readout = t.readout(W);
        assertEquals("", readout.order);
        assertEquals(0, readout.positions.length);
    }

    @Test
    public void aFlatFrameYieldsNoCupsRatherThanNonsense() {
        CupTracker t = new CupTracker();
        int[] flat = new int[W * H];
        java.util.Arrays.fill(flat, TABLE);
        assertTrue(t.update(flat, W, H).isEmpty());
        assertNull(t.lockAt(400f, 300f));
    }
}
