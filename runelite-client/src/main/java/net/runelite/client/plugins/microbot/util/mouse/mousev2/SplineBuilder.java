package net.runelite.client.plugins.microbot.util.mouse.mousev2;

import net.runelite.client.plugins.microbot.util.mouse.mousev2.calibration.FragmentLibrary;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Builds the sampled point list for a mouse stroke. Based on the upstream {@code TBrz}.
 * <p>
 * The main ({@link CurveStyle#BALLISTIC}) path is a <strong>natural cubic spline</strong> through
 * 1-3 perpendicular-offset control points (not Catmull-Rom): {@link #controlPoints} places the
 * control points biased late along the travel line and all to one side, {@link NaturalCubicSpline}
 * fits a C2 spline through them, it is resampled evenly by arc length to {@link #resampleCount}
 * points, then an Ornstein-Uhlenbeck (AR(1)) wander pass adds correlated micro-drift.
 * <p>
 * The {@link CurveStyle#CORRECTIVE} path is a near-straight line with a single small {@code 4t(1-t)}
 * bow, used for overshoot-correction legs.
 * <p>
 * The resulting point list is consumed by {@link PowerLawTimer} to assign per-segment timing.
 */
public final class SplineBuilder {
    /** Below this distance the move is a straight segment (callers usually use the short-move path). */
    private static final double MIN_CURVE_DISTANCE = 10.0;

    private SplineBuilder() {
    }

    public static List<Vec2> build(Vec2 start, Vec2 end, CurveStyle style,
                                   MouseProfile profile, MouseContext ctx, Random rng) {
        if (style == CurveStyle.CORRECTIVE) {
            return buildCorrection(start, end, rng);
        }
        return buildMain(start, end, profile, ctx, rng);
    }

    /** Main move: natural cubic spline through offset control points + arc-length resample + wander. */
    private static List<Vec2> buildMain(Vec2 start, Vec2 end, MouseProfile profile,
                                        MouseContext ctx, Random rng) {
        double dist = start.distance(end);
        if (dist < MIN_CURVE_DISTANCE) {
            return straight(start, end);
        }

        List<Vec2> control = controlPoints(start, end, dist, profile, ctx, rng);
        if (control.size() < 2) {
            return straight(start, end);
        }

        NaturalCubicSpline spline = NaturalCubicSpline.fit(control);
        int count = resampleCount(dist);
        List<Vec2> pts = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            pts.add(spline.sampleByArcFraction(i / (double) (count - 1)));
        }

        wander(pts, dist, profile, ctx, rng);

        // guarantee exact endpoints after resample/wander
        pts.set(0, start);
        pts.set(pts.size() - 1, end);
        return pts;
    }

    /**
     * Generates the spline control points (start, 1-3 interior, end). Port of {@code TBrz.TBa}:
     * count is distance-gated, the interior points are biased <em>late</em> along the line and all
     * offset to a single side, with magnitude driven by the profile curve scale and the context.
     */
    static List<Vec2> controlPoints(Vec2 start, Vec2 end, double dist,
                                    MouseProfile profile, MouseContext ctx, Random rng) {
        List<Vec2> pts = new ArrayList<>();
        pts.add(start);
        if (dist < MIN_CURVE_DISTANCE) {
            pts.add(end);
            return pts;
        }

        int count;
        if (dist < 100.0) {
            count = 1;
        } else if (dist < 300.0) {
            count = 1 + (rng.nextDouble() < 0.4 ? 1 : 0);
        } else {
            count = 2 + (rng.nextDouble() < 0.3 ? 1 : 0);
        }

        Vec2 dir = end.sub(start).normalized();
        Vec2 perp = new Vec2(-dir.y, dir.x);
        double biasThreshold = 0.5 + profile.curveBias * 0.25;
        double biasSign = rng.nextDouble() < biasThreshold ? 1.0 : -1.0;

        double accuracy = ctx.getAccuracy();
        double focus = ctx.getFocus();

        for (int i = 0; i < count; i++) {
            double t;
            if (count == 1) {
                t = 0.6 + rng.nextDouble() * 0.2;
            } else if (count == 2) {
                t = i == 0 ? 0.4 + rng.nextDouble() * 0.15 : 0.65 + rng.nextDouble() * 0.2;
            } else {
                t = 0.35 + (double) i / (count - 1) * 0.5 + rng.nextDouble() * 0.1;
            }
            t = clamp(t, 0.15, 0.9);

            Vec2 base = start.lerp(end, t);
            double accFactor = Math.max(0.55 + (1.0 - accuracy) * 1.2, 0.15);
            double focFactor = Math.max(0.7 + (1.0 - focus) * 0.6, 0.1);
            double amp = profile.curveScale * accFactor * focFactor;
            double rnd = 0.15 + rng.nextDouble() * rng.nextDouble() * 1.85;
            double mag = amp * dist * 0.05 * rnd;
            pts.add(base.add(perp.scale(biasSign * mag)));
        }

        pts.add(end);
        return pts;
    }

    /**
     * Resample count for the main spline (TBrz.TBZ): denser than a naive fixed cap so the cursor
     * emits a smooth stream rather than coarse hops.
     */
    static int resampleCount(double dist) {
        if (dist < 50.0) {
            return Math.max(20, (int) dist);
        }
        if (dist <= 200.0) {
            return 50 + (int) ((dist - 50.0) * 0.3);
        }
        return 100 + Math.min((int) ((dist - 200.0) * 0.1), 100);
    }

    /**
     * Ornstein-Uhlenbeck (AR(1)) wander pass (TBrz.TBu): adds correlated micro-drift to the
     * interior points, enveloped by {@code 4t(1-t)} so it vanishes at both endpoints. Endpoints are
     * left untouched.
     * <p>
     * Tier-2 (recorded noise textures): when {@link MouseV2Engine#useRecordedTextures} is true and
     * {@link FragmentLibrary} has data for this move's bucket, the synthetic AR(1) is replaced by a
     * resampled recorded perp-offset curve scaled by {@code dist}. Falls back to the synthetic path
     * when no fragment is available for the bucket.
     */
    private static void wander(List<Vec2> pts, double dist, MouseProfile profile,
                               MouseContext ctx, Random rng) {
        if (pts.size() < 3) {
            return;
        }
        double distFactor = clamp((dist - 20.0) / 200.0, 0.45, 1.0);
        double ctxFactor = Math.max(0.6 + (1.0 - ctx.getFocus()) * 0.8 + ctx.getFatigue() * 0.4, 0.05);

        // ---- Tier-2: try to overlay a recorded noise-texture ----
        if (MouseV2Engine.useRecordedTextures) {
            FragmentLibrary lib = FragmentLibrary.getInstance();
            if (lib.hasData()) {
                String distBucket  = FragmentLibrary.distBucket(dist);
                // Use urgency as a proxy for speed: high urgency → fast bucket
                String speedBucket = ctx.getUrgency() >= 0.6
                        ? FragmentLibrary.SPEED_FAST : FragmentLibrary.SPEED_SLOW;
                int interior = pts.size() - 2; // interior points only
                if (interior > 0) {
                    double[] perpOffsets = lib.sampleNoise(distBucket, speedBucket, interior, rng);
                    if (perpOffsets != null) {
                        // Compute direction perpendicular to start→end
                        Vec2 start = pts.get(0);
                        Vec2 end   = pts.get(pts.size() - 1);
                        double dx  = end.x - start.x;
                        double dy  = end.y - start.y;
                        double len = Math.sqrt(dx * dx + dy * dy);
                        if (len > 1e-9) {
                            // unit perp = (-dy, dx) / len
                            double px = -dy / len;
                            double py =  dx / len;
                            for (int i = 0; i < interior; i++) {
                                double f = (i + 1.0) / (pts.size() - 1);
                                double env = 4.0 * f * (1.0 - f);
                                // perpOffsets are normalised to unit path length; scale by dist
                                double off = perpOffsets[i] * dist * ctxFactor * env;
                                Vec2 p = pts.get(i + 1);
                                pts.set(i + 1, new Vec2(p.x + off * px, p.y + off * py));
                            }
                            return; // fragment applied; skip synthetic AR(1)
                        }
                    }
                }
            }
        }

        // ---- Synthetic AR(1) fallback ----
        double amp = profile.overshootOffsetAmp * 1.5 * distFactor * ctxFactor;

        double nx = 0.0;
        double ny = 0.0;
        int n = pts.size() - 1;
        for (int i = 1; i < n; i++) {
            nx = nx * 0.96 + rng.nextGaussian() * amp * 0.2;
            ny = ny * 0.96 + rng.nextGaussian() * amp * 0.2;
            double f = (double) i / (pts.size() - 1);
            double env = 4.0 * f * (1.0 - f);
            Vec2 p = pts.get(i);
            pts.set(i, new Vec2(p.x + nx * env, p.y + ny * env));
        }
    }

    /**
     * Correction leg (TBrz.TBd): straight lerp with a single small perpendicular bow of magnitude
     * {@code dist*0.03*(rand-0.5)} (so &plusmn;1.5% of distance, random side) enveloped by
     * {@code 4t(1-t)}.
     */
    static List<Vec2> buildCorrection(Vec2 start, Vec2 end, Random rng) {
        double dist = start.distance(end);
        int n = Math.max(8, Math.min((int) (dist * 0.4), 30));
        Vec2 dir = dist > 0.5 ? end.sub(start).normalized() : new Vec2(1.0, 0.0);
        Vec2 perp = new Vec2(-dir.y, dir.x);
        double amp = dist * 0.03 * (rng.nextDouble() - 0.5);
        List<Vec2> pts = new ArrayList<>(n + 1);
        for (int j = 0; j <= n; j++) {
            double t = (double) j / n;
            Vec2 base = start.lerp(end, t);
            double env = 4.0 * t * (1.0 - t);
            pts.add(base.add(perp.scale(amp * env)));
        }
        pts.set(0, start);
        pts.set(pts.size() - 1, end);
        return pts;
    }

    private static List<Vec2> straight(Vec2 start, Vec2 end) {
        List<Vec2> direct = new ArrayList<>(2);
        direct.add(start);
        direct.add(end);
        return direct;
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
