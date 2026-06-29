package net.runelite.client.plugins.microbot.util.mouse.mousev2;

import java.util.List;
import java.util.Random;

/**
 * Distributes a total time budget across the segments of a path, based on the upstream {@code TBdu.TB_}.
 * Three layers on top of the two-thirds power law (tangential velocity falls as the cube root of
 * curvature, {@code v ∝ curvature^(-1/3)}, so the cursor slows through bends and speeds up on
 * straight runs):
 * <ol>
 *   <li>a bell-shaped velocity envelope (accelerate then decelerate), peaking at
 *       {@link MouseProfile#velocityPeak} along the path;</li>
 *   <li>a per-move total-time jitter ({@code 1 + gaussian*0.12});</li>
 *   <li>per-segment timing noise ({@code 1 + gaussian*(0.02 + fatigue*0.03)}).</li>
 * </ol>
 */
public final class PowerLawTimer {
    /**
     * Curvature epsilon: keeps near-straight segments finite. The upstream {@code TBdu} writes this as
     * {@code 0.01 * TBdx.TBU}; the un-obfuscated value (matching the literal {@code 0.01} used in
     * the retarget re-time path {@code TBwr.TBw}) is {@code 0.01}.
     */
    private static final double CURVATURE_EPSILON = 0.01;

    private PowerLawTimer() {
    }

    /**
     * Discrete (Menger) curvature of the circle through three points: {@code 2*cross / (|AB||BC||CA|)},
     * equal to {@code 1 / circumradius}. Returns 0 for degenerate/collinear triples.
     */
    public static double mengerCurvature(Vec2 a, Vec2 b, Vec2 c) {
        double abx = b.x - a.x, aby = b.y - a.y;
        double acx = c.x - a.x, acy = c.y - a.y;
        double lab = Math.hypot(abx, aby);
        double lbc = c.distance(b);
        double lac = Math.hypot(acx, acy);
        if (lab < 1e-10 || lbc < 1e-10 || lac < 1e-10) {
            return 0.0;
        }
        double cross = abx * acy - aby * acx;
        return 2.0 * cross / (lab * lbc * lac);
    }

    /**
     * Computes a per-segment time (ms) for a path of {@code n} points, returning {@code n-1} delays
     * whose sum is approximately {@code totalMs} (before per-segment noise). Each segment's
     * tangential speed is {@code baseSpeed * bellEnvelope / cbrt(|curvature| + eps)}; its relative
     * time is {@code length / speed}; the vector is rescaled to the (jittered) total, then each
     * delay gets gaussian noise and is floored at 1ms.
     *
     * @param path    sampled path points (size &ge; 2)
     * @param totalMs total movement duration to spread across the segments
     * @param profile supplies {@link MouseProfile#powerLawBaseSpeed} and {@link MouseProfile#velocityPeak}
     * @param ctx     supplies fatigue (drives per-segment noise)
     * @param rng     source of the total-time jitter and per-segment noise
     */
    public static long[] timings(List<Vec2> path, long totalMs, MouseProfile profile,
                                 MouseContext ctx, Random rng) {
        int n = path.size();
        if (n < 2) {
            return new long[0];
        }
        int segments = n - 1;

        // Per-move total-time jitter (TBdu: d * (1 + gauss*0.12), floored at 15ms).
        double total = Math.max(totalMs * (1.0 + rng.nextGaussian() * 0.12), 15.0);

        double baseSpeed = profile.powerLawBaseSpeed;
        double peak = clamp(profile.velocityPeak, 1e-3, 1.0 - 1e-3);

        // Curvature at each point: 0 at the endpoints, Menger curvature for interior points.
        double[] curvature = new double[n];
        for (int i = 1; i < n - 1; i++) {
            curvature[i] = Math.abs(mengerCurvature(path.get(i - 1), path.get(i), path.get(i + 1)));
        }

        double[] relative = new double[segments];
        double sum = 0.0;
        for (int i = 0; i < segments; i++) {
            // bell envelope sampled at the segment midpoint, warped so its peak lands at `peak`
            double midParam = (i + 0.5) / (double) segments;
            double u = midParam > peak
                    ? 0.5 + (midParam - peak) / (1.0 - peak) * 0.5
                    : midParam / peak * 0.5;
            double bell = 30.0 * u * u - 60.0 * u * u * u + 30.0 * u * u * u * u;
            double envelope = 0.75 + 0.25 * clamp(bell / 1.875, 0.0, 1.0);
            double speed = baseSpeed * envelope / Math.cbrt(curvature[i] + CURVATURE_EPSILON);
            double len = Math.max(path.get(i).distance(path.get(i + 1)), 0.001);
            relative[i] = len / speed;
            sum += relative[i];
        }

        if (sum > 0.0) {
            double scale = total / sum;
            for (int i = 0; i < segments; i++) {
                relative[i] *= scale;
            }
        } else {
            double even = total / segments;
            for (int i = 0; i < segments; i++) {
                relative[i] = even;
            }
        }

        // Per-segment timing noise (TBdu: std = 0.02 + fatigue*0.03). Applied after normalisation,
        // so the sum is only approximately `total` — matching the upstream implementation.
        double noiseStd = 0.02 + ctx.getFatigue() * 0.03;
        long[] out = new long[segments];
        for (int i = 0; i < segments; i++) {
            out[i] = Math.max(1L, Math.round(relative[i] * (1.0 + rng.nextGaussian() * noiseStd)));
        }
        return out;
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
