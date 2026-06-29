package net.runelite.client.plugins.microbot.util.mouse.mousev2.prediction;

import java.awt.Point;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Supplier;

/**
 * Estimates where an on-screen target will be a short time in the future, so the cursor can aim at
 * the predicted position rather than where the target currently is. This is the core of MouseV2's
 * Enhanced Clicking: it accounts for the steady camera/player-driven motion of a target by
 * extrapolating from recent samples, while clamping the lead so the implied cursor speed never
 * becomes unrealistic.
 */
public final class ScreenPredictor {
    public static final long DEFAULT_SAMPLE_INTERVAL_MS = 50L;

    private static final int MAX_SAMPLES = 6;

    private final Supplier<Point> source;
    private final double maxLeadPx;
    private final long sampleIntervalMs;
    private final double maxVelocityPxPerMs;
    private final Deque<Sample> samples = new ArrayDeque<>();
    private long lastSampleAttemptMs = Long.MIN_VALUE;
    private boolean lastSourceMissing;
    private int consecutiveMisses;

    public ScreenPredictor(Supplier<Point> source, double maxLeadPx) {
        this(source, maxLeadPx, DEFAULT_SAMPLE_INTERVAL_MS, Double.POSITIVE_INFINITY);
    }

    public ScreenPredictor(Supplier<Point> source, double maxLeadPx,
                           long sampleIntervalMs, double maxVelocityPxPerMs) {
        this.source = source;
        this.maxLeadPx = maxLeadPx;
        this.sampleIntervalMs = Math.max(1L, sampleIntervalMs);
        this.maxVelocityPxPerMs = maxVelocityPxPerMs;
    }

    /**
     * Pulls a fresh sample from the source at the fixed sampling cadence, returning the cached
     * sample between cadence boundaries. No-op if the source returns null.
     */
    public Point sampleNow() {
        return sampleAt(System.currentTimeMillis());
    }

    /** Same as {@link #sampleNow()}, with an explicit clock for deterministic tests. */
    public Point sampleAt(long nowMs) {
        if (!isSampleDue(nowMs)) {
            return lastSourceMissing ? null : latest();
        }
        lastSampleAttemptMs = nowMs;
        Point p = source.get();
        if (p != null) {
            lastSourceMissing = false;
            consecutiveMisses = 0;
            addSample(nowMs, p);
        } else {
            lastSourceMissing = true;
            consecutiveMisses++;
        }
        return p;
    }

    /** Adds a sample explicitly (used by tests and for deterministic time bases). */
    public void addSample(long tMs, Point p) {
        if (p == null) {
            return;
        }
        samples.addLast(new Sample(tMs, p.x, p.y));
        while (samples.size() > MAX_SAMPLES) {
            samples.removeFirst();
        }
    }

    /** The most recently sampled point, or {@code null} if nothing has been sampled. */
    public Point latest() {
        Sample s = samples.peekLast();
        return s == null ? null : new Point((int) Math.round(s.x), (int) Math.round(s.y));
    }

    /** Number of consecutive cadence-boundary samples where the source returned null. */
    public int consecutiveMisses() {
        return consecutiveMisses;
    }

    /**
     * Defensive copy of the current sample points (oldest first), for debug visualisation. Must be
     * called on the predictor's owning (motion) thread — the deque is not thread-safe.
     */
    public java.util.List<Point> sampleSnapshot() {
        java.util.List<Point> out = new java.util.ArrayList<>(samples.size());
        for (Sample s : samples) {
            out.add(new Point((int) Math.round(s.x), (int) Math.round(s.y)));
        }
        return out;
    }

    /**
     * Predicts the target position {@code leadMs} into the future by linear extrapolation of the
     * sampled velocity, with the lead vector clamped to {@link #maxLeadPx}. Falls back to the latest
     * sample when there is insufficient history.
     */
    public Point predict(long leadMs) {
        if (samples.isEmpty()) {
            return null;
        }
        Sample last = samples.peekLast();
        if (samples.size() < 2) {
            return new Point((int) Math.round(last.x), (int) Math.round(last.y));
        }
        Sample first = samples.peekFirst();
        double dt = last.t - first.t;
        if (dt <= 0) {
            return new Point((int) Math.round(last.x), (int) Math.round(last.y));
        }
        double vx = (last.x - first.x) / dt;
        double vy = (last.y - first.y) / dt;
        double speed = Math.hypot(vx, vy);
        if (maxVelocityPxPerMs > 0.0 && speed > maxVelocityPxPerMs) {
            double s = maxVelocityPxPerMs / speed;
            vx *= s;
            vy *= s;
        }

        double stabilityScale = motionStabilityScale();
        double leadX = vx * leadMs * stabilityScale;
        double leadY = vy * leadMs * stabilityScale;
        double mag = Math.hypot(leadX, leadY);
        if (mag > maxLeadPx && mag > 1e-9) {
            double s = maxLeadPx / mag;
            leadX *= s;
            leadY *= s;
        }
        return new Point((int) Math.round(last.x + leadX), (int) Math.round(last.y + leadY));
    }

    private boolean isSampleDue(long nowMs) {
        return lastSampleAttemptMs == Long.MIN_VALUE
                || nowMs < lastSampleAttemptMs
                || nowMs - lastSampleAttemptMs >= sampleIntervalMs;
    }

    private double motionStabilityScale() {
        if (samples.size() < 3) {
            return 1.0;
        }

        Sample[] window = samples.toArray(new Sample[0]);
        Sample first = window[0];
        Sample prev = window[window.length - 2];
        Sample last = window[window.length - 1];

        double previousDt = prev.t - first.t;
        double currentDt = last.t - prev.t;
        if (previousDt <= 0 || currentDt <= 0) {
            return 1.0;
        }

        double previousVx = (prev.x - first.x) / previousDt;
        double previousVy = (prev.y - first.y) / previousDt;
        double currentVx = (last.x - prev.x) / currentDt;
        double currentVy = (last.y - prev.y) / currentDt;
        double previousSpeed = Math.hypot(previousVx, previousVy);
        double currentSpeed = Math.hypot(currentVx, currentVy);

        double scale = 1.0;
        if (previousSpeed > 1e-9 && currentSpeed < previousSpeed) {
            scale *= clamp(currentSpeed / previousSpeed, 0.0, 1.0);
        }
        if (previousSpeed > 1e-9 && currentSpeed > 1e-9) {
            double dot = (previousVx * currentVx + previousVy * currentVy) / (previousSpeed * currentSpeed);
            if (dot < 0.95) {
                scale *= clamp((dot + 1.0) * 0.5, 0.0, 1.0);
            }
        }
        return scale;
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static final class Sample {
        final long t;
        final double x;
        final double y;

        Sample(long t, double x, double y) {
            this.t = t;
            this.x = x;
            this.y = y;
        }
    }
}
