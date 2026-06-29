package net.runelite.client.plugins.microbot.util.mouse.mousev2;

/**
 * Computes the total wall-clock duration (ms) a movement should take, before that budget is
 * distributed across path segments by {@link PowerLawTimer}. A Fitts'-law-style log-distance term
 * combined with the per-account profile and shaped by the live {@link MouseContext} (urgency
 * shortens time, fatigue lengthens it, focus scales the base, target-box size sets the Fitts
 * divisor). Based on the upstream {@code TBdu.TBQ}; mouse speed affects this only indirectly via
 * the URGENCY modifier applied in {@link MouseContext#applyMouseSpeed}.
 */
public final class DurationModel {

    private DurationModel() {
    }

    public static long duration(double distance, MouseProfile profile, MouseContext ctx) {
        // Fitts target-size term: a larger target box is acquired faster (bigger divisor).
        double targetSize = Math.max(Math.max(ctx.getBoxWidth(), ctx.getBoxHeight()), 1.0);
        double logTerm = Math.log(2.0 * distance / targetSize + 1.0) / Math.log(2.0);
        double distFactor = clamp(distance / 200.0, 0.15, 1.0);
        double focusFactor = Math.max(0.6 + ctx.getFocus() * 0.4, 0.1);

        double t = profile.baseTimeMs * distFactor * focusFactor;
        t += profile.logScaleMs * logTerm;
        t *= Math.max(1.0 - ctx.getUrgency() * 0.4, 0.1);
        t *= 1.0 + ctx.getFatigue() * 0.25;

        return (long) Math.max(t, 15.0);
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
