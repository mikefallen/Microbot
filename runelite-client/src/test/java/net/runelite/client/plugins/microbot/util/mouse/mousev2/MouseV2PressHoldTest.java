package net.runelite.client.plugins.microbot.util.mouse.mousev2;

import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertTrue;

/**
 * Pure-logic test for the click press-hold distribution (based on TBqr.TBp(4,6)).
 */
public class MouseV2PressHoldTest {

    @Test
    public void holdIsNeverNegativeAndShortOnAverage() {
        Random rng = new Random(99);
        long sum = 0;
        long max = 0;
        int n = 100_000;
        for (int i = 0; i < n; i++) {
            long h = MouseV2Engine.pressHoldMs(rng);
            assertTrue("never negative", h >= 0);
            sum += h;
            max = Math.max(max, h);
        }
        double mean = sum / (double) n;
        // gaussian(mean=4, std=6) floored at 0 -> empirical mean is a few ms (raised by the floor)
        assertTrue("mean is short (was " + mean + ")", mean > 2.0 && mean < 8.0);
        assertTrue("occasionally produces a longer hold", max >= 15);
    }
}
