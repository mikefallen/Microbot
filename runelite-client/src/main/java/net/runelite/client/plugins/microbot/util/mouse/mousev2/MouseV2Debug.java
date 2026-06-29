package net.runelite.client.plugins.microbot.util.mouse.mousev2;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Shape;
import java.util.Collections;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lightweight, optional debug state for the MouseV2 engine. The engine publishes its internal
 * decisions here while {@link #isEnabled()} is true. MouseV2 paint is always enabled so the client
 * can show the emitted trail and live target hull without depending on DevTools toggles.
 * <p>
 * Each field is an independently-published, already-copied value: the engine writes from its motion
 * thread, the overlay reads from the client thread, and a single {@code volatile} reference per field
 * gives a safe (if slightly inconsistent across a frame) snapshot — which is all a debug paint needs.
 * No locks, and the overlay never touches live game/engine state.
 */
public final class MouseV2Debug {
    private static final long TRAIL_LIFETIME_MS = 900L;
    private static final int TRAIL_MAX_POINTS = 160;
    private static volatile boolean enabled = true;

    // --- planned movement (the intended spline, distinct from the emitted trail) ---
    private static final ConcurrentLinkedDeque<TrailPoint> emittedTrail = new ConcurrentLinkedDeque<>();
    private static volatile List<Point> plannedPath = Collections.emptyList();
    private static volatile String engine = "-";
    private static volatile String curveStyle = "-";
    private static volatile long lastMoveMs;
    private static volatile double lastMoveDist;

    // --- predictive "Enhanced Clicking" ---
    private static volatile boolean predictiveActive;
    private static volatile String motionReason = "-";
    private static volatile Point rawTarget;
    private static volatile Point predictedTarget;
    private static volatile long leadMs;
    private static volatile int consecutiveMisses;
    private static volatile List<Point> sampleHistory = Collections.emptyList();

    // --- click finalisation ---
    private static volatile Rectangle clickbox;
    private static volatile Point clickPoint;
    private static volatile boolean clickedInsideNoRemove;
    private static volatile long clickAtMs;
    private static volatile Point clickAnchor;
    /** The current target entity (net.runelite.api.Actor or TileObject), for live hull paint. */
    private static volatile Object targetEntity;
    /** The exact shape the aim picker sampled from, for overlay paint. */
    private static volatile Shape targetShape;

    // --- profile / context ---
    private static volatile String profileText = "-";
    private static volatile String contextText = "-";

    // --- counters ---
    private static final AtomicLong moves = new AtomicLong();
    private static final AtomicLong predictiveMoves = new AtomicLong();
    private static final AtomicLong overshootLegs = new AtomicLong();
    private static final AtomicLong cancels = new AtomicLong();

    private MouseV2Debug() {
    }

    private static final class TrailPoint {
        private final Point point;
        private final long timeMs;

        private TrailPoint(Point point, long timeMs) {
            this.point = point;
            this.timeMs = timeMs;
        }
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        if (!value && enabled) {
            clearTransient();
        }
        enabled = value;
    }

    /** Clears per-move markers so stale paint disappears when debugging is turned off. */
    private static void clearTransient() {
        emittedTrail.clear();
        plannedPath = Collections.emptyList();
        sampleHistory = Collections.emptyList();
        rawTarget = null;
        predictedTarget = null;
        clickbox = null;
        clickPoint = null;
        clickAtMs = 0L;
        clickAnchor = null;
        targetEntity = null;
        targetShape = null;
        predictiveActive = false;
    }

    /** Clears target paint state after a click pulse window ends. Thread-safe and idempotent. */
    public static void clearPostClick() {
        // Zeroing clickAtMs makes this fire once per click; otherwise the overlay would call it
        // every frame and wipe the markers the engine republishes during the next move.
        clickAtMs = 0L;
        clickAnchor = null;
        targetEntity = null;
        rawTarget = null;
        predictedTarget = null;
        clickbox = null;
        targetShape = null;
    }

    public static void setPlannedPath(List<Point> path, String style, long totalMs, double dist) {
        plannedPath = path == null ? Collections.emptyList() : path;
        curveStyle = style;
        lastMoveMs = totalMs;
        lastMoveDist = dist;
    }

    public static void addTrailPoint(Point point) {
        if (point == null || point.x <= 0 || point.y <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        emittedTrail.addLast(new TrailPoint(new Point(point), now));
        pruneTrail(now);
    }

    public static void setEngine(String value) {
        engine = value;
    }

    public static void setPredictive(boolean active, String reason) {
        predictiveActive = active;
        motionReason = reason;
    }

    public static void setPrediction(Point raw, Point predicted, long lead, int misses, List<Point> samples) {
        rawTarget = raw;
        predictedTarget = predicted;
        leadMs = lead;
        consecutiveMisses = misses;
        sampleHistory = samples == null ? Collections.emptyList() : samples;
    }

    public static void setClick(Rectangle box, Point point, boolean insideNoRemove) {
        clickbox = box;
        clickPoint = point;
        clickedInsideNoRemove = insideNoRemove;
        clickAtMs = System.currentTimeMillis();
        // anchor = current tracked target at click time (predicted if available, else raw)
        Point tracked = predictedTarget != null ? predictedTarget : rawTarget;
        clickAnchor = tracked != null ? new Point(tracked) : (point != null ? new Point(point) : null);
    }

    /**
     * Extended setClick that also records the click anchor explicitly (tracked target at click time).
     * The anchor is the point used for the orange pulse in the overlay.
     */
    public static void setClick(Rectangle box, Point point, boolean insideNoRemove, Point anchor) {
        clickbox = box;
        clickPoint = point;
        clickedInsideNoRemove = insideNoRemove;
        clickAtMs = System.currentTimeMillis();
        clickAnchor = anchor != null ? new Point(anchor) : (point != null ? new Point(point) : null);
    }

    /** Sets the current target entity (Actor or TileObject) whose live hull the overlay paints. */
    public static void setTargetEntity(Object entity) {
        targetEntity = entity;
    }

    public static Object getTargetEntity() {
        return targetEntity;
    }

    /** Sets the exact shape the aim picker sampled from, for overlay paint. */
    public static void setTargetShape(Shape shape) {
        targetShape = shape;
    }

    public static Shape getTargetShape() {
        return targetShape;
    }

    public static void setProfile(String text) {
        profileText = text;
    }

    public static void setContext(String text) {
        contextText = text;
    }

    public static void incMoves() {
        moves.incrementAndGet();
    }

    public static void incPredictiveMoves() {
        predictiveMoves.incrementAndGet();
    }

    public static void addOvershootLegs(int n) {
        if (n > 0) {
            overshootLegs.addAndGet(n);
        }
    }

    public static void incCancels() {
        cancels.incrementAndGet();
    }

    // --- read accessors for the overlay ---

    public static List<Point> getPlannedPath() {
        return plannedPath;
    }

    public static List<Point> getTrailPoints() {
        pruneTrail(System.currentTimeMillis());
        List<Point> out = new ArrayList<>(emittedTrail.size());
        for (TrailPoint point : emittedTrail) {
            out.add(new Point(point.point));
        }
        return out;
    }

    public static String getEngine() {
        return engine;
    }

    public static String getCurveStyle() {
        return curveStyle;
    }

    public static long getLastMoveMs() {
        return lastMoveMs;
    }

    public static double getLastMoveDist() {
        return lastMoveDist;
    }

    public static boolean isPredictiveActive() {
        return predictiveActive;
    }

    public static String getMotionReason() {
        return motionReason;
    }

    public static Point getRawTarget() {
        return rawTarget;
    }

    public static Point getPredictedTarget() {
        return predictedTarget;
    }

    public static long getLeadMs() {
        return leadMs;
    }

    public static int getConsecutiveMisses() {
        return consecutiveMisses;
    }

    public static List<Point> getSampleHistory() {
        return sampleHistory;
    }

    public static Rectangle getClickbox() {
        return clickbox;
    }

    public static Point getClickPoint() {
        return clickPoint;
    }

    public static boolean isClickedInsideNoRemove() {
        return clickedInsideNoRemove;
    }

    public static long getClickAtMs() {
        return clickAtMs;
    }

    public static Point getClickAnchor() {
        return clickAnchor;
    }

    public static String getProfileText() {
        return profileText;
    }

    public static String getContextText() {
        return contextText;
    }

    public static long getMoves() {
        return moves.get();
    }

    public static long getPredictiveMoves() {
        return predictiveMoves.get();
    }

    public static long getOvershootLegs() {
        return overshootLegs.get();
    }

    public static long getCancels() {
        return cancels.get();
    }

    private static void pruneTrail(long now) {
        long cutoff = now - TRAIL_LIFETIME_MS;
        while (true) {
            TrailPoint oldest = emittedTrail.peekFirst();
            if (oldest == null || oldest.timeMs >= cutoff) {
                break;
            }
            emittedTrail.pollFirst();
        }
        while (emittedTrail.size() > TRAIL_MAX_POINTS) {
            emittedTrail.pollFirst();
        }
    }
}
