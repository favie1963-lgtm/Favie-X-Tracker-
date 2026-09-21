package com.favie.xtracker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Detects the three cups of a Thimbles game and keeps a stable letter on each one
 * as they shuffle.
 *
 * <h2>Why this exists</h2>
 *
 * {@link NativeTargetTracker} follows an object by matching the colour signature
 * of the patch the user tapped. That works for a distinctive object, but it cannot
 * work for Thimbles: the three cups are visually identical, so when the cup the
 * user picked swaps with another, the best-matching patch on the next frame is
 * whichever identical cup happens to score highest — often the wrong one. There is
 * no appearance information to tell the cups apart, so no amount of tuning the
 * matcher can follow the right one.
 *
 * The cups are only distinguishable by <em>where they were a moment ago</em>. This
 * tracker therefore detects the cups on every frame and carries each one's
 * identity forward by nearest-neighbour motion between frames, which is the
 * standard way to follow identical objects through a shuffle. Identity, not
 * appearance, is what the letters and the marker are bound to.
 *
 * <h2>What the user sees</h2>
 *
 * At first detection the cups are labelled left to right A, B, C. Those letters
 * travel with the cups, so the left-to-right reading changes as they shuffle —
 * "B A C" means the cup originally in the middle has moved to the left. The marker
 * stays on the cup the user picked, because it is bound to that cup's identity.
 *
 * <h2>Detection</h2>
 *
 * The cups are found as connected regions that differ from the table, which is
 * estimated from the frame border. That is deliberately independent of any colour
 * the game might use. When the scene is too busy for that estimate to be
 * meaningful the components fragment and few or no cups are reported, in which
 * case the caller falls back to appearance tracking rather than showing a marker
 * on nonsense.
 *
 * All coordinates are analysis-frame pixels, the same space
 * {@link TargetMarkerView} scales to the screen.
 */
public class CupTracker {

    /** Longest side of the working grid; the frame is sampled to about this. */
    private static final int WORK_LONG_SIDE = 320;

    /** Per-channel sum tolerance to the table colour, out of 765. */
    private static final int FG_TOLERANCE = 120;

    /** Components smaller or larger than these fractions of the frame are noise. */
    private static final double MIN_AREA_FRACTION = 0.0015;
    private static final double MAX_AREA_FRACTION = 0.30;

    /** A cup is roughly upright: reject very thin or very wide components. */
    private static final float MIN_ASPECT = 0.30f;
    private static final float MAX_ASPECT = 3.2f;

    /** Fill ratio of the bounding box, to reject skeletal shapes. */
    private static final float MIN_SOLIDITY = 0.30f;

    /** How many cups to report. Thimbles is a three-cup game. */
    private static final int MAX_CUPS = 3;

    /** Frames an identity survives without being seen, so a brief miss is not a loss. */
    private static final int IDENTITY_GRACE = 8;

    /**
     * Gate for matching a detection to an identity, as a fraction of the shorter
     * frame edge. Generous, because a shuffle moves cups far in one capture
     * interval; nearest-neighbour still wins because a cup always lands closer to
     * its own previous position than to either of the other two.
     */
    private static final float MATCH_GATE_FRACTION = 0.6f;

    /**
     * Width, relative to an identity's own cup, above which a detection is treated
     * as two cups merged rather than a single cup.
     */
    private static final float MERGE_WIDTH_RATIO = 1.45f;

    private static final String[] LETTERS = {"A", "B", "C"};

    /** Geometry of one cup in analysis-frame pixels. */
    public static class Cup {
        public final float x;
        public final float y;
        public final float w;
        public final float h;

        public Cup(float x, float y, float w, float h) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }

        public float centerX() {
            return x + w / 2f;
        }

        public float centerY() {
            return y + h / 2f;
        }

        public boolean contains(float px, float py) {
            return px >= x && px <= x + w && py >= y && py <= y + h;
        }
    }

    /** A cup plus the letter that stays with it across frames. */
    public static class TrackedCup {
        public final String label;
        public final Cup cup;

        TrackedCup(String label, Cup cup) {
            this.label = label;
            this.cup = cup;
        }
    }

    private static class Identity {
        final String label;
        Cup cup;
        int missedTicks;
        /**
         * The cup's own size, captured once and never widened.
         *
         * The merge test compares a detection against this rather than against
         * {@link #cup}'s current width. Using the current width was a runaway: a
         * slightly-too-wide blob passed the ratio, the identity adopted its width,
         * which raised the threshold, which let it adopt an even wider blob — the
         * box grew by a cup's width every tick until one identity had swallowed all
         * three cups and sat in the middle of the shuffle. A cup does not change
         * size during a game, so a stable reference is both correct and what stops
         * the ladder.
         */
        final float baseW;
        final float baseH;
        /** Centre displacement per capture tick, for prediction through a merge. */
        float vx;
        float vy;

        Identity(String label, Cup cup) {
            this.label = label;
            this.cup = cup;
            this.baseW = cup.w;
            this.baseH = cup.h;
        }

        float predictedCenterX() {
            return cup.centerX() + vx * missedTicks;
        }

        float predictedCenterY() {
            return cup.centerY() + vy * missedTicks;
        }

        /** Whether a detection is too wide to be this cup, i.e. a merge of cups. */
        boolean isMerge(Cup detection) {
            return detection.w > baseW * MERGE_WIDTH_RATIO;
        }
    }

    private final Map<String, Identity> identities = new HashMap<>();

    /** Label of the cup the user selected, or null. */
    private String lockedLabel;

    /** Letters are handed out left-to-right the first time the cups are seen. */
    private boolean lettersAssigned;

    /**
     * Detect the cups and carry identities forward.
     *
     * @return the live cups, ordered left to right by current position
     */
    public List<TrackedCup> update(int[] argb, int width, int height) {
        List<Cup> cups = detectCups(argb, width, height);

        if (!lettersAssigned && cups.size() >= 2) {
            assignInitialLetters(cups);
        }

        matchToIdentities(cups, width, height);
        dropExpiredIdentities();

        List<TrackedCup> live = new ArrayList<>();
        for (Identity id : identities.values()) {
            live.add(new TrackedCup(id.label, id.cup));
        }
        Collections.sort(live, (a, b) -> Float.compare(a.cup.centerX(), b.cup.centerX()));
        return live;
    }

    /** Forgets everything; used when a session ends. */
    public void reset() {
        identities.clear();
        lockedLabel = null;
        lettersAssigned = false;
    }

    /**
     * Bind the marker to the cup under a point.
     *
     * @return the letter of the cup that was locked, or null when the point is not
     *         on any tracked cup (the caller then falls back to appearance tracking)
     */
    public String lockAt(float px, float py) {
        // Preferred: the cup the point actually falls in. When the point misses
        // every box — a tap on the table beside a cup, or slightly off after
        // scaling — the nearest cup within half its own width is used, because
        // asking the user to hit a 2px edge is not reasonable.
        for (Identity id : identities.values()) {
            if (id.cup.contains(px, py)) {
                lockedLabel = id.label;
                return lockedLabel;
            }
        }

        String nearest = null;
        float nearestDist = Float.MAX_VALUE;
        for (Identity id : identities.values()) {
            float dx = px - id.cup.centerX();
            float dy = py - id.cup.centerY();
            float dist = (float) Math.hypot(dx, dy);
            float reach = Math.max(id.cup.w, id.cup.h) * 0.75f;
            if (dist <= reach && dist < nearestDist) {
                nearestDist = dist;
                nearest = id.label;
            }
        }
        lockedLabel = nearest;
        return nearest;
    }

    /** The cup the marker should sit on, or null when it is gone. */
    public TrackedCup getLockedCup() {
        if (lockedLabel == null) return null;
        Identity id = identities.get(lockedLabel);
        if (id == null) return null;
        return new TrackedCup(id.label, id.cup);
    }

    public String getLockedLabel() {
        return lockedLabel;
    }

    /** Drops the marker binding but keeps the detected cups and their letters. */
    public void clearLock() {
        lockedLabel = null;
    }

    public boolean hasLock() {
        return getLockedCup() != null;
    }

    /** Left-to-right letters, e.g. {@code "B A C"}; empty when no cups are tracked. */
    public String orderText() {
        List<Identity> byX = new ArrayList<>(identities.values());
        Collections.sort(byX, (a, b) -> Float.compare(a.cup.centerX(), b.cup.centerX()));
        StringBuilder sb = new StringBuilder();
        for (Identity id : byX) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(id.label);
        }
        return sb.toString();
    }

    /**
     * The left-to-right letters together with each cup's horizontal position.
     *
     * Returned as one value because the letters strip draws {@code labels[i]} at
     * {@code positions[i]}; producing them from separate calls would let the strip
     * pair a letter with another cup's position on a frame where the order changed
     * between the two calls. Both are read from the same sorted list here, so they
     * always describe the same frame.
     */
    public static class ShuffleReadout {
        public final String order;
        /** Centre X of each cup, in reading order, as a fraction of frame width. */
        public final float[] positions;

        ShuffleReadout(String order, float[] positions) {
            this.order = order;
            this.positions = positions;
        }
    }

    public ShuffleReadout readout(int frameWidth) {
        List<Identity> byX = new ArrayList<>(identities.values());
        Collections.sort(byX, (a, b) -> Float.compare(a.cup.centerX(), b.cup.centerX()));

        StringBuilder sb = new StringBuilder();
        float[] positions = new float[byX.size()];
        for (int i = 0; i < byX.size(); i++) {
            Identity id = byX.get(i);
            if (sb.length() > 0) sb.append(' ');
            sb.append(id.label);
            float t = frameWidth <= 0 ? 0f : id.cup.centerX() / frameWidth;
            positions[i] = t < 0f ? 0f : (t > 1f ? 1f : t);
        }
        return new ShuffleReadout(sb.toString(), positions);
    }

    // --- Internals ------------------------------------------------------------

    private void assignInitialLetters(List<Cup> cups) {
        List<Cup> byX = new ArrayList<>(cups);
        Collections.sort(byX, (a, b) -> Float.compare(a.centerX(), b.centerX()));

        int n = Math.min(byX.size(), LETTERS.length);
        for (int i = 0; i < n; i++) {
            identities.put(LETTERS[i], new Identity(LETTERS[i], byX.get(i)));
        }
        lettersAssigned = true;
    }

    private void matchToIdentities(List<Cup> cups, int width, int height) {
        List<Identity> idList = new ArrayList<>(identities.values());

        // Candidates are ranked by distance to where each identity is *predicted*
        // to be, not where it was last seen. During a shuffle two cups overlap and
        // merge into one detected blob, so their identities miss a tick or two;
        // carrying their velocity across those ticks is what stops the letters
        // snapping onto whichever blob survived the merge.
        //
        // A detection much wider than the identity's own cup is a merge, not a cup,
        // and is never matched directly: pairing an identity with the centre of a
        // two-cup blob would teleport it into the middle of the shuffle. Those are
        // handled by carryMergedBlobs instead.
        List<float[]> pairs = new ArrayList<>();
        for (int i = 0; i < idList.size(); i++) {
            Identity id = idList.get(i);
            float px = id.predictedCenterX();
            float py = id.predictedCenterY();
            for (int j = 0; j < cups.size(); j++) {
                Cup cup = cups.get(j);
                if (id.isMerge(cup)) continue;
                float dist = (float) Math.hypot(px - cup.centerX(), py - cup.centerY());
                if (dist <= matchGate(id, width, height)) {
                    pairs.add(new float[]{dist, i, j});
                }
            }
        }
        Collections.sort(pairs, (a, b) -> Float.compare(a[0], b[0]));

        boolean[] cupUsed = new boolean[cups.size()];
        boolean[] idUsed = new boolean[idList.size()];
        for (float[] p : pairs) {
            int i = (int) p[1];
            int j = (int) p[2];
            if (idUsed[i] || cupUsed[j]) continue;
            idUsed[i] = true;
            cupUsed[j] = true;

            Identity id = idList.get(i);
            Cup next = cups.get(j);
            // Velocity is smoothed rather than replaced so one noisy box does not
            // send the prediction far off on the next merge.
            float dx = next.centerX() - id.cup.centerX();
            float dy = next.centerY() - id.cup.centerY();
            id.vx = id.vx * 0.4f + dx * 0.6f;
            id.vy = id.vy * 0.4f + dy * 0.6f;
            id.cup = next;
            id.missedTicks = 0;
        }

        for (int i = 0; i < idList.size(); i++) {
            if (!idUsed[i]) idList.get(i).missedTicks++;
        }

        carryMergedBlobs(cups, idList, idUsed);

        // An unmatched detection is a cup that has just entered the scene. It only
        // gets a letter while the game has room for it; anything else is a
        // mis-detected fragment and is dropped rather than sprouting a phantom
        // fourth cup in the readout.
        for (int j = 0; j < cups.size(); j++) {
            if (cupUsed[j]) continue;
            if (identities.size() >= MAX_CUPS) continue;
            String free = freeLetter();
            if (free == null) continue;
            identities.put(free, new Identity(free, cups.get(j)));
        }
    }

    /**
     * Keep unmatched identities moving while their cups are hidden inside a merged
     * blob.
     *
     * When two cups overlap the component walk sees a single region about two cups
     * wide, so both identities come out of the greedy pass unmatched. Dropping them
     * there is what froze the letters: on the far side of the merge the nearest
     * identity to each cup is the one that never moved. Instead the blob is treated
     * as a container: every unmatched identity whose predicted centre falls inside
     * it is advanced to that predicted centre and keeps its velocity. The identity
     * therefore travels across the blob with its cup and is already on the correct
     * side when the cups separate again.
     */
    private void carryMergedBlobs(List<Cup> cups, List<Identity> idList, boolean[] idUsed) {
        for (Cup blob : cups) {
            boolean isBlob = false;
            for (Identity id : idList) {
                if (id.isMerge(blob)) {
                    isBlob = true;
                    break;
                }
            }
            if (!isBlob) continue;

            for (int i = 0; i < idList.size(); i++) {
                if (idUsed[i]) continue;
                Identity id = idList.get(i);
                if (!id.isMerge(blob)) continue;

                float predictedX = id.predictedCenterX();
                // The blob spans the cups it contains; an identity predicted inside
                // it (with a cup's half-width of slack) is one of them.
                float slack = id.baseW * 0.75f;
                if (predictedX < blob.x - slack || predictedX > blob.x + blob.w + slack) {
                    continue;
                }

                float nx = clamp(predictedX, blob.x + id.baseW / 2f,
                        blob.x + blob.w - id.baseW / 2f);
                float ny = blob.centerY();
                float dx = nx - id.cup.centerX();
                float dy = ny - id.cup.centerY();
                id.vx = id.vx * 0.4f + dx * 0.6f;
                id.vy = id.vy * 0.4f + dy * 0.6f;
                // The identity keeps its own cup size: it is one cup travelling
                // inside the blob, and reporting the blob's width would draw a
                // marker spanning cups it does not own.
                id.cup = new Cup(nx - id.baseW / 2f, blob.y, id.baseW, id.baseH);
                id.missedTicks = 0;
                idUsed[i] = true;
            }
        }
    }

    private static float clamp(float v, float lo, float hi) {
        if (hi < lo) return lo;
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /**
     * Distance within which a detection may belong to an identity.
     *
     * Scaled by the expected motion since the identity was last seen: an identity
     * that has been merged for several ticks is allowed to have travelled further,
     * so its cup can be re-acquired on the far side of the merge instead of being
     * abandoned as lost.
     */
    private float matchGate(Identity id, int width, int height) {
        float base = Math.max(60f, Math.min(width, height) * MATCH_GATE_FRACTION);
        float travelled = (float) Math.hypot(id.vx, id.vy) * id.missedTicks;
        return base + travelled;
    }

    private String freeLetter() {
        for (String l : LETTERS) {
            if (!identities.containsKey(l)) return l;
        }
        return null;
    }

    private void dropExpiredIdentities() {
        List<String> gone = new ArrayList<>();
        for (Identity id : identities.values()) {
            if (id.missedTicks > IDENTITY_GRACE) gone.add(id.label);
        }
        for (String label : gone) {
            identities.remove(label);
            if (label.equals(lockedLabel)) lockedLabel = null;
        }
    }

    /**
     * Connected-component detection of the cups.
     *
     * The frame is sampled down to a small working grid so the component walk costs
     * a few thousand visits rather than one per pixel, then each surviving
     * component's bounds are mapped back to frame pixels.
     */
    List<Cup> detectCups(int[] argb, int width, int height) {
        List<Cup> out = new ArrayList<>();
        if (argb == null || width <= 0 || height <= 0) return out;

        int step = Math.max(1, Math.max(width, height) / WORK_LONG_SIDE);
        int gw = width / step;
        int gh = height / step;
        if (gw < 8 || gh < 8) return out;

        int table = estimateTableColour(argb, width, height);
        int tr = (table >> 16) & 0xFF;
        int tg = (table >> 8) & 0xFF;
        int tb = table & 0xFF;

        boolean[] fg = new boolean[gw * gh];
        for (int gy = 0; gy < gh; gy++) {
            int sy = gy * step;
            for (int gx = 0; gx < gw; gx++) {
                int c = argb[sy * width + gx * step];
                int diff = Math.abs(((c >> 16) & 0xFF) - tr)
                        + Math.abs(((c >> 8) & 0xFF) - tg)
                        + Math.abs((c & 0xFF) - tb);
                fg[gy * gw + gx] = diff > FG_TOLERANCE;
            }
        }

        boolean[] seen = new boolean[gw * gh];
        int[] stack = new int[gw * gh];
        int minArea = Math.max(1, (int) (gw * (long) gh * MIN_AREA_FRACTION));
        int maxArea = (int) (gw * (long) gh * MAX_AREA_FRACTION);

        for (int start = 0; start < fg.length; start++) {
            if (!fg[start] || seen[start]) continue;

            int top = 0;
            stack[top++] = start;
            seen[start] = true;

            int count = 0;
            int minX = Integer.MAX_VALUE;
            int minY = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE;
            int maxY = Integer.MIN_VALUE;

            while (top > 0) {
                int idx = stack[--top];
                int y = idx / gw;
                int x = idx - y * gw;
                count++;
                if (x < minX) minX = x;
                if (x > maxX) maxX = x;
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;

                if (x > 0 && fg[idx - 1] && !seen[idx - 1]) {
                    seen[idx - 1] = true;
                    stack[top++] = idx - 1;
                }
                if (x < gw - 1 && fg[idx + 1] && !seen[idx + 1]) {
                    seen[idx + 1] = true;
                    stack[top++] = idx + 1;
                }
                if (y > 0 && fg[idx - gw] && !seen[idx - gw]) {
                    seen[idx - gw] = true;
                    stack[top++] = idx - gw;
                }
                if (y < gh - 1 && fg[idx + gw] && !seen[idx + gw]) {
                    seen[idx + gw] = true;
                    stack[top++] = idx + gw;
                }
            }

            if (count < minArea || count > maxArea) continue;

            int bw = maxX - minX + 1;
            int bh = maxY - minY + 1;
            float aspect = bw / (float) bh;
            if (aspect < MIN_ASPECT || aspect > MAX_ASPECT) continue;

            float solidity = count / (float) (bw * bh);
            if (solidity < MIN_SOLIDITY) continue;

            out.add(new Cup(minX * (float) step, minY * (float) step,
                    bw * (float) step, bh * (float) step));
        }

        Collections.sort(out, (a, b) -> Float.compare(b.w * b.h, a.w * a.h));
        if (out.size() > MAX_CUPS) out = new ArrayList<>(out.subList(0, MAX_CUPS));
        return out;
    }

    /**
     * Table colour, estimated from the frame border.
     *
     * The border is the one part of a Thimbles frame the moving cups rarely occupy,
     * so its median colour is a dependable stand-in for the surface they sit on. A
     * per-channel median is used rather than a mean so a cup that does clip an edge
     * cannot drag the estimate.
     */
    private int estimateTableColour(int[] argb, int width, int height) {
        int[] rs = new int[256];
        int[] gs = new int[256];
        int[] bs = new int[256];
        int n = 0;

        int bandY = Math.max(1, height / 20);
        int bandX = Math.max(1, width / 20);

        for (int x = 0; x < width; x += 2) {
            for (int k = 0; k < bandY; k++) {
                n = add(argb[x + k * width], rs, gs, bs, n);
                n = add(argb[x + (height - 1 - k) * width], rs, gs, bs, n);
            }
        }
        for (int y = 0; y < height; y += 2) {
            for (int k = 0; k < bandX; k++) {
                n = add(argb[y * width + k], rs, gs, bs, n);
                n = add(argb[y * width + (width - 1 - k)], rs, gs, bs, n);
            }
        }
        if (n == 0) return 0;

        return 0xFF000000 | (median(rs, n) << 16) | (median(gs, n) << 8) | median(bs, n);
    }

    private int add(int c, int[] rs, int[] gs, int[] bs, int n) {
        if (n >= rs.length) return n;
        rs[n] = (c >> 16) & 0xFF;
        gs[n] = (c >> 8) & 0xFF;
        bs[n] = c & 0xFF;
        return n + 1;
    }

    private int median(int[] values, int n) {
        int[] copy = java.util.Arrays.copyOf(values, n);
        java.util.Arrays.sort(copy);
        return copy[n / 2];
    }
}
