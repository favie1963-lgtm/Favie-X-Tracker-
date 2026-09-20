package com.favie.xtracker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Behavioural tests for {@link NativeTargetTracker}.
 *
 * The tracker runs on the device inside {@code ScreenCaptureService}, where it
 * cannot be exercised without a real MediaProjection, so it is tested here
 * against synthetic ARGB frames instead. These are the same cases the JS side
 * asserts in {@code scripts/check-target-tracking.mjs}; the two trackers must
 * agree because the on-screen marker the user sees is driven by this one.
 */
public class NativeTargetTrackerTest {

    private static final int W = 320;
    private static final int H = 240;

    private static final int BACKGROUND = argb(0x80, 0x80, 0x80);
    private static final int RED = argb(0xFF, 0x00, 0x00);
    private static final int BLUE = argb(0x00, 0x00, 0xFF);

    private static int argb(int r, int g, int b) {
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static final class Frame {
        final int[] pixels;

        Frame() {
            pixels = new int[W * H];
            java.util.Arrays.fill(pixels, BACKGROUND);
        }

        Frame rect(int x, int y, int w, int h, int colour) {
            for (int dy = 0; dy < h; dy++) {
                for (int dx = 0; dx < w; dx++) {
                    int px = x + dx;
                    int py = y + dy;
                    if (px < 0 || py < 0 || px >= W || py >= H) continue;
                    pixels[py * W + px] = colour;
                }
            }
            return this;
        }

        boolean update(NativeTargetTracker tracker) {
            return tracker.update(pixels, W, H);
        }
    }

    /** Intersection-over-union of two boxes, for "is the box still on the object". */
    private static float iou(float ax, float ay, float aw, float ah,
                             float bx, float by, float bw, float bh) {
        float x1 = Math.max(ax, bx);
        float y1 = Math.max(ay, by);
        float x2 = Math.min(ax + aw, bx + bw);
        float y2 = Math.min(ay + ah, by + bh);
        float inter = Math.max(0f, x2 - x1) * Math.max(0f, y2 - y1);
        float union = aw * ah + bw * bh - inter;
        return union <= 0f ? 0f : inter / union;
    }

    private static final float A_X = 40f;
    private static final float A_Y = 100f;
    private static final float SIZE = 30f;
    private static final float DECOY_X = 220f;
    private static final float DECOY_Y = 100f;

    /** Frame with object A, a look-alike decoy B, and an unrelated blue object. */
    private static Frame scene(int aX, int aY) {
        return new Frame()
                .rect(aX, aY, (int) SIZE, (int) SIZE, RED)
                .rect((int) DECOY_X, (int) DECOY_Y, (int) SIZE, (int) SIZE, RED)
                .rect(130, 170, 20, 20, BLUE);
    }

    private static int tapX(int aX) {
        return aX + (int) SIZE / 2;
    }

    private static int tapY(int aY) {
        return aY + (int) SIZE / 2;
    }

    @Test
    public void selectionLocksOntoTheTappedObject() {
        NativeTargetTracker tracker = new NativeTargetTracker();
        Frame frame = scene((int) A_X, (int) A_Y);

        assertTrue(tracker.select(frame.pixels, W, H, tapX((int) A_X), tapY((int) A_Y)));
        assertEquals(NativeTargetTracker.STATE_LOCKED, tracker.getState());
        assertTrue("box should sit on the tapped object",
                iou(tracker.getBoxX(), tracker.getBoxY(), tracker.getBoxW(), tracker.getBoxH(),
                        A_X, A_Y, SIZE, SIZE) > 0.5f);
    }

    @Test
    public void targetStaysLockedAndFollowsTheObjectWhenItMoves() {
        NativeTargetTracker tracker = new NativeTargetTracker();
        assertTrue(tracker.select(scene((int) A_X, (int) A_Y).pixels, W, H,
                tapX((int) A_X), tapY((int) A_Y)));

        float movedX = A_X + 12f;
        Frame moved = scene((int) movedX, (int) A_Y);
        assertTrue(moved.update(tracker));
        assertEquals(NativeTargetTracker.STATE_LOCKED, tracker.getState());
        assertTrue("marker must follow the moved object",
                iou(tracker.getBoxX(), tracker.getBoxY(), tracker.getBoxW(), tracker.getBoxH(),
                        movedX, A_Y, SIZE, SIZE) > 0.5f);
        assertTrue("marker must not jump to the stationary decoy",
                iou(tracker.getBoxX(), tracker.getBoxY(), tracker.getBoxW(), tracker.getBoxH(),
                        DECOY_X, DECOY_Y, SIZE, SIZE) < 0.2f);
    }

    /**
     * The regression this class exists for.
     *
     * With the old summed-over-three-channels metric a region of flat grey scored
     * about 0.50 against a saturated red template, and the threshold was 0.35, so
     * a target that had vanished was accepted as "found" on empty background and
     * never reported lost.
     */
    @Test
    public void targetIsReportedLostWhenItDisappears() {
        NativeTargetTracker tracker = new NativeTargetTracker();
        assertTrue(tracker.select(scene((int) A_X, (int) A_Y).pixels, W, H,
                tapX((int) A_X), tapY((int) A_Y)));

        // Object A removed; background, the red decoy and the blue object remain.
        Frame gone = new Frame()
                .rect((int) DECOY_X, (int) DECOY_Y, (int) SIZE, (int) SIZE, RED)
                .rect(130, 170, 20, 20, BLUE);

        for (int i = 0; i < 10; i++) {
            gone.update(tracker);
        }

        assertEquals(NativeTargetTracker.STATE_LOST, tracker.getState());
    }

    @Test
    public void lostTargetDoesNotSilentlyBecomeTheLookAlike() {
        NativeTargetTracker tracker = new NativeTargetTracker();
        assertTrue(tracker.select(scene((int) A_X, (int) A_Y).pixels, W, H,
                tapX((int) A_X), tapY((int) A_Y)));

        Frame gone = new Frame()
                .rect((int) DECOY_X, (int) DECOY_Y, (int) SIZE, (int) SIZE, RED)
                .rect(130, 170, 20, 20, BLUE);
        for (int i = 0; i < 10; i++) {
            gone.update(tracker);
        }

        assertTrue("lost target must not adopt the decoy",
                iou(tracker.getBoxX(), tracker.getBoxY(), tracker.getBoxW(), tracker.getBoxH(),
                        DECOY_X, DECOY_Y, SIZE, SIZE) < 0.2f);
        assertEquals("lost confidence should read as zero", 0f, tracker.getConfidence(), 0.001f);
    }

    @Test
    public void aSingleDroppedFrameDoesNotFlickerTheMarkerToLost() {
        NativeTargetTracker tracker = new NativeTargetTracker();
        assertTrue(tracker.select(scene((int) A_X, (int) A_Y).pixels, W, H,
                tapX((int) A_X), tapY((int) A_Y)));

        new Frame().update(tracker);
        assertEquals("one blank frame is inside the grace period",
                NativeTargetTracker.STATE_LOCKED, tracker.getState());
    }

    @Test
    public void reselectingReacquiresTheObjectAtItsLastKnownPosition() {
        NativeTargetTracker tracker = new NativeTargetTracker();
        assertTrue(tracker.select(scene((int) A_X, (int) A_Y).pixels, W, H,
                tapX((int) A_X), tapY((int) A_Y)));

        Frame gone = new Frame().rect((int) DECOY_X, (int) DECOY_Y, (int) SIZE, (int) SIZE, RED);
        for (int i = 0; i < 10; i++) {
            gone.update(tracker);
        }
        assertEquals(NativeTargetTracker.STATE_LOST, tracker.getState());

        // Reacquire is explicit: the user taps the object again where it is.
        assertTrue(tracker.select(scene((int) A_X, (int) A_Y).pixels, W, H,
                tapX((int) A_X), tapY((int) A_Y)));
        assertEquals(NativeTargetTracker.STATE_LOCKED, tracker.getState());
        assertTrue(iou(tracker.getBoxX(), tracker.getBoxY(), tracker.getBoxW(), tracker.getBoxH(),
                A_X, A_Y, SIZE, SIZE) > 0.5f);
    }

    @Test
    public void tapOnAFlatBackgroundStillYieldsAReplaceableTarget() {
        NativeTargetTracker tracker = new NativeTargetTracker();
        Frame flat = new Frame();

        assertTrue("a tap on empty space must still lock something",
                tracker.select(flat.pixels, W, H, 160, 120));
        assertEquals(NativeTargetTracker.STATE_LOCKED, tracker.getState());
    }

    @Test
    public void resetClearsTheTarget() {
        NativeTargetTracker tracker = new NativeTargetTracker();
        tracker.select(scene((int) A_X, (int) A_Y).pixels, W, H, tapX((int) A_X), tapY((int) A_Y));
        tracker.reset();

        assertEquals(NativeTargetTracker.STATE_NONE, tracker.getState());
        assertFalse(tracker.isLocked());
    }
}
