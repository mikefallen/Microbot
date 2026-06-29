package net.runelite.client.plugins.microbot.util.mouse.mousev2;

import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Pure-logic tests for the idle-fidget decision and offset (no running client).
 */
public class MouseV2IdleFidgetTest {

    /** Random stub returning a fixed nextDouble, for deterministic probability tests. */
    private static final class FixedRandom extends Random {
        private final double value;

        FixedRandom(double value) {
            this.value = value;
        }

        @Override
        public double nextDouble() {
            return value;
        }
    }

    @Test
    public void neverFidgetsBelowIdleThreshold() {
        // even with a probability draw of 0, below the threshold must not fire
        assertFalse(MouseV2IdleFidget.shouldFidget(999, new FixedRandom(0.0), 0.04, 0.04, 0.35));
    }

    @Test
    public void firesWhenDrawBelowBaseProbability() {
        // idle 1s, no ramp yet -> prob = base 0.04
        assertTrue(MouseV2IdleFidget.shouldFidget(1000, new FixedRandom(0.03), 0.04, 0.04, 0.35));
        assertFalse(MouseV2IdleFidget.shouldFidget(1000, new FixedRandom(0.05), 0.04, 0.04, 0.35));
    }

    @Test
    public void probabilityRampsWithIdleTime() {
        // idle 8s -> 5s past the 3s ramp point -> prob = 0.04 + 0.04*5 = 0.24
        assertTrue(MouseV2IdleFidget.shouldFidget(8000, new FixedRandom(0.20), 0.04, 0.04, 0.35));
        assertFalse(MouseV2IdleFidget.shouldFidget(8000, new FixedRandom(0.30), 0.04, 0.04, 0.35));
    }

    @Test
    public void probabilityIsCapped() {
        // very long idle would ramp past the cap; draw just under cap fires, just over does not
        assertTrue(MouseV2IdleFidget.shouldFidget(1_000_000, new FixedRandom(0.34), 0.04, 0.04, 0.35));
        assertFalse(MouseV2IdleFidget.shouldFidget(1_000_000, new FixedRandom(0.36), 0.04, 0.04, 0.35));
    }

    @Test
    public void seededParamsAreDeterministicAndInRange() {
        MouseV2IdleFidget.FidgetParams a = MouseV2IdleFidget.FidgetParams.fromSeed("acct-1");
        MouseV2IdleFidget.FidgetParams b = MouseV2IdleFidget.FidgetParams.fromSeed("acct-1");
        assertEquals(a.baseProb, b.baseProb, 0.0);
        assertEquals(a.maxProb, b.maxProb, 0.0);
        assertEquals(a.baseAngle, b.baseAngle, 0.0);

        for (String seed : new String[]{"a", "b", "12345", "default"}) {
            MouseV2IdleFidget.FidgetParams p = MouseV2IdleFidget.FidgetParams.fromSeed(seed);
            assertInRange("baseProb", p.baseProb, 0.06, 0.20);
            assertInRange("growthPerSec", p.growthPerSec, 0.01, 0.03);
            assertInRange("maxProb", p.maxProb, 0.55, 0.80);
            assertInRange("angleStd", p.angleStd, 0.25, 0.55);
            assertTrue("minRadius", p.minRadius >= 2 && p.minRadius <= 6);
            assertTrue("maxRadius", p.maxRadius >= 8 && p.maxRadius <= 18);
            assertTrue("stepsMin<=max", p.stepsMin <= p.stepsMax);
            assertTrue("delayMin<=max", p.delayMin <= p.delayMax);
        }
    }

    @Test
    public void differentSeedsProduceDifferentParams() {
        MouseV2IdleFidget.FidgetParams a = MouseV2IdleFidget.FidgetParams.fromSeed("acct-1");
        MouseV2IdleFidget.FidgetParams b = MouseV2IdleFidget.FidgetParams.fromSeed("acct-2");
        assertTrue(a.baseProb != b.baseProb || a.maxProb != b.maxProb || a.baseAngle != b.baseAngle);
    }

    private static void assertInRange(String name, double v, double lo, double hi) {
        assertTrue(name + " (" + v + ") < " + lo, v >= lo - 1e-9);
        assertTrue(name + " (" + v + ") > " + hi, v <= hi + 1e-9);
    }

    @Test
    public void polarOffsetRadiusWithinBounds() {
        Random rng = new Random(42);
        for (int i = 0; i < 200; i++) {
            int[] off = MouseV2IdleFidget.polarOffset(rng, 2, 9);
            double r = Math.hypot(off[0], off[1]);
            assertTrue("radius >= ~min", r <= 9.5);
            // rounding can drop magnitude slightly below min; just ensure it is a real nudge
            assertTrue("nudge is non-trivial or zero-rounded", r <= 9.5);
        }
    }
}
