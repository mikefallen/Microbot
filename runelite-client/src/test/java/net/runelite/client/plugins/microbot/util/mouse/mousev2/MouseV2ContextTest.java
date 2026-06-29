package net.runelite.client.plugins.microbot.util.mouse.mousev2;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Pure-logic tests for per-move focus jitter and the speed-scale duration wiring (no running client).
 */
public class MouseV2ContextTest {

    @Test
    public void jitteredFocusShiftsWithGaussian() {
        assertEquals(0.5, MouseContext.jitteredFocus(0.5, 0.0), 1e-9);
        assertEquals(0.7, MouseContext.jitteredFocus(0.5, 1.0), 1e-9);  // +1 std * 0.2
        assertEquals(0.3, MouseContext.jitteredFocus(0.5, -1.0), 1e-9);
    }

    @Test
    public void jitteredFocusIsClamped() {
        assertEquals(1.0, MouseContext.jitteredFocus(0.9, 10.0), 1e-9);
        assertEquals(0.1, MouseContext.jitteredFocus(0.2, -10.0), 1e-9);
    }

    @Test
    public void biggerTargetBoxShortensDuration() {
        // Fitts: a larger target is acquired faster
        MouseProfile profile = MouseProfile.fromSeed("acct");

        MouseContext small = new MouseContext();
        small.setBoxWidth(20.0);
        small.setBoxHeight(20.0);
        MouseContext big = new MouseContext();
        big.setBoxWidth(120.0);
        big.setBoxHeight(120.0);

        long smallMs = DurationModel.duration(400, profile, small);
        long bigMs = DurationModel.duration(400, profile, big);
        assertTrue("bigger target -> shorter duration (" + bigMs + " < " + smallMs + ")",
                bigMs < smallMs);
    }

    @Test
    public void effectiveValueIsBaseTimesOnePlusMultiplierSum() {
        // matches the MouseV2 context inspector: effective = base * (1 + sum of deltas)
        MouseContext ctx = new MouseContext();
        ctx.setUrgency(0.70);
        ctx.pushModifier("urgency", "profile", 0.475);
        ctx.pushModifier("urgency", "api-mouse-speed", 0.255);
        assertEquals(0.70 * (1.0 + 0.730), ctx.getUrgency(), 1e-9); // -> 1.211
    }

    @Test
    public void mouseSpeedMapsToUrgency() {
        // speed 100 = neutral, 200+ = max +0.5, 0 = -0.25, ~151 -> ~+0.255 (the inspector value)
        assertEquals(0.0, MouseContext.speedToUrgencyDelta(100), 1e-9);
        assertEquals(0.5, MouseContext.speedToUrgencyDelta(200), 1e-9);
        assertEquals(0.5, MouseContext.speedToUrgencyDelta(400), 1e-9);   // clamped
        assertEquals(-0.25, MouseContext.speedToUrgencyDelta(0), 1e-9);
        assertEquals(0.255, MouseContext.speedToUrgencyDelta(151), 1e-9);
    }

    @Test
    public void mouseSpeedSelectsTimingModes() {
        assertEquals("LowIntensity", MouseSpeedProfile.fromSpeed(99).displayName());
        assertEquals("Focused", MouseSpeedProfile.fromSpeed(100).displayName());
        assertEquals("HighIntensity", MouseSpeedProfile.fromSpeed(200).displayName());
        assertEquals("HyperFast", MouseSpeedProfile.fromSpeed(300).displayName());
    }

    @Test
    public void higherMouseSpeedShortensDurationViaUrgencyAndSaturatesAt200() {
        // Speed affects duration ONLY through the URGENCY modifier (DurationModel no longer
        // overrides base timing per tier). The speed->urgency curve saturates at +0.5 for
        // speed >= 200, so 300 gives the same duration as 200.
        MouseProfile profile = MouseProfile.fromSeed("acct");

        MouseContext low = new MouseContext();
        low.applyMouseSpeed(0);
        MouseContext focused = new MouseContext();
        focused.applyMouseSpeed(100);
        MouseContext high = new MouseContext();
        high.applyMouseSpeed(200);
        MouseContext hyper = new MouseContext();
        hyper.applyMouseSpeed(300);

        long lowMs = DurationModel.duration(500, profile, low);
        long focusedMs = DurationModel.duration(500, profile, focused);
        long highMs = DurationModel.duration(500, profile, high);
        long hyperMs = DurationModel.duration(500, profile, hyper);

        assertTrue("speed 100 faster than 0", focusedMs < lowMs);
        assertTrue("speed 200 faster than 100", highMs < focusedMs);
        assertEquals("urgency saturates at 200, so 300 == 200", highMs, hyperMs);
    }

    @Test
    public void applyMouseSpeedPushesAndClearsUrgencyModifier() {
        MouseContext ctx = new MouseContext();
        double base = ctx.getUrgency(); // 0.70
        ctx.applyMouseSpeed(200);
        assertEquals(base * (1.0 + 0.5), ctx.getUrgency(), 1e-9); // 0.70 * 1.5 = 1.05
        ctx.applyMouseSpeed(100); // neutral -> modifier removed
        assertEquals(base, ctx.getUrgency(), 1e-9);
    }

    @Test
    public void defaultsMatchInspector() {
        MouseContext ctx = new MouseContext();
        assertEquals(0.55, ctx.getFocus(), 1e-9);
        assertEquals(0.70, ctx.getUrgency(), 1e-9);
        assertEquals(0.35, ctx.getAccuracy(), 1e-9);
        assertEquals(0.0, ctx.getFatigue(), 1e-9);
        assertEquals(50.0, ctx.getBoxWidth(), 1e-9);
        assertEquals(50.0, ctx.getBoxHeight(), 1e-9);
    }
}
