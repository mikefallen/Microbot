package net.runelite.client.plugins.microbot.util.mouse.mousev2;

import java.util.Random;

/**
 * Plans the overshoot/miss behaviour for a main move. Based on the upstream {@code TBlk}: every move's
 * target is offset by a small random <em>miss vector</em> and (probabilistically) extended along the
 * travel direction, and a distance/context-gated number of <em>correction legs</em> then converge
 * back onto the true target.
 * <p>
 * Immutable result produced by {@link #compute}.
 */
public final class OvershootPlan {
    public static final int MAX_OVERSHOOTS = 4;

    /** Number of correction legs after the main (missed) leg. */
    public final int legs;
    /** Sideways miss offset applied to the main-leg target, px. */
    public final double missX;
    public final double missY;
    /** Along-travel extension applied to the main-leg target, px (0 if not extended this move). */
    public final double extension;

    private OvershootPlan(int legs, double missX, double missY, double extension) {
        this.legs = legs;
        this.missX = missX;
        this.missY = missY;
        this.extension = extension;
    }

    /**
     * Computes the plan for a move of length {@code dist}. The miss vector is always applied; the
     * extension is probability-gated; the leg count is distance- and context-gated, and forced to at
     * least one when an extension is present (TBlk.TBF).
     */
    public static OvershootPlan compute(MouseProfile profile, MouseContext ctx, double dist, Random rng) {
        double accuracy = ctx.getAccuracy();
        double focus = ctx.getFocus();

        // base count (TBlk.TBD): sampled range + context adjustment, clamped
        int n = profile.overshootMin
                + (profile.overshootMax > profile.overshootMin
                ? rng.nextInt(profile.overshootMax - profile.overshootMin + 1) : 0);
        int adj = (int) ((accuracy - 0.5) * 1.5 + (1.0 - focus) * 0.5);
        n = clampInt(n + adj, 0, MAX_OVERSHOOTS);

        // distance gating (TBlk.TBF)
        if (dist < 120.0) {
            n = Math.max(n - 1, 0);
        }
        if (dist < 60.0) {
            n = 0;
        }

        // miss vector (TBlk.TBa): polar, random angle, magnitude from the profile offset amp
        double mag = profile.overshootOffsetAmp * (0.5 + rng.nextDouble());
        double angle = rng.nextDouble() * 2.0 * Math.PI;
        double mx = Math.cos(angle) * mag;
        double my = Math.sin(angle) * mag;

        // along-path extension (TBlk.TBf), probability-gated
        double prob = profile.overshootTendency
                * (0.4 + Math.max(1.0 - accuracy, 0.0) * 1.0 + Math.max(1.0 - focus, 0.0) * 0.4);
        double extension = 0.0;
        if (rng.nextDouble() < prob) {
            // The upstream implementation multiplies by TBcpt.TBY/3.0; TBcpt.TBY is a runtime-patched sentinel (999 in
            // the static init), whose de-obfuscated value (3) makes this factor 1.0.
            extension = profile.extensionFrac * dist * (0.7 + rng.nextDouble() * 0.6);
        }

        // an extension with no legs still needs a correction back to target (TBlk.TBF)
        if (extension > 0.0 && n == 0) {
            n = 1;
        }

        return new OvershootPlan(n, mx, my, extension);
    }

    private static int clampInt(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
