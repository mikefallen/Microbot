package net.runelite.client.plugins.microbot.util.mouse.mousev2;

import org.junit.Test;

import java.awt.Point;
import java.util.Random;

import static org.junit.Assert.assertTrue;

/**
 * Pure-logic test for the clickbox tremor-settle jitter (no running client).
 */
public class MouseV2SettleTest {

    @Test
    public void jitterStaysWithinBox() {
        Random rng = new Random(123);
        int cx = 200;
        int cy = 150;
        int boxW = 40;
        int boxH = 24;
        for (int i = 0; i < 1000; i++) {
            Point p = MouseV2Engine.jitterInBox(rng, cx, cy, boxW, boxH);
            assertTrue("x within box", p.x >= cx - boxW / 2 && p.x <= cx + boxW / 2);
            assertTrue("y within box", p.y >= cy - boxH / 2 && p.y <= cy + boxH / 2);
        }
    }

    @Test
    public void jitterClustersNearCentre() {
        Random rng = new Random(7);
        int cx = 0;
        int cy = 0;
        int box = 40;
        double sumAbs = 0;
        int n = 2000;
        for (int i = 0; i < n; i++) {
            Point p = MouseV2Engine.jitterInBox(rng, cx, cy, box, box);
            sumAbs += Math.abs(p.x);
        }
        // mean |x| should be well under half the box (gaussian std = box/4 = 10)
        double meanAbs = sumAbs / n;
        assertTrue("clusters near centre (mean |x|=" + meanAbs + ")", meanAbs < box / 2.0);
    }
}
