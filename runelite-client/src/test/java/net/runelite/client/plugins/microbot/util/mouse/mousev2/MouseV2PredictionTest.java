package net.runelite.client.plugins.microbot.util.mouse.mousev2;

import net.runelite.client.plugins.microbot.util.mouse.mousev2.prediction.LiveTargets;
import net.runelite.client.plugins.microbot.util.mouse.mousev2.prediction.RetargetBlend;
import net.runelite.client.plugins.microbot.util.mouse.mousev2.prediction.ScreenPredictor;
import org.junit.Test;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Pure-logic tests for the MouseV2 Enhanced Clicking pieces (no running client).
 */
public class MouseV2PredictionTest {

    // ----- RetargetBlend (smoothstep tail blend) -----

    @Test
    public void blendMovesTailFullyToNewTargetAndLeavesCurrentPointFixed() {
        List<Vec2> path = line(6, 10.0); // (0,0),(10,0)...(50,0)
        Vec2 originalLast = path.get(5);
        Vec2 anchor = path.get(2);
        Vec2 delta = new Vec2(30, 12);

        RetargetBlend.blend(path, 2, delta);

        // current point (fromIndex) gets weight 0 -> unchanged
        assertEquals(anchor.x, path.get(2).x, 1e-9);
        assertEquals(anchor.y, path.get(2).y, 1e-9);
        // final point gets weight 1 -> exactly original + delta (ends on new target)
        assertEquals(originalLast.x + delta.x, path.get(5).x, 1e-9);
        assertEquals(originalLast.y + delta.y, path.get(5).y, 1e-9);
    }

    @Test
    public void blendShiftIsMonotonicAcrossTail() {
        List<Vec2> path = line(8, 5.0);
        List<Vec2> before = new ArrayList<>(path);
        Vec2 delta = new Vec2(40, 0);
        RetargetBlend.blend(path, 1, delta);

        double prev = -1;
        for (int i = 1; i < path.size(); i++) {
            double shift = path.get(i).x - before.get(i).x; // applied x shift
            assertTrue("shift must be non-negative", shift >= -1e-9);
            assertTrue("shift must be non-decreasing along the tail", shift >= prev - 1e-9);
            prev = shift;
        }
    }

    @Test
    public void blendOutOfRangeIndexIsNoOp() {
        List<Vec2> path = line(4, 10.0);
        List<Vec2> copy = new ArrayList<>(path);
        RetargetBlend.blend(path, 99, new Vec2(5, 5));
        RetargetBlend.blend(path, -1, new Vec2(5, 5));
        for (int i = 0; i < path.size(); i++) {
            assertEquals(copy.get(i).x, path.get(i).x, 1e-9);
            assertEquals(copy.get(i).y, path.get(i).y, 1e-9);
        }
    }

    // ----- ScreenPredictor -----

    @Test
    public void predictExtrapolatesConstantVelocity() {
        ScreenPredictor p = new ScreenPredictor(nullSource(), 10_000);
        p.addSample(0, new Point(0, 0));
        p.addSample(100, new Point(10, 0)); // vx = 0.1 px/ms
        Point predicted = p.predict(100); // +100ms -> +10px
        assertNotNull(predicted);
        assertEquals(20, predicted.x);
        assertEquals(0, predicted.y);
    }

    @Test
    public void predictClampsLeadMagnitude() {
        double maxLead = 50.0;
        ScreenPredictor p = new ScreenPredictor(nullSource(), maxLead);
        p.addSample(0, new Point(0, 0));
        p.addSample(10, new Point(100, 0)); // vx = 10 px/ms -> huge
        Point predicted = p.predict(1000); // raw lead 10000px, must clamp to 50
        assertNotNull(predicted);
        double leadFromLatest = Math.hypot(predicted.x - 100, predicted.y - 0);
        assertTrue("lead clamped to <= maxLead (was " + leadFromLatest + ")",
                leadFromLatest <= maxLead + 1.0);
    }

    @Test
    public void predictWithSingleSampleReturnsLatest() {
        ScreenPredictor p = new ScreenPredictor(nullSource(), 100);
        p.addSample(0, new Point(7, 9));
        Point predicted = p.predict(500);
        assertNotNull(predicted);
        assertEquals(7, predicted.x);
        assertEquals(9, predicted.y);
    }

    @Test
    public void predictWithNoSamplesIsNull() {
        ScreenPredictor p = new ScreenPredictor(nullSource(), 100);
        assertNull(p.predict(100));
        assertNull(p.latest());
    }

    @Test
    public void nullSamplesAreIgnored() {
        ScreenPredictor p = new ScreenPredictor(nullSource(), 100);
        p.addSample(0, null);
        assertNull(p.latest());
    }

    @Test
    public void stationarySamplesHaveZeroLead() {
        ScreenPredictor p = new ScreenPredictor(nullSource(), 10_000);
        p.addSample(0, new Point(40, 60));
        p.addSample(50, new Point(40, 60));

        Point predicted = p.predict(500);

        assertNotNull(predicted);
        assertEquals(40, predicted.x);
        assertEquals(60, predicted.y);
    }

    @Test
    public void sampleAtUsesFixedCadence() {
        AtomicInteger reads = new AtomicInteger();
        ScreenPredictor p = new ScreenPredictor(
                () -> new Point(reads.incrementAndGet(), 0),
                100,
                50,
                Double.POSITIVE_INFINITY);

        Point first = p.sampleAt(0);
        Point cached = p.sampleAt(25);
        Point second = p.sampleAt(50);

        assertEquals(1, first.x);
        assertEquals(1, cached.x);
        assertEquals(2, second.x);
        assertEquals(2, reads.get());
    }

    @Test
    public void predictCapsEstimatedVelocity() {
        ScreenPredictor p = new ScreenPredictor(nullSource(), 10_000, 50, 0.2);
        p.addSample(0, new Point(0, 0));
        p.addSample(10, new Point(100, 0));

        Point predicted = p.predict(100);

        assertNotNull(predicted);
        assertTrue("velocity cap should limit lead", predicted.x - 100 <= 21);
    }

    @Test
    public void decelerationReducesLead() {
        ScreenPredictor p = new ScreenPredictor(nullSource(), 10_000, 50, 10.0);
        p.addSample(0, new Point(0, 0));
        p.addSample(100, new Point(100, 0));
        p.addSample(200, new Point(110, 0));

        Point predicted = p.predict(100);

        assertNotNull(predicted);
        assertTrue("deceleration should damp the old fast lead", predicted.x < 130);
    }

    @Test
    public void leadMsDecaysToZeroAtArrival() {
        assertEquals(0, MouseV2Engine.leadMsForRemainingDistance(0, 0.5));
        assertEquals(0, MouseV2Engine.leadMsForRemainingDistance(2, 0.5));
        assertEquals(200, MouseV2Engine.leadMsForRemainingDistance(100, 0.5));
    }

    @Test
    public void consecutiveMissesTracksNullSamplesPerCadenceBoundary() {
        AtomicInteger callCount = new AtomicInteger();
        // first 3 due samples return null; 4th returns a point
        Supplier<Point> source = () -> {
            int n = callCount.incrementAndGet();
            return n <= 3 ? null : new Point(n, 0);
        };
        ScreenPredictor p = new ScreenPredictor(source, 100, 50, Double.POSITIVE_INFINITY);

        p.sampleAt(0);   // due, null -> consecutiveMisses = 1
        p.sampleAt(25);  // not due, no change
        p.sampleAt(50);  // due, null -> consecutiveMisses = 2
        p.sampleAt(100); // due, null -> consecutiveMisses = 3
        assertEquals(3, p.consecutiveMisses());

        p.sampleAt(150); // due, non-null -> consecutiveMisses = 0
        assertEquals(0, p.consecutiveMisses());
        assertEquals(4, callCount.get()); // 4 due samples hit the source
    }

    @Test
    public void clickboxContainsRejectsOutsidePoint() {
        Rectangle clickbox = new Rectangle(10, 10, 20, 20);

        assertTrue(LiveTargets.isInsideClickbox(clickbox, new Point(15, 15)));
        assertTrue(!LiveTargets.isInsideClickbox(clickbox, new Point(35, 15)));
        assertTrue(!LiveTargets.isInsideClickbox(clickbox, null));
    }

    // ----- helpers -----

    private static Supplier<Point> nullSource() {
        return () -> null;
    }

    private static List<Vec2> line(int n, double step) {
        List<Vec2> path = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            path.add(new Vec2(i * step, 0));
        }
        return path;
    }
}
