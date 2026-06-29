package net.runelite.client.plugins.microbot.util.mouse.mousev2.prediction;

import java.awt.Point;
import java.awt.Shape;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Pure, headless-testable state machine for the upstream per-frame hull-exit re-pick logic
 * (TBoe.TBz BeforeRender callback).
 * <p>
 * Holds two points: {@code rawPick} (an in-hull candidate chosen by the aim picker) and
 * {@code tracked} (the point the mouse actually follows — usually a walk-lead projection of the
 * actor centre, or {@code rawPick} when the lead is null/zero). Between re-picks both points are
 * intentionally FIXED at their canvas coordinates; only when the hull moves far enough that either
 * point falls outside does a re-pick fire and a fresh lead get computed. This cadence lets the
 * follow loop (walkPathLive / smoothstep blending) handle the discrete jump exactly as the upstream
 * implementation does.
 * <p>
 * Threading: this object is not thread-safe; callers must not share it across threads without
 * external synchronisation. In practice, a single {@link LiveTarget} lambda captures it and is
 * polled exclusively by one {@code TargetSampler} daemon thread.
 */
public final class HullRepickState {

    /** Result of a single {@link #step} call. */
    public static final class StepResult {
        /** Point the mouse should follow (leaded projection, or rawPick, or fallback center). */
        public final Point tracked;
        /** In-hull pick chosen by the picker, or {@code null} if no hull pick was available. */
        public final Point rawPick;

        StepResult(Point tracked, Point rawPick) {
            this.tracked = tracked;
            this.rawPick = rawPick;
        }
    }

    // volatile: step() runs inside a client-thread hop while the settle view reads
    // getRawPick()/getTracked() from the motion thread.
    /** Currently cached in-hull aim point (null = no hull pick yet). */
    private volatile Point rawPick;
    /** Currently cached follow point (null = no pick yet). */
    private volatile Point tracked;
    /**
     * Whether the stored {@code tracked} / {@code rawPick} came from a real hull pick.
     * False means it was a fallback center; a null hull must not suppress the next real pick.
     */
    private volatile boolean fromRealPick;

    public HullRepickState() {
    }

    /**
     * Advances the state machine by one sampler tick.
     *
     * <ul>
     *   <li>If {@code hull != null} AND (no real pick is cached, or BOTH the tracked point AND the
     *       rawPick are null/outside the hull), a re-pick is triggered:
     *       <ul>
     *         <li>{@code pickFn} is called to obtain a new in-hull aim point {@code p}.</li>
     *         <li>If {@code p != null}: {@code leadFn.apply(p)} computes the leaded follow point
     *             (may return null → tracked := p). rawPick and tracked are cached, fromRealPick
     *             is set to true.</li>
     *         <li>If {@code p == null} (shape too thin): fall back to {@code fallbackFn} for this
     *             call without updating the cache, so the next call re-tries the pick.</li>
     *       </ul>
     *   <li>If {@code hull != null} and no re-pick is needed: return cached points unchanged (lead
     *       is NOT recomputed — the fixed-cadence property matching the upstream implementation).</li>
     *   <li>If {@code hull == null}: return the cached tracked if it came from a real pick;
     *       otherwise call {@code fallbackFn} without caching as a real pick.</li>
     * </ul>
     *
     * @param hull       live hull shape (null = temporarily off-screen)
     * @param pickFn     supplies an in-hull aim point (called at most once per step, only on repick)
     * @param leadFn     given the picked aim point, produces the leaded follow point; may return null
     *                   (called at most once per step, only on repick after a successful pick)
     * @param fallbackFn supplies the unleaded center projection used when no hull pick is possible
     * @return step result; {@code tracked} may be null only when all suppliers return null
     */
    public StepResult step(Shape hull,
                           Supplier<Point> pickFn,
                           Function<Point, Point> leadFn,
                           Supplier<Point> fallbackFn) {
        if (hull != null) {
            // The upstream implementation (TBoe.TBz) re-picks only when BOTH points are stale: the tracked (leaded)
            // point normally sits OUTSIDE the hull because it leads ahead of the target, so its
            // staleness alone must not trigger — otherwise the lead would be recomputed on every
            // poll, which is exactly the continuous-drift behavior this state machine eliminates.
            boolean trackedStale = tracked == null || !hull.contains(tracked);
            boolean rawStale = rawPick == null || !hull.contains(rawPick);
            boolean needsRepick = !fromRealPick || (trackedStale && rawStale);

            if (needsRepick) {
                Point p = pickFn.get();
                if (p != null) {
                    Point lead = leadFn.apply(p);
                    rawPick = p;
                    tracked = (lead != null) ? lead : p;
                    fromRealPick = true;
                    return new StepResult(tracked, rawPick);
                }
                // pick failed (hull too thin etc.) — fall through to fallback for this call
                // but do NOT overwrite cache, so the next call tries again
                Point fb = fallbackFn.get();
                return new StepResult(fb, null);
            }

            // No repick needed: return cached points untouched
            return new StepResult(tracked, rawPick);
        }

        // hull == null this frame
        if (fromRealPick && tracked != null) {
            // Keep the last real-pick cached point; do not downgrade to fallback
            return new StepResult(tracked, rawPick);
        }
        // No real pick cached — fallback, but don't store as a real pick
        Point fb = fallbackFn.get();
        return new StepResult(fb, null);
    }

    /** Resets cached state (useful for testing / actor change). */
    public void reset() {
        rawPick = null;
        tracked = null;
        fromRealPick = false;
    }

    // Accessors (package-visible + public for testing)
    public Point getRawPick() { return rawPick; }
    public Point getTracked() { return tracked; }
    public boolean isFromRealPick() { return fromRealPick; }
}
