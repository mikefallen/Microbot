package net.runelite.client.plugins.microbot.util.mouse.mousev2;

import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertTrue;

/**
 * Pure-logic tests for the overshoot plan (TBlk port, no running client).
 */
public class MouseV2OvershootTest {

    private static final MouseProfile PROFILE = MouseProfile.fromSeed("overshoot-seed");

    @Test
    public void shortMovesHaveNoExtraLegsWithoutExtension() {
        Random rng = new Random(1);
        for (int i = 0; i < 200; i++) {
            OvershootPlan p = OvershootPlan.compute(PROFILE, new MouseContext(), 45, rng);
            // under 60px the count is gated to 0; only a triggered extension can force a single leg
            if (p.extension == 0.0) {
                assertTrue("no legs under 60px without extension", p.legs == 0);
            } else {
                assertTrue("at most one corrective leg under 60px", p.legs == 1);
            }
        }
    }

    @Test
    public void countCappedAtMax() {
        Random rng = new Random(1);
        for (int i = 0; i < 500; i++) {
            OvershootPlan p = OvershootPlan.compute(PROFILE, new MouseContext(), 500, rng);
            assertTrue("count capped at MAX_OVERSHOOTS", p.legs <= OvershootPlan.MAX_OVERSHOOTS);
            assertTrue("count non-negative", p.legs >= 0);
        }
    }

    @Test
    public void missVectorIsAlwaysAppliedAndBounded() {
        Random rng = new Random(5);
        double maxMag = PROFILE.overshootOffsetAmp * 1.5; // amp * (0.5 + rand<1)
        for (int i = 0; i < 200; i++) {
            OvershootPlan p = OvershootPlan.compute(PROFILE, new MouseContext(), 300, rng);
            double mag = Math.hypot(p.missX, p.missY);
            assertTrue("miss vector present (" + mag + ")", mag > 0.0);
            assertTrue("miss vector bounded (" + mag + " <= " + maxMag + ")", mag <= maxMag + 1e-9);
        }
    }

    @Test
    public void extensionIsNonNegativeAndBounded() {
        Random rng = new Random(9);
        double dist = 400;
        double maxExt = PROFILE.extensionFrac * dist * 1.3; // (0.7 + rand*0.6) <= 1.3
        for (int i = 0; i < 200; i++) {
            OvershootPlan p = OvershootPlan.compute(PROFILE, new MouseContext(), dist, rng);
            assertTrue("extension non-negative", p.extension >= 0.0);
            assertTrue("extension bounded (" + p.extension + ")", p.extension <= maxExt + 1e-9);
        }
    }
}
