package com.favie.xtracker;

/**
 * Single-object tracker for a target the user explicitly picked.
 *
 * This is deliberately not an auto-detector: it never chooses a target. The user
 * taps an object, {@link #select} grows a region around that exact point and the
 * target is thereafter followed as a template. The behaviour mirrors
 * {@code TargetTracker} in {@code src/services/vision/tracker.js} so the Android
 * tracker and the web build agree.
 *
 * Lock strategy, in order:
 * <ol>
 *   <li>Search a window bounded by the target's own size for the locked colour
 *       signature (coarse 2px pass, then a 1px refinement).</li>
 *   <li>If nothing scores above the threshold, hold the lock for a grace period
 *       so a single dropped frame does not flicker the UI, then report LOST and
 *       keep the last known box.</li>
 * </ol>
 *
 * There is intentionally no global re-detection step. A target is only ever
 * replaced by an explicit user action, so the tracker cannot drift onto a
 * different or more easily found object.
 *
 * All coordinates are in analysis-frame pixels. Frames are ARGB_8888 int arrays.
 */
public class NativeTargetTracker {

    public static final int STATE_NONE = 0;
    public static final int STATE_LOCKED = 1;
    public static final int STATE_LOST = 2;

    /** Edge length of the colour signature grid. */
    private static final int SIG = 16;

    /** Per-channel sum tolerance for region growing, out of 765. */
    private static final int GROW_TOLERANCE = 120;

    /** Region growing stops once this fraction of the frame is filled. */
    private static final double MAX_REGION_FRACTION = 0.5;

    /**
     * Lower bound on the search window, as a fraction of the shorter frame edge.
     * Kept in step with the {@code 0.12} in {@code TargetTracker#searchRadius}.
     */
    private static final float FRAME_RADIUS_FRACTION = 0.12f;

    private final float maxShift;
    private final float matchThreshold;
    private final int lostGraceFrames;

    /** SIG*SIG*3 RGB bytes; the appearance template of the locked target. */
    private int[] signature;

    private float boxX;
    private float boxY;
    private float boxW;
    private float boxH;

    private int state = STATE_NONE;
    private int lostFrames = 0;
    private float confidence = 0f;
    private int colorLabel = 0;
    private long lastSeenAt = 0L;

    public NativeTargetTracker() {
        this(0.35f, 0.82f, 5);
    }

    public NativeTargetTracker(float maxShift, float matchThreshold, int lostGraceFrames) {
        this.maxShift = maxShift;
        this.matchThreshold = matchThreshold;
        this.lostGraceFrames = lostGraceFrames;
    }

    public boolean isLocked() {
        return state == STATE_LOCKED;
    }

    public int getState() {
        return state;
    }

    public int getLostFrames() {
        return lostFrames;
    }

    public float getConfidence() {
        return confidence;
    }

    public long getLastSeenAt() {
        return lastSeenAt;
    }

    public float getBoxX() {
        return boxX;
    }

    public float getBoxY() {
        return boxY;
    }

    public float getBoxW() {
        return boxW;
    }

    public float getBoxH() {
        return boxH;
    }

    public int getColor() {
        return colorLabel;
    }

    public void reset() {
        signature = null;
        state = STATE_NONE;
        lostFrames = 0;
        confidence = 0f;
        boxX = boxY = boxW = boxH = 0f;
        lastSeenAt = 0L;
    }

    /**
     * Lock onto the object under the user's tap.
     *
     * The region is grown from the tapped pixel across neighbouring pixels within
     * {@link #GROW_TOLERANCE}, which selects the visual blob the user actually
     * tapped rather than a fixed-size box. A region that fills the whole frame
     * (a flat background) is rejected as "not an object" and a small box centred
     * on the tap is used instead, so a tap on empty space still yields a target
     * the user can immediately replace.
     *
     * @return true if a target is now locked
     */
    public boolean select(int[] argb, int width, int height, int tapX, int tapY) {
        reset();
        if (argb == null || width <= 0 || height <= 0) return false;

        int px = Math.max(0, Math.min(width - 1, tapX));
        int py = Math.max(0, Math.min(height - 1, tapY));

        Region region = growRegion(argb, width, height, px, py);
        boolean usable = region != null && region.count >= 24 && !region.filledFrame;

        if (usable) {
            boxX = region.minX;
            boxY = region.minY;
            boxW = region.maxX - region.minX + 1;
            boxH = region.maxY - region.minY + 1;
            colorLabel = argb[py * width + px];
        } else {
            int size = Math.max(24, (int) Math.round(Math.min(width, height) * 0.1));
            boxW = size;
            boxH = size;
            boxX = clamp(px - size / 2f, 0, width - size);
            boxY = clamp(py - size / 2f, 0, height - size);
            colorLabel = argb[py * width + px];
        }

        signature = extractSignature(argb, width, height, boxX, boxY, boxW, boxH);
        state = STATE_LOCKED;
        lostFrames = 0;
        confidence = 100f;
        lastSeenAt = System.currentTimeMillis();
        return true;
    }

    /**
     * Advance the lock by one frame.
     *
     * @return true if the target is locked after this frame
     */
    public boolean update(int[] argb, int width, int height) {
        if (state == STATE_NONE || signature == null) return false;
        if (argb == null || width <= 0 || height <= 0) {
            markLost();
            return false;
        }

        Match match = search(argb, width, height);
        if (match != null && match.score >= matchThreshold) {
            boxX = match.x;
            boxY = match.y;
            boxW = match.w;
            boxH = match.h;
            signature = extractSignature(argb, width, height, boxX, boxY, boxW, boxH);
            state = STATE_LOCKED;
            lostFrames = 0;
            confidence = match.score * 100f;
            lastSeenAt = System.currentTimeMillis();
            return true;
        }

        markLost();
        return state == STATE_LOCKED;
    }

    private void markLost() {
        lostFrames++;
        confidence = 0f;
        if (lostFrames > lostGraceFrames) state = STATE_LOST;
    }

    /**
     * Flood-fill style region grow from a seed pixel.
     *
     * Bounded by {@link #MAX_REGION_FRACTION} so a tap on a flat background stops
     * early instead of scanning the whole frame.
     */
    private Region growRegion(int[] argb, int width, int height, int sx, int sy) {
        int seed = argb[sy * width + sx];
        int seedR = (seed >> 16) & 0xFF;
        int seedG = (seed >> 8) & 0xFF;
        int seedB = seed & 0xFF;

        int limit = (int) (width * (long) height * MAX_REGION_FRACTION);
        boolean[] seen = new boolean[width * height];
        int[] queue = new int[Math.min(limit, width * height)];
        int head = 0;
        int tail = 0;

        Region region = new Region();
        queue[tail++] = sy * width + sx;
        seen[sy * width + sx] = true;

        while (head < tail) {
            int idx = queue[head++];
            int y = idx / width;
            int x = idx - y * width;

            region.accept(x, y, width, height);

            // Neighbours, 4-connected: diagonal growth leaks across thin gaps
            // between two touching objects and merges them into one region.
            if (x > 0) tail = consider(argb, seen, queue, tail, limit, idx - 1, seedR, seedG, seedB);
            if (x < width - 1) tail = consider(argb, seen, queue, tail, limit, idx + 1, seedR, seedG, seedB);
            if (y > 0) tail = consider(argb, seen, queue, tail, limit, idx - width, seedR, seedG, seedB);
            if (y < height - 1) tail = consider(argb, seen, queue, tail, limit, idx + width, seedR, seedG, seedB);

            if (tail >= limit) break;
        }

        return region;
    }

    private int consider(int[] argb, boolean[] seen, int[] queue, int tail, int limit, int idx,
                         int seedR, int seedG, int seedB) {
        if (seen[idx] || tail >= limit) return tail;
        int c = argb[idx];
        int diff = Math.abs(((c >> 16) & 0xFF) - seedR)
                + Math.abs(((c >> 8) & 0xFF) - seedG)
                + Math.abs((c & 0xFF) - seedB);
        if (diff > GROW_TOLERANCE) return tail;
        seen[idx] = true;
        queue[tail] = idx;
        return tail + 1;
    }

    /**
     * Bounded local search for the locked signature.
     *
     * Coarse 2px scan over the allowed shift, then a 1px refinement around the
     * winner: the same offset an exhaustive search finds, at about a quarter of
     * the comparisons.
     */
    private Match search(int[] argb, int width, int height) {
        int w = Math.max(1, Math.round(boxW));
        int h = Math.max(1, Math.round(boxH));

        // Mirrors TargetTracker#searchRadius: scaled to the target's own size but
        // floored by a fraction of the frame, so a small object on a slow capture
        // interval is not lost to ordinary motion. The floor is far smaller than
        // the frame, which is what keeps a distant look-alike out of reach.
        int radius = Math.max(3, Math.round(Math.max(Math.max(w, h) * maxShift,
                Math.min(width, height) * FRAME_RADIUS_FRACTION)));

        int originX = Math.round(boxX);
        int originY = Math.round(boxY);

        Match best = new Match(0f, originX, originY, w, h);

        for (int dy = -radius; dy <= radius; dy += 2) {
            for (int dx = -radius; dx <= radius; dx += 2) {
                int x = originX + dx;
                int y = originY + dy;
                if (x < 0 || y < 0 || x + w > width || y + h > height) continue;
                float score = scoreRegion(argb, width, x, y, w, h);
                if (score > best.score) best.set(score, x, y, w, h);
            }
        }

        if (best.score > 0f) {
            int rx = best.x;
            int ry = best.y;
            Match refined = new Match(best.score, best.x, best.y, w, h);
            for (int dy = -2; dy <= 2; dy++) {
                for (int dx = -2; dx <= 2; dx++) {
                    int x = rx + dx;
                    int y = ry + dy;
                    if (x < 0 || y < 0 || x + w > width || y + h > height) continue;
                    float score = scoreRegion(argb, width, x, y, w, h);
                    if (score > refined.score) refined.set(score, x, y, w, h);
                }
            }
            best = refined;
        }

        return best;
    }

    /**
     * Mean per-cell largest-channel difference between a region and the template,
     * as a similarity in [0, 1].
     *
     * This mirrors {@code TargetTracker#scoreRegion} in
     * {@code src/services/vision/tracker.js}. It deliberately does not sum the
     * three channels into the divisor: that metric lets a uniformly wrong region
     * score about 0.5, which reads as a match under any sane threshold, so a
     * target that had left the frame would be "tracked" onto empty background
     * instead of being reported lost. Using the largest channel per cell puts a
     * fully mismatched region near 0.45 and a correct track above 0.9, leaving a
     * wide gap for the threshold to sit in.
     */
    private float scoreRegion(int[] argb, int width, int x0, int y0, int w, int h) {
        long sum = 0;
        for (int gy = 0; gy < SIG; gy++) {
            int sy = y0 + Math.min(h - 1, (int) Math.floor(((gy + 0.5) * h) / SIG));
            for (int gx = 0; gx < SIG; gx++) {
                int sx = x0 + Math.min(w - 1, (int) Math.floor(((gx + 0.5) * w) / SIG));
                int c = argb[sy * width + sx];
                int o = (gy * SIG + gx) * 3;
                int dr = Math.abs(((c >> 16) & 0xFF) - signature[o]);
                int dg = Math.abs(((c >> 8) & 0xFF) - signature[o + 1]);
                int db = Math.abs((c & 0xFF) - signature[o + 2]);
                sum += Math.max(dr, Math.max(dg, db));
            }
        }
        return 1f - (sum / (float) (SIG * SIG * 255));
    }

    private int[] extractSignature(int[] argb, int width, int height, float bx, float by,
                                   float bw, float bh) {
        int x0 = Math.max(0, (int) Math.floor(bx));
        int y0 = Math.max(0, (int) Math.floor(by));
        int w = Math.max(1, Math.min(width - x0, Math.round(bw)));
        int h = Math.max(1, Math.min(height - y0, Math.round(bh)));

        int[] out = new int[SIG * SIG * 3];
        for (int gy = 0; gy < SIG; gy++) {
            int sy = y0 + Math.min(h - 1, (int) Math.floor(((gy + 0.5) * h) / SIG));
            for (int gx = 0; gx < SIG; gx++) {
                int sx = x0 + Math.min(w - 1, (int) Math.floor(((gx + 0.5) * w) / SIG));
                int c = argb[sy * width + sx];
                int o = (gy * SIG + gx) * 3;
                out[o] = (c >> 16) & 0xFF;
                out[o + 1] = (c >> 8) & 0xFF;
                out[o + 2] = c & 0xFF;
            }
        }
        return out;
    }

    private static float clamp(float v, float lo, float hi) {
        if (hi < lo) return lo;
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** Running bounds of a grown region. */
    private static class Region {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int count = 0;
        boolean filledFrame = false;

        void accept(int x, int y, int width, int height) {
            if (x < minX) minX = x;
            if (x > maxX) maxX = x;
            if (y < minY) minY = y;
            if (y > maxY) maxY = y;
            count++;
            if (minX <= 0 && minY <= 0 && maxX >= width - 1 && maxY >= height - 1) {
                filledFrame = true;
            }
        }
    }

    /** A candidate match position and its score. */
    private static class Match {
        float score;
        int x;
        int y;
        int w;
        int h;

        Match(float score, int x, int y, int w, int h) {
            set(score, x, y, w, h);
        }

        void set(float score, int x, int y, int w, int h) {
            this.score = score;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }
    }
}