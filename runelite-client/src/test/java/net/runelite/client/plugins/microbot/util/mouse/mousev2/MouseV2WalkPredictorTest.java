package net.runelite.client.plugins.microbot.util.mouse.mousev2;

import net.runelite.client.plugins.microbot.util.mouse.mousev2.prediction.WalkPathPredictor;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Pure-logic tests for the player-walk physics predictor (no running client).
 */
public class MouseV2WalkPredictorTest {

    private static final double EPS = 1e-6;

    @Test
    public void stationaryPlayerHasNoDisplacement() {
        double[] d = WalkPathPredictor.playerDisplacement(50, 50, 50, 50, false, 600, 0.5);
        assertEquals(0.0, d[0], EPS);
        assertEquals(0.0, d[1], EPS);
    }

    @Test
    public void zeroTravelTimeHasNoDisplacement() {
        double[] d = WalkPathPredictor.playerDisplacement(0, 0, 1280, 0, true, 0, 0.5);
        assertEquals(0.0, d[0], EPS);
        assertEquals(0.0, d[1], EPS);
    }

    @Test
    public void walkSpeedIsOneTilePerTick() {
        // 1 tick (600ms) of walking covers 128 local units; commit 0.5 -> 64
        double[] d = WalkPathPredictor.playerDisplacement(0, 0, 1280, 0, false, 600, 0.5);
        assertEquals(64.0, d[0], EPS);
        assertEquals(0.0, d[1], EPS);
    }

    @Test
    public void runSpeedIsTwoTilesPerTick() {
        // running covers 256 units per tick; commit 0.5 -> 128
        double[] d = WalkPathPredictor.playerDisplacement(0, 0, 1280, 0, true, 600, 0.5);
        assertEquals(128.0, d[0], EPS);
    }

    @Test
    public void displacementNeverOvershootsDestination() {
        // destination only 64 units away, but a huge travel time
        double[] d = WalkPathPredictor.playerDisplacement(0, 0, 64, 0, true, 1_000_000, 0.5);
        assertEquals(32.0, d[0], EPS); // capped at 64 then * 0.5 commit
    }

    @Test
    public void commitFactorScalesLead() {
        double[] half = WalkPathPredictor.playerDisplacement(0, 0, 1280, 0, false, 600, 0.5);
        double[] full = WalkPathPredictor.playerDisplacement(0, 0, 1280, 0, false, 600, 1.0);
        assertEquals(half[0] * 2.0, full[0], EPS);
    }

    // ----- two-segment (player -> target -> dest) -----

    @Test
    public void twoSegmentWalksAlongSeg1WhenTimeShort() {
        // player(0,0) -> target(1280,0); short budget only covers part of seg1 (+x)
        double[] d = WalkPathPredictor.playerDisplacementTwoSegment(
                0, 0, 1280, 0, 1280, 640, true, true, 600, 0.5);
        assertTrue("moves toward target along +x", d[0] > 0);
        assertEquals("no y motion yet", 0.0, d[1], EPS);
    }

    @Test
    public void twoSegmentReachesSeg2WithLeftoverTime() {
        // player(0,0) -> target(64,0) [close] -> dest(64,640) [up]; enough time to enter seg2
        double[] d = WalkPathPredictor.playerDisplacementTwoSegment(
                0, 0, 64, 0, 64, 640, true, true, 900, 0.5);
        assertTrue("advanced along seg1 (+x)", d[0] > 0);
        assertTrue("advanced along seg2 (+y)", d[1] > 0);
    }

    @Test
    public void twoSegmentDampensShortTotalPaths() {
        // total path < 256 units -> scale = commit * (total/256), so displacement is reduced
        double[] shortPath = WalkPathPredictor.playerDisplacementTwoSegment(
                0, 0, 100, 0, 100, 50, true, true, 600, 0.5);
        double[] longPath = WalkPathPredictor.playerDisplacementTwoSegment(
                0, 0, 1000, 0, 1000, 50, true, true, 600, 0.5);
        // both move along +x; the long path is not damped (full commit), short one is
        assertTrue("short total path is damped", Math.hypot(shortPath[0], shortPath[1]) < 100);
        assertTrue("long path moves more per unit", longPath[0] > 0);
    }

    @Test
    public void displacementFollowsDirectionToDestination() {
        // 45-degree destination -> equal x/y components
        double[] d = WalkPathPredictor.playerDisplacement(0, 0, 1000, 1000, true, 600, 1.0);
        assertEquals(d[0], d[1], EPS);
        assertTrue("moved some distance", d[0] > 0);
    }
}
