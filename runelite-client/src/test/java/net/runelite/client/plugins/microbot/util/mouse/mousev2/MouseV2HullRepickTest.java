package net.runelite.client.plugins.microbot.util.mouse.mousev2;

import net.runelite.client.plugins.microbot.util.mouse.mousev2.prediction.HullRepickState;
import org.junit.Test;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Shape;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.junit.Assert.*;

/**
 * Pure-logic unit tests for {@link HullRepickState} (no running client required).
 * Verifies the upstream TBoe.TBz per-frame hull-exit re-pick semantics.
 */
public class MouseV2HullRepickTest {

    // ------------------------------------------------------------------ helpers

    /** A 100x100 square hull at (0,0). */
    private static Shape hull100() {
        return new Rectangle(0, 0, 100, 100);
    }

    /** A 100x100 square hull at (200,200) — does NOT contain points in [0,100]. */
    private static Shape hullFar() {
        return new Rectangle(200, 200, 100, 100);
    }

    /** A pickFn that always returns the given point. */
    private static Supplier<Point> constPick(Point p) {
        return () -> p;
    }

    /** A leadFn that returns a fixed leaded point regardless of pick. */
    private static Function<Point, Point> constLead(Point lead) {
        return ignored -> lead;
    }

    /** A leadFn that always returns null (simulates player stationary / no walk path). */
    private static Function<Point, Point> nullLead() {
        return ignored -> null;
    }

    /** A fallback supplier that always returns the given point. */
    private static Supplier<Point> constFallback(Point p) {
        return () -> p;
    }

    // ------------------------------------------------------------------ tests

    /**
     * (1) First call with a hull picks a point inside it and caches it.
     * tracked == leaded, rawPick == picked.
     */
    @Test
    public void firstCallWithHullPicksAndCaches() {
        HullRepickState state = new HullRepickState();
        Point pick = new Point(50, 50);
        Point lead = new Point(60, 60);
        HullRepickState.StepResult r = state.step(hull100(),
                constPick(pick), constLead(lead), constFallback(new Point(99, 99)));

        assertEquals("tracked should be the leaded point", lead, r.tracked);
        assertEquals("rawPick should be the picked point", pick, r.rawPick);
        assertTrue("state should be fromRealPick", state.isFromRealPick());
        assertEquals(pick, state.getRawPick());
        assertEquals(lead, state.getTracked());
    }

    /**
     * (2) Subsequent calls with the same hull (point still inside) return the SAME tracked point
     * WITHOUT invoking pickFn or leadFn again.
     */
    @Test
    public void subsequentCallsSameHullReturnCachedPointWithoutInvokingPickOrLead() {
        HullRepickState state = new HullRepickState();
        Point pick = new Point(50, 50);
        Point lead = new Point(60, 60);

        // prime the state
        state.step(hull100(), constPick(pick), constLead(lead), constFallback(new Point(0, 0)));

        AtomicInteger pickCalls = new AtomicInteger(0);
        AtomicInteger leadCalls = new AtomicInteger(0);
        Supplier<Point> countingPick = () -> { pickCalls.incrementAndGet(); return new Point(70, 70); };
        Function<Point, Point> countingLead = p -> { leadCalls.incrementAndGet(); return new Point(80, 80); };

        for (int i = 0; i < 5; i++) {
            HullRepickState.StepResult r = state.step(hull100(), countingPick, countingLead,
                    constFallback(new Point(0, 0)));
            assertEquals("cached tracked should be returned unchanged", lead, r.tracked);
            assertEquals("cached rawPick should be returned unchanged", pick, r.rawPick);
        }

        assertEquals("pickFn must NOT be called while point is inside hull", 0, pickCalls.get());
        assertEquals("leadFn must NOT be called while point is inside hull", 0, leadCalls.get());
    }

    /**
     * (2b) The leaded tracked point normally sits OUTSIDE the hull (it leads ahead of the target).
     * Its staleness alone must NOT trigger a re-pick while the rawPick is still inside — the
     * upstream implementation's
     * condition is (tracked stale AND rawPick stale), so a lead-outside/raw-inside state is stable.
     */
    @Test
    public void trackedOutsideHullButRawInsideDoesNotRepick() {
        HullRepickState state = new HullRepickState();
        Point pick = new Point(50, 50);          // inside hull100
        Point lead = new Point(150, 150);        // OUTSIDE hull100 — lead points ahead

        state.step(hull100(), constPick(pick), constLead(lead), constFallback(new Point(0, 0)));

        AtomicInteger pickCalls = new AtomicInteger(0);
        AtomicInteger leadCalls = new AtomicInteger(0);
        Supplier<Point> countingPick = () -> { pickCalls.incrementAndGet(); return new Point(70, 70); };
        Function<Point, Point> countingLead = p -> { leadCalls.incrementAndGet(); return new Point(80, 80); };

        for (int i = 0; i < 5; i++) {
            HullRepickState.StepResult r = state.step(hull100(), countingPick, countingLead,
                    constFallback(new Point(0, 0)));
            assertEquals("tracked must stay the cached out-of-hull lead", lead, r.tracked);
            assertEquals("rawPick must stay cached", pick, r.rawPick);
        }

        assertEquals("pickFn must NOT fire while rawPick is still inside the hull", 0, pickCalls.get());
        assertEquals("leadFn must NOT fire while rawPick is still inside the hull", 0, leadCalls.get());
    }

    /**
     * (3) Moving the hull so the cached point falls outside triggers exactly one re-pick
     * (the call with the new hull) and the NEW pick + lead are cached.
     */
    @Test
    public void hullMovesOutsidePointTriggersRepick() {
        HullRepickState state = new HullRepickState();
        Point pick1 = new Point(50, 50);
        Point lead1 = new Point(55, 55);

        // prime with initial hull
        state.step(hull100(), constPick(pick1), constLead(lead1), constFallback(new Point(0, 0)));

        // move the hull far away so pick1 (50,50) is no longer inside
        Point pick2 = new Point(250, 250);
        Point lead2 = new Point(260, 260);
        AtomicInteger pickCalls = new AtomicInteger(0);
        Supplier<Point> countingPick = () -> { pickCalls.incrementAndGet(); return pick2; };

        HullRepickState.StepResult r = state.step(hullFar(), countingPick, constLead(lead2),
                constFallback(new Point(0, 0)));

        assertEquals("one re-pick expected", 1, pickCalls.get());
        assertEquals("tracked should be the new leaded point", lead2, r.tracked);
        assertEquals("rawPick should be the new picked point", pick2, r.rawPick);
        assertEquals(lead2, state.getTracked());
        assertEquals(pick2, state.getRawPick());
    }

    /**
     * (4) Hull temporarily null → falls back without caching as real pick; when hull reappears
     * a real pick IS triggered (regression test for the sticky-fallback bug).
     */
    @Test
    public void hullNullFallbackThenHullAppearsTriggersRealPick() {
        HullRepickState state = new HullRepickState();
        Point fallbackPt = new Point(10, 10);

        // Call with null hull — should return fallback, not cache as real pick
        HullRepickState.StepResult r1 = state.step(null,
                constPick(new Point(50, 50)), nullLead(), constFallback(fallbackPt));

        assertEquals("fallback should be returned when hull is null", fallbackPt, r1.tracked);
        assertNull("rawPick should be null (no real pick)", r1.rawPick);
        assertFalse("fromRealPick must be false after fallback", state.isFromRealPick());

        // Now hull appears — must trigger a real pick, not be suppressed by the stored fallback
        Point pick = new Point(50, 50);
        Point lead = new Point(55, 55);
        AtomicInteger pickCalls = new AtomicInteger(0);
        Supplier<Point> countingPick = () -> { pickCalls.incrementAndGet(); return pick; };

        HullRepickState.StepResult r2 = state.step(hull100(), countingPick, constLead(lead),
                constFallback(fallbackPt));

        assertEquals("pick must be called when hull appears after fallback", 1, pickCalls.get());
        assertEquals("tracked should be the leaded real pick", lead, r2.tracked);
        assertEquals("rawPick should be the real pick", pick, r2.rawPick);
        assertTrue("fromRealPick must be true after real pick", state.isFromRealPick());
    }

    /**
     * (5) lead == null → tracked is set to rawPick (not null).
     */
    @Test
    public void leadNullMakesTrackedEqualRawPick() {
        HullRepickState state = new HullRepickState();
        Point pick = new Point(40, 40);

        HullRepickState.StepResult r = state.step(hull100(),
                constPick(pick), nullLead(), constFallback(new Point(99, 99)));

        assertEquals("when lead is null, tracked should equal rawPick", pick, r.tracked);
        assertEquals("rawPick should be the picked point", pick, r.rawPick);
    }

    /**
     * (6) Settle view: rawPick returned when present; fallback center when rawPick is null.
     * Verifies that a fresh HullRepickState has no rawPick.
     */
    @Test
    public void settleView_rawPickWhenPresent_fallbackWhenNull() {
        HullRepickState state = new HullRepickState();

        // No pick yet
        assertNull("getRawPick should be null before any step", state.getRawPick());

        // After a real pick
        Point pick = new Point(50, 50);
        state.step(hull100(), constPick(pick), nullLead(), constFallback(new Point(0, 0)));
        assertEquals("getRawPick should return the cached in-hull pick", pick, state.getRawPick());
    }

    /**
     * (7) Pick function returns null (shape too thin) → fallback returned, fromRealPick stays false,
     * and the next call with a valid pick DOES pick (no sticky-nothing bug).
     */
    @Test
    public void pickReturnsNullFallsBackAndNextCallRetries() {
        HullRepickState state = new HullRepickState();
        Point fallbackPt = new Point(5, 5);
        Supplier<Point> nullPick = () -> null;

        HullRepickState.StepResult r1 = state.step(hull100(), nullPick, nullLead(),
                constFallback(fallbackPt));

        assertEquals("fallback should be returned when pick returns null", fallbackPt, r1.tracked);
        assertNull("rawPick should be null after a failed pick", r1.rawPick);
        assertFalse("fromRealPick must be false after failed pick", state.isFromRealPick());

        // Next call with a valid pick should succeed
        Point pick = new Point(50, 50);
        Point lead = new Point(55, 55);
        AtomicInteger pickCalls = new AtomicInteger(0);
        HullRepickState.StepResult r2 = state.step(hull100(),
                () -> { pickCalls.incrementAndGet(); return pick; },
                constLead(lead), constFallback(fallbackPt));

        assertEquals("pick must be attempted again after a prior null pick", 1, pickCalls.get());
        assertEquals(lead, r2.tracked);
        assertEquals(pick, r2.rawPick);
    }

    /**
     * (8) hull == null after a real pick: cached tracked is returned (not fallback).
     */
    @Test
    public void hullNullAfterRealPickReturnsCachedTracked() {
        HullRepickState state = new HullRepickState();
        Point pick = new Point(50, 50);
        Point lead = new Point(55, 55);
        state.step(hull100(), constPick(pick), constLead(lead), constFallback(new Point(0, 0)));

        // null hull
        Point fallbackPt = new Point(99, 99);
        AtomicInteger fallbackCalls = new AtomicInteger(0);
        HullRepickState.StepResult r = state.step(null,
                constPick(new Point(1, 1)), nullLead(),
                () -> { fallbackCalls.incrementAndGet(); return fallbackPt; });

        assertEquals("cached tracked should be returned when hull temporarily null", lead, r.tracked);
        assertEquals("cached rawPick should still be reported", pick, r.rawPick);
        assertEquals("fallback should NOT be called after a real pick", 0, fallbackCalls.get());
    }

    /**
     * (9) leadFn receives the newly-picked point (not an old cached value).
     */
    @Test
    public void leadFnReceivesNewlyPickedPoint() {
        HullRepickState state = new HullRepickState();
        Point pick = new Point(42, 42);
        Point[] receivedByLead = {null};

        Function<Point, Point> capturingLead = (p) -> {
            receivedByLead[0] = p;
            return new Point(p.x + 10, p.y + 10);
        };

        state.step(hull100(), constPick(pick), capturingLead, constFallback(new Point(0, 0)));

        assertEquals("leadFn must receive the picked point", pick, receivedByLead[0]);
    }

    /**
     * (10) reset() clears state so the next call behaves as if fresh.
     */
    @Test
    public void resetClearsStateAndNextCallPicks() {
        HullRepickState state = new HullRepickState();
        Point pick1 = new Point(50, 50);
        state.step(hull100(), constPick(pick1), nullLead(), constFallback(new Point(0, 0)));

        state.reset();
        assertNull("getRawPick should be null after reset", state.getRawPick());
        assertFalse("fromRealPick should be false after reset", state.isFromRealPick());

        AtomicInteger pickCalls = new AtomicInteger(0);
        Point pick2 = new Point(30, 30);
        HullRepickState.StepResult r = state.step(hull100(),
                () -> { pickCalls.incrementAndGet(); return pick2; },
                nullLead(), constFallback(new Point(0, 0)));

        assertEquals("pick should be called after reset", 1, pickCalls.get());
        assertEquals(pick2, r.rawPick);
        assertEquals(pick2, r.tracked); // lead is null → tracked = rawPick
    }
}
