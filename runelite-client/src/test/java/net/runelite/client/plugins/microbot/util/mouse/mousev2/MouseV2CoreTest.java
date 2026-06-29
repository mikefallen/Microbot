package net.runelite.client.plugins.microbot.util.mouse.mousev2;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Pure-logic unit tests for the MouseV2 core (no running client required).
 */
public class MouseV2CoreTest {

    // ----- MouseProfile determinism -----

    @Test
    public void sameSeedProducesIdenticalProfile() {
        MouseProfile a = MouseProfile.fromSeed("account-123");
        MouseProfile b = MouseProfile.fromSeed("account-123");
        assertEquals(a.baseTimeMs, b.baseTimeMs, 0.0);
        assertEquals(a.curveScale, b.curveScale, 0.0);
        assertEquals(a.curveBias, b.curveBias, 0.0);
        assertEquals(a.overshootMin, b.overshootMin);
        assertEquals(a.overshootMax, b.overshootMax);
        assertEquals(a.velocityPeak, b.velocityPeak, 0.0);
    }

    @Test
    public void differentSeedsProduceDifferentProfiles() {
        MouseProfile a = MouseProfile.fromSeed("account-123");
        MouseProfile b = MouseProfile.fromSeed("account-999");
        // at least one core parameter must differ
        boolean differs = a.baseTimeMs != b.baseTimeMs
                || a.curveScale != b.curveScale
                || a.curveBias != b.curveBias
                || a.velocityPeak != b.velocityPeak;
        assertTrue("distinct seeds should yield distinct profiles", differs);
    }

    @Test
    public void profileParametersStayWithinRanges() {
        for (String seed : new String[]{"a", "b", "longer-seed-string", "12345", "default"}) {
            MouseProfile p = MouseProfile.fromSeed(seed);
            assertInRange("baseTimeMs", p.baseTimeMs, 100.0, 200.0);
            assertInRange("logScaleMs", p.logScaleMs, 80.0, 140.0);
            assertInRange("curveScale", p.curveScale, 0.5, 2.0);
            assertInRange("curveBias", p.curveBias, -1.0, 1.0);
            assertInRange("velocityPeak", p.velocityPeak, 0.3, 0.5);
            assertInRange("powerLawBaseSpeed", p.powerLawBaseSpeed, 50.0, 200.0);
            assertInRange("overshootDecayFrac", p.overshootDecayFrac, 0.1, 0.4);
            assertInRange("extensionFrac", p.extensionFrac, 0.05, 0.2);
            assertInRange("overshootTendency", p.overshootTendency, 0.0, 0.3);
            assertTrue("overshootMin<=max", p.overshootMin <= p.overshootMax);
        }
    }

    // ----- Menger curvature -----

    @Test
    public void collinearPointsHaveZeroCurvature() {
        double c = PowerLawTimer.mengerCurvature(new Vec2(0, 0), new Vec2(5, 0), new Vec2(10, 0));
        assertEquals(0.0, c, 1e-9);
    }

    @Test
    public void rightAngleCurvatureMatchesInverseCircumradius() {
        // points (0,0),(1,0),(1,1): right triangle, circumradius = hypotenuse/2 = sqrt(2)/2,
        // so curvature = 1/R = sqrt(2)
        double c = Math.abs(PowerLawTimer.mengerCurvature(new Vec2(0, 0), new Vec2(1, 0), new Vec2(1, 1)));
        assertEquals(Math.sqrt(2.0), c, 1e-9);
    }

    // ----- PowerLawTimer -----

    @Test
    public void timingsSumApproxTotalAndCountMatches() {
        List<Vec2> path = straightPath(20, 5.0); // 20 points, 5px apart
        long total = 500;
        // total-time jitter (12%) + per-segment noise mean the sum is only roughly the target
        long[] t = PowerLawTimer.timings(path, total, MouseProfile.fromSeed("x"),
                new MouseContext(), new Random(3));
        assertEquals(path.size() - 1, t.length);
        long sum = 0;
        for (long v : t) {
            assertTrue("each segment >= 1ms", v >= 1);
            sum += v;
        }
        assertTrue("sum (" + sum + ") should be roughly total (" + total + ")",
                Math.abs(sum - total) <= total * 0.4 + path.size());
    }

    @Test
    public void highCurvatureSegmentGetsMoreTimePerPixel() {
        // straight along x, then a 90-degree corner, then straight up; all segments length 10
        List<Vec2> path = new ArrayList<>();
        path.add(new Vec2(0, 0));
        path.add(new Vec2(10, 0));
        path.add(new Vec2(20, 0));
        path.add(new Vec2(20, 10));
        path.add(new Vec2(20, 20));
        long[] t = PowerLawTimer.timings(path, 1000, MouseProfile.fromSeed("x"),
                new MouseContext(), new Random(11));
        // segment index 2 starts at the corner point (20,0) -> high curvature -> slower -> more time
        assertTrue("corner segment (" + t[2] + ") should take longer than a straight one (" + t[0] + ")",
                t[2] > t[0]);
    }

    @Test
    public void velocityEnvelopeSlowsTheEndsRelativeToTheMiddle() {
        // a straight path has no curvature, so timing differences come purely from the bell
        // velocity envelope: the first and last segments should be slower (longer) than the middle.
        List<Vec2> path = straightPath(21, 5.0);
        long[] t = PowerLawTimer.timings(path, 2000, MouseProfile.fromSeed("envelope-seed"),
                new MouseContext(), new Random(1));
        int mid = t.length / 2;
        assertTrue("first segment slower than middle", t[0] > t[mid]);
        assertTrue("last segment slower than middle", t[t.length - 1] > t[mid]);
    }

    // ----- DurationModel -----

    @Test
    public void durationIsAtLeastFloorAndGrowsWithDistance() {
        MouseProfile p = MouseProfile.fromSeed("x");
        MouseContext ctx = new MouseContext();
        long near = DurationModel.duration(20, p, ctx);
        long far = DurationModel.duration(800, p, ctx);
        assertTrue("duration floor", near >= 15);
        assertTrue("farther should take longer (" + far + " > " + near + ")", far > near);
    }

    @Test
    public void urgencyShortensAndFatigueLengthens() {
        MouseProfile p = MouseProfile.fromSeed("x");
        MouseContext calm = new MouseContext();
        MouseContext rushed = new MouseContext();
        rushed.setUrgency(0.9);
        MouseContext tired = new MouseContext();
        tired.setFatigue(0.9);
        long base = DurationModel.duration(400, p, calm);
        assertTrue("urgency shortens", DurationModel.duration(400, p, rushed) < base);
        assertTrue("fatigue lengthens", DurationModel.duration(400, p, tired) > base);
    }

    // ----- SplineBuilder -----

    @Test
    public void splineStartsAndEndsExactlyOnEndpoints() {
        Vec2 start = new Vec2(100, 100);
        Vec2 end = new Vec2(540, 320);
        List<Vec2> path = SplineBuilder.build(start, end, CurveStyle.BALLISTIC,
                MouseProfile.fromSeed("x"), new MouseContext(), new Random(42));
        assertTrue(path.size() >= 2);
        assertEquals(start.x, path.get(0).x, 1e-9);
        assertEquals(start.y, path.get(0).y, 1e-9);
        assertEquals(end.x, path.get(path.size() - 1).x, 1e-9);
        assertEquals(end.y, path.get(path.size() - 1).y, 1e-9);
        for (Vec2 v : path) {
            assertTrue("finite x", !Double.isNaN(v.x) && !Double.isInfinite(v.x));
            assertTrue("finite y", !Double.isNaN(v.y) && !Double.isInfinite(v.y));
        }
    }

    @Test
    public void splineIsDeterministicForFixedRngSeed() {
        Vec2 start = new Vec2(0, 0);
        Vec2 end = new Vec2(300, 200);
        MouseProfile p = MouseProfile.fromSeed("acct");
        List<Vec2> a = SplineBuilder.build(start, end, CurveStyle.BALLISTIC, p, new MouseContext(), new Random(7));
        List<Vec2> b = SplineBuilder.build(start, end, CurveStyle.BALLISTIC, p, new MouseContext(), new Random(7));
        assertEquals(a.size(), b.size());
        for (int i = 0; i < a.size(); i++) {
            assertEquals(a.get(i).x, b.get(i).x, 1e-9);
            assertEquals(a.get(i).y, b.get(i).y, 1e-9);
        }
    }

    // ----- helpers -----

    private static List<Vec2> straightPath(int n, double step) {
        List<Vec2> path = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            path.add(new Vec2(i * step, 0));
        }
        return path;
    }

    private static void assertInRange(String name, double v, double lo, double hi) {
        assertTrue(name + " (" + v + ") < " + lo, v >= lo);
        assertTrue(name + " (" + v + ") > " + hi, v <= hi);
    }
}
