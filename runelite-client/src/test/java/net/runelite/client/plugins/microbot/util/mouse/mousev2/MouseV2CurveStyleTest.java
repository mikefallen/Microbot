package net.runelite.client.plugins.microbot.util.mouse.mousev2;

import org.junit.Test;

import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Pure-logic tests for the natural-cubic-spline curve geometry (no running client): control-point
 * count gating, arc-length resample density, and near-straight correction legs.
 */
public class MouseV2CurveStyleTest {

    private static final MouseProfile PROFILE = MouseProfile.fromSeed("curve-seed");

    @Test
    public void resampleDensityFollowsDistanceFormula() {
        // <50px: max(20, dist)
        assertEquals(20, SplineBuilder.resampleCount(10));
        assertEquals(40, SplineBuilder.resampleCount(40));
        // 50..200px: 50 + (dist-50)*0.3
        assertEquals(50, SplineBuilder.resampleCount(50));
        assertEquals(95, SplineBuilder.resampleCount(200));
        // >200px: 100 + min((dist-200)*0.1, 100)
        assertEquals(140, SplineBuilder.resampleCount(600));
        assertEquals(200, SplineBuilder.resampleCount(5000)); // capped at +100
    }

    @Test
    public void longMovesAreDenseNotCoarse() {
        // a 600px move should emit a smooth stream, not a handful of hops
        List<Vec2> path = SplineBuilder.build(new Vec2(0, 0), new Vec2(600, 0),
                CurveStyle.BALLISTIC, PROFILE, new MouseContext(), new Random(1));
        assertTrue("dense path (" + path.size() + " pts)", path.size() >= 100);
    }

    @Test
    public void controlPointCountIsDistanceGated() {
        Random rng = new Random(1);
        // short: start + 1 interior + end = 3
        assertEquals(3, SplineBuilder.controlPoints(new Vec2(0, 0), new Vec2(40, 0), 40,
                PROFILE, new MouseContext(), rng).size());
        // long (>=300): 2 or 3 interior -> 4 or 5 total
        for (int i = 0; i < 50; i++) {
            int n = SplineBuilder.controlPoints(new Vec2(0, 0), new Vec2(500, 0), 500,
                    PROFILE, new MouseContext(), rng).size();
            assertTrue("long move has 2-3 interior points (total " + n + ")", n == 4 || n == 5);
        }
    }

    @Test
    public void correctionLegIsNearlyStraight() {
        // a correction bow is at most ~1.5% of the leg length off the straight line
        Random rng = new Random(7);
        Vec2 a = new Vec2(0, 0);
        Vec2 b = new Vec2(100, 0);
        List<Vec2> path = SplineBuilder.buildCorrection(a, b, rng);
        double maxDev = 0;
        for (Vec2 p : path) {
            maxDev = Math.max(maxDev, Math.abs(p.y)); // straight line is y=0
        }
        assertTrue("correction stays near straight (dev=" + maxDev + ")", maxDev <= 100 * 0.03);
        assertEquals(a.x, path.get(0).x, 1e-9);
        assertEquals(b.x, path.get(path.size() - 1).x, 1e-9);
    }
}
